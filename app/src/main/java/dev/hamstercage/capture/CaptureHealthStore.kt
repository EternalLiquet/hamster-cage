package dev.hamstercage.capture

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.captureHealthPreferences by preferencesDataStore("capture_health")
private val DELIVERY_FAILED = booleanPreferencesKey("delivery_failed")

/** Only a sanitized failure flag is retained; source facts remain in Room. */
internal object CaptureHealthStore {
    fun deliveryFailure(context: Context): Flow<Boolean> = context.applicationContext.captureHealthPreferences.data
        .map { it[DELIVERY_FAILED] ?: false }
        .catch { if (it is CancellationException) throw it else emit(true) }

    suspend fun setDeliveryFailure(context: Context, failed: Boolean) {
        context.applicationContext.captureHealthPreferences.edit { it[DELIVERY_FAILED] = failed }
    }
}
