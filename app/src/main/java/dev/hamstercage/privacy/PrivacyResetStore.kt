package dev.hamstercage.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.privacyResetPreferences by preferencesDataStore("privacy_reset")
private val VERSION = intPreferencesKey("version")
private val GENERATION = longPreferencesKey("generation")
private val PENDING = booleanPreferencesKey("pending_history_delete")

sealed interface PrivacyResetState {
    val generation: Long
    data class Idle(override val generation: Long) : PrivacyResetState
    data class Pending(override val generation: Long) : PrivacyResetState
    /** Corrupt or unsupported metadata must never make capture look healthy. */
    data object Unavailable : PrivacyResetState { override val generation: Long = -1 }
}

/** The journal is persisted before any history fact is removed. Recovery repeats an unfinished deletion. */
internal class PrivacyResetJournal(private val preferences: DataStore<Preferences>) {
    val state: Flow<PrivacyResetState> = preferences.data
        .map(::decode)
        .catch { if (it is CancellationException) throw it else emit(PrivacyResetState.Unavailable) }

    suspend fun read(): PrivacyResetState = state.first()

    suspend fun begin(): PrivacyResetState.Pending {
        var result: PrivacyResetState.Pending? = null
        preferences.edit { mutable ->
            val current = decode(mutable)
            val generation = when (current) {
                is PrivacyResetState.Pending -> current.generation
                is PrivacyResetState.Idle -> Math.addExact(current.generation, 1L)
                PrivacyResetState.Unavailable -> throw IOException("Privacy reset state unavailable")
            }
            mutable[VERSION] = 1
            mutable[GENERATION] = generation
            mutable[PENDING] = true
            result = PrivacyResetState.Pending(generation)
        }
        return requireNotNull(result)
    }

    suspend fun complete(generation: Long) {
        preferences.edit { mutable ->
            require(decode(mutable) == PrivacyResetState.Pending(generation)) { "Privacy reset changed" }
            mutable[PENDING] = false
        }
    }

    private fun decode(preferences: Preferences): PrivacyResetState {
        require((preferences[VERSION] ?: 1) == 1) { "Unsupported privacy reset version" }
        val generation = preferences[GENERATION] ?: 0L
        require(generation >= 0) { "Invalid privacy reset generation" }
        return if (preferences[PENDING] == true) PrivacyResetState.Pending(generation)
        else PrivacyResetState.Idle(generation)
    }
}

internal object PrivacyResetStore {
    fun journal(context: Context) = PrivacyResetJournal(context.applicationContext.privacyResetPreferences)
    fun state(context: Context): Flow<PrivacyResetState> = journal(context).state
    suspend fun read(context: Context): PrivacyResetState = journal(context).read()
    suspend fun begin(context: Context): PrivacyResetState.Pending = journal(context).begin()
    suspend fun complete(context: Context, generation: Long) = journal(context).complete(generation)
}
