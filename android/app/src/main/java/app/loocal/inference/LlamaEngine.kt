package app.loocal.inference

import app.loocal.domain.Limits
import app.loocal.domain.Message
import app.loocal.domain.PhotoSlot
import app.loocal.domain.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * llama.cpp engine through JNI (src/main/cpp/loocal_llama.cpp, llama.cpp pinned as a submodule),
 * with photos through libmtmd when the model has a vision projector.
 * Not thread-safe: one reply at a time, which the ViewModel guarantees.
 */
class LlamaEngine(private val options: Options = Options()) : InferenceEngine {
    /**
     * [threads] generate one token at a time; [threadsBatch] read the prompt.
     * [loadMode] mirrors llama_load_mode (-1 auto, 0 none, 1 mmap). [repack] lets llama.cpp
     * re-lay weights for faster CPU kernels.
     *
     * Defaults come from measurements on a Xiaomi 14 (see android/README.md): 6 prompt threads
     * read ~45% faster than 4 (8 is slower: the two efficiency cores hold the others back),
     * and loading without mmap keeps one copy of the repacked weights instead of two (3.3 GB vs 5 GB).
     */
    data class Options(
        val threads: Int = 4,
        val threadsBatch: Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 6),
        val loadMode: Int = 0,
        val repack: Boolean = true,
    )

    private var handle = 0L
    private var loadedPath: String? = null
    override val isLoaded get() = handle != 0L
    override val hasVision get() = handle != 0L && nativeHasVision(handle)

    /** Loads [modelPath] (and its vision projector), replacing any other loaded model. */
    override suspend fun load(modelPath: String, visionPath: String?) = withContext(Dispatchers.IO) {
        if (isLoaded && loadedPath == modelPath) return@withContext
        unload()
        check(nativeAvailable) { "The llama.cpp native library is not built into this APK." }
        handle = nativeLoad(modelPath, visionPath, Limits.MAX_INPUT_TOKENS + Limits.MAX_REPLY_TOKENS,
            options.threads, options.threadsBatch, options.loadMode, options.repack)
        check(handle != 0L) { "The model could not be loaded. It may not fit in memory on this phone." }
        loadedPath = modelPath
    }

    override fun unload() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
        loadedPath = null
    }

    override fun countTokens(system: String, messages: List<Message>, photo: PhotoSlot?): Int =
        nativeCountTokens(requireHandle(), roles(messages), texts(system, messages), photo?.let { it.index + 1 } ?: -1, photo?.width ?: 0, photo?.height ?: 0)

    override fun generate(system: String, messages: List<Message>, photo: PhotoInput?, maxTokens: Int): Flow<String> = flow {
        val h = requireHandle()
        try {
            val started = nativeStart(
                h, roles(messages), texts(system, messages),
                photo?.let { it.slot.index + 1 } ?: -1, photo?.slot?.width ?: 0, photo?.slot?.height ?: 0, photo?.rgb, maxTokens,
            )
            check(started) { "The model could not read this prompt." }
            while (true) {
                val bytes = nativeNext(h) ?: break // null = end of sequence or max tokens
                // One emission per token (empty while a character is incomplete), so callers can count tokens.
                emit(bytes.decodeToString())
            }
        } finally {
            nativeReset(h) // fresh KV cache per reply, as on iOS
        }
    }.flowOn(Dispatchers.Default)

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "No model is loaded." } }

    // index 0 is the system prompt, then one entry per message
    private fun roles(messages: List<Message>) =
        arrayOf("system") + messages.map { if (it.role == Role.USER) "user" else "assistant" }
    private fun texts(system: String, messages: List<Message>) = arrayOf(system) + messages.map { it.modelText }

    private external fun nativeLoad(path: String, mmproj: String?, contextTokens: Int, threads: Int, threadsBatch: Int, loadMode: Int, repack: Boolean): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeHasVision(handle: Long): Boolean
    private external fun nativeCountTokens(handle: Long, roles: Array<String>, texts: Array<String>, imageIndex: Int, width: Int, height: Int): Int
    private external fun nativeStart(handle: Long, roles: Array<String>, texts: Array<String>, imageIndex: Int, width: Int, height: Int, rgb: ByteArray?, maxTokens: Int): Boolean
    private external fun nativeNext(handle: Long): ByteArray?
    private external fun nativeReset(handle: Long)

    companion object {
        val nativeAvailable: Boolean = runCatching { System.loadLibrary("loocal_llama") }.isSuccess
    }
}
