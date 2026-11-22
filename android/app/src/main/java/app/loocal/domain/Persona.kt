package app.loocal.domain

import kotlinx.serialization.Serializable
import java.util.UUID

/** A named system prompt with an icon. At most [LIMIT] exist; none selected means the default prompt. */
@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String,
    val prompt: String,
    /** The errand bot's body for this persona (a BotShape name), and its colour (a BotTint name). */
    val shape: String? = null,
    val tint: String? = null,
) {
    companion object {
        const val LIMIT = 3
        /** Same names as iOS (SF Symbols); the Android UI maps each to a Material icon. */
        val ICONS = listOf("sparkles", "brain.head.profile", "book.closed", "pencil.line", "briefcase", "heart", "leaf", "flame", "moon", "cup.and.saucer", "graduationcap", "theatermasks", "terminal", "globe", "music.note", "camera")
    }
}
