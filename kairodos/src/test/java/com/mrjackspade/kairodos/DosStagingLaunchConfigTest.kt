package com.mrjackspade.kairodos

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch configuration a game's catalog config becomes. */
class DosStagingLaunchConfigTest {
    private val root: File = Files.createTempDirectory("dos-launch").toFile()
    private val base = File(root, "base")
    private val overlay = File(root, "overlay")

    @After fun cleanUp() { root.deleteRecursively() }

    private val machine = DosStagingLaunchConfig.Machine("auto", false, false, null, false, false, "sf.sf2")

    private fun file(path: String, text: String = "") = File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun compose(launch: DosLaunch) = DosStagingLaunchConfig.compose({ id, _ -> "message $id" }, File(root, "launch.conf"),
        DosStagingStorage.Drive(base, null, overlay), launch, "dosbox.conf", emptyMap(), mapOf(base to overlay), null, machine)

    /** A line's host path references (`"kairo-host:<n>"`) spelled as the paths they name, relative to the base drive. */
    private fun resolved(composed: DosStagingLaunchConfig.Composed) = composed.lines.filter { it.isNotBlank() }.map { line ->
        Regex("\"kairo-host:(\\d+)\"").replace(line) { match ->
            val path = File(composed.hostPaths[match.groupValues[1].toInt()])
            "<" + (if (path.startsWith(overlay)) "overlay/" + path.relativeTo(overlay).invariantSeparatorsPath
                else path.relativeTo(base).invariantSeparatorsPath) + ">"
        }
    }

    @Test fun aCollectionChamberSetupMountsItsFoldersFromItsLaunchFolder() {
        file("DD1/HDD/GAME/GAME.EXE")
        file("DD1/CD/track01.cue", "FILE \"track01.bin\" BINARY")
        file("AMIGA/game.adf")
        val config = """
            [cpu]
            core=auto
            cycles=max

            [autoexec]
            mount C "HDD"
            #imgmount D "CD\ .iso" -t iso
            :MAIN
            CHOICE /N /C:12 /M "Enter your choice:"
            IF ERRORLEVEL 2 GOTO CDGAME
            C:
            cd GAME
            GAME.EXE
            GOTO End
            :CDGAME
            imgmount D "CD\track01.CUE" -t iso
            D:
            SETUP
            C:
            cd \
            :End
            exit
        """.trimIndent()
        val lines = resolved(compose(DosLaunch("DD1", mapOf("dosbox.conf" to config), false)))
        val autoexec = lines.subList(lines.indexOf("[autoexec]"), lines.indexOfFirst { it.startsWith("[sdl]") })
        assertEquals(listOf("[autoexec]", "@echo off",
            "mount C <DD1/HDD>", "mount C <overlay/DD1/HDD> -t overlay", "C:",
            "#imgmount D \"CD\\ .iso\" -t iso",
            ":MAIN", "CHOICE /N /C:12 /M \"Enter your choice:\"", "IF ERRORLEVEL 2 GOTO CDGAME",
            "C:", "cd GAME", "GAME.EXE", "GOTO End", ":CDGAME",
            "imgmount D <DD1/CD/track01.cue> -t iso", "D:", "SETUP", "C:", "cd \\", ":End", "exit"), autoexec)
        assertTrue(lines.takeWhile { it != "[autoexec]" }.joinToString("\n").contains("cycles=max"))
    }

    @Test fun anExodosConfigStillMountsItsGameFolder() {
        file("Creature/CRSHOCK.EXE")
        file("Creature/cd/CRSHOCK_1.cue")
        val config = "[autoexec]\nmount C \".\\eXoDOS\\Creature\"\nimgmount D \".\\eXoDOS\\Creature\\cd\\CRSHOCK_1.cue\" -t cdrom\nc:\nCRSHOCK.EXE\nexit\n"
        val lines = resolved(compose(DosLaunch("Creature", mapOf("dosbox.conf" to config), false)))
        val autoexec = lines.subList(lines.indexOf("[autoexec]"), lines.indexOfFirst { it.startsWith("[sdl]") })
        assertEquals(listOf("[autoexec]", "@echo off", "mount C <Creature>", "mount C <overlay/Creature> -t overlay", "C:",
            "imgmount D <Creature/cd/CRSHOCK_1.cue> -t cdrom", "c:", "CRSHOCK.EXE", "exit"), autoexec)
    }
}
