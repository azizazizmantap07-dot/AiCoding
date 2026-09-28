package com.kaneki.aicoder.domain.engine

import com.kaneki.aicoder.domain.model.ChatTurn

/**
 * Ringkas history jika mendekati budget (Bagian 4.2 blueprint).
 *
 * Strategi bertingkat:
 * 1. Ringkas ToolResults di luar 5 giliran terakhir
 * 2. Jika masih over: cuplik Assistant text lama
 * 3. Jika masih over: drop body tool hasil sangat lama
 */
class HistoryCompactor(
    private val budgetManager: ContextBudgetManager,
    private val keepRecentTurns: Int = 5
) {
    data class CompactResult(
        val history: List<ChatTurn>,
        val didCompact: Boolean,
        val tokensBefore: Int,
        val tokensAfter: Int
    )

    fun compactIfNeeded(history: List<ChatTurn>): CompactResult {
        val before = budgetManager.estimateHistoryTokens(history)
        if (!budgetManager.isOverBudget(history)) {
            return CompactResult(history, didCompact = false, tokensBefore = before, tokensAfter = before)
        }

        var current = compactToolResults(history, keepRecentTurns)
        if (budgetManager.isOverBudget(current)) {
            current = compactOldAssistantText(current, keepRecent = 3)
        }
        if (budgetManager.isOverBudget(current)) {
            current = aggressivelyDropOldToolBodies(current, keepRecent = 15)
        }

        val after = budgetManager.estimateHistoryTokens(current)
        return CompactResult(
            history = current,
            didCompact = true,
            tokensBefore = before,
            tokensAfter = after
        )
    }

    private fun compactToolResults(history: List<ChatTurn>, keepRecent: Int): List<ChatTurn> {
        val cutoff = (history.size - keepRecent).coerceAtLeast(0)
        return history.mapIndexed { index, turn ->
            if (index < cutoff && turn is ChatTurn.ToolResults) {
                ChatTurn.ToolResults(
                    turn.results.map { result ->
                        if (isAlreadySummary(result.content)) result
                        else result.copy(content = summarizeToolResult(result.content, result.name))
                    }
                )
            } else turn
        }
    }

    private fun compactOldAssistantText(history: List<ChatTurn>, keepRecent: Int): List<ChatTurn> {
        val cutoff = (history.size - keepRecent).coerceAtLeast(0)
        return history.mapIndexed { index, turn ->
            if (index < cutoff && turn is ChatTurn.Assistant && turn.text.length > 400) {
                val toolsNote = if (turn.toolCalls.isNotEmpty()) {
                    " [tools: ${turn.toolCalls.joinToString { it.name }}]"
                } else ""
                turn.copy(text = turn.text.take(200) + "…(diringkas)$toolsNote")
            } else turn
        }
    }

    private fun aggressivelyDropOldToolBodies(history: List<ChatTurn>, keepRecent: Int): List<ChatTurn> {
        val cutoff = (history.size - keepRecent).coerceAtLeast(0)
        return history.mapIndexed { index, turn ->
            if (index < cutoff && turn is ChatTurn.ToolResults) {
                ChatTurn.ToolResults(
                    turn.results.map { result ->
                        result.copy(
                            content = "[Hasil tool ${result.name} lama dihapus dari konteks. Panggil ulang jika perlu.]"
                        )
                    }
                )
            } else turn
        }
    }

    private fun isAlreadySummary(content: String): Boolean =
        content.startsWith("[Hasil sebelumnya:") || content.startsWith("[Hasil tool ")

    private fun summarizeToolResult(content: String, toolName: String): String {
        val lineCount = content.lines().size
        val chars = content.length
        return "[Hasil sebelumnya tool=$toolName: $lineCount baris / ~$chars char, sudah diproses. " +
            "Panggil ulang tool jika perlu detail lagi.]"
    }
}
