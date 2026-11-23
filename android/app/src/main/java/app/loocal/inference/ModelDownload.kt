package app.loocal.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
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
    /** Billions of parameters, for ordering small → large. */
    val parameters: Double = 0.0,
    /** Shared from Hugging Face, so it can be removed again. */
    val custom: Boolean = false,
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
 * Downloads a manifest's files into [directory]/<model id>. Files over 128 MB arrive as 128 MB
 * pieces, up to six at once, each written straight into its place in the file and recorded by an
 * empty marker, so nothing is stitched and finished pieces survive retries and relaunches. A file
 * is only kept once its SHA-256 matches; one that reached its last byte but was never verified is
 * checked without fetching anything (at launch through [finishVerified], or on Get).
 * Unpinned manifests are refused.
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

    /** Verifies files that were fully fetched but never checked (the app was closed during the check). Returns the models now ready. */
    fun finishVerified(manifests: Collection<ModelManifest>): List<ModelManifest> = manifests.filter { manifest ->
        !isReady(manifest) && manifest.files.isNotEmpty() && manifest.files.all { entry ->
            marker(manifest, entry).exists() && file(manifest, entry).length() == entry.size || isComplete(file(manifest, entry), entry.size) && verify(manifest, entry)
        } && isReady(manifest)
    }

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
            if (!isComplete(target, entry.size)) {
                if (entry.size > PIECE) fetchInPieces(manifest.url(entry), target, entry.size, report)
                else fetch(manifest.url(entry), target, 0, entry.size, report)
            } else report(entry.size)
            if (!verify(manifest, entry)) error("${entry.path} failed verification and was deleted. Try again.")
            done.addAndGet(entry.size)
            onProgress(done.get() / total)
        }
    }

    /** Hashes a fully fetched file: keeps it with its marker, or deletes it and its pieces. */
    private fun verify(manifest: ModelManifest, entry: ModelFile): Boolean {
        val target = file(manifest, entry)
        val ok = sha256(target) == entry.sha256
        clearPieces(target)
        if (ok) marker(manifest, entry).createNewFile() else target.delete()
        return ok
    }

    private fun pieceMarker(target: File, k: Int) = File(target.path + ".piece$k")
    private fun inPlace(target: File) = File(target.path + ".inplace")

    private fun clearPieces(target: File) {
        target.parentFile?.listFiles()?.filter { it.name.startsWith(target.name + ".piece") || it.name.startsWith(target.name + ".part") || it.name == target.name + ".inplace" }?.forEach { it.delete() }
    }

    /**
     * All bytes are on disk: every piece marker is there, or (from an older build that stitched
     * part files) the file has its full size and no pieces are being written into it.
     */
    private fun isComplete(target: File, size: Long): Boolean {
        if (target.length() != size) return false
        if (size <= PIECE) return !inPlace(target).exists()
        val count = ((size + PIECE - 1) / PIECE).toInt()
        return if (inPlace(target).exists()) (0 until count).all { pieceMarker(target, it).exists() } else true
    }

    /** 128 MB pieces, up to six at once, each written into its place in [target]. */
    private suspend fun fetchInPieces(url: String, target: File, size: Long, progress: (Long) -> Unit) = coroutineScope {
        if (!inPlace(target).exists()) {
            // Fresh, or left by an older build: its part files are dropped and the file is laid out at full size.
            clearPieces(target)
            target.delete()
            inPlace(target).createNewFile()
        }
        RandomAccessFile(target, "rw").use { it.setLength(size) }
        val count = ((size + PIECE - 1) / PIECE).toInt()
        val todo = (0 until count).filter { !pieceMarker(target, it).exists() }
        progress((0 until count).filter { it !in todo }.sumOf { minOf(PIECE, size - it * PIECE) }) // pieces kept from an earlier attempt
        val gate = Semaphore(CONNECTIONS)
        todo.map { k ->
            async {
                gate.withPermit {
                    val start = k * PIECE
                    val length = minOf(PIECE, size - start)
                    fetchInto(url, target, start, length, progress)
                    pieceMarker(target, k).createNewFile()
                }
            }
        }.awaitAll()
    }

    /** Fetches one piece into its place; a piece cut short starts again from its beginning. */
    private suspend fun fetchInto(url: String, target: File, offset: Long, length: Long, progress: (Long) -> Unit) {
        var written = 0L
        try {
            val connection = open(url, offset, offset + length - 1, allowWhole = false)
            RandomAccessFile(target, "rw").use { file ->
                file.seek(offset)
                connection.inputStream.use { input -> stream(input, progress) { buffer, n -> file.write(buffer, 0, n); written += n } }
            }
            check(written == length) { "Download cut short." }
        } catch (e: Throwable) {
            progress(-written)
            throw e
        }
    }

    /**
     * Fetches [length] bytes from [offset] into [target], resuming after whatever [target] already
     * holds. [progress] receives the number of new bytes written since the last call.
     */
    private suspend fun fetch(url: String, target: File, offset: Long, length: Long, progress: (Long) -> Unit) {
        val existing = target.length().coerceIn(0, length)
        if (existing == length) return
        val connection = open(url, offset + existing, offset + length - 1, allowWhole = offset + existing == 0L)
        connection.inputStream.use { input ->
            FileOutputStream(target, existing > 0).use { output -> stream(input, progress) { buffer, n -> output.write(buffer, 0, n) } }
        }
    }

    private fun open(url: String, from: Long, to: Long, allowWhole: Boolean): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Range", "bytes=$from-$to")
        }
        val code = connection.responseCode
        check(code == HttpURLConnection.HTTP_PARTIAL || (code == HttpURLConnection.HTTP_OK && allowWhole)) { "Download failed (HTTP $code)." }
        return connection
    }

    private suspend fun stream(input: InputStream, progress: (Long) -> Unit, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(1 shl 16)
        var pending = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            write(buffer, n)
            pending += n
            if (pending >= 1 shl 20) { progress(pending); pending = 0 }
        }
        if (pending > 0) progress(pending)
    }

    companion object {
        const val PIECE = 128L * 1024 * 1024
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
