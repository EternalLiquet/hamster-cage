package dev.hamstercage.capture

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * What one scheduled boundary check attempt did (#135). Diagnostic only: these rows never feed
 * attendance derivation, and an attempt that never started (delayed past retention, replaced by
 * a newer delivery, or cancelled) leaves no row at all.
 */
enum class BoundaryCheckOutcome {
    /** Location monitoring was switched off. */
    MONITORING_OFF,
    /** Re-establishing this process's office registration failed. */
    REGISTRATION_REFRESH_FAILED,
    /** Location permission, precision or the location setting was not ready. */
    PREREQUISITES_MISSING,
    /** Registration was not active or a capture outage was open. */
    REGISTRATION_NOT_ACTIVE,
    STORAGE_UNAVAILABLE,
    /** The attempt started after the EXIT's verification window had closed. */
    STARTED_PAST_DEADLINE,
    /** The observation was not awaiting verification (for example an EXIT with no open visit, or already settled). */
    NOT_AWAITING_VERIFICATION,
    /** A newer observation, an office edit or a gate change made the candidate ineligible before the fix. */
    SUPERSEDED_OR_CHANGED,
    /** The location request failed or timed out. */
    FIX_UNAVAILABLE,
    /** The same gates failed when rechecked after the fix. */
    CHANGED_DURING_FIX,
    /** The fix predates the request (a cached location). */
    FIX_CACHED,
    /** The fix was more than 30 seconds old, or its clocks disagreed. */
    FIX_STALE,
    /** The fix had no accuracy. */
    FIX_NO_ACCURACY,
    /** The accuracy circle was too large to decide inside or outside. */
    FIX_IMPRECISE,
    /** The accuracy circle crossed an office boundary or touched overlapping offices. */
    BOUNDARY_UNCERTAIN,
    /** The fix itself was observed after the verification window closed. */
    FIX_PAST_DEADLINE,
    /** Other evidence settled the EXIT before this fix's own observation time. */
    SETTLED_BEFORE_FIX,
    RECORDED_INSIDE,
    RECORDED_OUTSIDE,
    /** Writing the result failed or the capture gate timed out. */
    WRITE_FAILED,
    /** An unexpected error ended the attempt. */
    ERROR,
}

data class BoundaryCheckEntry(
    val candidateEventId: String, val attempt: Int, val startedAt: Instant, val finishedAt: Instant,
    val outcome: BoundaryCheckOutcome, val accuracyMeters: Float? = null, val fixAgeMs: Long? = null,
)

/** Line codec for the bounded log. Event IDs are Base64 so no separator can appear in them. */
internal object BoundaryCheckCodec {
    val RETENTION: Duration = Duration.ofDays(30)
    const val MAX_ENTRIES = 1_000
    private const val VERSION = "v1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(entries: List<BoundaryCheckEntry>): String = entries.joinToString("\n") { entry ->
        listOf(VERSION, encoder.encodeToString(entry.candidateEventId.toByteArray(Charsets.UTF_8)),
            entry.attempt.toString(), entry.startedAt.toEpochMilli().toString(),
            entry.finishedAt.toEpochMilli().toString(), entry.outcome.name,
            entry.accuracyMeters?.toString() ?: "-", entry.fixAgeMs?.toString() ?: "-").joinToString(";")
    }

    /** Malformed or unknown lines are dropped rather than failing the whole log. */
    fun decode(text: String?): List<BoundaryCheckEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split(';')
        if (parts.size != 8 || parts[0] != VERSION) return@mapNotNull null
        try {
            val id = String(decoder.decode(parts[1]), Charsets.UTF_8)
            val accuracy = parts[6].takeUnless { it == "-" }?.toFloat()
            BoundaryCheckEntry(id, parts[2].toInt(), Instant.ofEpochMilli(parts[3].toLong()),
                Instant.ofEpochMilli(parts[4].toLong()), BoundaryCheckOutcome.valueOf(parts[5]),
                accuracy, parts[7].takeUnless { it == "-" }?.toLong())
                .takeIf { valid(it) }
        } catch (_: RuntimeException) { null }
    }.toList()

    fun valid(entry: BoundaryCheckEntry): Boolean = entry.candidateEventId.isNotBlank() &&
        entry.candidateEventId.length <= 200 && entry.attempt in 1..AdaptiveConfirmation.MAX_EXIT_ATTEMPTS &&
        !entry.finishedAt.isBefore(entry.startedAt) &&
        (entry.accuracyMeters == null || entry.accuracyMeters.isFinite() && entry.accuracyMeters in 0f..10000f) &&
        (entry.fixAgeMs == null || entry.fixAgeMs >= 0)

    /** Keeps the newest [MAX_ENTRIES] rows started within [RETENTION] of [now]. */
    fun prune(entries: List<BoundaryCheckEntry>, now: Instant): List<BoundaryCheckEntry> {
        val oldest = now.minus(RETENTION)
        return entries.filter { !it.startedAt.isBefore(oldest) }
            .sortedWith(compareBy<BoundaryCheckEntry> { it.startedAt }.thenBy { it.candidateEventId }.thenBy { it.attempt })
            .takeLast(MAX_ENTRIES)
    }
}

private val Context.boundaryCheckPreferences by preferencesDataStore("boundary_check_diagnostics")
private val ENTRIES = stringPreferencesKey("entries")

/** App-private, bounded, and removed with attendance history by the privacy reset. */
internal object BoundaryCheckLog {
    /** Null means the log could not be read; the export then says so instead of implying no checks. */
    fun state(context: Context): Flow<List<BoundaryCheckEntry>?> = context.applicationContext
        .boundaryCheckPreferences.data.map<Preferences, List<BoundaryCheckEntry>?> {
            BoundaryCheckCodec.decode(it[ENTRIES])
        }.catch { if (it is CancellationException) throw it else emit(null) }

    /**
     * Best effort, and never for an older privacy generation: the write shares the capture gate
     * with history deletion, so a late attempt cannot recreate rows the reset removed.
     */
    suspend fun record(context: Context, generation: Long, entry: BoundaryCheckEntry) {
        if (!BoundaryCheckCodec.valid(entry)) return
        try {
            withTimeout(3_000) { CaptureWriteGate.mutex.withLock {
                if (PrivacyResetStore.read(context) != PrivacyResetState.Idle(generation)) return@withLock
                context.applicationContext.boundaryCheckPreferences.edit { preferences ->
                    preferences[ENTRIES] = BoundaryCheckCodec.encode(BoundaryCheckCodec.prune(
                        BoundaryCheckCodec.decode(preferences[ENTRIES]) + entry, entry.finishedAt))
                }
            } }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
        } catch (_: Exception) { Unit }
    }

    /** Called by the privacy reset while it holds the capture gate. */
    suspend fun clear(context: Context) {
        context.applicationContext.boundaryCheckPreferences.edit { it.remove(ENTRIES) }
    }
}
