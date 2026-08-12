package com.interpretertrainer.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PracticeSessionEntity::class], version = 1, exportSchema = false)
abstract class InterpreterDatabase : RoomDatabase() {
    abstract fun practiceSessionDao(): PracticeSessionDao

    companion object {
        fun create(context: Context): InterpreterDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                InterpreterDatabase::class.java,
                "interpreter_trainer.db"
            ).build()
    }
}
