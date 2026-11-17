package app.gstreminder.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.gstreminder.core.Pending
import kotlinx.coroutines.flow.Flow

/** One monthly import. Only one cycle is active at a time. */
@Entity(tableName = "cycle")
data class Cycle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display form, e.g. "May-2026". Used in the SMS. */
    val period: String,
    val fileName: String,
    val sheetName: String,
    val importedAt: Long,
    val active: Boolean = true,
)

object SmsStatus {
    /** Dealer has filed – no SMS needed. */
    const val NOT_NEEDED = "NOT_NEEDED"
    const val NO_MOBILE = "NO_MOBILE"
    const val QUEUED = "QUEUED"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "dealer",
    indices = [Index(value = ["cycleId", "gstin"], unique = true), Index("cycleId", "status")],
)
data class Dealer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleId: Long,
    val rowNum: Int,
    val gstin: String,
    val name: String,
    /** Normalised 10-digit mobile, or null. */
    val phone: String?,
    val rawPhone: String,
    /** [Pending] name. */
    val pending: String,
    /** Lower sends first. */
    val priority: Int,
    /** "Office: INDORE-5\nJurisdiction: State" */
    val extras: String,
    val status: String,
    /** The exact text sent (set when sending). */
    val message: String? = null,
    val segments: Int = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Was still waiting when an updated list was imported: goes to the top of the queue. */
    @ColumnInfo(defaultValue = "0") val carryOver: Boolean = false,
    /** How many reminders have been sent to this dealer in this cycle. */
    @ColumnInfo(defaultValue = "0") val reminders: Int = 0,
    /** SIM slot (0 = SIM 1, 1 = SIM 2) the last SMS was counted against. */
    @ColumnInfo(defaultValue = "0") val simSlot: Int = 0,
) {
    val pendingEnum: Pending get() = runCatching { Pending.valueOf(pending) }.getOrDefault(Pending.GST_RETURN)
    val filed: Boolean get() = pendingEnum == Pending.NONE
}

/** SMS parts sent per day per SIM slot (operators count the limit per SIM). */
@Entity(tableName = "sim_usage", primaryKeys = ["date", "slot"])
data class SimUsage(val date: String, val slot: Int, val used: Int)

data class Stats(
    val total: Int = 0,
    val filed: Int = 0,
    val notFiled: Int = 0,
    val queued: Int = 0,
    val sending: Int = 0,
    val sent: Int = 0,
    val delivered: Int = 0,
    val failed: Int = 0,
    val noMobile: Int = 0,
)

@Dao
interface CycleDao {
    @Query("SELECT * FROM cycle WHERE active = 1 ORDER BY id DESC LIMIT 1")
    fun activeFlow(): Flow<Cycle?>

    @Query("SELECT * FROM cycle WHERE active = 1 ORDER BY id DESC LIMIT 1")
    suspend fun active(): Cycle?

    @Query("SELECT * FROM cycle WHERE id = :id")
    suspend fun get(id: Long): Cycle?

    @Query("SELECT * FROM cycle ORDER BY id DESC")
    fun allFlow(): Flow<List<Cycle>>

    @Insert
    suspend fun insert(cycle: Cycle): Long

    @Update
    suspend fun update(cycle: Cycle)

    @Query("UPDATE cycle SET active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE cycle SET active = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun activate(id: Long)
}

@Dao
interface DealerDao {
    @Query("SELECT * FROM dealer WHERE cycleId = :cycleId ORDER BY priority, rowNum")
    fun forCycleFlow(cycleId: Long): Flow<List<Dealer>>

    @Query("SELECT * FROM dealer WHERE cycleId = :cycleId ORDER BY rowNum")
    suspend fun forCycle(cycleId: Long): List<Dealer>

    @Query("SELECT * FROM dealer WHERE id = :id")
    fun flow(id: Long): Flow<Dealer?>

    @Query("SELECT * FROM dealer WHERE id = :id")
    suspend fun get(id: Long): Dealer?

    @Query(
        "SELECT * FROM dealer WHERE cycleId = :cycleId AND status = 'QUEUED' " +
            "ORDER BY carryOver DESC, attempts, priority, rowNum LIMIT 1"
    )
    suspend fun nextQueued(cycleId: Long): Dealer?

    @Query("SELECT * FROM dealer WHERE status = 'SENDING' AND updatedAt < :before")
    suspend fun staleSending(before: Long): List<Dealer>

    @Query(
        """SELECT COUNT(*) AS total,
           COALESCE(SUM(pending = 'NONE'), 0) AS filed,
           COALESCE(SUM(pending != 'NONE'), 0) AS notFiled,
           COALESCE(SUM(status = 'QUEUED'), 0) AS queued,
           COALESCE(SUM(status = 'SENDING'), 0) AS sending,
           COALESCE(SUM(status = 'SENT'), 0) AS sent,
           COALESCE(SUM(status = 'DELIVERED'), 0) AS delivered,
           COALESCE(SUM(status = 'FAILED'), 0) AS failed,
           COALESCE(SUM(status = 'NO_MOBILE'), 0) AS noMobile
           FROM dealer WHERE cycleId = :cycleId"""
    )
    fun statsFlow(cycleId: Long): Flow<Stats>

    @Insert
    suspend fun insertAll(dealers: List<Dealer>)

    @Update
    suspend fun update(dealer: Dealer)

    @Update
    suspend fun updateAll(dealers: List<Dealer>)
}

@Dao
abstract class UsageDao {
    @Query("SELECT * FROM sim_usage WHERE date = :date")
    abstract fun forDateFlow(date: String): Flow<List<SimUsage>>

    @Query("SELECT used FROM sim_usage WHERE date = :date AND slot = :slot")
    abstract suspend fun used(date: String, slot: Int): Int?

    @Query("SELECT COALESCE(SUM(used), 0) FROM sim_usage WHERE date = :date")
    abstract suspend fun total(date: String): Int

    @Query("INSERT OR IGNORE INTO sim_usage(date, slot, used) VALUES(:date, :slot, 0)")
    protected abstract suspend fun ensure(date: String, slot: Int)

    @Query("UPDATE sim_usage SET used = MAX(0, used + :delta) WHERE date = :date AND slot = :slot")
    protected abstract suspend fun addRaw(date: String, slot: Int, delta: Int)

    @Query("UPDATE sim_usage SET used = MAX(0, :used) WHERE date = :date AND slot = :slot")
    protected abstract suspend fun setRaw(date: String, slot: Int, used: Int)

    open suspend fun add(date: String, slot: Int, delta: Int) { ensure(date, slot); addRaw(date, slot, delta) }
    open suspend fun set(date: String, slot: Int, used: Int) { ensure(date, slot); setRaw(date, slot, used) }
}

@Database(entities = [Cycle::class, Dealer::class, SimUsage::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun cycles(): CycleDao
    abstract fun dealers(): DealerDao
    abstract fun usage(): UsageDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "gst-reminder.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: carry-over / reminder count / SIM slot on dealers, and usage counted per SIM. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dealer ADD COLUMN carryOver INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dealer ADD COLUMN reminders INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dealer ADD COLUMN simSlot INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE dealer SET reminders = 1 WHERE status IN ('SENT', 'DELIVERED')")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sim_usage` (`date` TEXT NOT NULL, `slot` INTEGER NOT NULL, " +
                        "`used` INTEGER NOT NULL, PRIMARY KEY(`date`, `slot`))"
                )
                db.execSQL("INSERT OR REPLACE INTO sim_usage(date, slot, used) SELECT date, 0, used FROM daily_usage")
                db.execSQL("DROP TABLE IF EXISTS daily_usage")
            }
        }
    }
}
