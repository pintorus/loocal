package app.loocal.domain

/**
 * Same rules as the iOS app (App/InferenceEngine.swift, App/AppState.swift):
 * - system prompt = persona prompt, or the default when none / blank
 * - recent history = last 7 messages, never starting with a reply
 * - older turns are dropped until the prepared input fits [Limits.MAX_INPUT_TOKENS]
 */
object Limits {
    const val MAX_INPUT_TOKENS = 2048
    const val MAX_REPLY_TOKENS = 768
    const val RECENT_MESSAGES = 7
    const val MAX_PROMPT_CHARS = 6000
    const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Answer directly and accurately."
}

sealed interface PreparedPrompt {
    data class Ready(val system: String, val messages: List<Message>) : PreparedPrompt
    data object TooLong : PreparedPrompt
}

class PromptBuilder(
    /** Counts tokens of the fully templated input (system + messages). Supplied by the engine. */
    private val countTokens: (system: String, messages: List<Message>) -> Int,
) {
    fun systemPrompt(persona: Persona?): String =
        persona?.prompt?.trim()?.takeIf { it.isNotEmpty() } ?: Limits.DEFAULT_SYSTEM_PROMPT

    fun prepare(history: List<Message>, persona: Persona?): PreparedPrompt {
        val system = systemPrompt(persona)
        var recent = history.takeLast(Limits.RECENT_MESSAGES).dropLeadingReplies()
        while (countTokens(system, recent) > Limits.MAX_INPUT_TOKENS && recent.size > 1) {
            recent = recent.drop(1).dropLeadingReplies()
        }
        return if (countTokens(system, recent) > Limits.MAX_INPUT_TOKENS) PreparedPrompt.TooLong
        else PreparedPrompt.Ready(system, recent)
    }

    private fun List<Message>.dropLeadingReplies(): List<Message> {
        var list = this
        while (list.size > 1 && list.first().role == Role.ASSISTANT) list = list.drop(1)
        return list
    }

    companion object {
        /** Character guard checked before any tokenization, as on iOS. */
        fun isPromptTooLong(prompt: String) = prompt.length > Limits.MAX_PROMPT_CHARS
    }
}
