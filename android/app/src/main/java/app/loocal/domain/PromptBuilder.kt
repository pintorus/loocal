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

/** Which message carries the photo, and the size the model sees it at. */
data class PhotoSlot(val index: Int, val width: Int, val height: Int)

sealed interface PreparedPrompt {
    data class Ready(val system: String, val messages: List<Message>, val photo: PhotoSlot? = null) : PreparedPrompt
    data object TooLong : PreparedPrompt
}

class PromptBuilder(
    /** Counts tokens of the fully templated input (system + messages + photo). Supplied by the engine. */
    private val countTokens: (system: String, messages: List<Message>, photo: PhotoSlot?) -> Int,
) {
    fun systemPrompt(persona: Persona?): String =
        persona?.prompt?.trim()?.takeIf { it.isNotEmpty() } ?: Limits.DEFAULT_SYSTEM_PROMPT

    /**
     * Only the most recent photo within the recent messages is kept (as on iOS);
     * [vision] false drops photos entirely (text-only model).
     */
    fun prepare(history: List<Message>, persona: Persona?, vision: Boolean = false, system: String = systemPrompt(persona)): PreparedPrompt {
        var recent = history.takeLast(Limits.RECENT_MESSAGES).dropLeadingReplies()
        fun slot(list: List<Message>): PhotoSlot? {
            if (!vision) return null
            val i = list.indexOfLast { it.imageName != null && it.imageWidth > 0 && it.paint == null }
            return if (i < 0) null else PhotoSlot(i, list[i].imageWidth, list[i].imageHeight)
        }
        while (countTokens(system, recent, slot(recent)) > Limits.MAX_INPUT_TOKENS && recent.size > 1) {
            recent = recent.drop(1).dropLeadingReplies()
        }
        return if (countTokens(system, recent, slot(recent)) > Limits.MAX_INPUT_TOKENS) PreparedPrompt.TooLong
        else PreparedPrompt.Ready(system, recent, slot(recent))
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
