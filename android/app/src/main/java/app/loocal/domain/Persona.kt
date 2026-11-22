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
) {
    companion object {
        const val LIMIT = 3
        val ICONS = listOf("sparkles", "brain", "book", "pencil", "briefcase", "heart", "leaf", "flame", "moon", "cup")
    }
}
