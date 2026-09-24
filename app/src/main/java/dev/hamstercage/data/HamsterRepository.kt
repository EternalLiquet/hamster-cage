package dev.hamstercage.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.Correction
import dev.hamstercage.domain.ExcludedDate
import dev.hamstercage.domain.ExclusionReason
import dev.hamstercage.domain.ManualSession
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecordedEvent(
    val event: RawEvent, val receivedAt: Instant,
    val observedLocationAt: Instant? = null, val source: String = "PLAY_SERVICES_GEOFENCE",
)

/** Source facts only. Every caller supplies an explicit evaluation time for fresh derivation. */
data class AppSnapshot(
    val offices: List<Office>, val eventEvidence: List<RecordedEvent>,
    val corrections: List<Correction>, val manualSessions: List<ManualSession>, val policy: Policy,
) {
    val events: List<RawEvent> get() = eventEvidence.map { it.event }
    fun input(now: Instant) = AttendanceInput(offices, events, corrections, policy, now, manualSessions = manualSessions)
    fun derive(now: Instant) = AttendanceEngine.derive(input(now))
}

sealed interface StorageState {
    data object Loading : StorageState
    data class Ready(val snapshot: AppSnapshot) : StorageState
    /** Never includes SQLite paths, coordinates, raw records or exception details. */
    data object Unavailable : StorageState
}

/** Local source-fact boundary. Engine aggregates are never stored. */
class HamsterRepository internal constructor(
    private val database: HamsterDatabase,
    preferences: DataStore<Preferences>,
    private val clock: TimeSource,
) {
    private val dao = database.dao()
    private val policy = PolicyStore(preferences)
    private val policyEditLock = Mutex()
    private val facts = dao.changes(SimpleSQLiteQuery("SELECT COUNT(*) FROM raw_events")).map {
        database.withTransaction {
            val offices = dao.offices().map { it.toDomain() }
            val events = dao.events().map { it.toEvidence() }
            val corrections = dao.corrections().map { it.toDomain() }
            val manual = dao.manualSessions().map { it.toDomain() }
            val exclusions = dao.exclusions().map { ExcludedDate(LocalDate.parse(it.date), ExclusionReason.valueOf(it.reason), it.note) }
            val wfh = dao.labels().filter { it.isWfh }.map { LocalDate.parse(it.date) }.toSet()
            AppSnapshot(offices, events, corrections, manual, Policy(excludedDates = exclusions, wfhDates = wfh))
        }
    }
    val state: Flow<StorageState> = combine(facts, policy.settings) { snapshot, settings ->
        StorageState.Ready(snapshot.copy(policy = settings.toPolicy().copy(
            excludedDates = snapshot.policy.excludedDates, wfhDates = snapshot.policy.wfhDates,
        ))) as StorageState
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(StorageState.Unavailable)
    }

    suspend fun saveOffice(office: Office, expectedVersion: Long? = null) = database.withTransaction {
        validId(office.id); require(office.name.trim().isNotEmpty() && office.name.length <= 120)
        val existing = dao.office(office.id)
        require(existing == null || expectedVersion == existing.version) { "Office changed; reload before editing." }
        require(existing != null || expectedVersion == null) { "Office no longer exists." }
        require(existing != null || dao.offices().size < 100) { "Office limit reached." }
        val now = clock.now().toEpochMilli()
        dao.upsertOffice(OfficeRecord(office.id, office.name.trim(), office.latitude, office.longitude,
            office.radiusMeters, office.enabled, office.countsTowardAttendance, office.entryGraceMinutes,
            office.exitGraceMinutes, existing?.createdAt ?: now, now, Math.addExact(existing?.version ?: 0L, 1L)))
    }

    suspend fun officeVersion(id: String): Long? = dao.office(id)?.version
    suspend fun savePolicy(settings: PolicySettings, expected: PolicySettings? = null) = policyEditLock.withLock { policy.save(settings, expected) }

    /** Entire batch commits or rolls back. Identical replay is a no-op; conflicting IDs fail. */
    suspend fun appendRawEvents(events: List<RecordedEvent>) = database.withTransaction {
        events.forEach { evidence ->
            val event = evidence.event
            validId(event.id); validId(event.officeId)
            require(evidence.source == "PLAY_SERVICES_GEOFENCE") { "Unsupported raw event source." }
            val record = EventRecord(event.id, event.officeId, event.transition.name, event.at.persistedMillis(),
                evidence.receivedAt.persistedMillis(), evidence.observedLocationAt?.persistedMillis(), evidence.source)
            val previous = dao.event(event.id)
            // A second delivery of the same observation cannot rewrite the first receipt time.
            // The delivery itself may arrive later, while the observed fact remains identical.
            require(previous == null || previous.copy(receivedAt = record.receivedAt) == record) { "Conflicting event ID." }
            if (previous == null) {
                // Delivery may lag behind a user disabling the office. Keep the observation;
                // current policy decides whether it contributes credit during derivation.
                require(dao.office(event.officeId) != null) { "Office was not found." }
                dao.insertEvent(record)
            }
        }
    }

    suspend fun appendCorrection(correction: Correction) = database.withTransaction {
        validId(correction.id); validId(correction.sessionId); validNote(correction.note)
        require(correction.appendSequence >= 0) { "Invalid correction order." }
        if (correction.revertToOriginal) {
            require(correction.createdAt <= clock.now() && correction.start <= clock.now() &&
                (correction.end?.let { it <= clock.now() } != false)) { "Future attendance is invalid." }
        } else validBounds(correction.start, correction.end, correction.createdAt)
        val record = CorrectionRecord(correction.id, correction.sessionId, correction.start.persistedMillis(),
            correction.end?.persistedMillis(), correction.createdAt.persistedMillis(), correction.note, correction.revertToOriginal, correction.appendSequence)
        val previous = dao.correction(correction.id)
        require(previous == null || previous == record) { "Conflicting correction ID." }
        if (previous == null) {
            if (correction.appendSequence > 0) require(correction.appendSequence == Math.addExact(dao.corrections().maxOfOrNull { it.appendSequence } ?: 0, 1)) {
                "Correction order changed; preview again."
            }
            val input = AttendanceInput(dao.offices().map { it.toDomain() }, dao.events().map { it.toEvidence().event },
                now = clock.now(), manualSessions = dao.manualSessions().map { it.toDomain() })
            require(AttendanceEngine.derive(input).sessions.any { correction.sessionId in it.correctionTargetIds }) { "Session was not found." }
            dao.insertCorrection(record)
        }
    }

    suspend fun appendManualSession(session: ManualSession) = database.withTransaction {
        validId(session.id); validId(session.officeId); validNote(session.note)
        validBounds(session.start, session.end, session.createdAt)
        val record = ManualSessionRecord(session.id, session.officeId, session.start.persistedMillis(),
            session.end?.persistedMillis(), session.createdAt.persistedMillis(), session.note)
        val previous = dao.manualSession(session.id)
        require(previous == null || previous == record) { "Conflicting manual session ID." }
        if (previous == null) {
            require(dao.office(session.officeId) != null) { "Office was not found." }
            dao.insertManualSession(record)
        }
    }

    suspend fun saveExclusion(exclusion: ExcludedDate) {
        validNote(exclusion.note)
        dao.upsertExclusion(ExclusionRecord(exclusion.date.toString(), exclusion.reason.name, exclusion.note, clock.now().toEpochMilli()))
    }
    suspend fun removeExclusion(date: LocalDate) = dao.removeExclusion(date.toString())
    suspend fun setWfh(date: LocalDate, enabled: Boolean) = dao.upsertLabel(DayLabelRecord(date.toString(), enabled, clock.now().toEpochMilli()))

    /** Compare all preview source facts inside the Room transaction; a new observation/edit
     * rejects a stale confirmation instead of silently applying a different preview. */
    suspend fun commitAttendanceEdit(edit: AttendanceEdit) = policyEditLock.withLock { database.withTransaction {
        val settings = policy.settings.first()
        val current = AttendanceInput(dao.offices().map { it.toDomain() }, dao.events().map { it.toEvidence().event },
            corrections = dao.corrections().map { it.toDomain() },
            manualSessions = dao.manualSessions().map { it.toDomain() }, now = edit.baseline.now,
            policy = settings.toPolicy().copy(
                excludedDates = dao.exclusions().map { ExcludedDate(LocalDate.parse(it.date), ExclusionReason.valueOf(it.reason), it.note) },
                wfhDates = dao.labels().filter { it.isWfh }.map { LocalDate.parse(it.date) }.toSet(),
            ))
        check(current == edit.baseline.copy(historyStartDate = null, unknownDates = emptySet())) { "Attendance changed; preview again." }
        when (edit) {
            is AttendanceEdit.Correct -> appendCorrection(edit.value)
            is AttendanceEdit.AddManual -> appendManualSession(edit.value)
        }
    } }

    private fun validBounds(start: Instant, end: Instant?, createdAt: Instant) {
        val now = clock.now()
        require(end == null || end > start) { "End must follow start." }
        require(start <= now && (end == null || end <= now) && createdAt <= now) { "Future attendance is invalid." }
    }

    companion object {
        @Volatile private var instance: HamsterRepository? = null
        fun get(context: Context): HamsterRepository = instance ?: synchronized(this) {
            instance ?: HamsterRepository(HamsterDatabase.open(context), context.applicationContext.hamsterPreferences,
                SystemTimeSource()).also { instance = it }
        }
        fun newId(): String = UUID.randomUUID().toString()
    }
}

private fun validId(id: String) { require(id.isNotBlank() && id.length <= 200) { "Invalid source ID." } }
private fun validNote(note: String) { require(note.length <= 2000) { "Note is too long." } }
/** Schema 1 stores epoch milliseconds; reject extra precision rather than silently changing a fact. */
private fun Instant.persistedMillis(): Long = toEpochMilli().also { require(Instant.ofEpochMilli(it) == this) { "Use millisecond precision." } }
private fun OfficeRecord.toDomain() = Office(id, name, latitude, longitude, radiusMeters, enabled, countsTowardAttendance, entryGraceMinutes, exitGraceMinutes)
private fun EventRecord.toEvidence(): RecordedEvent {
    require(payloadVersion == 1) { "Unsupported event payload." }
    validId(id); validId(officeId)
    return RecordedEvent(RawEvent(id, officeId, Transition.valueOf(transition), Instant.ofEpochMilli(at)),
        Instant.ofEpochMilli(receivedAt), observedLocationAt?.let(Instant::ofEpochMilli), source)
}
private fun CorrectionRecord.toDomain() = Correction(id, sessionId, Instant.ofEpochMilli(start), end?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(createdAt), note, revertToOriginal, appendSequence)
private fun ManualSessionRecord.toDomain() = ManualSession(id, officeId, Instant.ofEpochMilli(start), end?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(createdAt), note)
