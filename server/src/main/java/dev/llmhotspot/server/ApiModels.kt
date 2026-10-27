package dev.llmhotspot.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    @SerialName("owned_by") val ownedBy: String = "llmhotspot",
)

@Serializable
data class ModelsResponse(val `object`: String = "list", val data: List<ModelInfo>)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: FunctionCall)

@Serializable
data class FunctionCall(val name: String, val arguments: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: JsonElement? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
)

@Serializable
data class CompletionRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class EmbeddingRequest(val model: String, val input: JsonElement)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int = promptTokens + completionTokens,
)

@Serializable
data class ChatChoice(val index: Int = 0, val message: ChatMessage, @SerialName("finish_reason") val finishReason: String = "stop")

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage,
)

@Serializable
data class CompletionChoice(val text: String, val index: Int = 0, @SerialName("finish_reason") val finishReason: String = "stop")

@Serializable
data class CompletionResponse(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String,
    val choices: List<CompletionChoice>,
    val usage: Usage,
)

@Serializable
data class EmbeddingData(val embedding: List<Float>, val index: Int = 0, val `object`: String = "embedding")

@Serializable
data class EmbeddingResponse(
    val `object`: String = "list",
    val model: String,
    val data: List<EmbeddingData>,
    val usage: Usage,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiError)

@Serializable
data class ApiError(val message: String, val type: String, val code: String? = null)

data class GenerationOptions(val maxTokens: Int = 256, val temperature: Float = 0.7f)

data class GenerationResult(val text: String, val promptTokens: Int, val generatedTokens: Int)
