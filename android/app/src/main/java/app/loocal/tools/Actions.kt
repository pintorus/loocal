package app.loocal.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Something Loocal keeps in mind about you across chats (iOS `Memory`). Saved on this phone only. */
@Serializable
data class Memory(val id: String = UUID.randomUUID().toString(), val text: String, val createdAt: Long = System.currentTimeMillis()) {
    companion object {
        const val LIMIT = 40

        /** Added to the system prompt: newest first, stopping at a few hundred characters. */
        fun prompt(list: List<Memory>): String {
            if (list.isEmpty()) return ""
            val lines = mutableListOf<String>()
            var used = 0
            for (m in list.sortedByDescending { it.createdAt }) {
                if (used + m.text.length >= 600) break
                lines.add("- " + m.text); used += m.text.length
            }
            return "What you know about the user (they asked you to remember it; use it when it helps, don't recite it):\n" + lines.joinToString("\n")
        }
    }
}

/**
 * A reminder kept by Loocal. Android has no system reminders app shared with other apps (iOS has
 * Reminders), so Loocal keeps the list itself and notifies at the due time.
 */
@Serializable
data class Reminder(val id: String = UUID.randomUUID().toString(), val title: String, val due: Long? = null, val timed: Boolean = false, val done: Boolean = false)

/**
 * Something the user asked Loocal to do, not just answer (port of iOS `ToolAction`): add a reminder,
 * put an event in the calendar, remember or forget a fact. Spotted in their own words, so it works
 * with small models that rarely call tools, and done right away with an Undo.
 * The iOS Shortcuts actions have no Android equivalent and aren't offered.
 */
@Serializable
data class ToolAction(
    val kind: Kind,
    val title: String,
    val date: Long? = null,
    val timed: Boolean = false,
    /** The created reminder, event or memory, so Undo can remove it. */
    val itemId: String? = null,
    /** For `forget`: the memory that was removed, so Undo can put it back. */
    val removed: Memory? = null,
) {
    @Serializable enum class Kind { REMINDER, EVENT, REMEMBER, FORGET }

    val undoable get() = true

    /** "Tomorrow 09:00", "Fri 21 Nov". */
    val whenText: String? get() {
        val d = date ?: return null
        val today = Dates.startOfDay(Calendar.getInstance()).timeInMillis
        val day = when {
            d >= today && d < today + DAY -> "Today"
            d >= today + DAY && d < today + 2 * DAY -> "Tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(d))
        }
        return if (timed) day + " " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d)) else day
    }

    /** The box shown in the reply. */
    fun box(): ToolUse {
        val detail = listOfNotNull(title, whenText).joinToString(" · ")
        return when (kind) {
            Kind.REMINDER -> ToolUse(kind = "reminder", icon = "checklist", running = "Adding a reminder", done = "Added to Reminders", detail = detail, action = this)
            Kind.EVENT -> ToolUse(kind = "event", icon = "calendar.badge.plus", running = "Adding to your calendar", done = "Added to Calendar", detail = detail, action = this)
            Kind.REMEMBER -> ToolUse(kind = "memory", icon = "brain", running = "Remembering", done = "Saved to memory", detail = title, action = this)
            Kind.FORGET -> ToolUse(kind = "memory", icon = "brain", running = "Forgetting", done = "Forgotten", detail = title, action = this)
        }
    }

    /** One line for the model, so its reply confirms what happened instead of telling the user to do it. */
    fun note(ok: Boolean, error: String?): String {
        val what = when (kind) {
            Kind.REMINDER -> "added a reminder “$title”" + (whenText?.let { " for $it" } ?: "") + " to the user's reminders in Loocal"
            Kind.EVENT -> "added “$title”" + (whenText?.let { " on $it" } ?: "") + " to the user's calendar"
            Kind.REMEMBER -> "saved to memory: “$title”"
            Kind.FORGET -> "removed from memory: “$title”"
        }
        return if (ok) "You (Loocal) just $what. It's done. Confirm it in one short, warm sentence. Don't tell them to do it themselves."
        else "You tried but couldn't do this: $what. Reason: ${error ?: "unknown"}. Say so in one sentence."
    }

    companion object {
        private const val DAY = 24L * 3600 * 1000
        private val reminderTriggers = listOf("remind me to ", "remind me ", "set a reminder to ", "set a reminder for ", "add a reminder to ", "add a reminder for ", "add a reminder ", "rappelle-moi de ", "rappelle-moi d'", "rappelle moi de ", "rappelle-moi ", "fais-moi penser à ", "ajoute un rappel pour ", "ajoute un rappel ")
        private val eventTriggers = listOf("add to my calendar ", "put in my calendar ", "create an event ", "schedule a ", "schedule an ", "schedule ", "book a ", "ajoute à mon agenda ", "ajoute dans mon agenda ", "ajoute à mon calendrier ", "crée un événement ", "planifie ")
        private val rememberTriggers = listOf("remember that ", "remember: ", "please remember ", "keep in mind that ", "note that ", "souviens-toi que ", "souviens toi que ", "retiens que ", "n'oublie pas que ", "n’oublie pas que ")
        private val forgetTriggers = listOf("forget that ", "forget about ", "oublie que ", "oublie ")

        /** Reads an action out of a message, if it asks for one. */
        fun detect(message: String): ToolAction? {
            val text = message.trim()
            val lower = text.lowercase()
            if (lower.startsWith("do you remember") || lower.startsWith("tu te souviens")) return null
            (after(rememberTriggers, text, lower) ?: if (lower.startsWith("remember ")) text.drop(9) else null)?.let { rest ->
                val fact = clean(rest)
                return if (fact.length > 2) ToolAction(Kind.REMEMBER, fact) else null
            }
            if (lower.startsWith("forget") || lower.startsWith("oublie")) after(forgetTriggers, text, lower)?.let { rest ->
                val fact = clean(rest)
                return if (fact.length > 2) ToolAction(Kind.FORGET, fact) else null
            }
            after(reminderTriggers, text, lower)?.let { rest ->
                val (title, date, timed) = split(rest)
                return if (title.isEmpty()) null else ToolAction(Kind.REMINDER, title, date, timed)
            }
            // "Add milk to my reminders / to-do list"
            Regex("""(?i)^add (.+?) to my (reminders|to-?do( list)?|list)""").find(text)?.let { m ->
                val (title, date, timed) = split(m.groupValues[1])
                return if (title.isEmpty()) null else ToolAction(Kind.REMINDER, title, date, timed)
            }
            after(eventTriggers, text, lower)?.let { rest ->
                val (title, date, timed) = split(rest)
                date ?: return null // an event needs a when
                return ToolAction(Kind.EVENT, title.ifEmpty { "Event" }, date, timed)
            }
            return null
        }

        private fun after(triggers: List<String>, text: String, lower: String): String? {
            for (t in triggers) { val i = lower.indexOf(t); if (i >= 0) return text.substring(i + t.length) }
            return null
        }

        /** Splits "call mum tomorrow at 9" into ("Call mum", tomorrow 09:00, timed). */
        fun split(text: String): Triple<String, Long?, Boolean> {
            Regex("""(?i)\b(in|dans) (\d{1,3}|an|a|une|un) ?(minutes?|mins?|hours?|hrs?|heures?|h)\b""").find(text)?.let { m ->
                val amount = m.groupValues[2].toDoubleOrNull() ?: 1.0
                val hours = m.groupValues[3].lowercase().startsWith("h")
                val date = System.currentTimeMillis() + (amount * (if (hours) 3600_000 else 60_000)).toLong()
                return Triple(clean(text.removeRange(m.range)), date, true)
            }
            val found = Dates.first(text) ?: return Triple(clean(text), null, false)
            var title = text.removeRange(found.start, found.end)
            // Leftover joiners around the date: "at", "on", "for", "le", "à"…
            title = title.replace(Regex("""(?i)\s+(at|on|by|for|in|à|le|la|pour|avant)\s*$"""), "")
            return Triple(clean(title), found.millis, found.timed)
        }

        private fun clean(text: String): String {
            val strip = { s: String -> s.trim { it.isWhitespace() || it in ".,;:!?\"'«»" } }
            var value = strip(text)
            for (lead in listOf("to ", "that ", "de ", "d'", "d’", "que ", "qu'", "qu’")) if (value.lowercase().startsWith(lead)) value = value.drop(lead.length)
            value = value.replace(Regex("(?i)^my "), "your ")
            value = strip(value)
            return value.replaceFirstChar { it.uppercase() }
        }
    }
}

/** Carries actions out (memory, Loocal reminders, the phone's calendar) and undoes them. */
class ActionRunner(private val context: Context, private val store: ToolStore) {
    /** Does it. Returns the action with what Undo needs, or an error to show. */
    fun perform(action: ToolAction): Pair<ToolAction, String?> = when (action.kind) {
        ToolAction.Kind.REMEMBER -> {
            val memory = Memory(text = action.title)
            store.memories = (listOf(memory) + store.memories).take(Memory.LIMIT)
            action.copy(itemId = memory.id) to null
        }
        ToolAction.Kind.FORGET -> {
            val words = action.title.lowercase().split(" ").filter { it.length > 2 }.toSet()
            val found = store.memories.firstOrNull { m ->
                val have = m.text.lowercase().split(" ").toSet()
                have.containsAll(words) || m.text.contains(action.title, ignoreCase = true)
            }
            if (found == null) action to "Nothing like that is in memory."
            else { store.memories = store.memories - found; action.copy(removed = found, title = found.text) to null }
        }
        ToolAction.Kind.REMINDER -> {
            val reminder = Reminder(title = action.title, due = action.date, timed = action.timed)
            store.reminders = store.reminders + reminder
            if (reminder.due != null && reminder.timed) ReminderAlarm.schedule(context, reminder)
            action.copy(itemId = reminder.id) to null
        }
        ToolAction.Kind.EVENT -> addEvent(action)
    }

    private fun addEvent(action: ToolAction): Pair<ToolAction, String?> {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return action to "Loocal isn't allowed to use Calendar. You can allow it in Settings."
        }
        val date = action.date ?: return action to "No date was given."
        val calendarId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return action to "No calendar on this phone accepts new events."
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, action.title)
            if (action.timed) {
                put(CalendarContract.Events.DTSTART, date)
                put(CalendarContract.Events.DTEND, date + 3600_000)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            } else {
                // All-day events are stored at UTC midnight.
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    val local = Calendar.getInstance().apply { timeInMillis = date }
                    clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
                put(CalendarContract.Events.DTSTART, utc)
                put(CalendarContract.Events.DTEND, utc + 24 * 3600_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
        }
        val uri = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            ?: return action to "The event couldn't be saved."
        return action.copy(itemId = uri.lastPathSegment) to null
    }

    fun undo(action: ToolAction): Boolean = when (action.kind) {
        ToolAction.Kind.REMEMBER -> { store.memories = store.memories.filterNot { it.id == action.itemId }; true }
        ToolAction.Kind.FORGET -> { action.removed?.let { store.memories = listOf(it) + store.memories }; true }
        ToolAction.Kind.REMINDER -> {
            store.reminders.firstOrNull { it.id == action.itemId }?.let { ReminderAlarm.cancel(context, it) }
            val before = store.reminders.size
            store.reminders = store.reminders.filterNot { it.id == action.itemId }
            store.reminders.size < before
        }
        ToolAction.Kind.EVENT -> {
            val id = action.itemId?.toLongOrNull()
            id != null && runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0 }.getOrDefault(false)
        }
    }
}

/** Memory, reminders and MCP servers, persisted in app-private preferences. */
class ToolStore(context: Context) {
    private val prefs = context.getSharedPreferences("tools", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var memories: List<Memory>
        get() = read("memories", Memory.serializer())
        set(value) = write("memories", Memory.serializer(), value.take(Memory.LIMIT))
    var reminders: List<Reminder>
        get() = read("reminders", Reminder.serializer())
        set(value) = write("reminders", Reminder.serializer(), value)
    var servers: List<McpServer>
        get() = read("mcp.servers", McpServer.serializer())
        set(value) = write("mcp.servers", McpServer.serializer(), value)

    private fun <T> read(key: String, s: kotlinx.serialization.KSerializer<T>): List<T> =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(ListSerializer(s), it) }.getOrNull() } ?: emptyList()

    private fun <T> write(key: String, s: kotlinx.serialization.KSerializer<T>, value: List<T>) =
        prefs.edit().putString(key, json.encodeToString(ListSerializer(s), value)).apply()
}

/** Notifies a Loocal reminder at its due time. */
object ReminderAlarm {
    const val CHANNEL = "reminders"

    fun schedule(context: Context, reminder: Reminder) {
        val due = reminder.due ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = intent(context, reminder)
        // Exact alarms need a special permission on recent Android; a window of a minute is fine for a reminder.
        alarms.setWindow(AlarmManager.RTC_WAKEUP, due, 60_000, pending)
    }

    fun cancel(context: Context, reminder: Reminder) = context.getSystemService(AlarmManager::class.java).cancel(intent(context, reminder))

    private fun intent(context: Context, reminder: Reminder) = PendingIntent.getBroadcast(
        context, reminder.id.hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra("id", reminder.id).putExtra("title", reminder.title),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val store = ToolStore(context)
        if (store.reminders.none { it.id == id && !it.done }) return // undone or completed meanwhile
        ReminderAlarm.ensureChannel(context)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ReminderAlarm.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText("Reminder from Loocal")
            .setContentIntent(open).setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }
}
