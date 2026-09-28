package com.kaneki.aicoder.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Skema Bagian 7 blueprint — riwayat chat per project.
 * role: "user" | "assistant" | "tool"
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index("projectId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)
