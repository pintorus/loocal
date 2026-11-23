package app.loocal.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class ModelDownloadTest {
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val downloader = ModelDownloader(dir)

    private fun manifest(vararg files: ModelFile) = ModelManifest("m", "M", repository = "a/b", revision = "0".repeat(40), files = files.toList())

    private fun write(bytes: ByteArray): ModelFile {
        File(dir, "m").mkdirs()
        File(dir, "m/w.gguf").writeBytes(bytes)
        return ModelFile("w.gguf", bytes.size.toLong(), sha(File(dir, "m/w.gguf")))
    }

    private fun sha(f: File) = ModelDownloader.sha256(f)

    @Test fun aFetchedButUncheckedFileIsFinishedWithoutFetching() {
        val m = manifest(write("weights".toByteArray()))
        assertFalse(downloader.isReady(m))
        assertEquals(listOf(m), downloader.finishVerified(listOf(m)))
        assertTrue(downloader.isReady(m))
    }

    @Test fun aWrongFileIsDeletedNotKept() {
        val good = write("weights".toByteArray())
        File(dir, "m/w.gguf").writeText("weightz")
        val m = manifest(good)
        assertTrue(downloader.finishVerified(listOf(m)).isEmpty())
        assertFalse(File(dir, "m/w.gguf").exists())
    }

    @Test fun piecesWrittenInPlaceCountOnlyWhenEveryPieceLanded() {
        val size = ModelDownloader.PIECE + 10
        val target = File(dir, "m/w.gguf").apply { parentFile.mkdirs() }
        RandomAccessFile(target, "rw").use { it.setLength(size) }
        val entry = ModelFile("w.gguf", size, sha(target))
        File(target.path + ".inplace").createNewFile()
        File(target.path + ".piece0").createNewFile()
        val m = manifest(entry)
        assertTrue(downloader.finishVerified(listOf(m)).isEmpty())
        assertTrue("a missing piece keeps the file for resuming", target.exists())
        File(target.path + ".piece1").createNewFile()
        assertEquals(listOf(m), downloader.finishVerified(listOf(m)))
        assertFalse(File(target.path + ".inplace").exists() || File(target.path + ".piece0").exists())
    }
}

class SharedFolderTest {
    @Test fun aSharedModelSurvivesTheSweep() {
        val dir = java.nio.file.Files.createTempDirectory("models").toFile()
        val d = ModelDownloader(dir)
        val m = ModelManifest("hf.owner/name", "N", repository = "owner/name", revision = "", files = emptyList())
        d.folder(m).mkdirs()
        d.sweep(listOf(m))
        org.junit.Assert.assertTrue(d.folder(m).exists())
        org.junit.Assert.assertEquals("hf.owner--name", d.folder(m).name)
    }
}
