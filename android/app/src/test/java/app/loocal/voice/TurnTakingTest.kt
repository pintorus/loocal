package app.loocal.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTakingTest {
    private val echo = TurnTaking.words("The weather in Paris is mild today").toSet()

    @Test fun backchannelsNeverCutIn() {
        assertFalse(TurnTaking.isInterruption("yeah", echo, 3.0))
        assertFalse(TurnTaking.isInterruption("mm-hmm ok", echo, 3.0))
        assertFalse(TurnTaking.isInterruption("Ouais d’accord", echo, 3.0))
    }

    @Test fun echoIsNotTheUser() = assertFalse(TurnTaking.isInterruption("weather in Paris", echo, 3.0))

    @Test fun stopWordsAndNewWordsCutIn() {
        assertTrue(TurnTaking.isInterruption("wait", echo, 0.1))
        assertTrue(TurnTaking.isInterruption("attends", echo, 0.1))
        assertTrue(TurnTaking.isInterruption("what about Lyon", echo, 0.1))
        assertFalse(TurnTaking.isInterruption("Lyon", echo, 0.2))
        assertTrue(TurnTaking.isInterruption("Lyon", echo, 1.0))
    }

    private val said = "The weather in Paris is mild today"

    @Test fun leadingEchoIsStripped() = assertEquals("what about Lyon", TurnTaking.strippingEcho("mild today what about Lyon", said))

    /** The start of your sentence is yours even when Loocal used the same little words. */
    @Test fun commonWordsAreKept() {
        assertEquals("is it raining in Lyon", TurnTaking.strippingEcho("is it raining in Lyon", said))
        assertEquals("the train to Lyon", TurnTaking.strippingEcho("the train to Lyon", said))
        assertEquals("what about Lyon", TurnTaking.strippingEcho("Paris what about Lyon", said))
    }

    @Test fun firstClauseGoesEarly() {
        assertEquals(22, TurnTaking.firstClauseEnd("Sure, here's the plan, step by step"))
        assertEquals(null, TurnTaking.firstClauseEnd("Sure, here"))
    }
}
