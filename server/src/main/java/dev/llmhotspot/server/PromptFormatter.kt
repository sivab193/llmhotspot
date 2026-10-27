package dev.llmhotspot.server

import kotlinx.serialization.json.Json

object PromptFormatter {
    private val json = Json { encodeDefaults = false }

    fun chat(request: ChatCompletionRequest): String = buildString {
        append("<|im_start|>system\n")
        append("You are a helpful assistant. Follow tool schemas when tools are supplied.<|im_end|>\n")
        request.tools?.let {
            append("<|im_start|>system\nAvailable tools: ")
            append(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it))
            append("\nTo call a tool, respond only with <tool_call>{\"name\":\"function_name\",\"arguments\":{...}}</tool_call>.")
            append("<|im_end|>\n")
        }
        request.messages.forEach { message ->
            append("<|im_start|>")
            append(message.role)
            append('\n')
            append(message.content.orEmpty())
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}
