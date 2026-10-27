package dev.llmhotspot.server

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolCallParser {
    private val block = Regex("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parse(text: String): List<ToolCall> = block.findAll(text).mapNotNull { match ->
        runCatching {
            val value = Json.parseToJsonElement(match.groupValues[1]).jsonObject
            val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val arguments = value["arguments"]?.toString() ?: "{}"
            ToolCall("call_${UUID.randomUUID().toString().replace("-", "").take(24)}", function = FunctionCall(name, arguments))
        }.getOrNull()
    }.toList()
}
