package app.loocal.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Pinned model definition: like App/ModelManifest*.json on iOS, every file is fixed by revision and SHA-256. */
@Serializable
data class ModelManifest(val id: String, val name: String, val repository: String, val revision: String, val files: List<ModelFile>) {
    val isPinned get() = revision.length == 40 && files.isNotEmpty() && files.all { it.sha256.length == 64 }
    fun url(file: ModelFile) = "https://huggingface.co/$repository/resolve/$revision/${file.path}"
}

@Serializable
data class ModelFile(val path: String, val size: Long, val sha256: String)

/**
 * Downloads a manifest's files into [directory]. Partial files resume with an HTTP Range request,
 * and a file is only kept once its SHA-256 matches. Unpinned manifests are refused.
 */
class ModelDownloader(private val directory: File) {
    fun file(manifest: ModelManifest, file: ModelFile) = File(File(directory, manifest.id), file.path)

    fun isReady(manifest: ModelManifest) = manifest.files.all { file(manifest, it).length() == it.size && File(file(manifest, it).path + ".ok").exists() }

    suspend fun download(manifest: ModelManifest, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        require(manifest.isPinned) { "This model is not pinned to a revision and hashes yet." }
        val total = manifest.files.sumOf { it.size }.toFloat()
        var done = 0L
        for (entry in manifest.files) {
            val target = file(manifest, entry)
            val marker = File(target.path + ".ok")
            if (!(marker.exists() && target.length() == entry.size)) {
                target.parentFile?.mkdirs()
                marker.delete()
                fetch(manifest.url(entry), target, entry.size) { onProgress((done + it) / total) }
                if (sha256(target) != entry.sha256) {
                    target.delete()
                    error("${entry.path} failed verification and was deleted. Try again.")
                }
                marker.createNewFile()
            }
            done += entry.size
            onProgress(done / total)
        }
    }

    private suspend fun fetch(url: String, target: File, size: Long, progress: (Long) -> Unit) {
        val existing = target.length().takeIf { it in 1 until size } ?: 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        check(resumed || connection.responseCode == HttpURLConnection.HTTP_OK) { "Download failed (HTTP ${connection.responseCode})." }
        var written = if (resumed) existing else 0L
        connection.inputStream.use { input ->
            FileOutputStream(target, resumed).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    written += n
                    progress(written)
                }
            }
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
