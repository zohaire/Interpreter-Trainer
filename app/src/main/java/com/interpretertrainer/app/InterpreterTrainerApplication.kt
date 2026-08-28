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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { InterpreterDatabase.create(this) }
    val sessionRepository by lazy {
        val recordingRoots = listOfNotNull(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            filesDir
        )
        SessionRepository(
            dao = database.practiceSessionDao(),
            recordingFileStore = RecordingFileStore(recordingRoots)
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { sessionRepository.pruneOrphanRecordings() }
    }
}
