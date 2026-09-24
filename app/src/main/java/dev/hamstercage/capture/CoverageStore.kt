package dev.hamstercage.capture

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.coveragePreferences by preferencesDataStore("capture_coverage")
private val VERSION = intPreferencesKey("version")
private val START = longPreferencesKey("history_start_day")
private val UNKNOWN = stringSetPreferencesKey("unknown_days")
private val REVIEWED = stringSetPreferencesKey("reviewed_days")
private val HEALTHY = longPreferencesKey("last_healthy_ms")
private val OUTAGE = longPreferencesKey("outage_started_ms")
private val THROUGH = longPreferencesKey("outage_through_day")
private val BOUNDARY = longPreferencesKey("recovery_boundary_ms")
private val OBSERVED = longPreferencesKey("last_observation_ms")
private val REGISTRATION = stringPreferencesKey("registration")

/** App-private, atomic capture confidence metadata, separate from immutable Room facts. */
internal object CoverageStore {
    fun state(context: Context): Flow<CoverageLedger> = context.applicationContext.coveragePreferences.data
        .map(::decode)
        .catch { if (it is CancellationException) throw it else emit(CoverageLedger(registration = RegistrationStatus.FAILED)) }

    suspend fun change(context: Context, update: (CoverageLedger) -> CoverageLedger): CoverageLedger {
        var result: CoverageLedger? = null
        context.applicationContext.coveragePreferences.edit { preferences ->
            result = update(decode(preferences))
            encode(preferences, result!!)
        }
        return requireNotNull(result)
    }

    private fun decode(p: Preferences): CoverageLedger {
        require((p[VERSION] ?: 1) == 1) { "Unsupported capture coverage version" }
        fun date(value: Long?) = value?.let(LocalDate::ofEpochDay)
        fun instant(value: Long?) = value?.let(Instant::ofEpochMilli)
        return CoverageLedger(date(p[START]), p[UNKNOWN].orEmpty().map(LocalDate::parse).toSet(),
            p[REVIEWED].orEmpty().map(LocalDate::parse).toSet(), instant(p[HEALTHY]),
            instant(p[OUTAGE]), date(p[THROUGH]), instant(p[BOUNDARY]), instant(p[OBSERVED]),
            p[REGISTRATION]?.let(RegistrationStatus::valueOf) ?: RegistrationStatus.UNKNOWN)
    }

    private fun encode(p: androidx.datastore.preferences.core.MutablePreferences, value: CoverageLedger) {
        p[VERSION] = 1
        fun set(key: Preferences.Key<Long>, value: Long?) { if (value == null) p.remove(key) else p[key] = value }
        set(START, value.historyStartDate?.toEpochDay())
        p[UNKNOWN] = value.unknownDates.map(LocalDate::toString).toSet()
        p[REVIEWED] = value.reviewedDates.map(LocalDate::toString).toSet()
        set(HEALTHY, value.lastHealthyAt?.toEpochMilli())
        set(OUTAGE, value.outageStartedAt?.toEpochMilli())
        set(THROUGH, value.outageRecordedThrough?.toEpochDay())
        set(BOUNDARY, value.recoveryBoundaryAt?.toEpochMilli())
        set(OBSERVED, value.lastObservationAt?.toEpochMilli())
        p[REGISTRATION] = value.registration.name
    }
}
