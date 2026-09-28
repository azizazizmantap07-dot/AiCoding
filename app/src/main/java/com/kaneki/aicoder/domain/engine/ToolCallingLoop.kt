package com.kaneki.aicoder.domain.engine

import com.kaneki.aicoder.data.remote.AiApiClient
import com.kaneki.aicoder.data.remote.GenerateWithToolsResult
import com.kaneki.aicoder.domain.model.ChatTurn
import com.kaneki.aicoder.domain.model.PendingChange
import com.kaneki.aicoder.domain.model.ToolCall
import com.kaneki.aicoder.domain.model.ToolExecutionResult
import com.kaneki.aicoder.domain.tools.ProjectTools
import com.kaneki.aicoder.domain.tools.ToolExecutor
import kotlinx.coroutines.delay

class ToolCallingLoop(
    private val apiClient: AiApiClient,
    private val toolExecutor: ToolExecutor,
    private val contextBudget: ContextBudgetManager = ContextBudgetManager(),
    historyCompactor: HistoryCompactor? = null
) {
    private val historyCompactor = historyCompactor ?: HistoryCompactor(contextBudget)

    companion object {
        // Free tier Gemini (terutama model *-pro) sering dibatasi ~5 RPM.
        // MAX_ITERATIONS diturunkan dari 20 -> 8: satu putaran "edit project" yang
        // memicu banyak tool call (list_directory/read_file/write_file berturut-turut)
        // dulu bisa menembak sampai 20 request API sekaligus, jauh melebihi RPM
        // free tier dan langsung memicu 429 beruntun. Dibatasi lebih rendah supaya
        // sekali mendekati limit, loop berhenti dan minta lanjut manual daripada
        // terus menembak dan berulang kali menunggu-gagal.
        const val MAX_ITERATIONS = 8
        const val MAX_FILES_PER_TURN = 8

        // Jeda minimum antar-request API dalam satu putaran tool-calling.
        // Ini self-throttle proaktif: mencegah request ke-2..ke-N dalam loop
        // menembak Google sebelum jendela rate-limit 1 menitnya reset, jadi
        // idealnya kita tidak pernah sampai kena 429 sama sekali di tengah loop.
        private const val MIN_GAP_BETWEEN_CALLS_MS = 13_000L
    }

    suspend fun run(
        apiKey: String,
        model: String,
        userMessage: String,
        history: MutableList<ChatTurn>,
        fileTreeSummary: String,
        hasProject: Boolean = true,
        androidBlock: String = "",
        images: List<com.kaneki.aicoder.domain.model.ImageAttachment> = emptyList(),
        onToolActivity: (String) -> Unit = {},
        onTextChunk: (String) -> Unit = {},
        onBudgetUpdate: (ContextBudgetManager.BudgetSnapshot, Boolean) -> Unit = { _, _ -> },
        useStreaming: Boolean = true
    ): ToolLoopOutcome {
        history.add(ChatTurn.User(userMessage, images = images))
        // Tanpa project: jangan kirim tools file, supaya model tidak memanggil
        // list_directory / read_file (indikator "Melihat struktur folder" tidak muncul).
        val tools = if (hasProject) ProjectTools.declarations else emptyList()
        val systemPrompt = if (hasProject) {
            ProjectTools.systemPrompt(fileTreeSummary, androidBlock)
        } else {
            ProjectTools.freeChatSystemPrompt()
        }

        var iteration = 0
        var lastInteractionId: String? = null
        val allTextParts = mutableListOf<String>()
        var lastApiTokens: Int? = null
        var anyCompacted = false
        var lastCallStartedAtMs = 0L

        while (iteration < MAX_ITERATIONS) {
            iteration++

            // Self-throttle: baru panggil API lagi kalau jarak dari request
            // sebelumnya sudah cukup. Ini mencegah loop tool-calling (banyak
            // read_file/write_file berturut-turut) menembak API lebih cepat
            // dari yang diizinkan free tier, sebelum sempat kena 429 dulu.
            if (lastCallStartedAtMs != 0L) {
                val elapsed = System.currentTimeMillis() - lastCallStartedAtMs
                val remainingGap = MIN_GAP_BETWEEN_CALLS_MS - elapsed
                if (remainingGap > 0) {
                    val waitSec = ((remainingGap + 999) / 1000).toInt()
                    onToolActivity("⏱️ Menjaga jarak request (${waitSec}s) untuk hindari rate limit…")
                    delay(remainingGap)
                }
            }

            val compactResult = historyCompactor.compactIfNeeded(history)
            if (compactResult.didCompact) {
                anyCompacted = true
                history.clear()
                history.addAll(compactResult.history)
            }

            val snapshot = contextBudget.usageSummary(history, lastApiTokens)
            onBudgetUpdate(snapshot, compactResult.didCompact)

            val onWait: (Int, Int) -> Unit = { sec, attempt ->
                onToolActivity("⏳ Rate limit — menunggu ${sec}s (percobaan $attempt)…")
            }
            lastCallStartedAtMs = System.currentTimeMillis()
            val result = if (useStreaming) {
                apiClient.generateWithToolsStreaming(
                    apiKey = apiKey,
                    model = model,
                    history = history.toList(),
                    tools = tools,
                    systemPrompt = systemPrompt,
                    previousInteractionId = lastInteractionId,
                    onTextChunk = onTextChunk,
                    onToolHint = { name -> onToolActivity("🔧 Model memanggil $name…") },
                    onRateLimitWait = onWait
                )
            } else {
                apiClient.generateWithTools(
                    apiKey = apiKey,
                    model = model,
                    history = history.toList(),
                    tools = tools,
                    systemPrompt = systemPrompt,
                    previousInteractionId = lastInteractionId,
                    onRateLimitWait = onWait
                )
            }

            when (result) {
                is GenerateWithToolsResult.Success -> {
                    lastInteractionId = result.interactionId
                    lastApiTokens = result.totalTokenCount
                    // Setelah request pertama berhasil, buang payload base64 gambar
                    // dari history agar iterasi tool berikutnya tidak mengirim ulang.
                    for (i in history.indices.reversed()) {
                        val t = history[i]
                        if (t is ChatTurn.User && t.images.isNotEmpty()) {
                            history[i] = t.copy(images = emptyList())
                            break
                        }
                    }
                    if (result.text.isNotBlank()) {
                        allTextParts.add(result.text)
                        if (!useStreaming) onTextChunk(result.text)
                    }
                    history.add(
                        ChatTurn.Assistant(
                            text = result.text,
                            toolCalls = result.toolCalls
                        )
                    )

                    if (result.toolCalls.isEmpty()) break

                    val toolResults = result.toolCalls.map { call ->
                        onToolActivity(describeToolCall(call))
                        val content = toolExecutor.execute(call)
                        ToolExecutionResult(
                            callId = call.id,
                            name = call.name,
                            content = content
                        )
                    }
                    history.add(ChatTurn.ToolResults(toolResults))
                }

                is GenerateWithToolsResult.ApiError -> {
                    return ToolLoopOutcome(
                        pendingChanges = toolExecutor.snapshotPendingChanges(),
                        finalText = allTextParts.joinToString("\n"),
                        errorMessage = "API error (${result.httpCode}): ${result.message}",
                        iterations = iteration,
                        lastApiTotalTokens = lastApiTokens,
                        historyCompacted = anyCompacted
                    )
                }

                is GenerateWithToolsResult.NetworkError -> {
                    return ToolLoopOutcome(
                        pendingChanges = toolExecutor.snapshotPendingChanges(),
                        finalText = allTextParts.joinToString("\n"),
                        errorMessage = "Koneksi gagal: ${result.reason}",
                        iterations = iteration,
                        lastApiTotalTokens = lastApiTokens,
                        historyCompacted = anyCompacted
                    )
                }

                is GenerateWithToolsResult.EmptyResponse -> break
            }
        }

        val pending = toolExecutor.snapshotPendingChanges()
        var finalText = allTextParts.joinToString("\n\n")
        if (iteration >= MAX_ITERATIONS) {
            finalText += "\n\n⚠️ Batas $MAX_ITERATIONS langkah per putaran tercapai " +
                "(pengaman rate limit). Ketik lanjut untuk melanjutkan sisanya."
            onTextChunk(finalText)
        }
        if (pending.size >= MAX_FILES_PER_TURN) {
            finalText += "\n\n⚠️ Perubahan ini menyentuh ${pending.size} file " +
                "(batas $MAX_FILES_PER_TURN/putaran). Silakan review dulu, lalu lanjutkan untuk sisanya."
            onTextChunk(finalText)
        }

        onBudgetUpdate(contextBudget.usageSummary(history, lastApiTokens), anyCompacted)

        return ToolLoopOutcome(
            pendingChanges = pending,
            finalText = finalText,
            errorMessage = null,
            iterations = iteration,
            lastApiTotalTokens = lastApiTokens,
            historyCompacted = anyCompacted
        )
    }

    private fun describeToolCall(call: ToolCall): String = when (call.name) {
        "list_directory" -> "📂 Melihat struktur folder ${call.argsPath()}..."
        "read_file" -> "🔍 Membaca ${call.argsPath()}..."
        "search_code" -> "🔎 Mencari \"${call.arguments["query"] ?: ""}\"..."
        "write_file" -> "✏️ Menyiapkan perubahan pada ${call.argsPath()}..."
        "rename_file" -> {
            val from = (call.arguments["old_path"] as? String) ?: call.arguments["old_path"]?.toString().orEmpty()
            val to = (call.arguments["new_path"] as? String) ?: call.arguments["new_path"]?.toString().orEmpty()
            "📦 Rename $from → $to..."
        }
        else -> "Memproses ${call.name}..."
    }

    private fun ToolCall.argsPath(): String =
        (arguments["path"] as? String) ?: arguments["path"]?.toString().orEmpty()
}

data class ToolLoopOutcome(
    val pendingChanges: List<PendingChange>,
    val finalText: String,
    val errorMessage: String?,
    val iterations: Int,
    val lastApiTotalTokens: Int? = null,
    val historyCompacted: Boolean = false
)
