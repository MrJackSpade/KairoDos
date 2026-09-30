// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object StagingStorageFixture {
    fun verify(cache: File) {
        val root = File(cache, "staging-storage-fixture")
        check(!root.exists()) { "Inspect existing migration fixture before retrying" }
        check(root.mkdirs())
        fun zip(file: File, entries: Map<String, String>) {
            file.parentFile!!.mkdirs()
            ZipOutputStream(file.outputStream()).use { output ->
                for ((name, value) in entries) {
                    output.putNextEntry(ZipEntry(name)); output.write(value.toByteArray()); output.closeEntry()
                }
            }
        }
        try {
            val original = File(root, "game.zip")
            zip(original, linkedMapOf("GAME.EXE" to "original executable", "A.DAT" to "A", "B.DAT" to "B",
                "GONE.DAT" to "delete", "SAVES/KEEP.DAT" to "keep"))
            val originalBytes = original.readBytes()
            val saves = File(root, "saves").apply { mkdirs() }
            val legacy = File(saves, "game.pure.zip")
            zip(legacy, linkedMapOf("FILEMODS.DBP" to "REDIRECTFILE|B.DAT|A.DAT\r\nREDIRECTFILE|C.DAT|B.DAT\r\nDELETE|GONE.DAT\r\n",
                "SAVES/SLOT1.SAV" to "existing progress", "AUTOBOOT.DBP" to "frontend"))
            val legacyBytes = legacy.readBytes()
            val state = File(saves, "slot1.state").apply { writeText("old emulator state") }
            val drive = DosStagingStorage.prepare(original, saves, "fixture", AtomicBoolean(false), "game").directory
            val game = File(drive, "game")
            check(File(game, "B.DAT").readText() == "A")
            check(File(game, "C.DAT").readText() == "B")
            check(!File(game, "GONE.DAT").exists() && !File(game, "AUTOBOOT.DBP").exists())
            check(File(game, "SAVES/SLOT1.SAV").readText() == "existing progress")
            File(game, "SAVES/SLOT1.SAV").writeText("new progress")
            check(DosStagingStorage.prepare(original, saves, "fixture", AtomicBoolean(false), "game").directory == drive)
            check(File(game, "SAVES/SLOT1.SAV").readText() == "new progress")
            check(runCatching { DosStagingStorage.prepare(original, saves, "different", AtomicBoolean(false)) }.isFailure)
            check(original.readBytes().contentEquals(originalBytes) && legacy.readBytes().contentEquals(legacyBytes))
            check(state.readText() == "old emulator state")
            check(DosStagingStorage.path(game, "saves/keep.dat") == File(game, "SAVES/KEEP.DAT"))
            for (unsafe in listOf("../outside", "/outside", "C:/outside", "a/../../outside"))
                check(runCatching { DosStagingStorage.path(game, unsafe) }.isFailure)
            val bad = File(root, "bad.zip")
            zip(bad, mapOf("../outside" to "bad"))
            val failed = File(root, "failed")
            check(runCatching { DosStagingStorage.prepare(bad, failed, "bad", AtomicBoolean(false)) }.isFailure)
            check(!File(failed, "staging/drive").exists() && !File(root, "outside").exists())
            check(runCatching { DosStagingStorage.prepare(original, failed, "cancelled", AtomicBoolean(true)) }.isFailure)
            check(!File(failed, "staging/drive").exists())
            val launch = DosGameCatalog.Launch("game", mapOf("dosbox.conf" to
                "[cpu]\ncycles=12000\n[autoexec]\nmount c .\\eXoDOS\\\nc:\ncd GAME\nGAME.EXE\n"), false)
            val config = DosStagingLaunchConfig.write(File(root, "launch.conf"), DosStagingStorage.Drive(drive),
                launch, "dosbox.conf", emptyMap(), null, false, false).readText()
            check(config.contains("mount C \"${drive.absolutePath}\"") && config.contains("cd GAME"))
            check(config.contains("cycles=12000") && !config.contains("KAIRO:") && !config.contains("eXoDOS"))
            check(config.contains("core = dynamic"))
            for ((profile, expected) in listOf(
                "core=auto" to "dynamic", "core=normal" to "normal",
                "core=full" to "full", "core=simple" to "simple",
                "core=dynamic_nodhfpu" to "dynamic",
                "core=auto\ncputype=386_prefetch" to "auto",
                "core=normal\ncputype=386_prefetch" to "normal")) {
                val cpuLaunch = launch.copy(configs = mapOf("dosbox.conf" to
                    "[cpu]\n$profile\ncycles=12000\n[autoexec]\nmount c .\\eXoDOS\\\nc:\ncd GAME\nGAME.EXE\n"))
                val cpuConfig = DosStagingLaunchConfig.write(File(root, "cpu.conf"), DosStagingStorage.Drive(drive),
                    cpuLaunch, "dosbox.conf", emptyMap(), null, false, false).readText()
                check(cpuConfig.contains("core = $expected") && cpuConfig.contains("cycles=12000"))
                // The product's last CPU section overrides desktop cycle budgets.
                check(cpuConfig.substringAfterLast("[cpu]").lineSequence()
                    .first { it.trim().startsWith("cycles") }.trim() == "cycles = auto")
            }
        } finally { check(root.deleteRecursively()) }
    }
}
