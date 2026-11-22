package app.loocal.inference

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelManifestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun load(id: String) = json.decodeFromString(ModelManifest.serializer(), File("src/main/assets/models/$id.json").readText())
    private val all = listOf("qwen3-vl-4b", "qwen3-vl-8b", "gemma-3-12b").map(::load)

    @Test fun everyShippedManifestIsPinned() = all.forEach { assertTrue(it.id, it.isPinned) }

    @Test fun visionModelsHaveTheirProjectorAndGemmaIsWordsOnly() {
        assertTrue(load("qwen3-vl-4b").vision)
        assertTrue(load("qwen3-vl-8b").vision)
        assertFalse(load("gemma-3-12b").vision)
        all.forEach { assertTrue(it.weights.path.endsWith(".gguf") && !it.weights.isVision) }
    }

    @Test fun namesMatchTheIosApp() {
        assertEquals(listOf("Qwen3 VL 4B", "Qwen3 VL 8B", "Gemma 3 12B"), all.map { it.name })
    }

    @Test fun urlUsesPinnedRevision() {
        val m = load("qwen3-vl-8b")
        assertEquals("https://huggingface.co/${m.repository}/resolve/${m.revision}/mmproj-F16.gguf", m.url(m.projector!!))
    }

    @Test fun unpinnedManifestIsRefused() {
        val m = load("qwen3-vl-4b")
        assertFalse(m.copy(revision = "main").isPinned)
        assertFalse(m.copy(files = emptyList()).isPinned)
    }
}
