package id.clipmi.android.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Database iOS di [directory] (folder Documents aplikasi); database baru langsung di skema terbaru dengan gaya bawaan. */
fun openDatabase(directory: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = "$directory/${AppDatabase.NAME}")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(
            object : Migration(10, 11) {
                override fun migrate(connection: SQLiteConnection) {
                    MIGRATION_10_11_SQL.forEach { connection.execSQL(it) }
                }
            },
            object : Migration(11, 12) {
                override fun migrate(connection: SQLiteConnection) {
                    MIGRATION_11_12_SQL.forEach { connection.execSQL(it) }
                }
            },
        )
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onCreate(connection: SQLiteConnection) {
                    val now = nowMillis()
                    DefaultStyles.captionStyles(now).forEach { connection.execSQL(DefaultStyles.insertSql("caption_styles", it)) }
                    DefaultStyles.frameStyles(now).forEach { connection.execSQL(DefaultStyles.insertSql("frame_styles", it)) }
                }
            },
        )
        .build()
