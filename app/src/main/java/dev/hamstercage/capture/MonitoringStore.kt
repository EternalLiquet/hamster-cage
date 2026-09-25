package dev.hamstercage.capture

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.monitoringPreferences by preferencesDataStore("attendance_monitoring")
private val ENABLED = booleanPreferencesKey("enabled")
private val VERIFIED = longPreferencesKey("last_verified_ms")
private val RESULT = stringPreferencesKey("last_result")
private val FIX_ACCURACY = floatPreferencesKey("last_fix_accuracy_meters")

data class MonitoringState(val enabled: Boolean = true, val lastVerifiedAt: Instant? = null,
    val lastResult: String? = null, val lastFixAccuracyMeters: Float? = null)

/** Holds only a user switch and sanitized health; coordinates remain transient. */
internal object MonitoringStore {
    fun state(context: Context): Flow<MonitoringState> = context.applicationContext.monitoringPreferences.data
        .map { MonitoringState(it[ENABLED] ?: true, it[VERIFIED]?.let(Instant::ofEpochMilli),
            it[RESULT], it[FIX_ACCURACY]) }
        .catch { if (it is CancellationException) throw it else emit(MonitoringState(enabled = false, lastResult = "Unavailable")) }

    suspend fun read(context: Context): MonitoringState = state(context).first()

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.monitoringPreferences.edit { it[ENABLED] = enabled }
    }

    suspend fun record(context: Context, result: String, verifiedAt: Instant? = null,
        accuracyMeters: Float? = null) {
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters in 0f..10000f)
        context.applicationContext.monitoringPreferences.edit {
            it[RESULT] = result
            if (verifiedAt != null) it[VERIFIED] = verifiedAt.toEpochMilli()
            if (accuracyMeters == null) it.remove(FIX_ACCURACY) else it[FIX_ACCURACY] = accuracyMeters
        }
    }

    suspend fun clearDiagnostics(context: Context) {
        context.applicationContext.monitoringPreferences.edit {
            it.remove(VERIFIED)
            it.remove(RESULT)
            it.remove(FIX_ACCURACY)
        }
    }
}
