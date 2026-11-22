package app.loocal.domain

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Photos sent with prompts (port of the iOS photo handling in AppState / LocalFiles):
 * - re-encoded as JPEG, which drops the original EXIF metadata (location, camera);
 * - stored up to 1,024 px on the long side; the model sees them bounded by 512 x 512;
 * - photos of temporary chats live in their own folder, deleted when the chat ends and at launch;
 * - at launch, any photo no saved chat refers to is removed.
 */
class PhotoStore(root: File) {
    val saved = File(root, "images").apply { mkdirs() }
    private val temporary = File(root, "images-temporary").apply { mkdirs() }

    data class Stored(val name: String, val modelWidth: Int, val modelHeight: Int)

    fun file(name: String): File = File(saved, name).takeIf { it.exists() } ?: File(temporary, name)

    /** Imports a picked or captured photo. Runs on a background thread. */
    fun import(resolver: ContentResolver, uri: Uri, temporaryChat: Boolean): Stored {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = fit(info.size.width, info.size.height, STORED_MAX)
            decoder.setTargetSize(w, h)
        }
        val name = UUID.randomUUID().toString() + ".jpg"
        File(if (temporaryChat) temporary else saved, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val (mw, mh) = fit(bitmap.width, bitmap.height, MODEL_MAX)
        bitmap.recycle()
        return Stored(name, mw, mh)
    }

    /** RGB bytes at the size the model reads (row-major, 3 bytes per pixel). */
    fun rgb(name: String, width: Int, height: Int): ByteArray {
        val decoded = BitmapFactory.decodeFile(file(name).path) ?: error("The photo is missing.")
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val out = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = (p shr 16 and 0xFF).toByte()
            out[i * 3 + 1] = (p shr 8 and 0xFF).toByte()
            out[i * 3 + 2] = (p and 0xFF).toByte()
        }
        return out
    }

    /** Moves a temporary photo into the saved folder (a draft attached before the chat was saved). */
    fun delete(names: Collection<String>) = names.forEach { file(it).delete() }

    /** Clears temporary photos, and saved ones no chat refers to. */
    fun sweep(referenced: Set<String>) {
        temporary.listFiles()?.forEach { it.delete() }
        saved.listFiles()?.filter { it.name !in referenced }?.forEach { it.delete() }
    }

    fun clearTemporary() { temporary.listFiles()?.forEach { it.delete() } }

    companion object {
        const val STORED_MAX = 1024
        const val MODEL_MAX = 512

        /** Scales width x height down to fit within max x max, keeping the aspect ratio. */
        fun fit(width: Int, height: Int, max: Int): Pair<Int, Int> {
            val scale = minOf(1.0, max.toDouble() / maxOf(width, height))
            return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
        }
    }
}
