package com.interpretertrainer.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PracticeSessionEntity::class], version = 2, exportSchema = false)
abstract class InterpreterDatabase : RoomDatabase() {
    abstract fun practiceSessionDao(): PracticeSessionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE practice_sessions ADD COLUMN recordingPath TEXT")
                database.execSQL("ALTER TABLE practice_sessions ADD COLUMN aiFeedback TEXT")
            }
        }

        fun create(context: Context): InterpreterDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                InterpreterDatabase::class.java,
                "interpreter_trainer.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
