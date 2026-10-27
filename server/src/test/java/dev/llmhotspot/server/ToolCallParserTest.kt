package dev.llmhotspot.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallParserTest {
    @Test fun parsesQwenStyleToolCall() {
        val calls = ToolCallParser.parse("<tool_call>{\"name\":\"weather\",\"arguments\":{\"city\":\"LA\"}}</tool_call>")
        assertEquals("weather", calls.single().function.name)
        assertEquals("{\"city\":\"LA\"}", calls.single().function.arguments)
    }
}
