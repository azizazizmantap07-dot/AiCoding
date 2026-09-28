package com.kaneki.aicoder.domain.model

/**
 * Lampiran gambar untuk pesan pengguna multimodal (vision).
 * [base64] disimpan hanya selama giliran request pertama ke API;
 * setelah itu diganti menjadi daftar kosong di history agar tidak
 * membengkak token/memori pada iterasi tool-calling berikutnya.
 */
data class ImageAttachment(
    /** Data gambar tanpa prefix data-URL, encoding Base64. */
    val base64: String,
    /** MIME type, misalnya image/jpeg atau image/png. */
    val mimeType: String,
    /** URI lokal opsional untuk ditampilkan di UI (content:// atau file://). */
    val localUri: String? = null
)

/**
 * Satu giliran di history tool-calling loop (Bagian 3.3 blueprint).
 * Giliran chat untuk tool-calling loop: user, assistant, atau hasil tool.
 */
sealed class ChatTurn {
    data class User(
        val text: String,
        val images: List<ImageAttachment> = emptyList()
    ) : ChatTurn()

    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList()
    ) : ChatTurn()

    data class ToolResults(
        val results: List<ToolExecutionResult>
    ) : ChatTurn()

    /** Serialisasi kasar untuk estimasi token / logging. */
    fun serializedContent(): String = when (this) {
        is User -> {
            val imgNote = if (images.isNotEmpty()) " [+${images.size} gambar]" else ""
            // Estimasi kasar: ~750 token per gambar (vision) + teks
            text + imgNote + images.joinToString("") { " ".repeat(750) }
        }
        is Assistant -> text + toolCalls.joinToString { " ${it.name}(${it.arguments})" }
        is ToolResults -> results.joinToString { it.content }
    }
}
