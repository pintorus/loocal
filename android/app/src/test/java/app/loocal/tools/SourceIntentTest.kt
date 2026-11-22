package app.loocal.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SourceIntentTest {
    // Friday 20 November 2026, 10:00
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 20, 10, 0) }
    private fun day(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }.let { it.get(Calendar.MONTH) to it.get(Calendar.DAY_OF_MONTH) }

    @Test fun wordsPickTheSources() {
        assertEquals(setOf(Connector.CALENDAR.id), SourceIntent.wanted("Check my calendar please"))
        assertEquals(setOf(Connector.CALENDAR.id), SourceIntent.wanted("Qu’est-ce que j’ai demain ?"))
        assertEquals(setOf(Connector.REMINDERS.id), SourceIntent.wanted("what's due this week"))
        assertTrue(SourceIntent.wanted("write a poem").isEmpty())
    }

    @Test fun windows() {
        assertEquals("tomorrow", SourceIntent.calendarWindow("what have I got tomorrow", now).label)
        val next = SourceIntent.calendarWindow("what have I got next month", now)
        assertEquals("next month", next.label)
        assertEquals(Calendar.DECEMBER to 1, day(next.start))
        assertEquals("next 10 days", SourceIntent.calendarWindow("my plans for the next 10 days", now).label)
        val weekend = SourceIntent.calendarWindow("am I free this weekend", now)
        assertEquals(Calendar.NOVEMBER to 21, day(weekend.start))
        assertEquals("next 7 days", SourceIntent.calendarWindow("check my calendar", now).label)
    }
}
