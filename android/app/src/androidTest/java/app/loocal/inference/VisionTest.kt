package app.loocal.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loocal.domain.Message
import app.loocal.domain.PhotoSlot
import app.loocal.domain.PhotoStore
import app.loocal.domain.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photo understanding with the model the app downloaded itself (Settings › Models › Qwen3 VL 4B).
 * Needs a test photo pushed to the app's external files dir as test-photo.jpg; skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class VisionTest {
    @Test fun describesAPhoto(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "models/qwen3-vl-4b")
        val weights = folder.listFiles()?.firstOrNull { it.name.endsWith(".gguf") && !it.name.contains("mmproj") }
        val projector = folder.listFiles()?.firstOrNull { it.name.startsWith("mmproj") && it.name.endsWith(".gguf") }
        val photo = File(context.getExternalFilesDir(null), "test-photo.jpg")
        assumeTrue("model or photo missing", weights != null && projector != null && photo.exists())

        val engine = LlamaEngine()
        engine.load(weights!!.path, projector!!.path)
        assertTrue("vision projector not loaded", engine.hasVision)

        val decoded = BitmapFactory.decodeFile(photo.path)
        val (w, h) = PhotoStore.fit(decoded.width, decoded.height, PhotoStore.MODEL_MAX)
        val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
        val pixels = IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        val rgb = ByteArray(w * h * 3).also { out -> pixels.forEachIndexed { i, p -> out[i * 3] = (p shr 16).toByte(); out[i * 3 + 1] = (p shr 8).toByte(); out[i * 3 + 2] = p.toByte() } }

        val messages = listOf(Message(role = Role.USER, text = "What is in this photo? One sentence."))
        val slot = PhotoSlot(0, w, h)
        val tokens = engine.countTokens("You are a helpful assistant.", messages, slot)
        val start = System.nanoTime()
        val reply = engine.generate("You are a helpful assistant.", messages, PhotoInput(slot, rgb), maxTokens = 60).toList().joinToString("")
        val seconds = (System.nanoTime() - start) / 1e9
        engine.unload()
        Log.i("VisionTest", "photo ${w}x$h, prompt $tokens tokens, ${"%.1f".format(seconds)} s: $reply")
        assertTrue("empty reply", reply.isNotBlank())
    }
}
