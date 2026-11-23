package id.clipmi.android.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * Database lokal. Nama tabel dan kolom sama dengan ClipMI desktop (snake_case), supaya Paket Standar Tim dan logika
 * inti bisa dipakai bersama. Skema diekspor ke shared/schemas; setiap kenaikan [version] wajib punya Migration teruji
 * di :app (tanpa fallbackToDestructiveMigration), karena data pengguna hanya ada di HP ini. Dipakai Android dan iOS.
 */
@Database(
    entities = [
        ProjectEntity::class, TranscriptSegmentEntity::class, CorrectionEntryEntity::class, AppSettingEntity::class,
        ClipSeriesEntity::class, ClipEntity::class, AiUsageEntity::class, CaptionStyleEntity::class, FrameStyleEntity::class,
        BumperEntity::class, SocialAccountEntity::class, ScheduleEntity::class, ScheduleAccountEntity::class, PostStatEntity::class,
        PerformanceReportEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun segments(): SegmentDao
    abstract fun corrections(): CorrectionDao
    abstract fun settings(): SettingDao
    abstract fun clips(): ClipDao
    abstract fun series(): SeriesDao
    abstract fun aiUsages(): AiUsageDao
    abstract fun bumpers(): BumperDao
    abstract fun styles(): StyleDao
    abstract fun schedules(): ScheduleDao
    abstract fun reports(): ReportDao

    companion object {
        const val NAME = "clipmi.db"
    }
}

/** Dibuat Room (KSP) untuk tiap target. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
