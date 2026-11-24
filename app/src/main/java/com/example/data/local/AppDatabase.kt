package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DownloadedClipEntity::class,
        PromptBlueprintEntity::class,
        VideoTranscriptEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_transcripts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoTitle` TEXT NOT NULL,
                        `sourceUriString` TEXT NOT NULL,
                        `geminiModelUsed` TEXT NOT NULL,
                        `languageModeName` TEXT NOT NULL,
                        `detectedLanguage` TEXT NOT NULL,
                        `previewSnippet` TEXT NOT NULL,
                        `segmentCount` INTEGER NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "reelforge_vault.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
