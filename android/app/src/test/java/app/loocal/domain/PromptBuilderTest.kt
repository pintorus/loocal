package app.loocal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private fun user(t: String) = Message(role = Role.USER, text = t)
    private fun reply(t: String) = Message(role = Role.ASSISTANT, text = t)

    /** One token per character: easy to reason about in tests. */
    private val byChars = PromptBuilder { system, messages -> system.length + messages.sumOf { it.text.length } }

    @Test fun defaultPromptWhenNoPersonaOrBlank() {
        assertEquals(Limits.DEFAULT_SYSTEM_PROMPT, byChars.systemPrompt(null))
        assertEquals(Limits.DEFAULT_SYSTEM_PROMPT, byChars.systemPrompt(Persona(name = "x", icon = "moon", prompt = "  ")))
        assertEquals("Be brief.", byChars.systemPrompt(Persona(name = "x", icon = "moon", prompt = " Be brief. ")))
    }

    @Test fun keepsSevenMostRecentAndNeverStartsWithReply() {
        val history = (1..10).flatMap { listOf(user("q$it"), reply("a$it")) }
        val ready = byChars.prepare(history, null) as PreparedPrompt.Ready
        assertTrue(ready.messages.size <= Limits.RECENT_MESSAGES)
        assertEquals(Role.USER, ready.messages.first().role)
        assertEquals("a10", ready.messages.last().text)
    }

    @Test fun dropsOlderTurnsUntilItFits() {
        val big = "x".repeat(1500)
        val history = listOf(user(big), reply(big), user("latest"))
        val ready = byChars.prepare(history, null) as PreparedPrompt.Ready
        assertEquals(listOf("latest"), ready.messages.map { it.text })
    }

    @Test fun singleOverlongPromptIsRejected() {
        val result = byChars.prepare(listOf(user("x".repeat(3000))), null)
        assertEquals(PreparedPrompt.TooLong, result)
    }

    @Test fun editRewindsAndRegenerateDropsLatestReply() {
        val q1 = user("q1"); val a1 = reply("a1"); val q2 = user("q2"); val a2 = reply("a2")
        val chat = Chat(messages = listOf(q1, a1, q2, a2))
        assertEquals(listOf(q1, a1), chat.rewoundTo(q2.id).messages)
        assertEquals(listOf(q1, a1, q2), chat.withoutLatestReply().messages)
    }
}
