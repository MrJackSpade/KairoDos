package com.mrjackspade.kairodos

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** A changed game source rebuilds the base drive and keeps the player's overlay. */
class DosStagingStorageTest {
    private val root: File = Files.createTempDirectory("dos-staging").toFile()
    private val saveDir = File(root, "save")
    private val overlay = File(root, "overlay")

    @After fun cleanUp() { root.deleteRecursively() }

    private fun archive(name: String, files: Map<String, String>) = File(root, name).also { zip ->
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((path, text) in files) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun prepare(source: File, contentId: String,
                        edited: (List<String>) -> Set<String> = { error("Unexpected conflict: $it") }) =
        DosStagingStorage.prepare(source, saveDir, contentId, overlay, AtomicBoolean(false), edited = edited)

    private fun saved(path: String, text: String) =
        File(overlay, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private val original = mapOf("GAME.EXE" to "game", "SOUND.CFG" to "sb", "DATA/LEVEL1.DAT" to "one")

    @Test fun anAddedFileRebuildsTheBaseWithoutAsking() {
        prepare(archive("v1.zip", original), "v1")
        saved("SAVE1.SAV", "progress")
        val drive = prepare(archive("v2.zip", original + ("RUN.BAT" to "game")), "v2")
        assertEquals("game", File(drive.directory, "RUN.BAT").readText())
        assertEquals("progress", File(overlay, "SAVE1.SAV").readText())
        assertEquals("v2", File(saveDir, "staging/base-v2.txt").readText())
        assertFalse(File(saveDir, "staging/base-previous").exists())
    }

    @Test fun anEditedFileTheGameAlsoSavedIsAConflict() {
        prepare(archive("v1.zip", original), "v1")
        saved("sound.cfg", "gus")
        saved("SAVE1.SAV", "progress")
        val edited = original + ("SOUND.CFG" to "sb16")
        var asked = emptyList<String>()
        prepare(archive("keep.zip", edited), "v2") { asked = it; emptySet() }
        assertEquals(listOf("sound.cfg"), asked)
        assertEquals("gus", File(overlay, "sound.cfg").readText())

        // The same edit again, choosing the edited file: the saved copy gives way.
        File(saveDir, "staging/base-v2.txt").writeText("v1")
        File(saveDir, "staging/base/SOUND.CFG").writeText("sb")
        prepare(archive("use.zip", edited), "v2") { it.toSet() }
        assertFalse(File(overlay, "sound.cfg").exists())
        assertEquals("progress", File(overlay, "SAVE1.SAV").readText())
    }

    @Test fun anEditedFileTheGameNeverSavedDoesNotAsk() {
        prepare(archive("v1.zip", original), "v1")
        saved("SAVE1.SAV", "progress")
        val drive = prepare(archive("v2.zip", original + ("SOUND.CFG" to "sb16")), "v2")
        assertEquals("sb16", File(drive.directory, "SOUND.CFG").readText())
    }

    @Test fun removedFilesAndFoldersInTheSavesConflictWithSourceChanges() {
        prepare(archive("v1.zip", original), "v1")
        saved("DBOVERLAY_DEL_SOUND.CFG", "empty")
        saved("DBOVERLAY_RMD_DATA", "empty")
        var asked = emptyList<String>()
        prepare(archive("v2.zip", mapOf("GAME.EXE" to "game", "SOUND.CFG" to "pas", "DATA/LEVEL1.DAT" to "uno"))
            , "v2") { asked = it; it.toSet() }
        assertEquals(listOf("DATA", "SOUND.CFG"), asked)
        assertTrue(overlay.listFiles().orEmpty().isEmpty())
    }

    @Test fun aSourceRemovingASavedFileIsAConflict() {
        prepare(archive("v1.zip", original), "v1")
        saved("SOUND.CFG", "gus")
        var asked = emptyList<String>()
        prepare(archive("v2.zip", original - "SOUND.CFG"), "v2") { asked = it; emptySet() }
        assertEquals(listOf("SOUND.CFG"), asked)
        assertEquals("gus", File(overlay, "SOUND.CFG").readText())
    }

    @Test fun cancellingLeavesTheEarlierBaseInUse() {
        prepare(archive("v1.zip", original), "v1")
        saved("SOUND.CFG", "gus")
        val failure = runCatching { prepare(archive("v2.zip", original + ("SOUND.CFG" to "sb16")), "v2") { error("Cancelled") } }
        assertEquals("Cancelled", failure.exceptionOrNull()?.message)
        assertEquals("v1", File(saveDir, "staging/base-v2.txt").readText())
        assertEquals("sb", File(saveDir, "staging/base/SOUND.CFG").readText())
        assertEquals("gus", File(overlay, "SOUND.CFG").readText())
    }

    @Test fun aRebuildInterruptedBetweenRenamesRecovers() {
        prepare(archive("v1.zip", original), "v1")
        // The previous base was moved aside and the new one never arrived.
        val staging = File(saveDir, "staging")
        assertTrue(File(staging, "base").renameTo(File(staging, "base-previous")))
        val drive = prepare(archive("v1-again.zip", original), "v1")
        assertEquals("sb", File(drive.directory, "SOUND.CFG").readText())
        assertFalse(File(staging, "base-previous").exists())
    }

    @Test fun aMissingBaseIsExtractedAgainWithoutAsking() {
        prepare(archive("v1.zip", original), "v1")
        saved("SOUND.CFG", "gus")
        assertTrue(File(saveDir, "staging/base").deleteRecursively())
        val drive = prepare(archive("v1-again.zip", original), "v1")
        assertEquals("sb", File(drive.directory, "SOUND.CFG").readText())
        assertEquals("gus", File(overlay, "SOUND.CFG").readText())
    }
}
