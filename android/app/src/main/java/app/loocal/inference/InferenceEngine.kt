package app.loocal.inference

import app.loocal.domain.Limits
import app.loocal.domain.Message
import app.loocal.domain.PhotoSlot
import kotlinx.coroutines.flow.Flow

/** A photo ready for the model: RGB bytes, [slot] width x height x 3. */
class PhotoInput(val slot: PhotoSlot, val rgb: ByteArray)

/** Runs one local model on the device and streams reply text. */
interface InferenceEngine {
    val isLoaded: Boolean
    /** True when the loaded model also has its vision projector. */
    val hasVision: Boolean
    suspend fun load(modelPath: String, visionPath: String? = null)
    fun unload()

    /** Token count of the fully templated input; used by PromptBuilder to fit the context budget. */
    fun countTokens(system: String, messages: List<Message>, photo: PhotoSlot? = null): Int

    /** Streams text pieces; stops at [maxTokens] or end of sequence. Cancel the collector to stop. */
    fun generate(system: String, messages: List<Message>, photo: PhotoInput? = null, maxTokens: Int = Limits.MAX_REPLY_TOKENS): Flow<String>
}
