package app.loocal.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelLibraryTest {
    @Test fun repositoryFromEveryKindOfModelLink() {
        assertEquals("bartowski/Llama-3.2-3B-Instruct-GGUF", ModelLibrary.repository("https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF"))
        assertEquals("a/b", ModelLibrary.repository("https://huggingface.co/a/b/tree/main"))
        assertEquals("a/b", ModelLibrary.repository("https://hf.co/a/b/blob/main/x.gguf"))
        assertEquals("a/b", ModelLibrary.repository("https://www.huggingface.co/models/a/b"))
        listOf("https://huggingface.co/datasets/a/b", "https://huggingface.co/spaces/a/b", "https://example.com/a/b", "https://huggingface.co/a").forEach {
            try { ModelLibrary.repository(it); fail(it) } catch (_: LibraryError) {}
        }
    }

    @Test fun theLinkIsFoundInSharedText() {
        assertEquals("https://huggingface.co/a/b", ModelLibrary.link("Look at this: https://huggingface.co/a/b."))
        assertNull(ModelLibrary.link("no link here"))
    }

    @Test fun namesAndSizesMatchTheIosRules() {
        assertEquals("Llama 3.2 3B", ModelLibrary.shortName("bartowski/Llama-3.2-3B-Instruct-GGUF"))
        assertEquals(3.0, ModelLibrary.parameters("Llama-3.2-3B-Instruct")!!, 0.0)
        assertEquals(0.27, ModelLibrary.parameters("gemma-3-270m-it")!!, 1e-9)
        assertEquals(30.0, ModelLibrary.parameters("Qwen3-30B-A3B")!!, 0.0)
    }

    @Test fun picksQ4KMAndTheProjectorAndSkipsSplitWeights() {
        fun f(n: String, size: Long = 100) = ModelLibrary.Sibling(n, size, "h")
        val (w, p) = ModelLibrary.wanted(listOf(f("m-Q8_0.gguf"), f("m-Q4_K_M.gguf"), f("m-Q4_K_S.gguf"), f("mmproj-m-f16.gguf"), f("mmproj-m-Q8_0.gguf"), f("README.md")))!!
        assertEquals("m-Q4_K_M.gguf", w.name)
        assertEquals("mmproj-m-f16.gguf", p!!.name)
        assertNull(ModelLibrary.wanted(listOf(f("m-Q4_K_M-00001-of-00002.gguf"), f("model.safetensors"))))
    }

    @Test fun sharedModelsSortAmongTheBuiltInOnes() {
        fun m(name: String, p: Double) = ModelManifest(name, name, repository = "a/$name", revision = "", files = emptyList(), parameters = p)
        assertEquals(listOf("A", "4B", "8B", "B"), ModelLibrary.ordered(listOf(m("8B", 8.0), m("B", 9.0), m("4B", 4.0), m("A", 1.0))).map { it.name })
    }

    @Test fun architectureIsReadFromTheGgufHeader() {
        val out = ByteArrayOutputStream()
        fun le(n: Int, v: Long) { val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); if (n == 4) b.putInt(v.toInt()) else b.putLong(v); out.write(b.array()) }
        fun str(s: String) { le(8, s.length.toLong()); out.write(s.toByteArray()) }
        le(4, 0x46554747); le(4, 3); le(8, 0); le(8, 2)
        str("general.alignment"); le(4, 4); le(4, 32)
        str("general.architecture"); le(4, 8); str("qwen3")
        assertEquals("qwen3", ModelLibrary.ggufArchitecture(out.toByteArray()))
        assertTrue("qwen3" in ModelLibrary.ARCHITECTURES && "gemma3" in ModelLibrary.ARCHITECTURES && "bert" !in ModelLibrary.ARCHITECTURES)
    }

    @Test fun architecturesFollowTheBundledLlamaCpp() {
        val source = File("src/main/cpp/llama.cpp/src/llama-arch.cpp").readText()
        val built = Regex("""\{ LLM_ARCH_[A-Z0-9_]+,\s+"([^"]+)"\s+\}""").findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue("every listed architecture exists in llama.cpp: ${ModelLibrary.ARCHITECTURES - built}", built.containsAll(ModelLibrary.ARCHITECTURES))
    }
}
