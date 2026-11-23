package app.loocal.inference

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume

/** What the chat shows for a picture being painted, and the picture once it's done. */
@Serializable
data class PaintInfo(val prompt: String, val width: Int, val height: Int, val model: String)

/**
 * Port of iOS `Painter`: paints pictures with a downloaded Stable Diffusion model through
 * stable-diffusion.cpp (on llama.cpp's ggml). One picture at a time; the model is let go
 * afterwards so the chat model gets its memory back.
 */
class Painter {
    /** Steps done of all, told after each denoising step. */
    fun interface Listener { fun step(done: Int, total: Int) }

    /**
     * Paints [prompt] with the model at [path]. [step] hears each step (1-based). Cancelling the
     * coroutine stops the picture between steps.
     */
    suspend fun paint(path: String, id: String, prompt: String, seed: Long = System.nanoTime() and 0x7fffffff, step: Listener): Bitmap = withContext(Dispatchers.Default) {
        val handle = nativeLoad(path, Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        if (handle == 0L) throw IllegalStateException(nativeError().ifEmpty { "The picture model couldn't be loaded." })
        try {
            val (steps, cfg) = settings(id)
            val pixels = suspendCancellableCoroutine<IntArray?> { cont ->
                val thread = Thread {
                    val result = runCatching { nativePaint(handle, prompt, NEGATIVE, SIDE, SIDE, steps, cfg, seed, step) }.getOrNull()
                    cont.resume(result)
                }
                cont.invokeOnCancellation { nativeCancel(handle) }
                thread.start()
            } ?: throw IllegalStateException(nativeError().ifEmpty { "The picture couldn't be painted." })
            Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        } finally {
            nativeFree(handle)
        }
    }

    companion object {
        /** Pictures come out square, this many pixels a side. */
        const val SIDE = 512
        private const val NEGATIVE = "blurry, low quality, deformed, watermark, text"

        /** Distilled models (Turbo, Lightning) need a couple of steps and no guidance; the rest want more of both. */
        fun settings(id: String): Pair<Int, Float> {
            val lower = id.lowercase()
            return when {
                "turbo" in lower -> 2 to 1f
                "lightning" in lower -> 4 to 1f
                "lcm" in lower -> 4 to 1f
                "xl" in lower -> 20 to 5f
                else -> 20 to 7.5f
            }
        }

        init { System.loadLibrary("loocal_llama") }

        @JvmStatic private external fun nativeLoad(path: String, threads: Int): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeError(): String
        @JvmStatic private external fun nativePaint(handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: Listener): IntArray?
    }
}
