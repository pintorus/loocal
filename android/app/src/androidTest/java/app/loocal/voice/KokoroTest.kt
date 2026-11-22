package app.loocal.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Kokoro natural voices on the phone. Needs the engine archive already in files/kokoro/engine.tar.bz2
 * (or the engine already installed); the test then verifies, unpacks and speaks one sentence.
 */
@RunWith(AndroidJUnit4::class)
class KokoroTest {
    @Test fun speaksWithHeart(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = KokoroEngine(context.filesDir)
        assumeTrue("no engine archive", engine.isReady || File(context.filesDir, "kokoro/engine.tar.bz2").length() == KokoroEngine.SIZE)
        val unpack = System.nanoTime()
        if (!engine.isReady) engine.download {}
        Log.i("KokoroTest", "engine ready in %.1f s".format((System.nanoTime() - unpack) / 1e9))
        for (voice in listOf(NeuralVoice.CATALOG.first { it.id == "af_heart" }, NeuralVoice.CATALOG.first { it.id == "bm_george" })) {
            val start = System.nanoTime()
            val audio = engine.synthesizer(voice).generate("Hi, this is how I'll sound when we talk.", voice.sid, 1.0f)
            val seconds = (System.nanoTime() - start) / 1e9
            val length = audio.samples.size.toDouble() / audio.sampleRate
            Log.i("KokoroTest", "${voice.name}: %.2f s of audio in %.2f s (%d Hz)".format(length, seconds, audio.sampleRate))
            assertTrue(length > 1.0)
        }
        // Once loaded: the speed conversation mode actually gets.
        val heart = NeuralVoice.CATALOG.first { it.id == "af_heart" }
        engine.synthesizer(heart)
        val start = System.nanoTime()
        val audio = engine.synthesizer(heart).generate("Sure, I can help with that. Here is a short answer.", heart.sid, 1.0f)
        Log.i("KokoroTest", "warm: %.2f s of audio in %.2f s".format(audio.samples.size.toDouble() / audio.sampleRate, (System.nanoTime() - start) / 1e9))
        engine.unload()
    }
}
