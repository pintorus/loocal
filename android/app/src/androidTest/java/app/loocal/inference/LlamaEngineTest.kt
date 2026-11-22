package app.loocal.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loocal.domain.Message
import app.loocal.domain.PreparedPrompt
import app.loocal.domain.PromptBuilder
import app.loocal.domain.Role
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real llama.cpp engine. Needs a small GGUF model pushed to the app's external files dir:
 *   adb push model.gguf /sdcard/Android/data/app.loocal/files/test-model.gguf
 * Skipped when the file is missing.
 */
@RunWith(AndroidJUnit4::class)
class LlamaEngineTest {
    private val engine = LlamaEngine()
    private val system = "You are a helpful assistant."

    @Before fun load() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "test-model.gguf")
        assumeTrue("test model not pushed", model.exists())
        engine.load(model.path)
    }

    @After fun unload() = engine.unload()

    @Test fun nativeLibraryLoads() = assertTrue(LlamaEngine.nativeAvailable)

    @Test fun countsTokensOfTemplatedPrompt() {
        val short = engine.countTokens(system, listOf(Message(role = Role.USER, text = "Hi")))
        val long = engine.countTokens(system, listOf(Message(role = Role.USER, text = "Hi ".repeat(50))))
        assertTrue("short=$short", short in 5..100)
        assertTrue("long=$long short=$short", long > short + 40)
    }

    @Test fun generatesAReply() = runBlocking {
        val reply = engine.generate(system, listOf(Message(role = Role.USER, text = "What is the capital of France?")), maxTokens = 32)
            .toList().joinToString("")
        android.util.Log.i("LlamaEngineTest", "reply: $reply")
        assertTrue("empty reply", reply.isNotBlank())
    }

    /** Logs time to first piece and generation speed; asserts nothing about speed. */
    @Test fun measuresSpeed() = runBlocking {
        val messages = listOf(Message(role = Role.USER, text = "Write a short paragraph about the sea. " + "Context. ".repeat(60)))
        val promptTokens = engine.countTokens(system, messages)
        val start = System.nanoTime()
        var first = 0L
        var pieces = 0
        engine.generate(system, messages, maxTokens = 128).collect {
            if (pieces == 0) first = System.nanoTime()
            pieces++
        }
        val end = System.nanoTime()
        val firstMs = (first - start) / 1e6
        val genPerSec = if (pieces > 1) (pieces - 1) / ((end - first) / 1e9) else 0.0
        android.util.Log.i("LlamaEngineTest", "speed: prompt=$promptTokens tokens, first piece=%.0f ms (%.0f tok/s prompt), %d pieces at %.1f pieces/s"
            .format(firstMs, promptTokens / (firstMs / 1000), pieces, genPerSec))
        assertTrue(pieces > 0)
    }

    @Test fun respectsMaxTokens() = runBlocking {
        val pieces = engine.generate(system, listOf(Message(role = Role.USER, text = "Count from 1 to 100.")), maxTokens = 5).toList()
        assertTrue("pieces=${pieces.size}", pieces.size <= 5)
    }

    @Test fun stoppingMidReplyLeavesEngineUsable() = runBlocking {
        val first = engine.generate(system, listOf(Message(role = Role.USER, text = "Tell me a long story.")), maxTokens = 64).take(3).toList()
        assertEquals(3, first.size)
        val second = engine.generate(system, listOf(Message(role = Role.USER, text = "Say hi.")), maxTokens = 16).toList()
        assertTrue(second.joinToString("").isNotBlank())
    }

    @Test fun nonAsciiOutputDecodesCleanly() = runBlocking {
        val reply = engine.generate(system, listOf(Message(role = Role.USER, text = "Écris « café » et un emoji 😀.")), maxTokens = 48)
            .toList().joinToString("")
        assertTrue("replacement char in: $reply", '�' !in reply)
    }

    @Test fun promptBuilderUsesRealTokenCounts() {
        val builder = PromptBuilder(engine::countTokens)
        val history = (1..20).flatMap { listOf(Message(role = Role.USER, text = "Question $it " + "word ".repeat(150)), Message(role = Role.ASSISTANT, text = "Answer $it")) }
        val ready = builder.prepare(history, null) as PreparedPrompt.Ready
        assertTrue(engine.countTokens(ready.system, ready.messages) <= 2048)
        assertEquals(Role.USER, ready.messages.first().role)
    }
}
