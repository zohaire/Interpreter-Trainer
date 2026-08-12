package com.interpretertrainer.app.data.repository

import com.interpretertrainer.app.data.database.PracticeSessionDao
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val dao: PracticeSessionDao) {
    fun observeAll(): Flow<List<PracticeSessionEntity>> = dao.observeAll()
    fun observeById(id: Long): Flow<PracticeSessionEntity?> = dao.observeById(id)
    suspend fun save(session: PracticeSessionEntity): Long = dao.insert(session)
    suspend fun delete(id: Long) = dao.deleteById(id)
}
