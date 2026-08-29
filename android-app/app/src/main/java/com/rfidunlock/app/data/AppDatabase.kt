package com.rfidunlock.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Конвертеры типов Room. */
class Converters {
    @TypeConverter
    fun toTagMode(value: String?): TagMode =
        runCatching { TagMode.valueOf(value ?: "") }.getOrDefault(TagMode.PRESENCE)

    @TypeConverter
    fun fromTagMode(mode: TagMode): String = mode.name
}

@Database(entities = [Tag::class, PcProfile::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun pcProfileDao(): PcProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Миграция v1→v2: добавлены колонки режима срабатывания. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tags ADD COLUMN mode TEXT NOT NULL DEFAULT 'PRESENCE'"
                )
                db.execSQL(
                    "ALTER TABLE tags ADD COLUMN toggleNextLock INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /** Миграция v2→v3: профили ПК и привязка меток к профилю. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pc_profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        token TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE tags ADD COLUMN profileId TEXT")
            }
        }

        /** Миграция v3→v4: семейство ОС ПК для иконки на плитке. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pc_profiles ADD COLUMN os TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Миграция v4→v5: id сети ZeroTier для встроенного узла (libzt). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pc_profiles ADD COLUMN ztNetworkId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rfid-unlock.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { INSTANCE = it }
            }
    }
}
