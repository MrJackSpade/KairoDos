package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.StateSlotStore
import java.io.File

/** Exercises failure and interruption paths without touching real games or saves. */
object StateSlotStoreFixture {
    fun verify(cache: File) {
        val root = File(cache, "state-slot-transaction-fixture")
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            val store = StateSlotStore(root, "sha256-hdi-v1:test", "state.np2")
            check(store.slots().size == 4 && store.slots().all { it.savedAt == null })
            check(runCatching { store.slot(0) }.isFailure)
            val first = store.beginSave(1)
            store.stateFileIn(first).writeText("original core state")
            File(first, "disk.hdi").writeText("original mounted disk")
            store.commitSave(1, first) { it.writeText("original thumbnail") }
            fun original() {
                val slot = store.slot(1)
                check(slot.savedAt != null)
                check(slot.stateFile.readText() == "original core state")
                check(File(slot.directory, "disk.hdi").readText() == "original mounted disk")
                check(slot.thumbnailFile.readText() == "original thumbnail")
            }
            original()
            val incomplete = store.beginSave(1)
            check(runCatching { store.commitSave(1, incomplete) }.isFailure)
            store.abandonSave(1, incomplete)
            original()
            val failedThumbnail = store.beginSave(1)
            store.stateFileIn(failedThumbnail).writeText("replacement")
            check(runCatching { store.commitSave(1, failedThumbnail) { error("encode failure") } }.isFailure)
            store.abandonSave(1, failedThumbnail)
            original()
            val disappearing = store.beginSave(1)
            store.stateFileIn(disappearing).writeText("replacement")
            val moved = File(disappearing.parentFile, "fixture-moved-candidate")
            check(runCatching { store.commitSave(1, disappearing) {
                check(disappearing.renameTo(moved))
            } }.isFailure)
            original()
            moved.deleteRecursively()
            val failedRename = store.beginSave(1)
            store.stateFileIn(failedRename).writeText("replacement")
            val base = store.slot(1).directory.parentFile!!
            check(base.setWritable(false, false))
            try {
                check(runCatching { store.commitSave(1, failedRename) }.isFailure)
            } finally { check(base.setWritable(true, true)) }
            store.abandonSave(1, failedRename)
            original()
            // Process termination between moving the original aside and installing the new save.
            check(store.slot(1).directory.renameTo(File(base, "slot1.old")))
            original()
            check(!File(base, "slot1.old").exists())
            val foreign = File(root, "unrelated").apply { mkdirs() }
            File(foreign, "state.np2").writeText("foreign")
            check(runCatching { store.commitSave(1, foreign) }.isFailure)
            check(runCatching { store.abandonSave(1, foreign) }.isFailure)
            check(foreign.isDirectory)
            original()

            val dos = StateSlotStore(root, "sha256-dos-manifest-v1:legacy", "state.dos", ".state")
            val dosBase = dos.slot(1).directory.parentFile!!.apply { mkdirs() }
            val legacy = File(dosBase, "slot1.state").apply { writeText("legacy DOS save") }
            File(dosBase, "slot1.png").writeText("legacy thumbnail")
            check(legacy.renameTo(File(dosBase, "slot1.old")))
            check(dos.slot(1).stateFile == legacy && dos.slot(1).thumbnailFile.readText() == "legacy thumbnail")
            val failure = dos.beginSave(1)
            check(runCatching { dos.commitSave(1, failure) }.isFailure)
            dos.abandonSave(1, failure)
            check(legacy.readText() == "legacy DOS save")
            val candidate = dos.beginSave(1)
            dos.stateFileIn(candidate).writeText("new DOS save")
            dos.commitSave(1, candidate) { it.writeText("new thumbnail") }
            check(!legacy.exists() && !File(dosBase, "slot1.png").exists())
            check(dos.slot(1).stateFile.readText() == "new DOS save")
            check(dos.slot(1).thumbnailFile.readText() == "new thumbnail")
        } finally { root.deleteRecursively() }
    }
}
