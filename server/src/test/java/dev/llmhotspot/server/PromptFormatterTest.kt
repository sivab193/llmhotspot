package dev.llmhotspot.server

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatterTest {
    @Test fun formatsConversation() {
        val prompt = PromptFormatter.chat(ChatCompletionRequest("test", listOf(ChatMessage("user", "hello"))))
        assertTrue(prompt.contains("<|im_start|>user\nhello<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }
}
