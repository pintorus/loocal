package app.loocal.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ToolsTest {
    // Friday 21 November 2026, 10:00
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 20, 10, 0) }
    private fun cal(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }

    @Test fun datesTomorrowAtNine() {
        val f = Dates.first("call mum tomorrow at 9", now)!!
        val c = cal(f.millis)
        assertTrue(f.timed)
        assertEquals(21, c.get(Calendar.DAY_OF_MONTH)); assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun datesFrenchDayMonthAndHour() {
        val f = Dates.first("rendez-vous le 3 décembre à 14h30", now)!!
        val c = cal(f.millis)
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH)); assertEquals(3, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, c.get(Calendar.HOUR_OF_DAY)); assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test fun datesWeekdayIsNextOccurrence() {
        val c = cal(Dates.first("dentist on monday", now)!!.millis)
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK)); assertEquals(23, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun datesPastDayRollsToNextYear() {
        val c = cal(Dates.first("party on 5 March", now)!!.millis)
        assertEquals(2027, c.get(Calendar.YEAR))
    }

    @Test fun reminderWithRelativeTime() {
        val a = ToolAction.detect("Remind me to take the pizza out in 20 minutes")!!
        assertEquals(ToolAction.Kind.REMINDER, a.kind)
        assertEquals("Take the pizza out", a.title)
        assertTrue(a.timed)
        assertTrue(a.date!! - System.currentTimeMillis() in 19 * 60_000L..21 * 60_000L)
    }

    @Test fun frenchReminderAndRemember() {
        val r = ToolAction.detect("rappelle-moi d'appeler Paul demain à 18h")!!
        assertEquals(ToolAction.Kind.REMINDER, r.kind)
        assertEquals("Appeler Paul", r.title)
        val m = ToolAction.detect("Souviens-toi que je suis végétarien")!!
        assertEquals(ToolAction.Kind.REMEMBER, m.kind)
        assertEquals("Je suis végétarien", m.title)
        assertNull(ToolAction.detect("Tu te souviens de mon chat ?"))
    }

    @Test fun eventNeedsADate() {
        assertNull(ToolAction.detect("schedule a meeting"))
        assertEquals(ToolAction.Kind.EVENT, ToolAction.detect("Schedule a meeting with Ana on Friday at 3pm")!!.kind)
    }

    @Test fun memoryPromptNewestFirstAndCapped() {
        val list = (1..50).map { Memory(text = "fact $it " + "x".repeat(20), createdAt = it.toLong()) }
        val prompt = Memory.prompt(list)
        assertTrue(prompt.contains("fact 50"))
        assertFalse(prompt.contains("fact 1 "))
    }

    @Test fun calculator() {
        assertEquals("12*7 + 3 = 87", Calculator.answer("what's 12*7 + 3?")!!.shown)
        assertEquals("15% of 80 = 12", Calculator.answer("15% of 80")!!.shown)
        assertEquals("√81 = 9", Calculator.answer("racine carrée de 81")!!.shown)
        assertNull(Calculator.answer("I have 2 cats"))
        assertNull(Calculator.answer("1/0 + 2"))
    }

    @Test fun webSearchTriggers() {
        assertTrue(WebSearch.wantsSearch("What's the latest news on Mars?"))
        assertTrue(WebSearch.wantsSearch("quelle est la météo à Lyon"))
        assertFalse(WebSearch.wantsSearch("Write me a poem about the sea"))
        assertTrue(WebSearch.explicitlyAsked("cherche sur internet les horaires"))
        assertEquals("the price of bitcoin", WebSearch.searchQuery("Can you search for the price of bitcoin?"))
    }

    @Test fun toolCallParsing() {
        val (name, args) = ToolCall.parse("Let me check.<tool_call>{\"name\": \"web_search\", \"arguments\": {\"query\": \"paris\"}}</tool_call>")!!
        assertEquals("web_search", name)
        assertEquals("paris", WebSearch.query(args))
        assertEquals("Let me check.", ToolCall.visible("Let me check.<tool_call>{...}"))
        assertNull(ToolCall.parse("no call here"))
    }

    @Test fun wikipediaKeywords() {
        assertEquals(listOf("Eiffel", "Tower"), Wikipedia.keywords("How tall is the Eiffel Tower?"))
        assertEquals(listOf("tallest", "mountain"), Wikipedia.keywords("what is the tallest mountain"))
    }

    @Test fun placesDetection() {
        assertEquals("pharmacy", Places.detect("find a pharmacy near me")!!.query)
        val trip = Places.detect("how long to get to the train station?")!!
        assertTrue(trip.isRoute); assertEquals("the train station", trip.query)
        assertNull(Places.detect("tell me a joke"))
    }

    @Test fun translationDetection() {
        val r = Translator.detect("translate good morning into Spanish")!!
        assertEquals("good morning", r.text); assertEquals("es", r.target)
        assertEquals("en", Translator.detect("traduis bonjour en anglais")!!.target)
        assertNotNull(Translator.detect("how do you say thank you in Japanese?"))
    }
}
