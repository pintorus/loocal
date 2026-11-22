package app.loocal.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tools that answer for the model where a small model is weak (port of App/Tools.swift): exact
 * maths and dates, places around you, the text of a web page, and real translation. Each is
 * spotted in your words, shows a box in the reply, and hands the model a fact to build on.
 */
object ToolBelt {
    /** Runs every tool the message calls for. [show] adds or updates a box in the reply. */
    suspend fun run(context: Context, message: String, show: (ToolUse) -> Unit): String {
        val notes = mutableListOf<String>()
        Calculator.answer(message)?.let { calc ->
            show(ToolUse(kind = "calc", icon = "function", running = "Calculating", done = "Calculated", detail = calc.shown, status = ToolUse.Status.DONE))
            notes.add("Exact result from the calculator (use it as is, don't redo the maths): ${calc.fact}")
        }
        Translator.detect(message)?.let { request ->
            var box = ToolUse(kind = "translate", icon = "character.bubble", running = "Translating", done = "Translated on this phone", detail = request.targetName)
            show(box)
            Translator.translate(request).fold(
                { text ->
                    box = box.copy(status = ToolUse.Status.DONE, detail = "${request.targetName} · $text")
                    notes.add("On-device translation into ${request.targetName}: “$text”. Give exactly this translation as the answer, then nothing else unless useful.")
                },
                { e ->
                    box = box.copy(status = ToolUse.Status.FAILED, detail = e.message.orEmpty())
                    notes.add("On-device translation wasn't available (${e.message}). Translate it yourself into ${request.targetName}, carefully.")
                },
            )
            show(box)
        }
        Places.detect(message)?.let { place ->
            var box = ToolUse(kind = "places", icon = if (place.isRoute) "car" else "mappin.and.ellipse", running = if (place.isRoute) "Working out the trip" else "Looking around you", done = if (place.isRoute) "Checked the map" else "Searched the map", detail = place.query)
            show(box)
            Places.run(context, place).fold(
                { found ->
                    box = box.copy(status = ToolUse.Status.DONE, links = found.links, detail = found.summary.ifEmpty { box.detail })
                    notes.add(found.fact)
                },
                { e ->
                    box = box.copy(status = ToolUse.Status.FAILED, detail = e.message.orEmpty())
                    notes.add("The map couldn't answer: ${e.message}. Say so briefly.")
                },
            )
            show(box)
        }
        PageReader.links(message).firstOrNull()?.let { url ->
            val host = runCatching { java.net.URL(url).host }.getOrDefault(url)
            var box = ToolUse(kind = "page", icon = "doc.text", running = "Reading the page", done = "Read the page", detail = host)
            show(box)
            val page = PageReader.read(url)
            if (page != null) {
                box = box.copy(status = ToolUse.Status.DONE, detail = page.title.ifEmpty { host }, links = listOf(ToolUse.Link(page.title.ifEmpty { url }, url)))
                notes.add("Text of the page the user linked ($host), ${page.title}:\n${page.text}")
            } else {
                box = box.copy(status = ToolUse.Status.FAILED, detail = host)
                notes.add("The linked page couldn't be read. Say so.")
            }
            show(box)
        }
        return notes.joinToString("\n\n")
    }
}

// MARK: - Calculator and dates

object Calculator {
    data class Answer(val shown: String, val fact: String)

    private val longDay get() = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())

    fun answer(message: String): Answer? {
        val text = message.trim()
        val lower = text.lowercase()
        dateAnswer(text, lower)?.let { return it }
        // "15% of 80", "20 % de 45"
        Regex("""(\d+(?:[.,]\d+)?)\s*%\s*(?:of|de|du|des)\s*(\d+(?:[.,]\d+)?)""").find(lower)?.let { m ->
            val a = number(m.groupValues[1]) ?: return@let; val b = number(m.groupValues[2]) ?: return@let
            val shown = "${m.groupValues[1]}% of ${m.groupValues[2]} = ${format(a / 100 * b)}"
            return Answer(shown, shown)
        }
        // "square root of 2", "racine carrée de 81"
        Regex("""(?:square root of|sqrt|racine carr[ée]e de)\s*\(?(\d+(?:[.,]\d+)?)\)?""").find(lower)?.let { m ->
            val a = number(m.groupValues[1]) ?: return@let
            return Answer("√${m.groupValues[1]} = ${format(sqrt(a))}", "the square root of ${m.groupValues[1]} is ${format(sqrt(a))}")
        }
        // Plain arithmetic somewhere in the message: "what's 12*7 + 3?"
        val normalized = text.replace("×", "*").replace("÷", "/")
            .replace(Regex("""(?<=\d)\s*[xX]\s*(?=\d)"""), "*")
            .replace(Regex("""(?<=\d),(?=\d{1,2}\b)"""), ".")
            .replace(Regex("""(?<=\d)[\s,](?=\d{3}\b)"""), "")
            .replace("plus", "+").replace("minus", "-").replace("times", "*").replace("divided by", "/")
        val range = Regex("""[\d(][\d\s.+\-*/^()]*[\d)]""").find(normalized) ?: return null
        val expression = range.value.trim()
        if (!Regex("""\d\s*[+\-*/^]\s*[\d(]""").containsMatchIn(expression)) return null
        val value = Expression(expression).evaluate() ?: return null
        if (!value.isFinite()) return null
        return Answer("$expression = ${format(value)}", "$expression = ${format(value)}")
    }

    private fun dateAnswer(text: String, lower: String): Answer? {
        val today = Dates.startOfDay(Calendar.getInstance())
        fun daysBetween(a: Long, b: Long) = ((Dates.startOfDay(Calendar.getInstance().apply { timeInMillis = b }).timeInMillis - Dates.startOfDay(Calendar.getInstance().apply { timeInMillis = a }).timeInMillis) / 86_400_000.0).roundToInt()
        val untilWords = listOf("how many days until", "how many days till", "how many days to", "how many days before", "days until", "days till", "how long until", "how long till", "combien de jours avant", "combien de jours jusqu", "dans combien de jours", "combien de jours reste")
        if (untilWords.any { lower.contains(it) }) {
            val target = Dates.first(text)?.millis ?: holiday(lower)
            if (target != null) {
                val days = daysBetween(today.timeInMillis, target)
                val day = longDay.format(Date(target))
                val shown = if (days >= 0) "$days day${if (days == 1) "" else "s"} until $day" else "${-days} days since $day"
                return Answer(shown, "Today is ${longDay.format(Date())}. $shown.")
            }
        }
        if (lower.contains("days between") || lower.contains("jours entre")) {
            val found = Dates.all(text)
            if (found.size >= 2) {
                val days = abs(daysBetween(found[0].millis, found[1].millis))
                val f = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
                return Answer("$days days between them", "There are $days days between ${f.format(Date(found[0].millis))} and ${f.format(Date(found[1].millis))}.")
            }
        }
        // "what's the date in 45 days", "dans 3 semaines on sera quel jour"
        Regex("""(?:in|dans)\s+(\d{1,4})\s+(days?|weeks?|months?|jours?|semaines?|mois)""").find(lower)?.let { m ->
            if (!(lower.contains("date") || lower.contains("what day") || lower.contains("quel jour") || lower.contains("which day"))) return@let
            val amount = m.groupValues[1].toInt(); val unit = m.groupValues[2]
            val c = today.clone() as Calendar
            when {
                unit.startsWith("week") || unit.startsWith("semaine") -> c.add(Calendar.WEEK_OF_YEAR, amount)
                unit.startsWith("month") || unit == "mois" -> c.add(Calendar.MONTH, amount)
                else -> c.add(Calendar.DAY_OF_YEAR, amount)
            }
            val day = longDay.format(c.time)
            return Answer("In $amount $unit: $day", "In $amount $unit from today it will be $day.")
        }
        // "what day is 3 March 2027", "quel jour tombe le 14 juillet"
        if (lower.contains("what day") || lower.contains("which day") || lower.contains("quel jour")) {
            val date = Dates.first(text)?.millis ?: holiday(lower) ?: return null
            val day = longDay.format(Date(date))
            return Answer(day, "That date is $day.")
        }
        return null
    }

    /** Named days a date parser doesn't know. */
    private fun holiday(lower: String): Long? {
        val named = listOf(Triple("christmas", 12, 25), Triple("noël", 12, 25), Triple("noel", 12, 25), Triple("new year", 1, 1), Triple("nouvel an", 1, 1), Triple("halloween", 10, 31), Triple("valentine", 2, 14), Triple("saint-valentin", 2, 14), Triple("14 juillet", 7, 14), Triple("bastille", 7, 14))
        val match = named.firstOrNull { lower.contains(it.first) } ?: return null
        val today = Dates.startOfDay(Calendar.getInstance())
        for (add in 0..1) {
            val c = (today.clone() as Calendar).apply { add(Calendar.YEAR, add); set(Calendar.MONTH, match.second - 1); set(Calendar.DAY_OF_MONTH, match.third) }
            if (!c.before(today)) return c.timeInMillis
        }
        return null
    }

    private fun number(text: String) = text.replace(",", ".").toDoubleOrNull()

    fun format(value: Double): String =
        if (value == Math.rint(value) && abs(value) < 1e15) value.toLong().toString()
        else java.math.BigDecimal(value).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()

    /** A tiny, safe arithmetic parser: + - * / ^, brackets, unary minus. */
    class Expression(text: String) {
        private val chars = text.filterNot { it.isWhitespace() }
        private var i = 0

        fun evaluate(): Double? { i = 0; val v = sum() ?: return null; return if (i == chars.length) v else null }

        private fun sum(): Double? {
            var value = product() ?: return null
            while (i < chars.length && (chars[i] == '+' || chars[i] == '-')) {
                val op = chars[i++]; val rhs = product() ?: return null
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun product(): Double? {
            var value = power() ?: return null
            while (i < chars.length && (chars[i] == '*' || chars[i] == '/')) {
                val op = chars[i++]; val rhs = power() ?: return null
                if (op == '/' && rhs == 0.0) return null
                value = if (op == '*') value * rhs else value / rhs
            }
            return value
        }

        private fun power(): Double? {
            val base = unary() ?: return null
            if (i < chars.length && chars[i] == '^') { i++; val e = power() ?: return null; return base.pow(e) }
            return base
        }

        private fun unary(): Double? {
            if (i < chars.length && chars[i] == '-') { i++; return unary()?.let { -it } }
            if (i < chars.length && chars[i] == '(') {
                i++
                val v = sum() ?: return null
                if (i >= chars.length || chars[i] != ')') return null
                i++
                return v
            }
            val start = i
            while (i < chars.length && (chars[i].isDigit() || chars[i] == '.')) i++
            return chars.substring(start, i).toDoubleOrNull()
        }
    }
}

// MARK: - Places

/**
 * Places around you and trip times. iOS asks Apple Maps; Android has no built-in map search, so
 * this uses OpenStreetMap (Nominatim for places, OSRM for routes). Only the query and your
 * approximate position leave the phone.
 */
object Places {
    data class Request(val query: String, val isRoute: Boolean)
    data class Found(val summary: String, val fact: String, val links: List<ToolUse.Link>)

    private val nearby = listOf("near me", "nearby", "near here", "around me", "around here", "close by", "closest", "nearest", "near", "around", "local", "à proximité", "près de moi", "près d'ici", "autour de moi", "à côté", "le plus proche", "la plus proche", "les plus proches", "dans le coin", "dans le quartier", "proche", "près")
    private val kinds = listOf("restaurant", "café", "cafe", "coffee", "bar", "pub", "pharmacy", "chemist", "drugstore", "supermarket", "grocery", "bakery", "hotel", "gym", "park", "atm", "bank", "gas station", "petrol", "parking", "hospital", "doctor", "dentist", "museum", "cinema", "movie theater", "shop", "store", "mall", "sushi", "pizza", "burger", "brunch", "breakfast", "lunch", "dinner", "food", "ramen", "tacos", "vegan", "vegetarian", "library", "post office", "laundry", "laundromat", "metro", "subway", "train station", "bus stop", "hairdresser", "barber", "florist", "vet", "boulangerie", "pharmacie", "supermarché", "épicerie", "hôtel", "salle de sport", "parc", "banque", "distributeur", "station-service", "hôpital", "médecin", "dentiste", "musée", "cinéma", "magasin", "boutique", "bibliothèque", "poste", "laverie", "gare", "coiffeur", "fleuriste", "vétérinaire", "brasserie", "bistrot", "crêperie", "glacier", "librairie")
    private val asks = listOf("find", "where", "any ", "recommend", "suggest", "best", "good", "show", "look for", "search", "places", "somewhere", "trouve", "cherche", "où", "conseille", "recommande", "meilleur", "bon ", "bonne ", "y a-t-il", "il y a")
    private val filler = setOf("can", "could", "you", "please", "find", "show", "search", "for", "look", "looking", "where", "where's", "is", "are", "there", "any", "some", "a", "an", "the", "me", "us", "my", "i", "want", "need", "to", "eat", "go", "recommend", "suggest", "best", "good", "nice", "near", "nearby", "around", "here", "closest", "nearest", "local", "places", "place", "somewhere", "in", "area", "trouve", "trouver", "cherche", "chercher", "moi", "nous", "un", "une", "des", "le", "la", "les", "de", "du", "où", "est", "sont", "il", "y", "a-t-il", "bon", "bonne", "bons", "bonnes", "meilleur", "meilleurs", "meilleure", "proche", "près", "coin", "quartier", "conseille", "recommande", "manger", "aller", "pour", "qui", "ici", "what", "what's", "which")

    fun detect(message: String): Request? {
        val lower = message.lowercase()
        Regex("""(?:how long (?:does it take |will it take |would it take )?to (?:get|go|drive|walk|bike|cycle) to|how far (?:is|away is)|directions to|route to|itinéraire (?:vers|pour aller à|jusqu'à)|combien de temps pour aller (?:à|au|aux|chez)|à quelle distance (?:est|se trouve))\s+(.+?)[?.!]*$""").find(lower)?.let { m ->
            val destination = m.groupValues[1].trim()
            return if (destination.isEmpty()) null else Request(destination, true)
        }
        val words = lower.split(Regex("[^\\p{L}'’-]+")).toSet()
        val hasNear = nearby.any { p -> if (p.contains(" ")) lower.contains(p) else p in words }
        val kind = kinds.firstOrNull { k -> if (k.contains(" ")) lower.contains(k) else k in words || "${k}s" in words || "${k}es" in words }
        val asking = asks.any { lower.contains(it) }
        if (!((hasNear && (kind != null || asking)) || (kind != null && asking))) return null
        var query = " $lower "
        nearby.filter { it.contains(" ") }.forEach { query = query.replace(it, " ") }
        query = query.replace(Regex("[?!.,;:]"), " ")
        val kept = query.split(" ").filter { it.isNotEmpty() && it !in filler }.joinToString(" ").trim()
        return Request(kept.ifEmpty { kind ?: "restaurants" }, false)
    }

    suspend fun run(context: Context, request: Request): Result<Found> = runCatching {
        val here = Locator.current(context)
        if (request.isRoute) return@runCatching trip(request.query, here)
        // A ~4 km box around you, like the iOS search region.
        val d = 0.02
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&bounded=1&q=" + enc(request.query) +
            "&viewbox=${here.longitude - d},${here.latitude + d},${here.longitude + d},${here.latitude - d}&accept-language=" + Locale.getDefault().language
        val results = JSONArray(withContext(Dispatchers.IO) { Http.get(url, timeout = 8000) })
        val items = (0 until results.length()).map { results.getJSONObject(it) }.map { o ->
            val lat = o.getString("lat").toDouble(); val lon = o.getString("lon").toDouble()
            Triple(o.optString("name").ifEmpty { o.getString("display_name").substringBefore(",") }, distance(here, lat, lon), o)
        }.sortedBy { it.second }.take(5)
        if (items.isEmpty()) return@runCatching Found("Nothing found nearby", "The map found no ${request.query} near the user.", emptyList())
        val rows = items.map { (name, meters, o) ->
            val address = o.getString("display_name").split(",").drop(1).take(2).joinToString(",").trim()
            "$name, ${meters(meters)} away${if (address.isEmpty()) "" else ", $address"}" to ToolUse.Link("$name · ${meters(meters)}", mapLink(name, o.getString("lat"), o.getString("lon")))
        }
        val fact = "Places matching “${request.query}” near the user, nearest first (from OpenStreetMap):\n" + rows.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value.first}" } + "\nAnswer from these; don't invent others."
        Found("${items.size} places · nearest ${meters(items[0].second)}", fact, rows.map { it.second })
    }

    private suspend fun trip(destination: String, here: Location): Found = withContext(Dispatchers.IO) {
        val d = 0.25 // about 50 km around you
        val search = JSONArray(Http.get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + enc(destination) +
            "&viewbox=${here.longitude - d},${here.latitude + d},${here.longitude + d},${here.latitude - d}", timeout = 8000))
        if (search.length() == 0) error("Couldn’t find “$destination” on the map.")
        val place = search.getJSONObject(0)
        val lat = place.getString("lat").toDouble(); val lon = place.getString("lon").toDouble()
        val name = place.optString("name").ifEmpty { destination }
        val lines = listOf("foot" to "walking", "driving" to "by car").mapNotNull { (profile, label) ->
            runCatching {
                val route = JSONObject(Http.get("https://router.project-osrm.org/route/v1/$profile/${here.longitude},${here.latitude};$lon,$lat?overview=false", timeout = 8000))
                    .getJSONArray("routes").getJSONObject(0)
                "$label: ${minutes(route.getDouble("duration"))} (${meters(route.getDouble("distance"))})"
            }.getOrNull()
        }
        val straight = meters(distance(here, lat, lon))
        val fact = "Trip from the user's location to $name (OpenStreetMap): " + (if (lines.isEmpty()) "about $straight as the crow flies; no route times available." else lines.joinToString("; ") + ".")
        Found(lines.firstOrNull()?.let { "$name · $it" } ?: "$name · $straight", fact, listOf(ToolUse.Link("$name on the map", mapLink(name, place.getString("lat"), place.getString("lon")))))
    }

    private fun mapLink(name: String, lat: String, lon: String) = "geo:$lat,$lon?q=$lat,$lon(" + enc(name) + ")"

    private fun distance(here: Location, lat: Double, lon: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, lat, lon, out)
        return out[0].toDouble()
    }

    fun meters(value: Double): String = if (value < 1000) "${(value / 10).roundToInt() * 10} m" else "%.1f km".format(value / 1000)
    fun minutes(seconds: Double): String { val m = (seconds / 60).roundToInt(); return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
}

/** One fix of where you are: a recent known position, or a fresh one within 10 s. */
object Locator {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            error("Loocal isn’t allowed to use your location. You can allow it in Settings.")
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 300_000 }.maxByOrNull { it.time }?.let { return it }
        val provider = providers.firstOrNull() ?: error("Your location isn’t available right now.")
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location -> if (cont.isActive) cont.resumeWith(Result.success(location)) }
            }
        } ?: error("Your location isn’t available right now.")
    }
}

// MARK: - Reading a web page

object PageReader {
    data class Page(val title: String, val text: String)

    fun links(text: String): List<String> = Regex("""https?://[^\s<>"')\]]+""").findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.toList()

    /** The readable text of a page: its article or main part, without scripts, menus and footers. */
    suspend fun read(url: String, limit: Int = 1600): Page? = withContext(Dispatchers.IO) {
        runCatching {
            val html = Http.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"), timeout = 8000)
            extract(html, limit)
        }.getOrNull()
    }

    fun extract(html: String, limit: Int): Page? {
        val title = Regex("""(?i)<title[^>]*>([\s\S]*?)</title>""").find(html)?.groupValues?.get(1)?.let(::plain).orEmpty()
        var body = html
        for (tag in listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form", "iframe", "template", "textarea", "table", "figure", "sup")) {
            body = body.replace(Regex("(?i)<$tag[\\s\\S]*?</$tag>"), " ")
        }
        (Regex("""(?i)<article[\s\S]*?</article>""").find(body) ?: Regex("""(?i)<main[\s\S]*?</main>""").find(body))?.let { body = it.value }
        // Real prose lives in paragraphs: use them when there are enough.
        val paragraphs = Regex("""(?i)<p[^>]*>([\s\S]*?)</p>""").findAll(body).map { plain(it.groupValues[1]) }.filter { it.length > 60 && !it.contains("{{") }.toList()
        if (paragraphs.joinToString("").length > 300) return Page(title, paragraphs.joinToString("\n").take(limit))
        body = body.replace(Regex("""(?i)</(p|div|h[1-6]|li|br)>|<br\s*/?>"""), "\n")
        val text = plain(body).split("\n").map { it.trim() }.filter { it.length > 40 }.joinToString("\n")
        return if (text.length > 80) Page(title, text.take(limit)) else null
    }

    private fun plain(html: String): String {
        var text = html.replace(Regex("<[^>]+>"), "")
        for ((e, v) in listOf("&amp;" to "&", "&quot;" to "\"", "&#x27;" to "’", "&#39;" to "’", "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…")) text = text.replace(e, v)
        return text.replace(Regex("[ \t]+"), " ").replace(Regex("\n\\s*\n+"), "\n").trim()
    }
}

// MARK: - Translation

/** On-device translation (ML Kit; iOS uses Apple Translation). Language packs download once. */
object Translator {
    data class Request(val text: String, val target: String, val targetName: String)

    fun detect(message: String): Request? {
        val text = message.trim()
        val patterns = listOf(
            Regex("""(?i)^(?:please |can you |could you )?translate\s+["“']?(.+?)["”']?\s+(?:to|into|in)\s+([A-Za-zÀ-ÿ\-]+)\s*[.?!]?$"""),
            Regex("""(?i)^how (?:do|would) you say\s+["“']?(.+?)["”']?\s+in\s+([A-Za-zÀ-ÿ\-]+)\s*[?.!]?$"""),
            Regex("""(?i)^(?:peux-tu |tu peux )?tradui[st]\s+["“']?(.+?)["”']?\s+en\s+([A-Za-zÀ-ÿ\-]+)\s*[.?!]?$"""),
            Regex("""(?i)^comment (?:dit-on|on dit|dire)\s+["“']?(.+?)["”']?\s+en\s+([A-Za-zÀ-ÿ\-]+)\s*[?.!]?$"""),
        )
        for (p in patterns) p.find(text)?.let { m -> language(m.groupValues[2])?.let { (code, name) -> return Request(m.groupValues[1], code, name) } }
        // "translate to Spanish: good morning" / "traduis en anglais : bonjour"
        Regex("""(?i)^(?:translate (?:to|into)|tradui[st] en)\s+([A-Za-zÀ-ÿ\-]+)\s*:\s*(.+)$""").find(text)?.let { m ->
            language(m.groupValues[1])?.let { (code, name) -> return Request(m.groupValues[2], code, name) }
        }
        return null
    }

    private val codes = listOf("en", "fr", "es", "de", "it", "pt", "nl", "ja", "ko", "zh", "ru", "ar", "hi", "tr", "pl", "uk", "sv", "da", "no", "fi", "el", "he", "th", "vi", "id", "cs", "ro", "hu")

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")

    /** "Spanish", "espagnol", "español" → es. */
    private fun language(name: String): Pair<String, String>? {
        val wanted = fold(name)
        val namers = listOf(Locale.ENGLISH, Locale.FRENCH, Locale.getDefault())
        for (code in codes) {
            val locale = Locale(code)
            if (namers.any { fold(locale.getDisplayLanguage(it)) == wanted } || fold(locale.getDisplayLanguage(locale)) == wanted) {
                return code to locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    suspend fun translate(request: Request): Result<String> = runCatching {
        val source = LanguageIdentification.getClient().identifyLanguage(request.text).await()
        if (source == "und") error("Couldn’t tell which language the text is in")
        val from = TranslateLanguage.fromLanguageTag(source) ?: error("This language pair isn’t supported")
        val to = TranslateLanguage.fromLanguageTag(request.target) ?: error("This language pair isn’t supported")
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            translator.downloadModelIfNeeded().await()
            translator.translate(request.text).await()
        } finally { translator.close() }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
