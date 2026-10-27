package dev.llmhotspot.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmHotspotServerTest {
    private val engine = object : InferenceEngine {
        override val modelId = "test-model"
        override suspend fun load(modelPath: String, contextSize: Int) = Result.success(Unit)
        override suspend fun unload() = Unit
        override suspend fun complete(prompt: String, suffix: String?, options: GenerationOptions) = GenerationResult("ok", 3, 1)
        override fun stream(prompt: String, suffix: String?, options: GenerationOptions): Flow<String> = flowOf("o", "k")
        override suspend fun embed(input: String) = listOf(1f, 0f)
    }

    @Test fun modelsRequiresBearerKey() = testApplication {
        val hotspot = LlmHotspotServer(engine, "secret")
        application { with(hotspot) { module() } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer secret") }.status)
    }

    @Test fun healthDoesNotExposeKey() = testApplication {
        val hotspot = LlmHotspotServer(engine, "secret")
        application { with(hotspot) { module() } }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
