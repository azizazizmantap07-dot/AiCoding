package com.kaneki.aicoder.data.repository

import com.kaneki.aicoder.data.local.db.ChatDao
import com.kaneki.aicoder.data.local.db.ChatMessageEntity
import com.kaneki.aicoder.domain.model.ChatTurn
import com.kaneki.aicoder.ui.chat.ChatDisplayItem

/**
 * Persistensi riwayat chat per project (hanya giliran user & assistant teks
 * untuk UI + konteks LLM; hasil tool mentah tidak disimpan agar DB tidak membengkak).
 */
class ChatRepository(private val chatDao: ChatDao) {

    suspend fun loadDisplayMessages(projectId: String): List<ChatDisplayItem> {
        return chatDao.getByProject(projectId).mapNotNull { entity ->
            when (entity.role) {
                "user" -> ChatDisplayItem.UserText(
                    entity.content.removePrefix(IMAGE_PROMPT_PREFIX)
                )
                "assistant" -> parseGeneratedImage(entity.content)
                    ?: ChatDisplayItem.AssistantText(entity.content)
                else -> null
            }
        }
    }

    /**
     * Format yang ditulis ChatViewModel.runImageGeneration:
     * "[gambar dihasilkan] <prompt> → <path>". Kalau file-nya masih ada di cache,
     * tampilkan sebagai gambar; kalau sudah terhapus (cache dibersihkan OS), jatuh ke teks.
     */
    private fun parseGeneratedImage(content: String): ChatDisplayItem.AssistantImage? {
        if (!content.startsWith(IMAGE_RESULT_PREFIX)) return null
        val body = content.removePrefix(IMAGE_RESULT_PREFIX)
        val sep = body.lastIndexOf(" → ")
        if (sep < 0) return null
        val prompt = body.substring(0, sep)
        val path = body.substring(sep + 3).trim()
        if (!java.io.File(path).exists()) return null
        return ChatDisplayItem.AssistantImage(
            localPath = path,
            caption = "Hasil generate: $prompt"
        )
    }

    /** History minimal untuk tool loop (user + assistant text saja). */
    suspend fun loadChatTurns(projectId: String): List<ChatTurn> {
        return chatDao.getByProject(projectId).mapNotNull { entity ->
            // Giliran generate-gambar bukan bagian percakapan teks — jangan dimasukkan
            // ke konteks LLM (hemat token, dan model tak perlu tahu path cache lokal).
            if (entity.content.startsWith(IMAGE_PROMPT_PREFIX) ||
                entity.content.startsWith(IMAGE_RESULT_PREFIX)
            ) return@mapNotNull null
            when (entity.role) {
                "user" -> ChatTurn.User(entity.content)
                "assistant" -> ChatTurn.Assistant(text = entity.content, toolCalls = emptyList())
                else -> null
            }
        }
    }

    suspend fun appendUser(projectId: String, text: String) {
        chatDao.insert(
            ChatMessageEntity(
                projectId = projectId,
                role = "user",
                content = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun appendAssistant(projectId: String, text: String) {
        if (text.isBlank()) return
        chatDao.insert(
            ChatMessageEntity(
                projectId = projectId,
                role = "assistant",
                content = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearProject(projectId: String) {
        chatDao.deleteByProject(projectId)
    }

    /**
     * Pindahkan seluruh pesan dari [oldProjectId] ke [newProjectId].
     * Dipakai saat user di chat bebas meng-import file/ZIP agar sesi chat
     * tidak hilang dan berlanjut di project yang baru dibuat.
     */
    suspend fun migrateProject(oldProjectId: String, newProjectId: String) {
        if (oldProjectId == newProjectId) return
        chatDao.reassignProject(oldProjectId, newProjectId)
    }

    private companion object {
        const val IMAGE_PROMPT_PREFIX = "[generate gambar] "
        const val IMAGE_RESULT_PREFIX = "[gambar dihasilkan] "
    }
}
