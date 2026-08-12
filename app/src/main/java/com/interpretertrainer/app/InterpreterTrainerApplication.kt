package com.interpretertrainer.app

import android.app.Application
import com.interpretertrainer.app.data.database.InterpreterDatabase
import com.interpretertrainer.app.data.repository.SessionRepository

class InterpreterTrainerApplication : Application() {
    val database by lazy { InterpreterDatabase.create(this) }
    val sessionRepository by lazy { SessionRepository(database.practiceSessionDao()) }
}
