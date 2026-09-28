package com.kaneki.aicoder.data.remote

import com.kaneki.aicoder.data.remote.dto.InteractionStep
import com.kaneki.aicoder.data.remote.dto.parseArguments
import com.kaneki.aicoder.domain.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser SSE Interactions API (`?alt=sse` / `"stream": true`).
 *
 * Event yang relevan:
 * - step.delta + delta.type=text → potongan teks model
 * - step.start + step.type=function_call → mulai tool call
 * - step.delta + delta.type=arguments → potongan argumen JSON
 * - interaction.completed → selesai
 *
 * Format baris SSE: `event: ...` / `data: {...}` dipisah blank line.
 */
class StreamingParser(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
) {
    private val textBuilder = StringBuilder()
    private val toolCalls = linkedMapOf<String, MutableToolCall>()
    private var interactionId: String? = null
    private var totalTokens: Int? = null
    private var currentEvent: String? = null
    private val dataBuffer = StringBuilder()

    data class MutableToolCall(
        var id: String,
        var name: String,
        val argumentsJson: StringBuilder = StringBuilder()
    )

    sealed interface StreamEvent {
        data class TextDelta(val chunk: String) : StreamEvent
        data class ToolActivity(val name: String) : StreamEvent
        data object Completed : StreamEvent
    }

    /**
     * Feed satu baris mentah dari stream (tanpa \\n akhir).
     * Mengembalikan event UI jika ada.
     */
    fun feedLine(line: String): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        when {
            line.startsWith("event:") -> {
                flushData(events)
                currentEvent = line.removePrefix("event:").trim()
            }
            line.startsWith("data:") -> {
                val payload = line.removePrefix("data:").trim()
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(payload)
            }
            line.isBlank() -> flushData(events)
        }
        return events
    }

    fun finish(): FinalSnapshot {
        flushData(mutableListOf())
        return FinalSnapshot(
            text = textBuilder.toString(),
            toolCalls = toolCalls.values.map { m ->
                ToolCall(
                    id = m.id,
                    name = m.name,
                    arguments = parseArgsJson(m.argumentsJson.toString())
                )
            },
            interactionId = interactionId,
            totalTokenCount = totalTokens
        )
    }

    data class FinalSnapshot(
        val text: String,
        val toolCalls: List<ToolCall>,
        val interactionId: String?,
        val totalTokenCount: Int?
    )

    private fun flushData(out: MutableList<StreamEvent>) {
        if (dataBuffer.isEmpty()) {
            currentEvent = null
            return
        }
        val raw = dataBuffer.toString()
        dataBuffer.clear()
        val ev = currentEvent
        currentEvent = null
        try {
            val obj = json.parseToJsonElement(raw).jsonObject
            handlePayload(ev, obj, out)
        } catch (_: Exception) {
            // ignore malformed chunk
        }
    }

    private fun handlePayload(
        eventType: String?,
        obj: JsonObject,
        out: MutableList<StreamEvent>
    ) {
        // Beberapa response menaruh event_type di body
        val type = eventType
            ?: obj["event_type"]?.jsonPrimitive?.contentOrNull
            ?: obj["type"]?.jsonPrimitive?.contentOrNull

        obj["id"]?.jsonPrimitive?.contentOrNull?.let { interactionId = it }
        obj["interaction"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.let {
            interactionId = it
        }
        obj["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.let { totalTokens = it }

        when (type) {
            "step.delta", "content.delta" -> {
                val delta = obj["delta"]?.jsonObject
                val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull
                    ?: obj["delta_type"]?.jsonPrimitive?.contentOrNull
                when (deltaType) {
                    "text" -> {
                        val chunk = delta?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: obj["text"]?.jsonPrimitive?.contentOrNull
                            ?: return
                        if (chunk.isNotEmpty()) {
                            textBuilder.append(chunk)
                            out.add(StreamEvent.TextDelta(chunk))
                        }
                    }
                    "arguments", "arguments_delta" -> {
                        val partial = delta?.get("partial_arguments")?.jsonPrimitive?.contentOrNull
                            ?: delta?.get("arguments")?.toString()
                            ?: return
                        val index = obj["index"]?.jsonPrimitive?.contentOrNull ?: "0"
                        toolCalls[index]?.argumentsJson?.append(partial)
                    }
                }
                // Juga tangkap text langsung di root
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                    if (chunk.isNotEmpty() && deltaType == null) {
                        textBuilder.append(chunk)
                        out.add(StreamEvent.TextDelta(chunk))
                    }
                }
            }
            "step.start" -> {
                val step = obj["step"]?.jsonObject ?: return
                val stepType = step["type"]?.jsonPrimitive?.contentOrNull
                if (stepType == "function_call") {
                    val id = step["id"]?.jsonPrimitive?.contentOrNull
                        ?: "call_${System.currentTimeMillis()}"
                    val name = step["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val index = obj["index"]?.jsonPrimitive?.contentOrNull ?: id
                    val argsEl = step["arguments"]
                    val m = MutableToolCall(id = id, name = name)
                    if (argsEl != null && argsEl !is JsonPrimitive) {
                        m.argumentsJson.append(argsEl.toString())
                    } else if (argsEl is JsonPrimitive && !argsEl.isString) {
                        m.argumentsJson.append(argsEl.content)
                    }
                    toolCalls[index] = m
                    out.add(StreamEvent.ToolActivity(name))
                }
                if (stepType == "model_output") {
                    // text may follow in deltas
                }
            }
            "interaction.completed", "completed" -> {
                out.add(StreamEvent.Completed)
            }
        }
    }

    private fun parseArgsJson(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val el = json.parseToJsonElement(raw)
            if (el is JsonObject) {
                // Reuse InteractionStep helper via synthetic step
                val step = InteractionStep(
                    type = "function_call",
                    arguments = el
                )
                step.parseArguments()
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
