package cn.edu.bjtu.mis.data.agent.llm

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.agent.model.AgentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AgentChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class LlmTurnResult(
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
)

sealed interface LlmStreamEvent {
    data class ContentDelta(val delta: String) : LlmStreamEvent
}

class LlmClient {
    suspend fun streamChat(
        settings: AgentSettings,
        apiKey: String,
        messages: List<AgentChatMessage>,
        tools: List<JsonObject>,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmTurnResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        val result = runCatching {
            executeStream(client, settings, apiKey, messages, tools, onEvent)
        }.getOrElse { error ->
            if (tools.isNotEmpty() && error is IOException) {
                executeStream(client, settings, apiKey, messages + fallbackInstruction(), emptyList(), onEvent)
            } else {
                throw error
            }
        }
        normalizeJsonFallback(result)
    }

    private suspend fun executeStream(
        client: OkHttpClient,
        settings: AgentSettings,
        apiKey: String,
        messages: List<AgentChatMessage>,
        tools: List<JsonObject>,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmTurnResult {
        val bodyJson = buildJsonObject {
            put("model", settings.textModel)
            put("temperature", settings.temperature)
            put("stream", true)
            put("messages", JsonArray(messages.map { it.toOpenAiJson() }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools))
                put("tool_choice", "auto")
            }
        }.toString()
        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("LLM HTTP ${it.code}: ${it.body?.string().orEmpty().take(300)}")
            }
            val content = StringBuilder()
            val toolCalls = linkedMapOf<Int, ToolCallDelta>()
            val reader = it.body?.charStream()?.buffered() ?: throw IOException("LLM response body is empty")
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val root = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject ?: continue
                val text = delta["content"]?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    content.append(text)
                    onEvent(LlmStreamEvent.ContentDelta(text))
                }
                delta["tool_calls"]?.jsonArray?.forEach { callElement ->
                    val call = callElement.jsonObject
                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val acc = toolCalls.getOrPut(index) { ToolCallDelta() }
                    call["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                    val function = call["function"]?.jsonObject
                    function?.get("name")?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { acc.arguments.append(it) }
                }
            }
            return LlmTurnResult(
                content = content.toString(),
                toolCalls = toolCalls.values.mapNotNull { acc ->
                    val name = acc.name ?: return@mapNotNull null
                    LlmToolCall(
                        id = acc.id ?: "call_${UUID.randomUUID()}",
                        name = name,
                        arguments = acc.arguments.toString(),
                    )
                },
            )
        }
    }

    private fun AgentChatMessage.toOpenAiJson(): JsonObject = buildJsonObject {
        put("role", role)
        when (role) {
            "tool" -> {
                put("tool_call_id", toolCallId.orEmpty())
                put("content", content)
            }
            "assistant" -> {
                put("content", content)
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JsonArray(toolCalls.map { call ->
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            })
                        }
                    }))
                }
            }
            else -> put("content", content)
        }
    }

    private fun fallbackInstruction(): AgentChatMessage =
        AgentChatMessage(
            role = "system",
            content = """
                If native tool calling is unavailable, respond with strict JSON only.
                For a tool call: {"type":"tool_call","name":"file.read","arguments":{"path":"work/a.txt"}}
                For the final answer: {"type":"final","answer":"..."}
            """.trimIndent(),
        )

    private fun normalizeJsonFallback(result: LlmTurnResult): LlmTurnResult {
        if (result.toolCalls.isNotEmpty()) return result
        val root = runCatching { AppJson.parseToJsonElement(result.content).jsonObject }.getOrNull() ?: return result
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "tool_call" -> {
                val name = root["name"]?.jsonPrimitive?.contentOrNull ?: return result
                val arguments = root["arguments"]?.toString() ?: "{}"
                LlmTurnResult(
                    content = "",
                    toolCalls = listOf(LlmToolCall("call_${UUID.randomUUID()}", name, arguments)),
                )
            }
            "final" -> LlmTurnResult(content = root["answer"]?.jsonPrimitive?.contentOrNull.orEmpty())
            else -> result
        }
    }

    private data class ToolCallDelta(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}
