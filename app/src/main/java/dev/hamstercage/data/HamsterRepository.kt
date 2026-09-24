package dev.hamstercage.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.hamstercage.domain.*
import dev.hamstercage.location.GeofenceRegistrar
import dev.hamstercage.location.TrackingHealth
import dev.hamstercage.location.LocationPermissions
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.UUID

private val Context.hamsterPreferences by preferencesDataStore("attendance_policy")

data class AppSnapshot(
    val offices: List<Office> = emptyList(), val events: List<RawEvent> = emptyList(),
    val corrections: List<Correction> = emptyList(), val policy: Policy = Policy(),
    val historyStartDate: LocalDate? = null, val trackingHealth: TrackingHealth = TrackingHealth(),
    val manualEventIds: Set<String> = emptySet(), val unknownDates: Set<LocalDate> = emptySet(),
    val eventEvidence: List<EventEvidence> = emptyList(),
    val manualSessions: List<ManualSession> = emptyList(),
    val canReviewToday: Boolean = false,
)
data class EventEvidence(val id: String, val receivedAt: Instant, val observedLocationAt: Instant?, val source: String)

/** All writes remain local. Raw facts/corrections are append-only except explicitly confirmed erasure. */
class HamsterRepository(context: Context) {
    private val context = context.applicationContext
    private val dao = HamsterDatabase.get(context).dao()
    private val preferences = this.context.hamsterPreferences
    private val sourceState = combine(dao.offices(), dao.events(), dao.corrections(), dao.exclusions(), dao.labels()) {
        offices, events, corrections, exclusions, labels ->
        AppSnapshot(
            offices = offices.map { it.toDomain() },
            events = events.map { RawEvent(it.id, it.officeId, Transition.valueOf(it.transition), Instant.ofEpochMilli(it.at)) },
            corrections = corrections.map { Correction(it.id, it.sessionId, Instant.ofEpochMilli(it.start), it.end?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(it.createdAt), it.note) },
            policy = Policy(excludedDates = exclusions.map { ExcludedDate(LocalDate.parse(it.date), ExclusionReason.valueOf(it.reason), it.note) },
                wfhDates = labels.filter { it.isWfh }.map { LocalDate.parse(it.date) }.toSet()),
            manualEventIds = events.filter { it.source == "MANUAL" }.map { it.id }.toSet(),
            eventEvidence = events.map { EventEvidence(it.id, Instant.ofEpochMilli(it.receivedAt), it.observedLocationAt?.let(Instant::ofEpochMilli), it.source) },
        )
    }
    val state: Flow<AppSnapshot> = combine(sourceState, preferences.data, dao.manualSessions()) { snapshot, prefs, manual ->
        snapshot.copy(
            canReviewToday = LocationPermissions.health(context).canTrack &&
                (prefs[REGISTERED_COUNT] ?: 0) > 0 && prefs[REGISTRATION_ERROR] == null,
            manualSessions = manual.map { ManualSession(it.id, it.officeId, Instant.ofEpochMilli(it.start), it.end?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(it.createdAt), it.note) },
            policy = snapshot.policy.copy(
                zoneId = ZoneId.of(prefs[ZONE] ?: "America/New_York"),
                targetMinutesPerDay = prefs[TARGET] ?: 360,
                expectedWeekdays = (prefs[WEEKDAYS] ?: setOf("1", "2", "3", "4", "5")).map { DayOfWeek.of(it.toInt()) }.toSet(),
                shortGapMinutes = prefs[GAP] ?: 10,
                maxOpenSessionHours = prefs[MAX_OPEN] ?: 16,
            ),
            historyStartDate = prefs[HISTORY_START]?.let(LocalDate::parse),
            unknownDates = unknownCoverageDates(prefs[UNKNOWN_DATES] ?: emptySet(), prefs[GAP_SINCE],
                LocalDate.now(ZoneId.of(prefs[ZONE] ?: "America/New_York")), prefs[VERIFIED_DATES] ?: emptySet()),
            trackingHealth = LocationPermissions.health(context).copy(
                registeredOfficeCount = prefs[REGISTERED_COUNT] ?: 0,
                lastRegisteredAt = prefs[REGISTERED_AT]?.let(Instant::ofEpochMilli),
                registrationError = prefs[CAPTURE_ERROR] ?: prefs[REGISTRATION_ERROR],
            ),
        )
    }

    suspend fun saveOffice(office: Office) {
        require(office.name.length <= 120 && office.id.length <= 100)
        val existing = dao.office(office.id)
        require(existing != null || dao.officeList().size < 100) { "At most 100 offices are supported." }
        val now = Instant.now().toEpochMilli()
        dao.upsertOffice(OfficeRecord(office.id, office.name.trim(), office.latitude, office.longitude,
            office.radiusMeters, office.enabled, office.countsTowardAttendance, office.entryGraceMinutes,
            office.exitGraceMinutes, existing?.createdAt ?: now, now, (existing?.version ?: 0) + 1))
        requestRegistration(force = true)
    }

    suspend fun savePolicy(policy: Policy) {
        preferences.edit {
            it[ZONE] = policy.zoneId.id; it[TARGET] = policy.targetMinutesPerDay
            it[WEEKDAYS] = policy.expectedWeekdays.map { day -> day.value.toString() }.toSet()
            it[GAP] = policy.shortGapMinutes; it[MAX_OPEN] = policy.maxOpenSessionHours
        }
    }
    suspend fun saveExclusion(exclusion: ExcludedDate) {
        require(exclusion.note.length <= 2000)
        dao.upsertExclusion(ExclusionRecord(exclusion.date.toString(), exclusion.reason.name, exclusion.note, System.currentTimeMillis()))
    }
    suspend fun removeExclusion(date: LocalDate) = dao.removeExclusion(date.toString())
    suspend fun setWfh(date: LocalDate, isWfh: Boolean) = dao.upsertLabel(DayLabelRecord(date.toString(), isWfh, System.currentTimeMillis()))
    /** Call only after the user explicitly verifies the entire day's entries, including zero attendance. */
    suspend fun confirmDayReviewed(date: LocalDate) {
        val health = LocationPermissions.health(context)
        preferences.edit {
            val zone = ZoneId.of(it[ZONE] ?: "America/New_York")
            val today = LocalDate.now(zone)
            require(!date.isAfter(today)) { "Future dates cannot be verified." }
            val tracking = health.canTrack && (it[REGISTERED_COUNT] ?: 0) > 0 && it[REGISTRATION_ERROR] == null
            require(date != today || tracking) { "Today is still in progress without automatic monitoring. Review it after the day ends." }
            val hadNoHistory = it[HISTORY_START] == null
            val start = it[HISTORY_START]?.let(LocalDate::parse) ?: today
            var unknown = it[UNKNOWN_DATES] ?: emptySet()
            if (hadNoHistory) unknown = unknown + today.toString()
            if (!tracking && it[GAP_SINCE] == null) it[GAP_SINCE] = today.toString()
            if (date.isBefore(start)) {
                // Verifying one historical day does not imply the intervening days were observed.
                unknown = unknown + generateSequence(date.plusDays(1)) { d -> d.plusDays(1) }
                    .takeWhile { d -> d.isBefore(start) }.map { d -> d.toString() }.toSet()
                it[HISTORY_START] = date.toString()
            } else if (it[HISTORY_START] == null) it[HISTORY_START] = date.toString()
            it[UNKNOWN_DATES] = unknown - date.toString()
            it[VERIFIED_DATES] = (it[VERIFIED_DATES] ?: emptySet()) + date.toString()
            val diagnostics = context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE)
            val failedAt = diagnostics.getLong("capture_failed_at", 0)
            if (failedAt > 0 && Instant.ofEpochMilli(failedAt).atZone(zone).toLocalDate() == date) {
                it.remove(CAPTURE_ERROR)
                diagnostics.edit().clear().commit()
            }
        }
    }
    suspend fun saveCorrection(correction: Correction) {
        val end = correction.end
        require(correction.id.isNotBlank() && correction.sessionId.isNotBlank() && correction.note.length <= 2000)
        require(end == null || end.isAfter(correction.start)) { "End must follow start." }
        require(!correction.start.isAfter(Instant.now()) && (end == null || !end.isAfter(Instant.now()))) { "Attendance cannot be in the future." }
        dao.insertCorrection(CorrectionRecord(correction.id, correction.sessionId, correction.start.toEpochMilli(), end?.toEpochMilli(), correction.createdAt.toEpochMilli(), correction.note))
        ensureHistoryStart(Instant.now())
    }

    suspend fun addManualSession(officeId: String, start: Instant, end: Instant?, note: String = "") {
        require(dao.office(officeId) != null) { "Choose an existing office." }
        require(end == null || end.isAfter(start)) { "End must follow start." }
        require(!start.isAfter(Instant.now()) && (end == null || !end.isAfter(Instant.now()))) { "Attendance cannot be in the future." }
        require(note.length <= 2000)
        val received = Instant.now().toEpochMilli()
        dao.insertManualSession(ManualSessionRecord(UUID.randomUUID().toString(), officeId,
            start.toEpochMilli(), end?.toEpochMilli(), received, note))
        ensureHistoryStart(Instant.now())
    }

    /** Typed DELETE confirmation is checked again here, not only in the UI. Keeps office configuration. */
    suspend fun deleteAttendanceHistory(confirmation: String) {
        require(confirmation == "DELETE") { "Type DELETE to erase local attendance history." }
        dao.clearAttendance()
        preferences.edit { it.remove(HISTORY_START); it.remove(UNKNOWN_DATES); it.remove(VERIFIED_DATES); it.remove(CAPTURE_ERROR); it.remove(GAP_SINCE) }
        context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun requestRegistration(force: Boolean = false) = GeofenceRegistrar(context, this).register(force)

    suspend fun refreshHealth() {
        val health = LocationPermissions.health(context)
        val prefs = preferences.data.first()
        val today = LocalDate.now(ZoneId.of(prefs[ZONE] ?: "America/New_York")).toString()
        preferences.edit {
            it[HEALTH_CHECK] = System.currentTimeMillis()
            if ((!health.canTrack || (it[REGISTERED_COUNT] ?: 0) == 0) && it[HISTORY_START] != null) {
                it[UNKNOWN_DATES] = (it[UNKNOWN_DATES] ?: emptySet()) + today
                if (it[GAP_SINCE] == null) it[GAP_SINCE] = today
            }
            if (context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE).getBoolean("capture_failed", false)) {
                it[CAPTURE_ERROR] = "An office event could not be saved. Review your recent attendance and add a correction."
                val failureAt = context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE).getLong("capture_failed_at", System.currentTimeMillis())
                val failureDate = Instant.ofEpochMilli(failureAt).atZone(ZoneId.of(it[ZONE] ?: "America/New_York")).toLocalDate().toString()
                it[UNKNOWN_DATES] = (it[UNKNOWN_DATES] ?: emptySet()) + failureDate
                it[VERIFIED_DATES] = (it[VERIFIED_DATES] ?: emptySet()) - failureDate
            }
        }
        requestRegistration()
    }

    internal suspend fun officeList(): List<Office> = dao.officeList().map { it.toDomain() }
    internal suspend fun recordPlatformEvents(officeIds: List<String>, transition: Transition, receivedAt: Instant, observedLocationAt: Instant?) {
        val knownOffices = dao.officeList().map { it.id }.toSet()
        // The fix timestamp is kept as provenance only; receipt time is the conservative observed bound.
        val events = officeIds.distinct().filter { it in knownOffices }.map {
            EventRecord(UUID.randomUUID().toString(), it, transition.name, receivedAt.toEpochMilli(), receivedAt.toEpochMilli(),
                observedLocationAt?.toEpochMilli(), "PLAY_SERVICES_GEOFENCE")
        }
        dao.insertEvents(events)
        if (events.isNotEmpty()) ensureHistoryStart(receivedAt)
    }
    internal suspend fun registrationState(): Triple<String?, Long?, String?> {
        val p = preferences.data.first()
        return Triple(p[REGISTRATION_FINGERPRINT], p[REGISTERED_AT], p[REGISTRATION_ERROR])
    }
    internal suspend fun recordRegistration(count: Int, fingerprint: String?, error: String?) {
        preferences.edit {
            it[REGISTERED_COUNT] = count
            val today = LocalDate.now(ZoneId.of(it[ZONE] ?: "America/New_York"))
            if ((error != null || count == 0) && it[HISTORY_START] != null) {
                it[UNKNOWN_DATES] = (it[UNKNOWN_DATES] ?: emptySet()) + today.toString()
                if (it[GAP_SINCE] == null) it[GAP_SINCE] = today.toString()
                // A fresh interruption after healthy monitoring invalidates an earlier same-day review.
                // Repeated reports of the same unresolved gap must not undo an explicit manual review.
                if (error != null && it[REGISTRATION_ERROR] == null)
                    it[VERIFIED_DATES] = (it[VERIFIED_DATES] ?: emptySet()) - today.toString()
            }
            if (error == null) {
                it[GAP_SINCE]?.takeIf { count > 0 }?.let { start ->
                    val startDate = LocalDate.parse(start)
                    val gapDates = generateSequence(startDate) { date -> date.plusDays(1) }.takeWhile { date -> !date.isAfter(today) }.map { date -> date.toString() }.toSet()
                    it[UNKNOWN_DATES] = (it[UNKNOWN_DATES] ?: emptySet()) + gapDates
                    it.remove(GAP_SINCE)
                }
                it.remove(REGISTRATION_ERROR)
                it[REGISTERED_AT] = System.currentTimeMillis()
                if (fingerprint != null) it[REGISTRATION_FINGERPRINT] = fingerprint
            } else {
                it[REGISTRATION_ERROR] = error
                it.remove(REGISTRATION_FINGERPRINT)
            }
        }
        if (error == null && count > 0) ensureHistoryStart(Instant.now())
    }
    private suspend fun ensureHistoryStart(instant: Instant) {
        preferences.edit {
            val date = instant.atZone(ZoneId.of(it[ZONE] ?: "America/New_York")).toLocalDate().toString()
            if (it[HISTORY_START] == null) {
                it[HISTORY_START] = date
                // A mid-day start cannot prove what happened before monitoring began.
                it[UNKNOWN_DATES] = (it[UNKNOWN_DATES] ?: emptySet()) + date
            }
        }
    }
    companion object {
        private val ZONE = stringPreferencesKey("zone")
        private val TARGET = intPreferencesKey("target_minutes")
        private val WEEKDAYS = stringSetPreferencesKey("expected_weekdays")
        private val GAP = intPreferencesKey("short_gap_minutes")
        private val MAX_OPEN = intPreferencesKey("max_open_hours")
        private val HISTORY_START = stringPreferencesKey("history_start_date")
        private val UNKNOWN_DATES = stringSetPreferencesKey("unknown_dates")
        private val VERIFIED_DATES = stringSetPreferencesKey("verified_dates")
        private val REGISTERED_COUNT = intPreferencesKey("registered_count")
        private val REGISTERED_AT = longPreferencesKey("registered_at")
        private val REGISTRATION_ERROR = stringPreferencesKey("registration_error")
        private val REGISTRATION_FINGERPRINT = stringPreferencesKey("registration_fingerprint")
        private val HEALTH_CHECK = longPreferencesKey("health_check")
        private val CAPTURE_ERROR = stringPreferencesKey("capture_error")
        private val GAP_SINCE = stringPreferencesKey("monitoring_gap_since")
    }
}

internal fun OfficeRecord.toDomain() = Office(id, name, latitude, longitude, radiusMeters, enabled,
    countsTowardAttendance, entryGraceMinutes, exitGraceMinutes)
