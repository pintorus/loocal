package app.loocal.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Photo file name in the app's images folder, if one was sent with this prompt. */
    val imageName: String? = null,
    /** Size the photo is given to the model at (fits 512 x 512), for counting its tokens. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Attached file names, shown as chips above the prompt. */
    val files: List<String> = emptyList(),
    /** Text of attached files, given to the model before the prompt (never shown). */
    val context: String? = null,
    /** Steps a reply took before answering: sources read, tools run, actions done. */
    val tools: List<app.loocal.tools.ToolUse> = emptyList(),
    /** Tools, connectors and attachments placed in the prompt as islands (markers in [text]). */
    val islands: List<Island> = emptyList(),
) {
    /** The prompt as a person reads it (titles, copy, edit, History). */
    val readable get() = IslandText.titled(text, islands)

    /** What the model reads for this message: attached file text, then the message, islands by name. */
    val modelText get() = IslandText.spoken(text, islands).let { spoken -> context?.let { it + "\n\n" + spoken } ?: spoken }
}


/** A saved conversation. A temporary chat is never written to the archive. */
@Serializable
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val messages: List<Message> = emptyList(),
    val isTemporary: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
) {
    /** History title: the first prompt, shortened. */
    val title: String
        get() = messages.firstOrNull { it.role == Role.USER }?.readable?.lineSequence()?.first()?.take(60) ?: "New chat"

    /** A chat takes its date from its first message (typed, spoken, or continued from an errand), not from when it was opened. */
    fun appending(message: Message) = copy(messages = messages + message, updatedAt = message.createdAt, createdAt = if (messages.isEmpty()) message.createdAt else createdAt)

    /** Edit rewinds the chat: drops the edited prompt and everything after it. */
    fun rewoundTo(messageId: String): Chat {
        val index = messages.indexOfFirst { it.id == messageId }
        return if (index < 0) this else copy(messages = messages.take(index))
    }

    /** Regenerate applies to the latest reply only. */
    fun withoutLatestReply(): Chat =
        if (messages.lastOrNull()?.role == Role.ASSISTANT) copy(messages = messages.dropLast(1)) else this
}
