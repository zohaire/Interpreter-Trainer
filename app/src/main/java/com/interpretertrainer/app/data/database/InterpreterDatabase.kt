package com.interpretertrainer.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PracticeSessionEntity::class, PracticeLibraryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class InterpreterDatabase : RoomDatabase() {
    abstract fun practiceSessionDao(): PracticeSessionDao
    abstract fun practiceLibraryDao(): PracticeLibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE practice_sessions ADD COLUMN recordingPath TEXT")
                database.execSQL("ALTER TABLE practice_sessions ADD COLUMN aiFeedback TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS practice_library (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        speaker TEXT NOT NULL,
                        institutionEvent TEXT NOT NULL,
                        eventDate TEXT NOT NULL,
                        language TEXT NOT NULL,
                        field TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        difficulty TEXT NOT NULL,
                        speakingSpeed TEXT NOT NULL,
                        interpretationTypes TEXT NOT NULL,
                        durationMillis INTEGER NOT NULL,
                        sourceUrl TEXT,
                        mediaUri TEXT,
                        sourceLabel TEXT NOT NULL,
                        isOfficial INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        lastPracticedAt INTEGER,
                        progressMillis INTEGER NOT NULL,
                        completionCount INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): InterpreterDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                InterpreterDatabase::class.java,
                "interpreter_trainer.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
