package dev.llmhotspot.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class LlmHotspotServer(
    private val engine: InferenceEngine,
    initialApiKey: String,
    private val onTokens: (Int) -> Unit = {},
    private val isAcceptingRequests: () -> Boolean = { true },
) {
    private val key = AtomicReference(initialApiKey)
    private var server: ApplicationEngine? = null

    fun start(bindHost: String, port: Int) {
        check(server == null) { "Server is already running" }
        server = embeddedServer(CIO, host = bindHost, port = port) { module() }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 2_000)
        server = null
    }

    fun rotateKey(newKey: String) { key.set(newKey) }

    internal fun Application.module() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        routing {
            get("/") { call.respondText(LandingPage.html, ContentType.Text.Html) }
            get("/health") {
                call.respond(mapOf("status" to if (engine.modelId == null) "loading" else "ready"))
            }
            route("/v1") {
                get("/models") {
                    if (!call.authorized()) return@get
                    val model = engine.modelId
                    call.respond(ModelsResponse(data = if (model == null) emptyList() else listOf(ModelInfo(model))))
                }
                post("/chat/completions") {
                    if (!call.authorized() || !call.ready()) return@post
                    val request = call.receive<ChatCompletionRequest>()
                    val prompt = PromptFormatter.chat(request)
                    val options = GenerationOptions(request.maxTokens?.coerceIn(1, 4096) ?: 256, request.temperature?.toFloat() ?: 0.7f)
                    if (request.stream) {
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            val id = "chatcmpl-${UUID.randomUUID()}"
                            if (request.tools != null) {
                                val result = engine.complete(prompt, null, options)
                                onTokens(result.generatedTokens)
                                val calls = ToolCallParser.parse(result.text)
                                if (calls.isNotEmpty()) {
                                    calls.forEachIndexed { index, tool ->
                                        write("data: {\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":$index,\"id\":${Json.encodeToString(tool.id)},\"type\":\"function\",\"function\":{\"name\":${Json.encodeToString(tool.function.name)},\"arguments\":${Json.encodeToString(tool.function.arguments)}}}]},\"finish_reason\":null}]}\n\n")
                                    }
                                    write("data: {\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                                } else {
                                    write("data: {\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"index\":0,\"delta\":{\"content\":${Json.encodeToString(result.text)}},\"finish_reason\":null}]}\n\n")
                                    write("data: {\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                                }
                            } else {
                                engine.stream(prompt, null, options).collect { token ->
                                    onTokens(1)
                                    val escaped = Json.encodeToString(token)
                                    write("data: {\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"index\":0,\"delta\":{\"content\":$escaped},\"finish_reason\":null}]}\n\n")
                                    flush()
                                }
                                write("data: {\"id\":\"$id\",\"object\":\"chat.completion.chunk\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                            }
                            write("data: [DONE]\n\n")
                        }
                    } else {
                        val result = engine.complete(prompt, null, options)
                        onTokens(result.generatedTokens)
                        val toolCalls = ToolCallParser.parse(result.text).takeIf { it.isNotEmpty() }
                        call.respond(ChatCompletionResponse(
                            id = "chatcmpl-${UUID.randomUUID()}",
                            model = engine.modelId ?: request.model,
                            choices = listOf(ChatChoice(
                                message = ChatMessage("assistant", if (toolCalls == null) result.text else null, toolCalls = toolCalls),
                                finishReason = if (toolCalls == null) "stop" else "tool_calls",
                            )),
                            usage = Usage(result.promptTokens, result.generatedTokens),
                        ))
                    }
                }
                post("/completions") {
                    if (!call.authorized() || !call.ready()) return@post
                    val request = call.receive<CompletionRequest>()
                    val options = GenerationOptions(request.maxTokens?.coerceIn(1, 4096) ?: 128, request.temperature?.toFloat() ?: 0.2f)
                    if (request.stream) {
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            val id = "cmpl-${UUID.randomUUID()}"
                            engine.stream(request.prompt, request.suffix, options).collect { token ->
                                onTokens(1)
                                write("data: {\"id\":\"$id\",\"object\":\"text_completion\",\"model\":${Json.encodeToString(request.model)},\"choices\":[{\"text\":${Json.encodeToString(token)},\"index\":0,\"finish_reason\":null}]}\n\n")
                                flush()
                            }
                            write("data: [DONE]\n\n")
                        }
                    } else {
                        val result = engine.complete(request.prompt, request.suffix, options)
                        onTokens(result.generatedTokens)
                        call.respond(CompletionResponse(
                            id = "cmpl-${UUID.randomUUID()}",
                            model = engine.modelId ?: request.model,
                            choices = listOf(CompletionChoice(result.text)),
                            usage = Usage(result.promptTokens, result.generatedTokens),
                        ))
                    }
                }
                post("/embeddings") {
                    if (!call.authorized() || !call.ready()) return@post
                    val request = call.receive<EmbeddingRequest>()
                    val inputs = when (val input = request.input) {
                        is JsonArray -> input.mapNotNull { it.jsonPrimitive.contentOrNull }
                        is JsonPrimitive -> listOfNotNull(input.contentOrNull)
                        else -> emptyList()
                    }
                    if (inputs.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorEnvelope(ApiError("input must be a string or array of strings", "invalid_request_error")))
                        return@post
                    }
                    val data = inputs.mapIndexed { index, input -> EmbeddingData(engine.embed(input), index) }
                    call.respond(EmbeddingResponse(model = engine.modelId ?: request.model, data = data, usage = Usage(inputs.sumOf { it.length / 4 }, 0)))
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorized(): Boolean {
        val supplied = request.header("Authorization")?.removePrefix("Bearer ")
        val expected = key.get()
        if (supplied != null && MessageDigest.isEqual(supplied.toByteArray(), expected.toByteArray())) return true
        respond(HttpStatusCode.Unauthorized, ApiErrorEnvelope(ApiError("Invalid or missing API key", "authentication_error")))
        return false
    }

    private suspend fun io.ktor.server.application.ApplicationCall.ready(): Boolean {
        if (!isAcceptingRequests()) {
            respond(HttpStatusCode.ServiceUnavailable, ApiErrorEnvelope(ApiError("Inference paused by battery or thermal guardrail", "service_unavailable")))
            return false
        }
        if (engine.modelId != null) return true
        respond(HttpStatusCode.ServiceUnavailable, ApiErrorEnvelope(ApiError("No model is loaded", "service_unavailable")))
        return false
    }
}
