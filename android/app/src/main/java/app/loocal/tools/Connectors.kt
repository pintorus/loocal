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
        val BUILT_IN = listOf(WEB, CALENDAR, REMINDERS, CLIPBOARD, WIKIPEDIA)

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
        if (Connector.CALENDAR.id in active) calendar(context)?.let(parts::add)
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

    /** Next 7 days from the phone's calendars (Calendar Provider, read-only). */
    fun calendar(context: Context): String? {
        val start = System.currentTimeMillis()
        val end = start + 7L * 24 * 3600 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
        val lines = runCatching {
            context.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < 14) {
                    val allDay = c.getInt(2) == 1
                    val begin = Date(c.getLong(1))
                    val whenText = if (allDay) dayFormat.format(begin) + " (all day)" else dayTimeFormat.format(begin)
                    val location = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
                    out.add("- $whenText: ${c.getString(0) ?: "Untitled"}$location")
                }
                out
            }
        }.getOrNull() ?: return null // no permission
        if (lines.isEmpty()) return "Calendar: nothing scheduled in the next 7 days."
        return ("Calendar (next 7 days):\n" + lines.joinToString("\n")).take(BUDGET)
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

