package com.example

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val REPORT_TYPE_TEXT = "TEXT"
const val REPORT_TYPE_AUDIO = "AUDIO"

object LeaveTypes {
    const val DAILY = "مرخصی روزانه"
    const val SICK = "مرخصی استعلاجی"
    const val TOURAHI = "توراهی"

    val ALL = listOf(DAILY, SICK, TOURAHI)
}

@Entity(tableName = "time_records")
data class TimeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jalaliYear: Int,
    val jalaliMonth: Int,
    val jalaliDay: Int,
    val startTimeMillis: Long,
    val endTimeMillis: Long?, // Null if the current shift is active (not finished)
    val isManual: Boolean = false
)

@Entity(tableName = "obligatory_hours", primaryKeys = ["jalaliYear", "jalaliMonth"])
data class MonthlyObligatoryHours(
    val jalaliYear: Int,
    val jalaliMonth: Int,
    val requiredHours: Double
)

/**
 * گزارش ثبت شده برای هر سابقه کارکرد.
 * در گزارش صوتی فایل صوتی داخل دیتابیس ذخیره نمی‌شود و فقط نشانی نسخه‌های
 * ارسال شده به گوگل درایو و تلگرام نگه داشته می‌شود تا حجم دیتابیس بالا نرود.
 */
@Entity(tableName = "work_reports", indices = [Index(value = ["recordId"])])
data class WorkReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val reportType: String,
    val textContent: String? = null,
    val audioFileName: String? = null,
    val driveUrl: String? = null,
    val telegramRef: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

val WorkReport.isAudio: Boolean
    get() = reportType == REPORT_TYPE_AUDIO

@Entity(
    tableName = "leave_days",
    indices = [Index(value = ["jalaliYear", "jalaliMonth", "jalaliDay"], unique = true)]
)
data class LeaveDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jalaliYear: Int,
    val jalaliMonth: Int,
    val jalaliDay: Int,
    val leaveType: String,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TimeTrackingDao {
    @Query("SELECT * FROM time_records ORDER BY startTimeMillis DESC")
    fun getAllRecordsFlow(): Flow<List<TimeRecord>>

    @Query("SELECT * FROM time_records WHERE jalaliYear = :year AND jalaliMonth = :month ORDER BY startTimeMillis ASC")
    suspend fun getRecordsForMonth(year: Int, month: Int): List<TimeRecord>

    @Query("SELECT * FROM time_records WHERE startTimeMillis >= :startMillis AND startTimeMillis <= :endMillis ORDER BY startTimeMillis ASC")
    suspend fun getRecordsInRange(startMillis: Long, endMillis: Long): List<TimeRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: TimeRecord): Long

    @Update
    suspend fun updateRecord(record: TimeRecord)

    @Delete
    suspend fun deleteRecord(record: TimeRecord)

    @Query("SELECT * FROM time_records WHERE endTimeMillis IS NULL LIMIT 1")
    suspend fun getActiveRecord(): TimeRecord?

    @Query("SELECT * FROM obligatory_hours WHERE jalaliYear = :year AND jalaliMonth = :month LIMIT 1")
    suspend fun getObligatoryHours(year: Int, month: Int): MonthlyObligatoryHours?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObligatoryHours(hours: MonthlyObligatoryHours)

    @Query("SELECT * FROM work_reports ORDER BY createdAt DESC")
    fun getAllReportsFlow(): Flow<List<WorkReport>>

    @Query("SELECT * FROM work_reports WHERE recordId = :recordId ORDER BY createdAt ASC")
    suspend fun getReportsForRecord(recordId: Long): List<WorkReport>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: WorkReport): Long

    @Update
    suspend fun updateReport(report: WorkReport)

    @Delete
    suspend fun deleteReport(report: WorkReport)

    @Query("SELECT * FROM leave_days ORDER BY jalaliYear DESC, jalaliMonth DESC, jalaliDay DESC")
    fun getAllLeavesFlow(): Flow<List<LeaveDay>>

    @Query("SELECT * FROM leave_days WHERE jalaliYear = :year AND jalaliMonth = :month ORDER BY jalaliDay ASC")
    suspend fun getLeavesForMonth(year: Int, month: Int): List<LeaveDay>

    @Query("SELECT * FROM leave_days WHERE jalaliYear = :year AND jalaliMonth = :month AND jalaliDay = :day LIMIT 1")
    suspend fun getLeaveForDay(year: Int, month: Int, day: Int): LeaveDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeave(leave: LeaveDay): Long

    @Delete
    suspend fun deleteLeave(leave: LeaveDay)
}

@Database(
    entities = [
        TimeRecord::class,
        MonthlyObligatoryHours::class,
        WorkReport::class,
        LeaveDay::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TimeTrackingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `work_reports` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`reportType` TEXT NOT NULL, " +
                        "`textContent` TEXT, " +
                        "`audioFileName` TEXT, " +
                        "`driveUrl` TEXT, " +
                        "`telegramRef` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_work_reports_recordId` " +
                        "ON `work_reports` (`recordId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `leave_days` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`jalaliYear` INTEGER NOT NULL, " +
                        "`jalaliMonth` INTEGER NOT NULL, " +
                        "`jalaliDay` INTEGER NOT NULL, " +
                        "`leaveType` TEXT NOT NULL, " +
                        "`note` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_leave_days_jalaliYear_jalaliMonth_jalaliDay` " +
                        "ON `leave_days` (`jalaliYear`, `jalaliMonth`, `jalaliDay`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "time_tracking_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class TimeTrackingRepository(private val dao: TimeTrackingDao) {
    val allRecords: Flow<List<TimeRecord>> = dao.getAllRecordsFlow()
    val allReports: Flow<List<WorkReport>> = dao.getAllReportsFlow()
    val allLeaves: Flow<List<LeaveDay>> = dao.getAllLeavesFlow()

    suspend fun getRecordsForMonth(year: Int, month: Int): List<TimeRecord> =
        dao.getRecordsForMonth(year, month)

    suspend fun getRecordsInRange(startMillis: Long, endMillis: Long): List<TimeRecord> =
        dao.getRecordsInRange(startMillis, endMillis)

    suspend fun insertRecord(record: TimeRecord): Long = dao.insertRecord(record)

    suspend fun updateRecord(record: TimeRecord) = dao.updateRecord(record)

    suspend fun deleteRecord(record: TimeRecord) = dao.deleteRecord(record)

    suspend fun getActiveRecord(): TimeRecord? = dao.getActiveRecord()

    suspend fun getObligatoryHours(year: Int, month: Int): MonthlyObligatoryHours? =
        dao.getObligatoryHours(year, month)

    suspend fun insertObligatoryHours(hours: MonthlyObligatoryHours) =
        dao.insertObligatoryHours(hours)

    suspend fun getReportsForRecord(recordId: Long): List<WorkReport> =
        dao.getReportsForRecord(recordId)

    suspend fun insertReport(report: WorkReport): Long = dao.insertReport(report)

    suspend fun updateReport(report: WorkReport) = dao.updateReport(report)

    suspend fun deleteReport(report: WorkReport) = dao.deleteReport(report)

    suspend fun getLeavesForMonth(year: Int, month: Int): List<LeaveDay> =
        dao.getLeavesForMonth(year, month)

    suspend fun getLeaveForDay(year: Int, month: Int, day: Int): LeaveDay? =
        dao.getLeaveForDay(year, month, day)

    suspend fun insertLeave(leave: LeaveDay): Long = dao.insertLeave(leave)

    suspend fun deleteLeave(leave: LeaveDay) = dao.deleteLeave(leave)
}
