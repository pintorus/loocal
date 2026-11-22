package app.loocal.domain

import app.loocal.ui.brokenMarkerRanges
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandsTest {
    private val maps = Island.connector("maps", "Maps").copy(id = "aaaa1111")
    private val cal = Island.connector("calendar", "Calendar").copy(id = "bbbb2222")
    private val text = "Find a quiet café ${maps.marker} that doesn’t clash with ${cal.marker}"

    @Test fun theModelReadsNames() =
        assertEquals("Find a quiet café [Maps] that doesn’t clash with [Calendar]", IslandText.spoken(text, listOf(maps, cal)))

    @Test fun toolsReadWordsOnly() =
        assertEquals("Find a quiet café that doesn’t clash with", IslandText.stripped(text))

    @Test fun peopleReadTitles() =
        assertEquals("Find a quiet café Maps that doesn’t clash with Calendar", IslandText.titled(text, listOf(maps, cal)))

    @Test fun deletedMarkersAreGone() = assertEquals(listOf(maps), IslandText.present("x ${maps.marker}", listOf(maps, cal)))

    @Test fun backspaceTakesTheWholeIsland() {
        val broken = "café ⟦aaaa111 then ${cal.marker}"
        assertEquals(listOf(5..12), brokenMarkerRanges(broken))
        assertEquals(emptyList<IntRange>(), brokenMarkerRanges(text))
    }

    @Test fun messageTitleIsReadable() {
        val chat = Chat().appending(Message(role = Role.USER, text = text, islands = listOf(maps, cal)))
        assertEquals("Find a quiet café Maps that doesn’t clash with Calendar", chat.title)
    }
}

class IslandFieldTest {
    @Test fun typedToolNamesAreFound() {
        val found = app.loocal.ui.mentionRanges("Check maps and Calendar, not mapsy ⟦maps1234⟧", mapOf("maps" to "maps", "calendar" to "calendar"))
        assertEquals(listOf(6..9 to "maps", 15..22 to "calendar"), found)
    }

    @Test fun carriedIslandsLandBetweenWords() {
        assertEquals(5, app.loocal.ui.landing("find quiet cafe", 7))
        assertEquals(10, app.loocal.ui.landing("find quiet cafe", 9))
        assertEquals(0, app.loocal.ui.landing("find", 0))
    }
}
