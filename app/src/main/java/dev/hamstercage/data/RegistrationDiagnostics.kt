package dev.hamstercage.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import java.time.Instant

/**
 * Diagnostic evidence about geofence registration. It never feeds attendance derivation: the
 * engine reads only raw_events, corrections and manual sessions. Rows are pruned by age and count
 * and are removed with attendance history by the explicit privacy reset.
 */
@Entity(tableName = "registration_cycles", indices = [Index("startedAt")])
internal data class RegistrationCycleRecord(
    @PrimaryKey val id: String, val kind: String, val reason: String, val generation: Long,
    val plannedCount: Int, val startedAt: Long, val completedAt: Long?, val outcome: String,
    val removal: String?, val cleanup: String?, val errorCode: String?,
)

/** One actual addGeofences call, persisted before the call is made. */
@Entity(tableName = "registration_attempts", indices = [Index("cycleId"), Index("startedAt"), Index("officeId")])
internal data class RegistrationAttemptRecord(
    @PrimaryKey val id: String, val cycleId: String, val officeId: String, val officeVersion: Long?,
    val initialTrigger: Int, val startedAt: Long, val completedAt: Long?, val status: String,
    val errorCode: String?,
)

/** What a registration cycle did. A skipped no-op synchronization records no cycle. */
enum class RegistrationKind {
    /** Removed the previous set and added every desired office. */
    SYNC,
    /** Re-added offices with an ENTER initial trigger after a rejected EXIT. */
    RESYNC,
    /** Removed fences because setup, permission or monitoring is not ready. */
    REMOVE,
}

/**
 * The caller's trigger, propagated rather than inferred. [STATE_CHANGED] means stored offices or
 * the monitoring setting changed; it does not claim which. [UNKNOWN] is the explicit fallback.
 */
enum class RegistrationReason {
    PROCESS_START, FOREGROUND_CHECK, STATE_CHANGED, BOOT_COMPLETED, PACKAGE_REPLACED,
    MONITORING_SETTING, HISTORY_DELETION, PERIODIC_VERIFICATION, ADAPTIVE_CONFIRMATION,
    REJECTED_EXIT_RESYNC, UNKNOWN,
}

/** PENDING is never success. INTERRUPTED means the outcome is unknown (cancelled or process lost). */
enum class RegistrationOutcome { PENDING, SUCCEEDED, FAILED, INTERRUPTED }

/** Steps around the adds. CLEANUP describes removal after a failed or partial cycle. */
enum class RegistrationStep { REMOVED, FAILED }

data class RegistrationCycle(
    val id: String, val kind: RegistrationKind, val reason: RegistrationReason, val generation: Long,
    val plannedCount: Int, val startedAt: Instant, val completedAt: Instant?, val outcome: RegistrationOutcome,
    val removal: RegistrationStep?, val cleanup: RegistrationStep?, val errorCode: String?,
)

data class RegistrationAttempt(
    val id: String, val cycleId: String, val officeId: String, val officeVersion: Long?,
    val initialTrigger: Int, val startedAt: Instant, val completedAt: Instant?,
    val status: RegistrationOutcome, val errorCode: String?,
)

/** Retained registration diagnostics for export. */
data class RegistrationDiagnostics(val cycles: List<RegistrationCycle>, val attempts: List<RegistrationAttempt>) {
    companion object {
        const val RETENTION_DAYS = 30L
        const val MAX_CYCLES = 2_000
        const val MAX_ATTEMPTS = 5_000
    }
}

/** Write side used by the registrar. The registrar ignores failures here so logging never changes registration. */
interface RegistrationLog {
    /** Marks rows left PENDING by an earlier process as INTERRUPTED. */
    suspend fun markInterrupted()
    suspend fun cycleStarted(cycle: RegistrationCycle)
    suspend fun cycleFinished(id: String, completedAt: Instant, outcome: RegistrationOutcome,
        removal: RegistrationStep?, cleanup: RegistrationStep?, errorCode: String?)
    suspend fun attemptStarted(attempt: RegistrationAttempt)
    suspend fun attemptFinished(id: String, completedAt: Instant, status: RegistrationOutcome, errorCode: String?)
}

/** Room-backed log. Completions update only PENDING rows, so a late result cannot recreate deleted history. */
internal class RoomRegistrationLog(private val database: HamsterDatabase) : RegistrationLog {
    private val dao = database.dao()

    override suspend fun markInterrupted() {
        database.withTransaction {
            dao.interruptPendingAttempts(RegistrationOutcome.INTERRUPTED.name)
            dao.interruptPendingCycles(RegistrationOutcome.INTERRUPTED.name)
        }
    }

    override suspend fun cycleStarted(cycle: RegistrationCycle) { database.withTransaction {
        val cutoff = cycle.startedAt.minusSeconds(RegistrationDiagnostics.RETENTION_DAYS * 24 * 60 * 60).toEpochMilli()
        dao.pruneCycles(cutoff, RegistrationDiagnostics.MAX_CYCLES - 1)
        dao.pruneCyclesForAttempts(cutoff, RegistrationDiagnostics.MAX_ATTEMPTS -
            cycle.plannedCount.coerceIn(0, RegistrationDiagnostics.MAX_ATTEMPTS))
        dao.pruneOrphanAttempts()
        dao.insertCycle(RegistrationCycleRecord(cycle.id, cycle.kind.name, cycle.reason.name, cycle.generation,
            cycle.plannedCount, cycle.startedAt.toEpochMilli(), cycle.completedAt?.toEpochMilli(),
            cycle.outcome.name, cycle.removal?.name, cycle.cleanup?.name, cycle.errorCode))
    } }

    override suspend fun cycleFinished(id: String, completedAt: Instant, outcome: RegistrationOutcome,
        removal: RegistrationStep?, cleanup: RegistrationStep?, errorCode: String?) {
        dao.finishCycle(id, completedAt.toEpochMilli(), outcome.name, removal?.name, cleanup?.name, errorCode)
    }

    override suspend fun attemptStarted(attempt: RegistrationAttempt) { database.withTransaction {
        // An attempt belongs to a cycle written earlier in this process. If history deletion
        // removed that cycle, do not start an orphan record.
        if (dao.cycleExists(attempt.cycleId)) dao.insertAttempt(RegistrationAttemptRecord(attempt.id,
            attempt.cycleId, attempt.officeId, attempt.officeVersion, attempt.initialTrigger,
            attempt.startedAt.toEpochMilli(), attempt.completedAt?.toEpochMilli(), attempt.status.name,
            attempt.errorCode))
        // Enforce the cap even if a caller makes more attempts than it originally planned.
        val cutoff = attempt.startedAt.minusSeconds(RegistrationDiagnostics.RETENTION_DAYS * 24 * 60 * 60).toEpochMilli()
        dao.pruneCyclesForAttempts(cutoff, RegistrationDiagnostics.MAX_ATTEMPTS)
        dao.pruneOrphanAttempts()
    } }

    override suspend fun attemptFinished(id: String, completedAt: Instant, status: RegistrationOutcome, errorCode: String?) {
        dao.finishAttempt(id, completedAt.toEpochMilli(), status.name, errorCode)
    }
}

internal fun RegistrationCycleRecord.toEvidence() = RegistrationCycle(id, RegistrationKind.valueOf(kind),
    enumOr(reason, RegistrationReason.UNKNOWN), generation, plannedCount, Instant.ofEpochMilli(startedAt),
    completedAt?.let(Instant::ofEpochMilli), RegistrationOutcome.valueOf(outcome),
    removal?.let(RegistrationStep::valueOf), cleanup?.let(RegistrationStep::valueOf), errorCode)

internal fun RegistrationAttemptRecord.toEvidence() = RegistrationAttempt(id, cycleId, officeId, officeVersion,
    initialTrigger, Instant.ofEpochMilli(startedAt), completedAt?.let(Instant::ofEpochMilli),
    RegistrationOutcome.valueOf(status), errorCode)

private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

/**
 * A short, enumerable failure code. Exception messages, stack traces and payloads are never kept.
 * Play services API failures keep only their numeric status (for example 1000 when geofencing is
 * unavailable).
 */
fun registrationErrorCode(failure: Throwable): String = when {
    failure is com.google.android.gms.common.api.ApiException -> "API_${failure.statusCode}"
    failure is SecurityException -> "PERMISSION"
    else -> "OTHER"
}

/** Only these exported error codes can appear; anything else is rewritten to OTHER. */
internal fun sanitizedErrorCode(code: String?): String? = when {
    code == null -> null
    code == "PERMISSION" || code == "OTHER" -> code
    Regex("API_-?\\d{1,6}").matches(code) -> code
    else -> "OTHER"
}
