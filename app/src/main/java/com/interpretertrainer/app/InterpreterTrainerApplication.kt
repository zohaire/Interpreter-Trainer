package com.interpretertrainer.app

import android.app.Application
import android.os.Environment
import com.interpretertrainer.app.data.database.InterpreterDatabase
import com.interpretertrainer.app.data.repository.RecordingFileStore
import com.interpretertrainer.app.data.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InterpreterTrainerApplication : Application() {

    private val repositories = mutableMapOf<String, SessionRepository>()

    @Synchronized
    fun repositoryFor(uid: String): SessionRepository = repositories.getOrPut(uid) {
        val owner = getSharedPreferences("practice_owner", MODE_PRIVATE)
        // Preserve pre-account practice data for the first verified account on this installation.
        val legacyOwner = owner.getString("legacy_uid", null) ?: uid.also {
            owner.edit().putString("legacy_uid", it).apply()
        }
        val suffix = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        val dbName = if (legacyOwner == uid) "interpreter_trainer.db" else "practice_$suffix.db"
        val database = InterpreterDatabase.create(this, dbName)
        SessionRepository(database.practiceSessionDao(), RecordingFileStore(listOfNotNull(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC), filesDir)))
    }

    override fun onCreate() {
        super.onCreate()
        com.interpretertrainer.app.auth.AccountSession.initialize(this)
        // Do not prune shared recordings using only one account's database.
    }
}
