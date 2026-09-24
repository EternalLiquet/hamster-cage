package dev.hamstercage.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "offices")
data class OfficeRecord(
    @PrimaryKey val id: String, val name: String, val latitude: Double, val longitude: Double,
    val radiusMeters: Float, val enabled: Boolean, val countsTowardAttendance: Boolean,
    val entryGraceMinutes: Int, val exitGraceMinutes: Int,
    val createdAt: Long, val updatedAt: Long, val version: Long,
)

/** Append-only evidence. observedLocationAt is a location fix time, NOT an exact crossing time. */
@Entity(tableName = "raw_events", indices = [Index("officeId"), Index("at")])
data class EventRecord(
    @PrimaryKey val id: String, val officeId: String, val transition: String,
    val at: Long, val receivedAt: Long, val observedLocationAt: Long?,
    val source: String, val payloadVersion: Int = 1,
)

@Entity(tableName = "corrections", indices = [Index("sessionId")])
data class CorrectionRecord(
    @PrimaryKey val id: String, val sessionId: String, val start: Long, val end: Long?,
    val createdAt: Long, val note: String,
)

@Entity(tableName = "manual_sessions")
data class ManualSessionRecord(
    @PrimaryKey val id: String, val officeId: String, val start: Long, val end: Long?,
    val createdAt: Long, val note: String,
)

@Entity(tableName = "excluded_dates")
data class ExclusionRecord(@PrimaryKey val date: String, val reason: String, val note: String, val updatedAt: Long)

@Entity(tableName = "day_labels")
data class DayLabelRecord(@PrimaryKey val date: String, val isWfh: Boolean, val updatedAt: Long)

@Dao
interface HamsterDao {
    @Query("SELECT * FROM offices ORDER BY createdAt, id") fun offices(): Flow<List<OfficeRecord>>
    @Query("SELECT * FROM offices ORDER BY createdAt, id") suspend fun officeList(): List<OfficeRecord>
    @Query("SELECT * FROM offices WHERE id = :id") suspend fun office(id: String): OfficeRecord?
    @Upsert suspend fun upsertOffice(record: OfficeRecord)
    @Query("SELECT * FROM raw_events ORDER BY at, id") fun events(): Flow<List<EventRecord>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvents(records: List<EventRecord>)
    @Query("SELECT * FROM corrections ORDER BY createdAt, id") fun corrections(): Flow<List<CorrectionRecord>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCorrection(record: CorrectionRecord)
    @Query("SELECT * FROM manual_sessions ORDER BY createdAt, id") fun manualSessions(): Flow<List<ManualSessionRecord>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertManualSession(record: ManualSessionRecord)
    @Query("SELECT * FROM excluded_dates ORDER BY date") fun exclusions(): Flow<List<ExclusionRecord>>
    @Upsert suspend fun upsertExclusion(record: ExclusionRecord)
    @Query("DELETE FROM excluded_dates WHERE date = :date") suspend fun removeExclusion(date: String)
    @Query("SELECT * FROM day_labels ORDER BY date") fun labels(): Flow<List<DayLabelRecord>>
    @Upsert suspend fun upsertLabel(record: DayLabelRecord)
    @Query("DELETE FROM raw_events") suspend fun clearEvents()
    @Query("DELETE FROM corrections") suspend fun clearCorrections()
    @Query("DELETE FROM excluded_dates") suspend fun clearExclusions()
    @Query("DELETE FROM day_labels") suspend fun clearLabels()
    @Query("DELETE FROM manual_sessions") suspend fun clearManualSessions()
    @Transaction suspend fun clearAttendance() {
        clearCorrections(); clearEvents(); clearManualSessions(); clearExclusions(); clearLabels()
    }
}

@Database(entities = [OfficeRecord::class, EventRecord::class, CorrectionRecord::class,
    ExclusionRecord::class, DayLabelRecord::class, ManualSessionRecord::class], version = 1, exportSchema = true)
abstract class HamsterDatabase : RoomDatabase() {
    abstract fun dao(): HamsterDao
    companion object {
        @Volatile private var instance: HamsterDatabase? = null
        fun get(context: Context): HamsterDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HamsterDatabase::class.java,
                "hamster-cage.db").build().also { instance = it }
        }
    }
}
