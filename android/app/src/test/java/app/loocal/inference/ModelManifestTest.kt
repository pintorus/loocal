package app.loocal.inference

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelManifestTest {
    private val manifest = Json.decodeFromString(ModelManifest.serializer(), File("src/main/assets/model-4b.json").readText())

    @Test fun shippedManifestIsPinned() {
        assertTrue(manifest.isPinned)
        assertTrue(manifest.files.single().path.endsWith(".gguf"))
    }

    @Test fun urlUsesPinnedRevision() {
        assertEquals(
            "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/ae44f08e1392f39c0e474af10c3ff8355c8b6688/Qwen_Qwen3-4B-Instruct-2507-Q4_0.gguf",
            manifest.url(manifest.files.single()),
        )
    }

    @Test fun unpinnedManifestIsRefused() {
        assertTrue(!manifest.copy(revision = "main").isPinned)
        assertTrue(!manifest.copy(files = emptyList()).isPinned)
    }
}
