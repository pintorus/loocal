package app.loocal.inference

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loocal.domain.Message
import app.loocal.domain.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Speed and memory for one engine configuration, chosen with instrumentation arguments:
 *   adb shell am instrument -w -e class app.loocal.inference.LlamaBenchmark \
 *     -e threads 4 -e threadsBatch 6 -e loadMode -1 -e repack true app.loocal.test/androidx.test.runner.AndroidJUnitRunner
 * Results are logged under the tag LlamaBenchmark.
 */
@RunWith(AndroidJUnit4::class)
class LlamaBenchmark {
    @Test fun run(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val defaults = LlamaEngine.Options()
        val options = LlamaEngine.Options(
            threads = args.getString("threads")?.toInt() ?: defaults.threads,
            threadsBatch = args.getString("threadsBatch")?.toInt() ?: defaults.threadsBatch,
            loadMode = args.getString("loadMode")?.toInt() ?: defaults.loadMode,
            repack = args.getString("repack")?.toBoolean() ?: defaults.repack,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), args.getString("model") ?: "test-model.gguf")
        assumeTrue("test model not pushed", model.exists())

        val engine = LlamaEngine(options)
        val loadStart = System.nanoTime()
        engine.load(model.path)
        val loadMs = (System.nanoTime() - loadStart) / 1e6

        val system = "You are a helpful assistant."
        // About 500 prompt tokens: a realistic chat with some history.
        val messages = listOf(Message(role = Role.USER, text = "Summarize this in two sentences: " + "The sea is calm and blue today. ".repeat(60)))
        val promptTokens = engine.countTokens(system, messages)

        val start = System.nanoTime()
        var first = 0L
        var pieces = 0
        engine.generate(system, messages, maxTokens = 64).collect {
            if (pieces == 0) first = System.nanoTime()
            pieces++
        }
        val end = System.nanoTime()
        val pssMb = Debug.getPss() / 1024
        engine.unload()

        val promptSec = (first - start) / 1e9
        val genRate = if (pieces > 1) (pieces - 1) / ((end - first) / 1e9) else 0.0
        Log.i("LlamaBenchmark", "%s load=%.0fms prompt=%d tok in %.2fs (%.1f tok/s) gen=%.1f tok/s pss=%d MB"
            .format(options, loadMs, promptTokens, promptSec, promptTokens / promptSec, genRate, pssMb))
    }
}
