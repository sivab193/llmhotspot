package dev.llmhotspot.nativebridge

internal object NativeLlama {
    init { System.loadLibrary("llmhotspot") }

    external fun loadModel(path: String, contextSize: Int, gpuLayers: Int): String?
    external fun unloadModel()
    external fun generate(
        prompt: String,
        suffix: String?,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback,
    ): LongArray
    external fun embed(input: String): FloatArray

    fun interface TokenCallback { fun onToken(token: String) }
}
