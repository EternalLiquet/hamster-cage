package dev.hamstercage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "offices")
internal data class OfficeRecord(
    @PrimaryKey val id: String, val name: String, val latitude: Double, val longitude: Double,
    val radiusMeters: Float, val enabled: Boolean, val countsTowardAttendance: Boolean,
    val entryGraceMinutes: Int, val exitGraceMinutes: Int,
    val createdAt: Long, val updatedAt: Long, val version: Long,
)

/** Immutable receipt evidence; the optional location-fix time is not a precise crossing time. */
@Entity(tableName = "raw_events", indices = [Index("officeId"), Index("at")])
internal data class EventRecord(
    @PrimaryKey val id: String, val officeId: String, val transition: String,
    val at: Long, val receivedAt: Long, val observedLocationAt: Long?,
    val source: String, val payloadVersion: Int = 1,
    val accuracyMeters: Float? = null,
    /** Age of the platform fix at receipt, from the elapsed-realtime clock. Null when unknown. */
    val fixAgeMs: Long? = null,
    /** Sanitized provider name of the platform fix, or null when no fix was delivered. */
    val locationProvider: String? = null,
    /** Platform mock flag of the delivered fix; null when no fix was delivered. */
    val locationMock: Boolean? = null,
)

@Entity(tableName = "corrections", indices = [Index("sessionId")])
internal data class CorrectionRecord(
    @PrimaryKey val id: String, val sessionId: String, val start: Long, val end: Long?,
    val createdAt: Long, val note: String,
    @ColumnInfo(defaultValue = "0") val revertToOriginal: Boolean = false,
    @ColumnInfo(defaultValue = "0") val appendSequence: Long = 0,
)

@Entity(tableName = "manual_sessions")
internal data class ManualSessionRecord(
    @PrimaryKey val id: String, val officeId: String, val start: Long, val end: Long?,
    val createdAt: Long, val note: String,
)

@Entity(tableName = "excluded_dates")
internal data class ExclusionRecord(@PrimaryKey val date: String, val reason: String, val note: String, val updatedAt: Long)

@Entity(tableName = "day_labels")
internal data class DayLabelRecord(@PrimaryKey val date: String, val isWfh: Boolean, val updatedAt: Long)

/** Ordinary edits never update/delete source facts; only the explicit privacy reset deletes whole tables. */
@Dao
internal interface HamsterDao {
    @RawQuery(observedEntities = [OfficeRecord::class, EventRecord::class, CorrectionRecord::class,
        ManualSessionRecord::class, ExclusionRecord::class, DayLabelRecord::class])
    fun changes(query: SupportSQLiteQuery): Flow<Int>
    @Query("SELECT * FROM offices ORDER BY createdAt, id") suspend fun offices(): List<OfficeRecord>
    @Query("SELECT * FROM offices WHERE id = :id") suspend fun office(id: String): OfficeRecord?
    @Upsert suspend fun upsertOffice(record: OfficeRecord)
    @Query("SELECT * FROM raw_events ORDER BY at, id") suspend fun events(): List<EventRecord>
    @Query("SELECT * FROM raw_events WHERE id = :id") suspend fun event(id: String): EventRecord?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEvent(record: EventRecord)
    @Query("SELECT * FROM corrections ORDER BY createdAt, id") suspend fun corrections(): List<CorrectionRecord>
    @Query("SELECT * FROM corrections WHERE id = :id") suspend fun correction(id: String): CorrectionRecord?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCorrection(record: CorrectionRecord)
    @Query("SELECT * FROM manual_sessions ORDER BY createdAt, id") suspend fun manualSessions(): List<ManualSessionRecord>
    @Query("SELECT * FROM manual_sessions WHERE id = :id") suspend fun manualSession(id: String): ManualSessionRecord?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertManualSession(record: ManualSessionRecord)
    @Query("SELECT * FROM excluded_dates ORDER BY date") suspend fun exclusions(): List<ExclusionRecord>
    @Upsert suspend fun upsertExclusion(record: ExclusionRecord)
    @Query("DELETE FROM excluded_dates WHERE date = :date") suspend fun removeExclusion(date: String)
    @Query("SELECT * FROM day_labels ORDER BY date") suspend fun labels(): List<DayLabelRecord>
    @Upsert suspend fun upsertLabel(record: DayLabelRecord)
    // Called only by the explicit privacy reset transaction; ordinary editing remains append-only.
    @Query("DELETE FROM raw_events") suspend fun deleteAllEvents()
    @Query("DELETE FROM corrections") suspend fun deleteAllCorrections()
    @Query("DELETE FROM manual_sessions") suspend fun deleteAllManualSessions()
    @Query("DELETE FROM excluded_dates") suspend fun deleteAllExclusions()
    @Query("DELETE FROM day_labels") suspend fun deleteAllLabels()

    @RawQuery(observedEntities = [RegistrationCycleRecord::class, RegistrationAttemptRecord::class])
    fun registrationChanges(query: SupportSQLiteQuery): Flow<Int>
    @Query("SELECT * FROM registration_cycles ORDER BY startedAt, id") suspend fun registrationCycles(): List<RegistrationCycleRecord>
    @Query("SELECT * FROM registration_attempts ORDER BY startedAt, id") suspend fun registrationAttempts(): List<RegistrationAttemptRecord>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCycle(record: RegistrationCycleRecord)
    @Query("SELECT EXISTS(SELECT 1 FROM registration_cycles WHERE id = :id)") suspend fun cycleExists(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttempt(record: RegistrationAttemptRecord)
    @Query("""UPDATE registration_cycles SET completedAt = :completedAt, outcome = :outcome, removal = :removal,
        cleanup = :cleanup, errorCode = :errorCode WHERE id = :id AND outcome = 'PENDING'""")
    suspend fun finishCycle(id: String, completedAt: Long, outcome: String, removal: String?, cleanup: String?, errorCode: String?)
    @Query("UPDATE registration_attempts SET completedAt = :completedAt, status = :status, errorCode = :errorCode WHERE id = :id AND status = 'PENDING'")
    suspend fun finishAttempt(id: String, completedAt: Long, status: String, errorCode: String?)
    @Query("UPDATE registration_attempts SET status = :interrupted WHERE status = 'PENDING'")
    suspend fun interruptPendingAttempts(interrupted: String)
    @Query("UPDATE registration_cycles SET outcome = :interrupted WHERE outcome = 'PENDING'")
    suspend fun interruptPendingCycles(interrupted: String)
    @Query("""DELETE FROM registration_cycles WHERE startedAt < :cutoff OR id NOT IN
        (SELECT id FROM registration_cycles ORDER BY startedAt DESC, id DESC LIMIT :keep)""")
    suspend fun pruneCycles(cutoff: Long, keep: Int)
    @Query("""DELETE FROM registration_attempts WHERE startedAt < :cutoff OR id NOT IN
        (SELECT id FROM registration_attempts ORDER BY startedAt DESC, id DESC LIMIT :keep)""")
    suspend fun pruneAttempts(cutoff: Long, keep: Int)
    // Privacy reset only, in the same transaction as attendance history.
    @Query("DELETE FROM registration_attempts") suspend fun deleteAllRegistrationAttempts()
    @Query("DELETE FROM registration_cycles") suspend fun deleteAllRegistrationCycles()
}

@Database(entities = [OfficeRecord::class, EventRecord::class, CorrectionRecord::class,
    ManualSessionRecord::class, ExclusionRecord::class, DayLabelRecord::class,
    RegistrationCycleRecord::class, RegistrationAttemptRecord::class], version = 4, exportSchema = true)
internal abstract class HamsterDatabase : RoomDatabase() {
    abstract fun dao(): HamsterDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE corrections ADD COLUMN revertToOriginal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE corrections ADD COLUMN appendSequence INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE raw_events ADD COLUMN accuracyMeters REAL")
            }
        }
        /** Additive only: existing facts and correction history are untouched; new fix fields stay NULL. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE raw_events ADD COLUMN fixAgeMs INTEGER")
                db.execSQL("ALTER TABLE raw_events ADD COLUMN locationProvider TEXT")
                db.execSQL("ALTER TABLE raw_events ADD COLUMN locationMock INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `registration_cycles` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `reason` TEXT NOT NULL, `generation` INTEGER NOT NULL, `plannedCount` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER, `outcome` TEXT NOT NULL, `removal` TEXT, `cleanup` TEXT, `errorCode` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_registration_cycles_startedAt` ON `registration_cycles` (`startedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `registration_attempts` (`id` TEXT NOT NULL, `cycleId` TEXT NOT NULL, `officeId` TEXT NOT NULL, `officeVersion` INTEGER, `initialTrigger` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER, `status` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_registration_attempts_cycleId` ON `registration_attempts` (`cycleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_registration_attempts_startedAt` ON `registration_attempts` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_registration_attempts_officeId` ON `registration_attempts` (`officeId`)")
            }
        }
        fun open(context: Context, name: String = "hamster-cage.db"): HamsterDatabase =
            Room.databaseBuilder(context.applicationContext, HamsterDatabase::class.java, name)
                .openHelperFactory(PreservingOpenHelperFactory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        // No destructive fallback. Every future version requires a reviewed, tested Migration.
    }
}

/** Android's default corruption callback deletes database files; preserve them and report failure. */
private object PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                throw SQLiteDatabaseCorruptException("Local database could not be read; data was not reset.")
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false).build(),
        )
    }
}
