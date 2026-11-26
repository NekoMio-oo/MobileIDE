package dev.mobileide.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class],
    version = 2,
    exportSchema = true
)
abstract class ProjectDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    companion object {
        private const val DATABASE_NAME = "mobileide.db"

        /**
         * Moves projects from SAF URIs to real paths without dropping data.
         * Migrated rows get path = NULL and keep their old URI in legacyUri so
         * the UI can ask the user to relocate them. NULLs do not collide in the
         * unique path index.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `projects_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `path` TEXT,
                        `legacyUri` TEXT,
                        `locationName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastOpenedAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `projects_new`
                        (`id`, `name`, `type`, `path`, `legacyUri`, `locationName`,
                         `createdAt`, `lastOpenedAt`, `updatedAt`)
                    SELECT `id`, `name`, `type`, NULL, `uri`, `locationName`,
                           `createdAt`, `lastOpenedAt`, `updatedAt`
                    FROM `projects`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `projects`")
                db.execSQL("ALTER TABLE `projects_new` RENAME TO `projects`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_projects_path` " +
                        "ON `projects` (`path`)"
                )
            }
        }

        fun create(context: Context): ProjectDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ProjectDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
