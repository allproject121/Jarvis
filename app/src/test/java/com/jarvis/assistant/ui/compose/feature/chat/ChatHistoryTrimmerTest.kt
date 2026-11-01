package com.jarvis.assistant.ui.compose.feature.chat

import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTrimmerTest {

    private fun msg(i: Int, role: MessageRole, content: String) =
        Message(id = "m$i", conversationId = "c", role = role, content = content, timestamp = i.toLong())

    private fun conversation(turns: Int, size: Int): List<Message> = buildList {
        var i = 0
        repeat(turns) {
            add(msg(i++, MessageRole.USER, "q".repeat(size)))
            add(msg(i++, MessageRole.ASSISTANT, "a".repeat(size)))
        }
        add(msg(i, MessageRole.USER, "current question"))
    }

    @Test
    fun keeps_everything_when_it_fits() {
        val history = conversation(turns = 3, size = 10)
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 4096, reservedOutputTokens = 256)
        assertEquals(history, trimmed)
    }

    @Test
    fun drops_oldest_first_and_keeps_current_prompt() {
        val history = conversation(turns = 20, size = 400) // ~104 tokens per message
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 1024, reservedOutputTokens = 256)
        assertEquals(history.last(), trimmed.last())
        assertTrue(trimmed.size < history.size)
        // Window slides over the tail: contiguous suffix of the history.
        assertEquals(history.takeLast(trimmed.size), trimmed)
        val used = trimmed.sumOf { ChatHistoryTrimmer.estimateTokens(it.content) }
        assertTrue(used <= 1024 - 256)
    }

    @Test
    fun result_starts_with_user_message() {
        val history = conversation(turns = 10, size = 400)
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 700, reservedOutputTokens = 100)
        assertEquals(MessageRole.USER, trimmed.first().role)
    }

    @Test
    fun oversized_current_prompt_is_still_kept() {
        val history = listOf(
            msg(0, MessageRole.USER, "old"),
            msg(1, MessageRole.ASSISTANT, "reply"),
            msg(2, MessageRole.USER, "x".repeat(20_000))
        )
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 512, reservedOutputTokens = 128)
        assertEquals(listOf(history.last()), trimmed)
    }

    @Test
    fun exhausted_budget_returns_only_current_prompt() {
        val history = conversation(turns = 2, size = 10)
        val trimmed = ChatHistoryTrimmer.trim(history, contextLength = 100, reservedOutputTokens = 100)
        assertEquals(listOf(history.last()), trimmed)
    }

    @Test
    fun no_user_message_returns_empty() {
        val trimmed = ChatHistoryTrimmer.trim(
            listOf(msg(0, MessageRole.ASSISTANT, "hi")),
            contextLength = 4096,
            reservedOutputTokens = 256
        )
        assertTrue(trimmed.isEmpty())
    }

    @Test
    fun non_ascii_is_not_underestimated() {
        val cjk = "你好世界".repeat(10) // 40 wide chars
        assertTrue(ChatHistoryTrimmer.estimateTokens(cjk) >= 40)
    }
}
