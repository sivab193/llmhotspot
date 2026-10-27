package dev.llmhotspot.server

import kotlinx.coroutines.flow.Flow

interface InferenceEngine {
    val modelId: String?
    suspend fun load(modelPath: String, contextSize: Int = 8192): Result<Unit>
    suspend fun unload()
    suspend fun complete(prompt: String, suffix: String?, options: GenerationOptions): GenerationResult
    fun stream(prompt: String, suffix: String?, options: GenerationOptions): Flow<String>
    suspend fun embed(input: String): List<Float>
}

class EngineUnavailableException(message: String) : IllegalStateException(message)
