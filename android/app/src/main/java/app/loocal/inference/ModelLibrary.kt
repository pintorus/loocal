package app.loocal.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A failure worth showing as is. */
class LibraryError(message: String) : Exception(message)

/**
 * Port of iOS `ModelLibrary`: models shared to Loocal from a Hugging Face page. Android runs
 * llama.cpp, so a shared model is a GGUF repository: one quantization of the weights is picked
 * (Q4_K_M first, like the built-in models), plus its vision projector when there is one, and its
 * architecture is read from the GGUF header and checked against the ones llama.cpp can build.
 */
object ModelLibrary {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(file: File): List<ModelManifest> = runCatching { json.decodeFromString<List<ModelManifest>>(file.readText()) }.getOrDefault(emptyList())
    fun save(models: List<ModelManifest>, file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ModelManifest.serializer()), models))
        tmp.renameTo(file)
    }

    /** Smallest first; equal sizes by name. Also the carousel's left-to-right order. */
    fun ordered(models: List<ModelManifest>) = models.sortedWith(compareBy<ModelManifest> { it.parameters }.thenBy { it.name.lowercase() })

    // Links

    /** Hugging Face pages that aren't a model. */
    private val reserved = setOf("datasets", "spaces", "docs", "blog", "settings", "organizations", "collections", "papers", "posts", "pricing", "learn", "join", "login", "api", "tasks", "enterprise", "chat")

    /** The first link in shared text ("Look at this https://huggingface.co/…"). */
    fun link(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '>')

    /** `owner/name` from a Hugging Face model link: its page, files tab, a file in it, `hf.co/…`, or `huggingface.co/models/…`. */
    fun repository(link: String): String {
        val uri = runCatching { URI(link.trim()) }.getOrNull()
        val host = uri?.host?.lowercase()?.removePrefix("www.") ?: ""
        if (host != "huggingface.co" && host != "hf.co") throw LibraryError("That isn’t a Hugging Face link. Share a model’s page from huggingface.co.")
        var parts = uri!!.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() == "models") parts = parts.drop(1)
        parts.firstOrNull()?.lowercase()?.takeIf { it in reserved }?.let { first ->
            throw LibraryError(when (first) { "datasets" -> "That’s a dataset, not a model."; "spaces" -> "That’s a Space, not a model. Share the model’s own page."; else -> "Share a model’s page from Hugging Face." })
        }
        if (parts.size < 2) throw LibraryError("Share a model’s page from Hugging Face, like huggingface.co/owner/model.")
        return parts[0] + "/" + parts[1]
    }

    // Looking a model up

    data class Sibling(val name: String, val size: Long?, val sha256: String?)

    /** Quantizations in the order they're picked: small enough for a phone, and quick on its CPU. */
    private val quants = listOf("Q4_K_M", "Q4_0", "Q4_K_S", "IQ4_XS", "IQ4_NL", "Q4_K_L", "Q5_K_M", "Q5_K_S", "Q3_K_L", "Q3_K_M", "Q6_K", "Q8_0")

    /** The GGUF weights and vision projector worth fetching, or null when there are no single-file GGUF weights. */
    fun wanted(siblings: List<Sibling>): Pair<Sibling, Sibling?>? {
        val ggufs = siblings.filter { it.name.endsWith(".gguf", ignoreCase = true) && it.size != null && it.sha256 != null && !it.name.contains("/.") }
        val projectors = ggufs.filter { it.name.contains("mmproj", ignoreCase = true) }
        // Weights split over several files ("-00001-of-00003") need them all; Loocal loads one.
        val weights = ggufs.filter { it !in projectors && !Regex("""-\d{5}-of-\d{5}""").containsMatchIn(it.name) }
        fun rank(s: Sibling): Int { val up = s.name.uppercase(); return quants.indexOfFirst { q -> Regex("""(^|[^A-Z0-9])${q}([^A-Z0-9_]|$)""").containsMatchIn(up) }.let { if (it < 0) quants.size else it } }
        val pick = weights.filter { rank(it) < quants.size }.minWithOrNull(compareBy<Sibling> { rank(it) }.thenBy { it.size })
            ?: weights.filter { !it.name.uppercase().let { n -> "F32" in n || "BF16" in n || "F16" in n } }.minByOrNull { it.size!! }
            ?: return null
        val projector = projectors.minWithOrNull(compareBy<Sibling> { s -> listOf("F16", "BF16", "Q8_0").indexOfFirst { s.name.uppercase().contains(it) }.let { if (it < 0) 9 else it } }.thenBy { it.size })
        return pick to projector
    }

    /** Looks the model up on Hugging Face and works out what Loocal would download. */
    fun fetch(repository: String): ModelManifest {
        val (code, body) = get("https://huggingface.co/api/models/$repository?blobs=true")
        when (code) {
            200 -> Unit
            401, 403, 404 -> throw LibraryError("$repository wasn’t found on Hugging Face. It may be private.")
            else -> throw LibraryError("Hugging Face didn’t answer. Try sharing the model again in a moment.")
        }
        val info = json.parseToJsonElement(body).jsonObject
        val id = info.string("id") ?: repository
        val gated = info["gated"]?.let { (it as? JsonPrimitive)?.booleanOrNull != false } ?: false
        if (gated) throw LibraryError("$id asks you to accept its license on Hugging Face, which Loocal can’t do for you. Look for an ungated GGUF copy, such as one from bartowski or unsloth.")
        val revision = info.string("sha") ?: throw LibraryError("Hugging Face didn’t say which version of $id to fetch.")
        val tags = (info["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
        val siblings = (info["siblings"] as? JsonArray).orEmpty().map { it.jsonObject }.map { s ->
            val lfs = s["lfs"] as? JsonObject
            Sibling(s.string("rfilename") ?: "", (lfs?.get("size") as? JsonPrimitive)?.longOrNull ?: (s["size"] as? JsonPrimitive)?.longOrNull, lfs?.string("sha256"))
        }
        val image = info.string("pipeline_tag") in setOf("text-to-image", "image-to-image") || info.string("library_name") == "diffusers" || "diffusers" in tags
        if (image) throw LibraryError("$id makes pictures. Loocal on Android runs chat models only for now.")
        val (weights, projector) = wanted(siblings)
            ?: throw LibraryError(if (siblings.any { it.name.endsWith(".safetensors") }) "$id has no GGUF weights. Loocal on Android runs GGUF models: look for a GGUF copy, such as one from bartowski or unsloth." else "$id has no GGUF weights Loocal can run.")
        val url = "https://huggingface.co/$id/resolve/$revision/${weights.name}"
        val architecture = runCatching { architecture(url) }.getOrNull()
            ?: throw LibraryError("Loocal couldn’t tell what kind of model $id is.")
        if (architecture !in ARCHITECTURES) throw LibraryError("Loocal can’t run $architecture models yet.")
        val owner = id.substringBefore('/')
        val files = listOfNotNull(weights, projector).map { ModelFile(it.name, it.size!!, it.sha256!!) }
        return ModelManifest(
            id = "hf." + id.lowercase(),
            name = shortName(id),
            detail = owner + " · " + if (projector != null) "words and vision" else "words only",
            repository = id, revision = revision, files = files,
            parameters = parameters(id) ?: (weights.size!! * 8.0 / 4.8 / 1e9),
            custom = true,
        )
    }

    /** `general.architecture` from the start of a GGUF file, read with a range request. */
    fun architecture(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Range", "bytes=0-${HEADER - 1}")
        }
        val bytes = connection.inputStream.use { it.readNBytesCompat(HEADER) }
        return ggufArchitecture(bytes)
    }

    /** Reads metadata keys from a GGUF header until `general.architecture`. */
    fun ggufArchitecture(bytes: ByteArray): String? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != 0x46554747) return null // "GGUF"
        val version = b.int
        if (version < 2) return null
        b.long // tensors
        val count = b.long
        fun str(): String { val n = b.long.toInt(); val a = ByteArray(n); b.get(a); return String(a) }
        fun skip(type: Int) {
            when (type) {
                0, 1, 7 -> b.position(b.position() + 1)
                2, 3 -> b.position(b.position() + 2)
                4, 5, 6 -> b.position(b.position() + 4)
                10, 11, 12 -> b.position(b.position() + 8)
                8 -> str()
                9 -> { val t = b.int; val n = b.long; repeat(n.toInt()) { skip(t) } }
                else -> error("type $type")
            }
        }
        repeat(count.toInt()) {
            val key = str()
            val type = b.int
            if (key == "general.architecture" && type == 8) return str()
            skip(type)
        }
        null
    }.getOrNull()

    /** A name short enough for the model carousel: "Llama-3.2-3B-Instruct-GGUF" → "Llama 3.2 3B". */
    fun shortName(repository: String): String {
        val base = repository.substringAfterLast('/')
        val noise = setOf("mlx", "instruct", "it", "chat", "hf", "bf16", "fp16", "fp32", "f16", "dwq", "qat", "abliterated", "uncensored", "gguf", "awq", "gptq", "mixed", "optimized", "q4", "q8", "q6", "q3")
        val words = base.split('-', '_', ' ').filter { it.isNotEmpty() }.filter { w ->
            val lower = w.lowercase()
            lower !in noise && !Regex("""\d+(\.\d+)?bits?""").matches(lower) && !Regex("""\d{4}""").matches(lower)
        }
        var name = (words.ifEmpty { listOf(base) }).joinToString(" ")
        name = Regex("""(\d)b\b""").replace(name) { it.groupValues[1] + "B" }
        name = name.replaceFirstChar { it.uppercase() }
        return if (name.length > 22) name.take(21) + "…" else name
    }

    /** Billions of parameters, from the name: "3B", "0.5B", "270M". "A3B" (active experts) and "E4B" don't count. */
    fun parameters(repository: String): Double? {
        val name = repository.substringAfterLast('/')
        fun first(unit: String) = Regex("""(?<![A-Za-z0-9.])(\d+(?:\.\d+)?)[$unit](?![A-Za-z])""").find(name)?.groupValues?.get(1)?.toDoubleOrNull()
        return first("Bb") ?: first("Mm")?.let { it / 1000 }
    }

    /** Whether a model's files likely need more memory than this phone has. */
    fun mayNotFit(model: ModelManifest, totalMemory: Long): Boolean =
        totalMemory > 0 && model.totalBytes + 700L * 1024 * 1024 > totalMemory * 6 / 10

    private const val HEADER = 4 * 1024 * 1024

    private fun get(url: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000 }
        val code = connection.responseCode
        return code to if (code == 200) connection.inputStream.bufferedReader().use { it.readText() } else ""
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (out.size() < n) { val r = read(buf, 0, minOf(buf.size, n - out.size())); if (r < 0) break; out.write(buf, 0, r) }
        return out.toByteArray()
    }

    /** Architectures llama.cpp builds (from llama-arch.cpp), less encoders that can't hold a chat. */
    val ARCHITECTURES = setOf(
        "llama", "llama4", "deci", "falcon", "grok", "gpt2", "gptj", "gptneox", "mpt", "baichuan", "starcoder", "refact", "bloom",
        "stablelm", "qwen", "qwen2", "qwen2moe", "qwen2vl", "sensenova_u1", "qwen3", "flint", "qwen3moe", "clover_moe",
        "qwen3next", "qwen3vl", "qwen3vlmoe", "qwen35", "qwen35moe", "clef", "vllm-decision2", "qwen4exp", "phi2", "phi3",
        "phimoe", "plamo", "plamo2", "plamo3", "codeshell", "orion", "internlm2", "minicpm", "minicpm3", "gemma", "gemma2",
        "gemma3", "gemma3n", "gemma4", "starcoder2", "mamba", "mamba2", "maple", "jamba", "falcon-h1", "xverse", "command-r",
        "cohere2", "cohere2moe", "dbrx", "olmo", "olmo2", "olmoe", "muse-glimmer", "muse-spark", "openelm", "arctic", "deepseek",
        "deepseek2", "deepseek2-ocr", "deepseek32", "deepseek4", "deepseek41", "xing4_0", "chatglm", "glm4", "glm4moe", "glm-dsa",
        "bitnet", "t5", "jais", "jais2", "nemotron", "nemotron_h", "nemotron_h_moe", "metis16", "exaone", "exaone4", "exaone-moe",
        "rwkv6", "rwkv6qwen2", "rwkv7", "arwkv7", "granite", "granitemoe", "granitehybrid", "graniteswitch", "granite_swa",
        "chameleon", "plm", "bailingmoe", "bailingmoe2", "bailingmoe3", "dots1", "dots3note", "arcee", "afmoe", "laguna",
        "ernie4_5", "ernie4_5-moe", "hunyuan-moe", "hunyuan-dense", "hunyuan_vl", "hy_v3", "hy_v4", "smollm3", "gpt-oss", "lfm2",
        "lfm2moe", "lfm3", "zaya1", "dream", "smallthinker", "llada", "llada-moe", "seed_oss", "grovemoe", "apertus",
        "minimax-01", "hrm_text", "limite", "minimax-m2", "minimax-m3", "cogvlm", "rnd1", "pangu-embedded", "mistral3", "eagle3",
        "dflash", "mistral4", "mistral-large4", "paddleocr", "mimo2", "step35", "spark2_5", "llama-embed", "maincoder",
        "maion-coder", "kimi-linear", "kimi-k3", "kimi-k31", "glm5-next", "talkie", "mellum", "nanbeige", "qwen3tts", "pockettts",
        "moshi", "zonos3", "kokoro", "tiny-voca", "k2-horizon", "beam", "kolibri1", "ricursive", "inkling2", "inkling2_loop",
        "domynedge", "(unknown)",
    )
}
