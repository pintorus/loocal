package app.loocal.tools

import android.content.ClipboardManager
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A source of context the model can read (port of App/Connectors.swift). Built-ins are fetched
 * on device right before a reply and added to the system prompt; MCP servers expose tools.
 * [icon] keeps the iOS SF Symbol name; the UI maps it to a Material icon.
 */
data class Connector(val id: String, val name: String, val icon: String, val detail: String) {
    /** The box shown in a reply when this source is read. */
    fun use(): ToolUse = when (id) {
        CALENDAR.id -> ToolUse(kind = id, icon = icon, running = "Reading your calendar", done = "Read your calendar", detail = "Next 7 days")
        REMINDERS.id -> ToolUse(kind = id, icon = icon, running = "Reading your reminders", done = "Read your reminders", detail = "Open reminders")
        CLIPBOARD.id -> ToolUse(kind = id, icon = icon, running = "Reading the clipboard", done = "Read the clipboard")
        WIKIPEDIA.id -> ToolUse(kind = id, icon = "globe", running = "Looking it up on Wikipedia", done = "Looked it up on Wikipedia")
        else -> ToolUse(kind = id, icon = icon, running = "Using $name", done = "Used $name")
    }

    companion object {
        val CALENDAR = Connector("calendar", "Calendar", "calendar", "Your next 7 days")
        val REMINDERS = Connector("reminders", "Reminders", "checklist", "Open reminders")
        val CLIPBOARD = Connector("clipboard", "Clipboard", "doc.on.clipboard", "What you last copied")
        val WIKIPEDIA = Connector("wikipedia", "Wikipedia", "globe", "Looks your question up online")
        val WEB = Connector("web", "Web search", "magnifyingglass", "Lets the model search the web")
        val MAPS = Connector("maps", "Maps", "map", "Searches the map around you")
        val BUILT_IN = listOf(WEB, MAPS, CALENDAR, REMINDERS, CLIPBOARD, WIKIPEDIA)

        fun mcp(server: McpServer) = Connector(server.connectorId, server.name, "server.rack", "${server.tools.size} tools · ${URL(server.url).host}")
    }
}

/** One step a reply took before answering (iOS `ToolUse`), shown as a box above the reply. */
@Serializable
data class ToolUse(
    val id: String = java.util.UUID.randomUUID().toString(),
    val kind: String,
    val icon: String,
    val running: String,
    val done: String,
    val detail: String = "",
    val status: Status = Status.RUNNING,
    val links: List<Link> = emptyList(),
    val action: ToolAction? = null,
) {
    @Serializable enum class Status { RUNNING, DONE, FAILED, UNDONE }
    @Serializable data class Link(val title: String, val url: String) {
        val host get() = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault(url)
    }
    val title get() = when (status) { Status.RUNNING -> running; Status.UNDONE -> "Undone"; else -> done }
}

/** Built-in context, read right before a reply. Each source's share of the prompt is capped. */
object ConnectorHub {
    private const val BUDGET = 700

    suspend fun context(context: Context, active: Set<String>, prompt: String, reminders: List<Reminder>): String = withContext(Dispatchers.IO) {
        val parts = mutableListOf<String>()
        if (Connector.CALENDAR.id in active) calendar(context, SourceIntent.calendarWindow(prompt))?.let(parts::add)
        if (Connector.REMINDERS.id in active) parts.add(remindersText(reminders))
        if (Connector.CLIPBOARD.id in active) {
            val text = withContext(Dispatchers.Main) {
                context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }
            if (!text.isNullOrEmpty()) parts.add("Clipboard:\n" + text.take(BUDGET))
        }
        if (Connector.WIKIPEDIA.id in active) Wikipedia.summary(prompt)?.let(parts::add)
        if (parts.isEmpty()) "" else "Context from the user's connected sources (use it when relevant):\n\n" + parts.joinToString("\n\n")
    }

    /** Whether Loocal may read this source now. */
    fun isAllowed(context: Context, id: String) = when (id) {
        Connector.CALENDAR.id -> context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true // reminders and the clipboard need no permission on Android
    }

    /**
     * Sources a request asks for in so many words. Allowed ones come back to be read; for any that
     * aren't, a note tells the model so it says that rather than guessing. (The screen asks for the
     * permission before sending, so nothing is asked here.)
     */
    fun resolve(context: Context, wanted: Set<String>): Pair<Set<String>, String> {
        val allowed = wanted.filter { isAllowed(context, it) }.toSet()
        val refused = (wanted - allowed).map { id -> Connector.BUILT_IN.firstOrNull { it.id == id }?.name ?: id }
        if (refused.isEmpty()) return allowed to ""
        return allowed to "Loocal isn't allowed to read the user's ${refused.joinToString(" and ")}, so you can't see it. Say so plainly, and that they can allow it in Android Settings › Apps › Loocal › Permissions. Never guess what's in it."
    }

    /** The phone's calendars over [window] (Calendar Provider, read-only): the user's real events, all of them. */
    fun calendar(context: Context, window: SourceIntent.Window): String? {
        if (!isAllowed(context, Connector.CALENDAR.id)) return null
        val long = window.end - window.start > 8 * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(window.start.toString()).appendPath(window.end.toString()).build()
        val all = runCatching {
            context.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) {
                    val begin = Date(c.getLong(1))
                    val day = (if (long) java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()) else dayFormat).format(begin)
                    val whenText = if (c.getInt(2) == 1) "$day (all day)" else day + " " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(begin)
                    val location = if (long) "" else c.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
                    out.add("- $whenText: ${c.getString(0) ?: "Untitled"}$location")
                }
                out
            }
        }.getOrNull() ?: return null
        val events = all.take(if (long) 40 else 14)
        val span = dayFormat.format(Date(window.start)) + " – " + dayFormat.format(Date(window.end - 1))
        if (events.isEmpty()) return "Calendar (${window.label}, $span): nothing scheduled. Say the calendar is clear then."
        val more = if (all.size > events.size) "\n(and ${all.size - events.size} more)" else ""
        return ("Calendar (${window.label}, $span), the user's real events, all of them:\n" + events.joinToString("\n") + more).take(if (long) BUDGET * 2 else BUDGET)
    }

    private fun remindersText(list: List<Reminder>): String {
        val open = list.filter { !it.done }.take(14)
        if (open.isEmpty()) return "Reminders: none open."
        return ("Open reminders:\n" + open.joinToString("\n") { r -> "- ${r.title}" + (r.due?.let { " (due ${dayFormat.format(Date(it))})" } ?: "") }).take(BUDGET)
    }

    private val dayFormat get() = java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val dayTimeFormat get() = java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
}

/** Wikipedia search + summary in the device language. Sends only the question's key terms. */
object Wikipedia {
    private val stopwords = setOf("a", "an", "the", "is", "are", "was", "were", "be", "of", "in", "on", "at", "to", "for", "and", "or", "what", "who", "whom", "which", "when", "where", "why", "how", "tall", "big", "old", "much", "many", "does", "do", "did", "can", "could", "tell", "me", "about", "i", "you", "it", "its", "le", "la", "les", "un", "une", "des", "de", "du", "est", "qui", "que", "quoi", "quel", "quelle", "comment", "pourquoi", "où", "et", "en")

    suspend fun summary(question: String): String? = withContext(Dispatchers.IO) {
        val language = Locale.getDefault().language.ifEmpty { "en" }
        val terms = keywords(question)
        if (terms.isEmpty()) return@withContext null
        runCatching {
            val search = Http.get("https://$language.wikipedia.org/w/api.php?action=query&list=search&srlimit=5&format=json&srsearch=" + enc(terms.joinToString(" ")), timeout = 6000)
            val hits = JSONObject(search).getJSONObject("query").getJSONArray("search").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("title") } }
            // Prefer the result whose title shares the most words with the question.
            val title = hits.withIndex().maxByOrNull { (i, t) -> overlap(t, terms) - i * 0.01 }?.value ?: return@runCatching null
            val summary = JSONObject(Http.get("https://$language.wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(' ', '_')).replace("+", "%20"), timeout = 6000))
            "Wikipedia — $title:\n" + summary.getString("extract").take(700)
        }.getOrNull()
    }

    /** Search terms: proper nouns if the question has any, otherwise its content words. */
    fun keywords(question: String): List<String> {
        val words = question.split(Regex("[^\\p{L}\\p{N}'-]+")).filter { it.isNotEmpty() }
        val proper = words.drop(1).filter { it.first().isUpperCase() }
        if (proper.isNotEmpty()) return proper
        return words.filter { it.lowercase() !in stopwords }
    }

    private fun overlap(title: String, terms: List<String>): Double {
        val words = title.lowercase().split(" ").toSet()
        return terms.count { it.lowercase() in words }.toDouble()
    }
}

/**
 * Web search for the on-device model, as a tool it can call. Only the query leaves the phone:
 * it goes to DuckDuckGo, with no account and no cookies. Falls back to Wikipedia.
 */
object WebSearch {
    data class Hit(val title: String, val url: String, val snippet: String)

    val definition = ToolDefinition(
        url = "loocal://web-search", token = null,
        tool = McpTool("web_search", "Search the web. Use it for news, recent events, prices, opening hours, places, people, or anything you are not sure about.",
            """{"type":"object","properties":{"query":{"type":"string","description":"A short search query"}},"required":["query"]}"""),
    )

    fun isLocal(definition: ToolDefinition) = definition.url.startsWith("loocal:")

    private val triggerWords = setOf(
        "latest", "news", "today", "today's", "tonight", "current", "currently", "recent", "recently", "update", "updates",
        "price", "prices", "cost", "weather", "forecast", "score", "scores", "winner", "won", "release", "released", "launch",
        "launched", "upcoming", "search", "google", "trending", "stock", "stocks", "election", "headlines", "2025", "2026", "2027",
        "dernier", "dernière", "derniers", "dernières", "actu", "actualité", "actualités", "aujourd'hui", "aujourd’hui", "récent",
        "récente", "récemment", "prix", "météo", "sortie", "cherche", "recherche", "tendance",
    )
    private val triggerPhrases = listOf("right now", "look up", "this week", "this year", "this month", "what's new", "what’s new", "en ce moment", "cette semaine", "cette année", "quoi de neuf", "open now", "near me")

    /** Small models often answer from memory: when a message clearly asks about something current, search first. */
    fun wantsSearch(text: String): Boolean {
        val lower = text.lowercase()
        val words = lower.split(Regex("[^\\p{L}\\p{N}'’]+")).filter { it.isNotEmpty() }.toSet()
        return words.any { it in triggerWords } || triggerPhrases.any { lower.contains(it) }
    }

    /** Words that ask for a search outright: these run one even with web search off. */
    fun explicitlyAsked(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("search", "look up", "look it up", "google", "on the web", "online", "cherche", "recherche", "sur internet", "sur le web").any { lower.contains(it) }
    }

    /** The message as a search query: its first line, without the polite padding. */
    fun searchQuery(text: String): String {
        var query = text.lineSequence().firstOrNull() ?: text
        for (filler in listOf("can you ", "could you ", "please ", "tell me ", "search the web for ", "search online for ", "search for ", "search ", "look up ", "google ", "cherche sur internet ", "cherche ")) {
            if (query.lowercase().startsWith(filler)) query = query.drop(filler.length)
        }
        return query.trim { it.isWhitespace() || it in ".,;:!?\"'«»" }.take(120)
    }

    fun arguments(query: String) = JSONObject().put("query", query).toString()

    const val GUIDANCE = "You can search the web with web_search. Use it for anything that may have changed recently: news, the latest versions, prices, weather, results, opening hours. Do not guess current facts."

    fun query(argumentsJson: String): String = runCatching {
        val o = JSONObject(argumentsJson)
        (o.optString("query").ifEmpty { o.optString("q") }).trim()
    }.getOrDefault("")

    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        if (query.isEmpty()) return@withContext emptyList()
        val hits = runCatching { duckDuckGo(query) }.getOrDefault(emptyList())
        if (hits.isNotEmpty()) return@withContext hits
        // Nothing back (offline page, a challenge page): try Wikipedia instead.
        val text = Wikipedia.summary(query) ?: return@withContext emptyList()
        val title = text.lineSequence().first()
        val name = title.removePrefix("Wikipedia — ").removeSuffix(":")
        val language = Locale.getDefault().language.ifEmpty { "en" }
        listOf(Hit(name, "https://$language.wikipedia.org/wiki/" + enc(name.replace(' ', '_')), text.drop(title.length + 1)))
    }

    /** What the model reads back: numbered results, trimmed to fit the small context. */
    fun report(hits: List<Hit>, query: String): String {
        if (hits.isEmpty()) return "No web results for “$query”."
        return "Web results for “$query”:\n" + hits.take(5).withIndex().joinToString("\n") { (i, h) ->
            "${i + 1}. ${h.title} (${runCatching { URL(h.url).host }.getOrDefault("")}): ${h.snippet.take(220)}"
        } + "\nAnswer from these results and name the sources you used."
    }

    private fun duckDuckGo(query: String): List<Hit> {
        val region = Locale.getDefault().let { if (it.country.isNotEmpty()) "${it.country.lowercase()}-${it.language}" else "wt-wt" }
        val body = "q=" + enc(query) + "&kl=" + enc(region)
        val html = Http.post(
            "https://html.duckduckgo.com/html/", body, "application/x-www-form-urlencoded",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"), timeout = 8000,
        )
        return parse(html)
    }

    fun parse(html: String): List<Hit> = html.split("class=\"result results_links").drop(1).mapNotNull { block ->
        if (block.take(120).contains("result--ad")) return@mapNotNull null
        val link = Regex("""class="result__a"[^>]*href="([^"]*)"[^>]*>([\s\S]*?)</a>""").find(block) ?: return@mapNotNull null
        val url = target(link.groupValues[1]) ?: return@mapNotNull null
        val snippet = Regex("""class="result__snippet"[^>]*>([\s\S]*?)</a>""").find(block)?.groupValues?.get(1).orEmpty()
        Hit(plain(link.groupValues[2]), url, plain(snippet))
    }.take(6)

    /** Result links are either direct or DuckDuckGo redirects carrying the target in `uddg`. */
    private fun target(href: String): String? {
        val clean = href.replace("&amp;", "&").let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { android.net.Uri.parse(clean) }.getOrNull() ?: return null
        uri.getQueryParameter("uddg")?.let { return it }
        if (uri.host?.contains("duckduckgo.com") != false) return null
        return clean
    }

    fun plain(html: String): String {
        var text = html.replace(Regex("<[^>]+>"), "")
        for ((entity, value) in listOf("&amp;" to "&", "&quot;" to "\"", "&#x27;" to "’", "&#39;" to "’", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")) text = text.replace(entity, value)
        return text.replace(Regex("\\s+"), " ").trim()
    }
}

/** Minimal HTTP helpers (no extra library). */
object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 8000): String = request(url, "GET", null, null, headers, timeout).second

    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap(), timeout: Int = 8000): String =
        request(url, "POST", body, contentType, headers, timeout).second

    /** Returns (connection, body). Throws on non-2xx. */
    fun request(url: String, method: String, body: String?, contentType: String?, headers: Map<String, String>, timeout: Int): Pair<HttpURLConnection, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout; readTimeout = timeout
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", headers["User-Agent"] ?: "Loocal/1.0 (Android app)")
            headers.filterKeys { it != "User-Agent" }.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw HttpError(code, text)
        return c to text
    }

    class HttpError(val code: Int, body: String) : Exception("HTTP $code")
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")



/**
 * Which of the user's own sources a request is about, from its words alone (iOS `SourceIntent`),
 * so "check my calendar" or "what have I got next month" reads the calendar without a Calendar island.
 */
object SourceIntent {
    private val calendarWords = listOf(
        "calendar", "agenda", "schedule", "meeting", "appointment", "event", "going on", "am i free", "am i busy", "i'm free", "im free",
        "what do i have", "what have i got", "what i have", "what's on", "whats on", "my plans", "plans for", "my day", "my week", "my weekend", "my month",
        "calendrier", "rendez-vous", "rdv", "reunion", "evenement", "ce que j'ai", "qu'est-ce que j'ai", "j'ai quoi", "je suis libre", "suis-je libre",
        "suis libre", "mon planning", "planning", "ma journee", "ma semaine", "mon week-end", "mon mois", "mes plans", "prevu",
    )
    private val reminderWords = listOf("reminders", "my reminder", "to-do", "todo", "to do list", "my tasks", "what's due", "whats due", "need to do", "rappels", "mes rappels", "taches", "a faire", "dois faire")
    private val clipboardWords = listOf("clipboard", "i copied", "i've copied", "presse-papier", "j'ai copie")

    fun folded(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").replace("’", "'")

    fun wanted(text: String): Set<String> {
        val t = folded(text)
        return buildSet {
            if (calendarWords.any { t.contains(it) }) add(Connector.CALENDAR.id)
            if (reminderWords.any { t.contains(it) }) add(Connector.REMINDERS.id)
            if (clipboardWords.any { t.contains(it) }) add(Connector.CLIPBOARD.id)
        }
    }

    data class Window(val start: Long, val end: Long, val label: String)

    /** The stretch of calendar the words are about; the next 7 days when they don't say. */
    fun calendarWindow(text: String, now: Calendar = Calendar.getInstance()): Window {
        val t = folded(text)
        fun has(words: List<String>) = words.any { t.contains(it) }
        val nowMs = now.timeInMillis
        val today = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        fun day(offset: Int) = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }.timeInMillis
        Regex("""(?:next|coming|prochains?)\s+(\d{1,2})\s+(?:days|jours)|(\d{1,2})\s+(?:prochains|next)\s+(?:jours|days)""").find(t)?.let { m ->
            val days = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt().coerceIn(1, 60)
            return Window(nowMs, day(days + 1), "next $days days")
        }
        fun monthBounds(add: Int): Pair<Long, Long> {
            val c = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, add) }
            val start = c.timeInMillis; c.add(Calendar.MONTH, 1)
            return start to c.timeInMillis
        }
        fun weekBounds(add: Int): Pair<Long, Long> {
            val c = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek); if (timeInMillis > today.timeInMillis) add(Calendar.WEEK_OF_YEAR, -1); add(Calendar.WEEK_OF_YEAR, add) }
            val start = c.timeInMillis; c.add(Calendar.WEEK_OF_YEAR, 1)
            return start to c.timeInMillis
        }
        if (has(listOf("next month", "mois prochain", "coming month"))) monthBounds(1).let { return Window(it.first, it.second, "next month") }
        if (has(listOf("this month", "ce mois", "my month", "mon mois", "rest of the month"))) return Window(nowMs, monthBounds(0).second, "the rest of this month")
        if (has(listOf("next week", "semaine prochaine"))) weekBounds(1).let { return Window(it.first, it.second, "next week") }
        if (has(listOf("weekend", "week-end", "week end"))) {
            val weekday = now.get(Calendar.DAY_OF_WEEK) // 1 Sunday … 7 Saturday
            if (weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY) return Window(nowMs, day(if (weekday == Calendar.SATURDAY) 2 else 1), "this weekend")
            return Window(day(7 - weekday), day(9 - weekday), "the weekend")
        }
        if (has(listOf("week ahead", "coming week", "7 days", "sept jours"))) return Window(nowMs, day(7), "next 7 days")
        if (has(listOf("this week", "cette semaine", "my week", "ma semaine", "rest of the week"))) return Window(nowMs, weekBounds(0).second, "the rest of this week")
        if (has(listOf("tomorrow", "demain"))) return Window(day(1), day(2), "tomorrow")
        if (has(listOf("today", "tonight", "aujourd'hui", "ce soir", "my day", "ma journee"))) return Window(nowMs, day(1), "today")
        return Window(nowMs, day(7), "next 7 days")
    }
}
