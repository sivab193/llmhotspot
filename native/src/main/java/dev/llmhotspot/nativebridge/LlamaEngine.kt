package dev.llmhotspot.nativebridge

import dev.llmhotspot.server.EngineUnavailableException
import dev.llmhotspot.server.GenerationOptions
import dev.llmhotspot.server.GenerationResult
import dev.llmhotspot.server.InferenceEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamaEngine(private val gpuLayers: Int = 0) : InferenceEngine {
    private val mutex = Mutex()
    @Volatile private var loadedModelId: String? = null
    override val modelId: String? get() = loadedModelId

    override suspend fun load(modelPath: String, contextSize: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(File(modelPath).isFile) { "GGUF model does not exist: $modelPath" }
            mutex.withLock {
                val error = NativeLlama.loadModel(modelPath, contextSize.coerceIn(512, 32768), gpuLayers)
                if (error != null) throw EngineUnavailableException(error)
                loadedModelId = File(modelPath).nameWithoutExtension
            }
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            NativeLlama.unloadModel()
            loadedModelId = null
        }
    }

    override suspend fun complete(prompt: String, suffix: String?, options: GenerationOptions): GenerationResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureLoaded()
            val output = StringBuilder()
            val counts = NativeLlama.generate(prompt, suffix, options.maxTokens, options.temperature) { output.append(it) }
            GenerationResult(output.toString(), counts.getOrElse(0) { 0 }.toInt(), counts.getOrElse(1) { 0 }.toInt())
        }
    }

    override fun stream(prompt: String, suffix: String?, options: GenerationOptions): Flow<String> = callbackFlow {
        val job = launch(Dispatchers.Default) {
            try {
                mutex.withLock {
                    ensureLoaded()
                    NativeLlama.generate(prompt, suffix, options.maxTokens, options.temperature) { trySend(it) }
                }
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun embed(input: String): List<Float> = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureLoaded()
            NativeLlama.embed(input).toList()
        }
    }

    private fun ensureLoaded() {
        if (loadedModelId == null) throw EngineUnavailableException("No GGUF model is loaded")
    }
}
