package app.loocal.inference

import app.loocal.domain.Limits
import app.loocal.domain.Message
import kotlinx.coroutines.flow.Flow

/** Runs one local model on the device and streams reply text. */
interface InferenceEngine {
    val isLoaded: Boolean
    suspend fun load(modelPath: String)
    fun unload()

    /** Token count of the fully templated input; used by PromptBuilder to fit the context budget. */
    fun countTokens(system: String, messages: List<Message>): Int

    /** Streams text pieces; stops at [maxTokens] or end of sequence. Cancel the collector to stop. */
    fun generate(system: String, messages: List<Message>, maxTokens: Int = Limits.MAX_REPLY_TOKENS): Flow<String>
}
