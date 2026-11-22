package app.loocal.voice

import java.text.Normalizer

/**
 * Who has the floor in a spoken conversation (port of iOS `TurnTaking`): what you say over
 * Loocal, minus the echo of its own voice, cuts it off only when it's really you taking a turn.
 */
object TurnTaking {
    fun normalized(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")
            .trim { !it.isLetterOrDigit() && it != '-' }.replace("'", "").replace("’", "")

    fun words(text: String) = text.split(Regex("\\s+")).map(::normalized).filter { it.isNotEmpty() }

    /** Sounds that say "go on", not "stop": they never cut Loocal off. */
    val backchannels = setOf(
        "yeah", "yes", "yep", "ok", "okay", "mm", "mmm", "hmm", "mhm", "mm-hmm", "uh-huh", "uh", "um", "huh", "right", "sure", "cool", "nice", "wow", "ah", "oh",
        "oui", "ouais", "daccord", "dac", "euh", "bah", "voila", "cest", "ca", "si", "vale", "ja", "genau",
    )

    /** A single one of these is enough to stop Loocal. */
    val stopWords = setOf(
        "stop", "wait", "hold", "no", "nope", "hey", "sorry", "actually", "pause", "enough", "but", "listen",
        "attends", "attend", "arrete", "non", "pardon", "mais", "ecoute", "chut", "basta", "para", "espera", "halt",
    )

    /**
     * How many of your first words are an echo of what Loocal said: they must follow its words in
     * order, so a lone "is" or "je" that it also happened to use is kept. A single echoed word
     * counts only when it's a long one.
     */
    fun echoLength(text: String, spoken: String): Int {
        val mine = words(text)
        val said = words(spoken)
        var best = 0
        for (start in said.indices) {
            var k = 0
            while (k < mine.size && start + k < said.size && mine[k] == said[start + k]) k++
            if (k > best) best = k
        }
        return if (best == 1 && mine[0].length < 5) 0 else best
    }

    /** Your words with any leading echo of what Loocal just said stripped off. */
    fun strippingEcho(text: String, spoken: String): String =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.drop(echoLength(text, spoken)).joinToString(" ")

    /** Whether what you said while Loocal was talking is you taking the floor. */
    fun isInterruption(text: String, echo: Set<String>, talkingFor: Double): Boolean {
        val novel = words(text).filter { it !in echo && it !in backchannels }
        if (novel.size >= 2) return true
        val word = novel.firstOrNull() ?: return false
        return word in stopWords || talkingFor >= 0.6
    }

    /** Where the reply's first clause ends (a comma, colon or dash after a few words), to start speaking early. */
    fun firstClauseEnd(text: String): Int? {
        val m = Regex("[,;:—–]\\s").findAll(text).firstOrNull { words(text.substring(0, it.range.first)).size >= 4 } ?: return null
        return m.range.first + 1
    }
}
