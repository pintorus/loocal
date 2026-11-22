package app.loocal.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** How often an errand comes back. `NOW` is a one-off run that never repeats (iOS `Cadence`). */
@Serializable
enum class Cadence(val label: String, val short: String, val symbol: String) {
    NOW("Now", "Now", "bolt"), DAILY("Every day", "Daily", "sun.max"), WEEKDAYS("Weekdays", "Weekdays", "briefcase"), WEEKLY("Every week", "Weekly", "calendar");
}

/** A routine: instructions Loocal carries out on device at a set time. */
@Serializable
data class Errand(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val instructions: String,
    val cadence: Cadence,
    val hour: Int,
    val minute: Int,
    /** Calendar weekday (1 = Sunday), for weekly errands. */
    val weekday: Int,
    /** Connector ids read before each run. */
    val sources: List<String>,
    val paused: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRun: Long? = null,
) {
    private val weekdays: List<Int?> get() = when (cadence) {
        Cadence.NOW -> emptyList()
        Cadence.DAILY -> listOf(null)
        Cadence.WEEKDAYS -> listOf(2, 3, 4, 5, 6)
        Cadence.WEEKLY -> listOf(weekday)
    }

    /** The first scheduled time strictly after [after]. */
    fun next(after: Long): Long? = weekdays.map { day ->
        val c = Calendar.getInstance().apply { timeInMillis = after; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        if (day != null) { while (c.get(Calendar.DAY_OF_WEEK) != day || c.timeInMillis <= after) c.add(Calendar.DAY_OF_YEAR, 1) }
        else while (c.timeInMillis <= after) c.add(Calendar.DAY_OF_YEAR, 1)
        c.timeInMillis
    }.minOrNull()

    val nextRun: Long? get() = if (paused) null else next(maxOf(lastRun ?: createdAt, System.currentTimeMillis() - 1000))
    /** A scheduled time has passed since the last run (or since it was made). */
    val isDue: Boolean get() = !paused && (next(lastRun ?: createdAt)?.let { it <= System.currentTimeMillis() } ?: false)

    val time: String get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }.time)

    val schedule: String get() = when (cadence) {
        Cadence.NOW -> "Once"
        Cadence.WEEKLY -> DateFormatSymbols.getInstance(Locale.getDefault()).weekdays[weekday].replaceFirstChar { it.uppercase() } + " · " + time
        else -> "${cadence.short} · $time"
    }

    companion object {
        /** Spoken (and typed) openers that say nothing about the errand itself. */
        private val fillers = listOf("and", "also", "then", "so", "ok", "okay", "please", "can you", "could you", "would you", "i need you to", "i want you to", "i'd like you to", "et", "puis", "peux-tu", "tu peux", "pourrais-tu", "s'il te plaît")
        /** Small words a title shouldn't trail off on. */
        private val dangling = setOf("to", "a", "an", "the", "for", "of", "with", "and", "or", "in", "on", "at", "my", "de", "à", "pour", "le", "la", "les", "un", "une", "et", "avec", "mon", "ma")

        fun title(instructions: String): String {
            val words = stripped(instructions).split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6).toMutableList()
            while (words.size > 1 && words.last().lowercase() in dangling) words.removeAt(words.lastIndex)
            val clean = words.joinToString(" ").trim { !it.isLetterOrDigit() }
            return if (clean.isEmpty()) "Errand" else clean.replaceFirstChar { it.uppercase() }
        }

        /** The request without spoken openers like "and" or "can you". */
        fun stripped(instructions: String): String {
            var text = instructions.trim()
            var trimmed = true
            while (trimmed) {
                trimmed = false
                for (f in fillers) if (text.lowercase().startsWith("$f ") || text.lowercase().startsWith("$f,")) {
                    text = text.drop(f.length + 1).trim { it.isWhitespace() || it in ".,;:!?" }
                    trimmed = true
                }
            }
            return text
        }
    }
}

/** One time Loocal carried out an errand, and what came back. */
@Serializable
data class ErrandRun(
    val id: String = UUID.randomUUID().toString(),
    val errandId: String? = null,
    val title: String,
    val instructions: String,
    val sources: List<String>,
    val startedAt: Long = System.currentTimeMillis(),
    val text: String = "",
    val status: Status = Status.QUEUED,
    val note: String? = null,
    /** What it's doing right now ("Reading Calendar", "Next up"), while on the go. */
    val stage: String? = null,
    /** Tokens written so far, for the crew card's progress line. */
    val tokens: Int? = null,
    /** Opened since it finished; unseen results stay with the crew. */
    val seen: Boolean = false,
    /** The persona that ran it, which also decides its bot's shape. */
    val personaId: String? = null,
) {
    @Serializable enum class Status { QUEUED, RUNNING, DONE, STOPPED, FAILED }
    val isActive get() = status == Status.QUEUED || status == Status.RUNNING

    companion object { const val NEXT_UP = "Next up" }
}

/** A ready-made errand for the suggestion row. */
data class ErrandIdea(val title: String, val detail: String, val instructions: String, val sources: List<String>, val cadence: Cadence, val hour: Int, val minute: Int) {
    companion object {
        val ALL = listOf(
            ErrandIdea("Morning brief", "every day at 7:30", "Give me a calm morning brief: what’s on my calendar today, which reminders are due, and the one thing worth doing first.", listOf("calendar", "reminders"), Cadence.DAILY, 7, 30),
            ErrandIdea("Plan my week", "Mondays at 8:00", "Look at my week ahead and lay it out day by day. Flag clashes, tight days and anything I should prepare for.", listOf("calendar"), Cadence.WEEKLY, 8, 0),
            ErrandIdea("Sort my reminders", "right now", "Sort my open reminders into Today, This week and Someday, and suggest which ones to drop.", listOf("reminders"), Cadence.NOW, 9, 0),
            ErrandIdea("Tidy the clipboard", "right now", "Turn what I last copied into a clean, well-structured note with a short title.", listOf("clipboard"), Cadence.NOW, 9, 0),
        )
    }
}

/** Errands and their results (iOS `ErrandDesk`), kept on this phone only. */
class ErrandStore(directory: File) {
    @Serializable private data class Archive(val errands: List<Errand> = emptyList(), val runs: List<ErrandRun> = emptyList())
    private val file = File(directory, "errands.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads; a run cut off by the app quitting keeps its partial text and shows as stopped. */
    fun load(): Pair<List<Errand>, List<ErrandRun>> {
        val archive = runCatching { json.decodeFromString(Archive.serializer(), file.readText()) }.getOrDefault(Archive())
        return archive.errands to archive.runs.map { if (it.isActive) it.copy(status = ErrandRun.Status.STOPPED, stage = null) else it }
    }

    fun save(errands: List<Errand>, runs: List<ErrandRun>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Archive.serializer(), Archive(errands, runs.take(200))))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val SYSTEM_PROMPT = "You are Loocal, running an errand for the user on their phone, fully offline. Do the whole task in one go without asking questions; if something is missing, make a sensible assumption and say so in one line. Reply with the finished result only: a short first line as a title, then the result, concise and easy to scan."
        const val CREW_SIZE = 4

        fun now(): String = SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.getDefault()).format(Date())
    }
}
