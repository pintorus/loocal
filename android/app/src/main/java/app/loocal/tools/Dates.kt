package app.loocal.tools

import java.util.Calendar

/**
 * Finds a date (and maybe a time) in English or French text: the role NSDataDetector plays on iOS.
 * Understands today/tomorrow/tonight, weekdays ("friday", "vendredi", "next monday"), day + month
 * ("21 November", "Nov 21", "le 21 novembre", "21/11"), and times ("9", "9am", "3:30 pm", "21h",
 * "14h30", "noon", "midi", "this evening").
 */
object Dates {
    data class Found(val start: Int, val end: Int, val millis: Long, val timed: Boolean)

    private val months = mapOf(
        "january" to 1, "jan" to 1, "janvier" to 1, "janv" to 1, "february" to 2, "feb" to 2, "février" to 2, "fevrier" to 2, "févr" to 2,
        "march" to 3, "mar" to 3, "mars" to 3, "april" to 4, "apr" to 4, "avril" to 4, "avr" to 4, "may" to 5, "mai" to 5,
        "june" to 6, "jun" to 6, "juin" to 6, "july" to 7, "jul" to 7, "juillet" to 7, "juil" to 7, "august" to 8, "aug" to 8, "août" to 8, "aout" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "septembre" to 9, "october" to 10, "oct" to 10, "octobre" to 10,
        "november" to 11, "nov" to 11, "novembre" to 11, "december" to 12, "dec" to 12, "décembre" to 12, "decembre" to 12, "déc" to 12,
    )
    private val weekdays = mapOf(
        "monday" to Calendar.MONDAY, "lundi" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "mardi" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "mercredi" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY, "jeudi" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "vendredi" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY, "samedi" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY, "dimanche" to Calendar.SUNDAY,
    )

    private val monthNames = months.keys.sortedByDescending { it.length }.joinToString("|")
    private val dayWords = "today|tomorrow|tonight|aujourd'hui|aujourd’hui|demain|ce soir|après-demain|apres-demain|day after tomorrow|" + weekdays.keys.joinToString("|")
    private val time = """(?:(?:at|à|a|vers)\s+)?(?:(\d{1,2})(?::(\d{2})|h(\d{2})?)?\s*(am|pm|a\.m\.|p\.m\.)?|noon|midnight|midi|minuit)"""

    private val patterns = listOf(
        // "next friday at 3pm", "demain à 9h", "tonight"
        Regex("""(?i)\b(?:(next|this|prochain|ce)\s+)?($dayWords)(?:\s+(prochain))?(?:\s*,?\s*(?:at|à|a|vers)?\s*($time))?\b"""),
        // "21 November [2027] [at 9]", "le 21 novembre"
        Regex("""(?i)\b(?:le\s+)?(\d{1,2})(?:st|nd|rd|th|er)?\s+($monthNames)\.?(?:\s+(\d{4}))?(?:\s*,?\s*($time))?\b"""),
        // "November 21[, 2027] [at 9]"
        Regex("""(?i)\b($monthNames)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?(?:\s*,?\s*($time))?\b"""),
        // "21/11[/2027]"
        Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b"""),
        // a time alone: "at 9", "à 21h", "3pm"
        Regex("""(?i)(?:\b(?:at|à|vers)\s+(\d{1,2})(?::(\d{2})|h(\d{2})?)?\s*(am|pm)?\b|\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b|\b(\d{1,2})h(\d{2})?\b|\b(noon|midnight|midi|minuit)\b)"""),
    )

    fun all(text: String, now: Calendar = Calendar.getInstance()): List<Found> =
        patterns.indices.flatMap { i -> patterns[i].findAll(text).mapNotNull { m -> parse(i, m, now) } }.sortedBy { it.start }
            .fold(mutableListOf<Found>()) { acc, f -> if (acc.none { f.start < it.end && it.start < f.end }) acc.add(f); acc }

    fun first(text: String, now: Calendar = Calendar.getInstance()) = all(text, now).firstOrNull()

    private fun parse(kind: Int, m: MatchResult, now: Calendar): Found? {
        val c = (now.clone() as Calendar).apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var timed = false
        fun applyTime(t: String?): Boolean {
            if (t.isNullOrBlank()) return false
            val tm = Regex("""(?i)(\d{1,2})(?::(\d{2})|h(\d{2})?)?\s*(am|pm|a\.m\.|p\.m\.)?|noon|midnight|midi|minuit""").find(t) ?: return false
            val word = tm.value.lowercase()
            val (h, min) = when (word) {
                "noon", "midi" -> 12 to 0
                "midnight", "minuit" -> 0 to 0
                else -> {
                    var hour = tm.groupValues[1].toIntOrNull() ?: return false
                    val minute = (tm.groupValues[2].ifEmpty { tm.groupValues[3] }).toIntOrNull() ?: 0
                    val ampm = tm.groupValues[4].lowercase()
                    if (ampm.startsWith("p") && hour < 12) hour += 12
                    if (ampm.startsWith("a") && hour == 12) hour = 0
                    if (hour > 23 || minute > 59) return false
                    hour to minute
                }
            }
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min)
            return true
        }
        when (kind) {
            0 -> {
                val word = m.groupValues[2].lowercase()
                val next = m.groupValues[1].lowercase() in setOf("next", "prochain") || m.groupValues[3].isNotEmpty()
                when (word) {
                    "today", "aujourd'hui", "aujourd’hui" -> {}
                    "tonight", "ce soir" -> { c.set(Calendar.HOUR_OF_DAY, 20); c.set(Calendar.MINUTE, 0); timed = true }
                    "tomorrow", "demain" -> c.add(Calendar.DAY_OF_YEAR, 1)
                    "après-demain", "apres-demain", "day after tomorrow" -> c.add(Calendar.DAY_OF_YEAR, 2)
                    else -> {
                        val target = weekdays[word] ?: return null
                        var add = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                        if (add == 0 || next && add < 7 && m.groupValues[1].isNotEmpty() && add == 0) add = 7
                        c.add(Calendar.DAY_OF_YEAR, add)
                    }
                }
                if (applyTime(m.groupValues[4])) timed = true
            }
            1, 2 -> {
                val day = (if (kind == 1) m.groupValues[1] else m.groupValues[2]).toInt()
                val month = months[(if (kind == 1) m.groupValues[2] else m.groupValues[1]).lowercase()] ?: return null
                val year = m.groupValues[3].toIntOrNull()
                if (day !in 1..31) return null
                c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.MONTH, month - 1); c.set(Calendar.DAY_OF_MONTH, day)
                if (year != null) c.set(Calendar.YEAR, year) else if (c.before(startOfDay(now))) c.add(Calendar.YEAR, 1)
                if (applyTime(m.groupValues[4])) timed = true
            }
            3 -> {
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                // Day first, as in France; "11/21" (month first) also works when the first number can't be a month.
                val (day, month) = if (a > 12) a to b else if (b > 12) b to a else a to b
                if (month !in 1..12 || day !in 1..31) return null
                c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.MONTH, month - 1); c.set(Calendar.DAY_OF_MONTH, day)
                m.groupValues[3].toIntOrNull()?.let { c.set(Calendar.YEAR, if (it < 100) 2000 + it else it) } ?: run { if (c.before(startOfDay(now))) c.add(Calendar.YEAR, 1) }
            }
            4 -> {
                if (!applyTime(m.value)) return null
                timed = true
                if (c.before(now)) c.add(Calendar.DAY_OF_YEAR, 1) // "at 9" after 9 means tomorrow
            }
        }
        if (!timed) { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0) }
        return Found(m.range.first, m.range.last + 1, c.timeInMillis, timed)
    }

    fun startOfDay(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
