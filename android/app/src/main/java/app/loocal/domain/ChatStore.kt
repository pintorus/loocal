package app.loocal.domain

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Archive of saved chats and personas in app-private storage (excluded from backup in the manifest).
 * Temporary chats are never written.
 */
class ChatStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val chatsFile get() = File(directory, "chats.json")
    private val personasFile get() = File(directory, "personas.json")

    fun loadChats(): List<Chat> = read(chatsFile, ListSerializer(Chat.serializer())).sortedByDescending { it.updatedAt }

    fun save(chat: Chat) {
        if (chat.isTemporary || chat.messages.isEmpty()) return
        val others = loadChats().filterNot { it.id == chat.id }
        write(chatsFile, ListSerializer(Chat.serializer()), listOf(chat) + others)
    }

    fun delete(chatId: String) =
        write(chatsFile, ListSerializer(Chat.serializer()), loadChats().filterNot { it.id == chatId })

    fun loadPersonas(): List<Persona> = read(personasFile, ListSerializer(Persona.serializer()))

    fun savePersonas(personas: List<Persona>) =
        write(personasFile, ListSerializer(Persona.serializer()), personas.take(Persona.LIMIT))

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        if (!file.exists()) emptyList() else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    /** Write to a temp file then rename, so a crash never leaves a half-written archive. */
    private fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<List<T>>, value: List<T>) {
        directory.mkdirs()
        val tmp = File(directory, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
