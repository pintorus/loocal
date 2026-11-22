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
)

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
        get() = messages.firstOrNull { it.role == Role.USER }?.text?.lineSequence()?.first()?.take(60) ?: "New chat"

    fun appending(message: Message) = copy(messages = messages + message, updatedAt = message.createdAt)

    /** Edit rewinds the chat: drops the edited prompt and everything after it. */
    fun rewoundTo(messageId: String): Chat {
        val index = messages.indexOfFirst { it.id == messageId }
        return if (index < 0) this else copy(messages = messages.take(index))
    }

    /** Regenerate applies to the latest reply only. */
    fun withoutLatestReply(): Chat =
        if (messages.lastOrNull()?.role == Role.ASSISTANT) copy(messages = messages.dropLast(1)) else this
}
