package com.kaneki.aicoder.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatDao {

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE projectId = :projectId ORDER BY timestamp ASC, id ASC")
    suspend fun getByProject(projectId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    /** Pindahkan seluruh riwayat chat dari satu projectId ke projectId lain (mis. free → project hasil import). */
    @Query("UPDATE chat_messages SET projectId = :newId WHERE projectId = :oldId")
    suspend fun reassignProject(oldId: String, newId: String)
}
