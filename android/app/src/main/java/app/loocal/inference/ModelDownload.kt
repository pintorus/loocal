package app.loocal.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Pinned model definition: like App/ModelManifest*.json on iOS, every file is fixed by revision and SHA-256. */
@Serializable
data class ModelManifest(
    val id: String,
    val name: String,
    val detail: String = "",
    val repository: String,
    val revision: String,
    val files: List<ModelFile>,
) {
    val isPinned get() = revision.length == 40 && files.isNotEmpty() && files.all { it.sha256.length == 64 }
    val totalBytes get() = files.sumOf { it.size }
    /** The language model weights. */
    val weights get() = files.first { !it.isVision }
    /** The vision projector, for models that read photos. */
    val projector get() = files.firstOrNull { it.isVision }
    val vision get() = projector != null
    fun url(file: ModelFile) = "https://huggingface.co/$repository/resolve/$revision/${file.path}"
}

@Serializable
data class ModelFile(val path: String, val size: Long, val sha256: String) {
    val isVision get() = path.contains("mmproj")
}

/**
 * Downloads a manifest's files into [directory]/<model id>. Files over 128 MB are fetched as up
 * to six byte ranges in parallel, stitched in order and SHA-256 verified; finished pieces survive
 * retries and relaunches. A file is only kept once its hash matches. Unpinned manifests are refused.
 */
class ModelDownloader(private val directory: File) {
    fun folder(manifest: ModelManifest) = File(directory, manifest.id)
    fun file(manifest: ModelManifest, file: ModelFile) = File(folder(manifest), file.path)
    private fun marker(manifest: ModelManifest, file: ModelFile) = File(file(manifest, file).path + ".ok")

    fun isReady(manifest: ModelManifest) = manifest.files.all { file(manifest, it).length() == it.size && marker(manifest, it).exists() }

    /** Removes model folders that no manifest uses any more (for example an older model choice). */
    fun sweep(keep: Collection<ModelManifest>) {
        val ids = keep.map { it.id }.toSet()
        directory.listFiles()?.filter { it.isDirectory && it.name !in ids }?.forEach { it.deleteRecursively() }
    }

    fun delete(manifest: ModelManifest) { folder(manifest).deleteRecursively() }

    suspend fun download(manifest: ModelManifest, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        require(manifest.isPinned) { "This model is not pinned to a revision and hashes yet." }
        val total = manifest.totalBytes.toFloat()
        val done = AtomicLong(manifest.files.filter { marker(manifest, it).exists() && file(manifest, it).length() == it.size }.sumOf { it.size })
        for (entry in manifest.files) {
            val target = file(manifest, entry)
            if (marker(manifest, entry).exists() && target.length() == entry.size) continue
            target.parentFile?.mkdirs()
            marker(manifest, entry).delete()
            val before = done.get()
            val received = AtomicLong(0)
            // Pieces report new bytes as they arrive, possibly from several connections at once.
            val report: (Long) -> Unit = { more -> onProgress((before + received.addAndGet(more)) / total) }
            if (entry.size > PARALLEL_THRESHOLD) fetchInRanges(manifest.url(entry), target, entry.size, report)
            else fetch(manifest.url(entry), target, 0, entry.size, report)
            if (sha256(target) != entry.sha256) {
                target.delete()
                error("${entry.path} failed verification and was deleted. Try again.")
            }
            marker(manifest, entry).createNewFile()
            done.addAndGet(entry.size)
            onProgress(done.get() / total)
        }
    }

    /** Up to six ranges at once into numbered part files, then joined in order. */
    private suspend fun fetchInRanges(url: String, target: File, size: Long, progress: (Long) -> Unit) = coroutineScope {
        val parts = CONNECTIONS
        val span = (size + parts - 1) / parts
        val pieces = (0 until parts).map { k ->
            val start = k * span
            val end = minOf(size, start + span)
            File(target.path + ".part$k") to (start until end)
        }
        progress(pieces.sumOf { (file, _) -> file.length() }) // pieces kept from an earlier attempt
        pieces.map { (file, range) ->
            async {
                val length = range.last - range.first + 1
                if (file.length() < length) {
                    fetch(url, file, range.first, length, progress)
                }
            }
        }.awaitAll()
        FileOutputStream(target).use { out -> pieces.forEach { (file, _) -> file.inputStream().use { it.copyTo(out, 1 shl 20) } } }
        pieces.forEach { (file, _) -> file.delete() }
    }

    /**
     * Fetches [length] bytes from [offset] into [target], resuming after whatever [target] already
     * holds. [progress] receives the number of new bytes written since the last call.
     */
    private suspend fun fetch(url: String, target: File, offset: Long, length: Long, progress: (Long) -> Unit) {
        val existing = target.length().coerceIn(0, length)
        if (existing == length) return
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Range", "bytes=${offset + existing}-${offset + length - 1}")
        }
        val code = connection.responseCode
        check(code == HttpURLConnection.HTTP_PARTIAL || (code == HttpURLConnection.HTTP_OK && offset + existing == 0L)) { "Download failed (HTTP $code)." }
        connection.inputStream.use { input ->
            FileOutputStream(target, existing > 0).use { output ->
                val buffer = ByteArray(1 shl 16)
                var pending = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    pending += n
                    if (pending >= 1 shl 20) { progress(pending); pending = 0 }
                }
                if (pending > 0) progress(pending)
            }
        }
    }

    companion object {
        private const val PARALLEL_THRESHOLD = 128L * 1024 * 1024
        private const val CONNECTIONS = 6

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
