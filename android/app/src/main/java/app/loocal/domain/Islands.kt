package app.loocal.domain

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A tool, connector or attachment set into the prompt itself (port of App/Islands.swift): a small
 * coloured capsule between words, so you can say *where* a source matters ("find a café ⟦Maps⟧
 * that doesn't clash with ⟦Calendar⟧"). In text it's stored as a marker, `⟦id⟧`.
 */
@Serializable
data class Island(
    val id: String = newId(),
    val kind: Kind,
    /** The connector id, the attached file's name, or the photo's image name. */
    val ref: String,
    val title: String,
    val icon: String,
    val tint: IslandTint,
    /** The attached file's own name; its island just says "File". */
    val name: String? = null,
) {
    @Serializable enum class Kind { PHOTO, FILE, CONNECTOR }

    val marker get() = OPEN + id + CLOSE

    /** How the model reads it in your prompt. */
    val spoken get() = when (kind) {
        Kind.PHOTO -> "[the attached photo]"
        Kind.FILE -> "[the attached file “${name ?: title}”]"
        Kind.CONNECTOR -> "[$title]"
    }

    companion object {
        const val OPEN = "⟦"
        const val CLOSE = "⟧"
        fun newId() = UUID.randomUUID().toString().take(8)

        fun connector(id: String, name: String) = Island(kind = Kind.CONNECTOR, ref = id, title = name, icon = islandIcon(id), tint = IslandTint.forConnector(id))
        fun file(name: String) = Island(kind = Kind.FILE, ref = name, title = "File", icon = "doc", tint = IslandTint.AMBER, name = name)
        fun photo(imageName: String) = Island(kind = Kind.PHOTO, ref = imageName, title = "Photo", icon = "photo", tint = IslandTint.PINK)

        fun islandIcon(id: String) = when (id) {
            "maps" -> "pin"; "web" -> "compass"; "calendar" -> "calendar"; "reminders" -> "checklist"; "clipboard" -> "clipboard"; "wikipedia" -> "globe"; else -> "server"
        }
    }
}

/** The splash of colour each island brings to the monochrome page, borrowed from the app it stands for. */
@Serializable
enum class IslandTint(val stops: List<Int>) {
    GREEN(listOf(0x7BDD72, 0x34B556)),        // Maps
    BLUE(listOf(0x5BBDFF, 0x1A73F0)),         // web
    RED(listOf(0xFF6F61, 0xE5302A)),          // Calendar
    ORANGE(listOf(0xFFB24A, 0xFF7A1C)),       // Reminders
    PURPLE(listOf(0xBE96FF, 0x7D4AEA)),       // Clipboard
    TEAL(listOf(0x5ADBCB, 0x14968F)),         // MCP servers
    AMBER(listOf(0xFFE07A, 0xF6C12E)),        // files
    PINK(listOf(0xFFB86B, 0xFF5E8E, 0xA86DFF)), // photos
    INDIGO(listOf(0x938CFF, 0x4F46E5)),       // translate
    SLATE(listOf(0xF7F7F7, 0xDCDCDC));        // Wikipedia

    /** The deepest colour of the fill (a drop caret, a dot). */
    val deep get() = stops.last()

    companion object {
        fun forConnector(id: String) = when (id) {
            "maps" -> GREEN; "web" -> BLUE; "calendar" -> RED; "reminders" -> ORANGE; "clipboard" -> PURPLE; "wikipedia" -> SLATE; else -> TEAL
        }
    }
}

/** Text with island markers. */
object IslandText {
    sealed interface Piece { data class Text(val text: String) : Piece; data class Isle(val island: Island) : Piece }

    private val marker = Regex("⟦([^⟧]*)⟧")

    /** Splits marker text into words and islands. Markers for unknown islands are dropped. */
    fun pieces(text: String, islands: List<Island>): List<Piece> {
        if (!text.contains(Island.OPEN)) return if (text.isEmpty()) emptyList() else listOf(Piece.Text(text))
        val byId = islands.associateBy { it.id }
        val out = mutableListOf<Piece>()
        var last = 0
        for (m in marker.findAll(text)) {
            if (m.range.first > last) out.add(Piece.Text(text.substring(last, m.range.first)))
            byId[m.groupValues[1]]?.let { out.add(Piece.Isle(it)) }
            last = m.range.last + 1
        }
        if (last < text.length) out.add(Piece.Text(text.substring(last)))
        return out
    }

    /** The prompt as the model reads it: "find a café [Maps] near me". */
    fun spoken(text: String, islands: List<Island>) = join(text, islands) { it.spoken }

    /** The prompt as a person reads it (copy, edit, titles): "find a café Maps near me". */
    fun titled(text: String, islands: List<Island>) = join(text, islands) { it.name ?: it.title }

    /** Words only, for spotting what a prompt asks (tools ignore the islands themselves). */
    fun stripped(text: String): String =
        if (!text.contains(Island.OPEN)) text else text.replace(marker, " ").replace(Regex(" {2,}"), " ").trim()

    /** The islands whose markers are still in the text. */
    fun present(text: String, islands: List<Island>) = islands.filter { text.contains(it.marker) }

    private fun join(text: String, islands: List<Island>, word: (Island) -> String) =
        pieces(text, islands).joinToString("") { when (it) { is Piece.Text -> it.text; is Piece.Isle -> word(it.island) } }.replace(Regex(" {2,}"), " ")
}
