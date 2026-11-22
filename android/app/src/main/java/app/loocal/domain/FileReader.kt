package app.loocal.domain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

data class AttachedFile(val name: String, val text: String)

/** Port of iOS `AppState.attachFile`: a picked file as text (PDFs extracted), trimmed to fit the phone context. */
object FileReader {
    const val LIMIT = 4_000

    /** Returns the file, or an error message to show. Runs on a background thread. */
    fun read(context: android.content.Context, uri: Uri): Result<AttachedFile> = runCatching {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: "file"
        val text: String? = if (name.lowercase().endsWith(".pdf") || resolver.getType(uri) == "application/pdf") {
            PDFBoxResourceLoader.init(context)
            resolver.openInputStream(uri)?.use { PDDocument.load(it).use { doc -> PDFTextStripper().getText(doc) } }
        } else {
            resolver.openInputStream(uri)?.use { it.readBytes() }?.let { bytes ->
                if (bytes.contains(0)) null // binary
                else runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
            }
        }
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) error("“$name” has no readable text. Text, Markdown, code, CSV, JSON and PDF files work.")
        val clipped = if (body.length > LIMIT) body.take(LIMIT) + "\n[…file truncated to fit the phone context]" else body
        AttachedFile(name, clipped)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}
