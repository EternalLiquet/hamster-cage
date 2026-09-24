package dev.hamstercage.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hamstercage.domain.Policy
import java.time.DayOfWeek
import java.time.ZoneId
import kotlinx.coroutines.flow.map

/** Lightweight policy only; calendar source facts have separate Room write operations. */
data class PolicySettings(
    val zoneId: ZoneId = ZoneId.of("America/New_York"),
    val targetMinutesPerDay: Int = 360,
    val expectedWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val shortGapMinutes: Int = 10,
    val maxOpenSessionHours: Int = 16,
) {
    internal fun toPolicy() = Policy(zoneId, targetMinutesPerDay, expectedWeekdays.toSet(),
        shortGapMinutes = shortGapMinutes, maxOpenSessionHours = maxOpenSessionHours)
}

internal val Context.hamsterPreferences by preferencesDataStore(
    "attendance_policy", produceMigrations = { listOf(LegacyPolicyMigration) },
)

internal class PolicyStore(private val preferences: DataStore<Preferences>) {
    val settings = preferences.data.map(::readSettings)
    suspend fun save(settings: PolicySettings, expected: PolicySettings? = null) {
        settings.toPolicy() // Domain validation occurs before the atomic edit.
        require(settings.expectedWeekdays.isNotEmpty()) { "Choose at least one expected weekday." }
        preferences.edit { current ->
            val existing = readSettings(current) // Invalid/future state must not be silently replaced.
            check(expected == null || existing == expected) { "Policy changed; reopen before editing." }
            current[SCHEMA] = 1
            current[ZONE] = settings.zoneId.id
            current[TARGET] = settings.targetMinutesPerDay
            current[WEEKDAYS] = settings.expectedWeekdays.map { it.value.toString() }.toSet()
            current[GAP] = settings.shortGapMinutes
            current[MAX_OPEN] = settings.maxOpenSessionHours
        }
    }
}

/** Preserve unversioned policy keys from the reference preview; no corruption reset handler. */
internal object LegacyPolicyMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[SCHEMA] == null
    override suspend fun migrate(currentData: Preferences): Preferences {
        readSettings(currentData)
        return currentData.toMutablePreferences().apply { this[SCHEMA] = 1 }
    }
    override suspend fun cleanUp() = Unit
}

private val SCHEMA = intPreferencesKey("policy_schema_version")
private val ZONE = stringPreferencesKey("zone")
private val TARGET = intPreferencesKey("target_minutes")
private val WEEKDAYS = stringSetPreferencesKey("expected_weekdays")
private val GAP = intPreferencesKey("short_gap_minutes")
private val MAX_OPEN = intPreferencesKey("max_open_hours")

private fun readSettings(prefs: Preferences): PolicySettings {
    require(prefs[SCHEMA] == null || prefs[SCHEMA] == 1) { "Unsupported policy schema" }
    val settings = PolicySettings(
        ZoneId.of(prefs[ZONE] ?: "America/New_York"), prefs[TARGET] ?: 360,
        (prefs[WEEKDAYS] ?: setOf("1", "2", "3", "4", "5")).map { DayOfWeek.of(it.toInt()) }.toSet(),
        prefs[GAP] ?: 10, prefs[MAX_OPEN] ?: 16,
    )
    settings.toPolicy()
    return settings
}
