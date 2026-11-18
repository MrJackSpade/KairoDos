package com.mrjackspade.kairodos

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Collection Chamber setup's launch, from its shortcuts, batch files, menus and DOSBox configurations. */
class DosClickteamLaunchTest {
    private class Installed(vararg files: Pair<String, Any>) : DosClickteamLaunch.Files {
        private val byPath = files.associate { (path, content) ->
            path.lowercase(Locale.ROOT) to (content as? ByteArray ?: (content as String).toByteArray(Charsets.ISO_8859_1))
        }
        override fun read(path: String) = byPath[path.lowercase(Locale.ROOT)]
        override fun exists(path: String) = path.lowercase(Locale.ROOT) in byPath
    }

    private val dosbox = "MZ ... DOSBox version 0.74 ... IMGMOUNT ...".toByteArray()
    private val dosboxX = "MZ ... DOSBox-X 2025.02.01 ... IMGMOUNT ...".toByteArray()

    private fun entry(target: String, arguments: String = "") = DosClickteamLaunch.Entry(target, arguments)

    @Test fun aBatchFileStartingDosboxXGivesItsConfiguration() {
        val launch = DosClickteamLaunch.launch(Installed(
            "Run.bat" to "START \"\" Run.exe -conf dosbox-x.conf -noconsole -fastlaunch\r\n",
            "Run.exe" to dosboxX,
            "dosbox-x.conf" to "[sdl]\r\nfullscreen=true # comment\r\n\r\n[cpu]\r\ncycles=max\r\n[autoexec]\r\n# Lines here run\r\nmount C \"HDD\"\r\nC:\r\nGAME\r\n\r\n"),
            listOf(entry("Run.bat")))!!
        assertEquals("", launch.folder)
        assertEquals(mapOf("dosbox.conf" to "[sdl]\nfullscreen = true\n[cpu]\ncycles = max\n[autoexec]\n# Lines here run\nmount C \"HDD\"\nC:\nGAME\n"),
            launch.configs)
    }

    @Test fun anAutoPlayMenuGivesEachWayItStartsDosbox() {
        val menu = autoplay("%ROOT%\\TC1\\Run.exe" to "-conf T1.conf -noconsole", "%ROOT%\\TC1\\Run-T2.bat" to null,
            "%ROOT%\\DATA\\cover.jpg" to null, "%ROOT%\\MAN\\Manual.pdf" to null)
        val launch = DosClickteamLaunch.launch(Installed("Start-TC1.exe" to menu, "TC1/Run.exe" to dosbox,
            "TC1/Run-T2.bat" to "set __COMPAT_LAYER=highdpiaware\r\nstart \"\" Run.exe -conf T2.conf -noconsole\r\n",
            "TC1/T1.conf" to "[autoexec]\nT1\n", "TC1/T2.conf" to "[autoexec]\nT2\n"), listOf(entry("Start-TC1.exe")))!!
        assertEquals("TC1", launch.folder)
        assertEquals(mapOf("T1.conf" to "[autoexec]\nT1\n", "T2.conf" to "[autoexec]\nT2\n"), launch.configs)
    }

    @Test fun aKeyedMenuRunsDosboxWithItsDefaultConfiguration() {
        val menu = keyed("P0.O2.S18.RO0.1" to "DOS\\Run.exe", "P0.O2.S18.RO0.2" to "", "P0.O3.S18.RO0.1" to "AMI\\Run.bat")
        val launch = DosClickteamLaunch.launch(Installed("Start.exe" to menu, "DOS/Run.exe" to dosbox,
            "DOS/dosbox.conf" to "[autoexec]\nGAME\n", "AMI/Run.bat" to "fs-uae --fullscreen GAME.fs-uae\r\n"),
            listOf(entry("Start.exe")))!!
        assertEquals("DOS", launch.folder)
        assertEquals(mapOf("dosbox.conf" to "[autoexec]\nGAME\n"), launch.configs)
    }

    @Test fun aMissingConfigurationIsReplacedByTheDefaultAsDosboxDoes() {
        val launch = DosClickteamLaunch.launch(Installed("Run.bat" to "start \"\" Run.exe -conf GUS.conf\r\n",
            "Run.exe" to dosbox, "dosbox.conf" to "[autoexec]\nGAME\n"), listOf(entry("Run.bat")))!!
        assertEquals(mapOf("dosbox.conf" to "[autoexec]\nGAME\n"), launch.configs)
    }

    @Test fun configurationsMergeInOrderAndCommandsRunAfterTheAutoexec() {
        val launch = DosClickteamLaunch.launch(Installed("Run.exe" to dosbox,
            "base.conf" to "[cpu]\ncycles=3000\ncore=normal\n[autoexec]\nmount C HDD\n",
            "game.conf" to "[cpu]\ncycles=max\n[sblaster]\nirq=5\n[autoexec]\nC:\nGAME\n"),
            listOf(entry("Run.exe", "-conf base.conf -conf game.conf -c exit")))!!
        assertEquals(mapOf("dosbox.conf" to "[cpu]\ncycles = max\ncore = normal\n[sblaster]\nirq = 5\n[autoexec]\nmount C HDD\nC:\nGAME\nexit\n"),
            launch.configs)
    }

    @Test fun aSetupThatDoesNotStartDosboxHasNoLaunch() {
        assertNull(DosClickteamLaunch.launch(Installed("scummvm.exe" to "MZ ScummVM".toByteArray(),
            "SETUP.bat" to "scummvm.exe -c rama.ini\r\n"), listOf(entry("scummvm.exe", "-c rama.ini rama"), entry("SETUP.bat"))))
    }

    @Test fun anInstalledArchiveRecordsItsInstallerAsItsComment() {
        val installed = DosClickteam.Installed("sha256-dos-clickteam-v1:abc", listOf(entry("Run.bat"), entry("Start.exe", "-x")))
        val zip = Files.createTempFile("installed", ".zip").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.setComment(DosClickteam.comment(installed))
                out.putNextEntry(ZipEntry("Run.bat")); out.write("x".toByteArray()); out.closeEntry()
            }
            val comment = FileChannel.open(zip.toPath()).use(DosZip::comment)
            assertEquals(installed, DosClickteam.installed(comment))
            assertNull(DosClickteam.installed("Made by some archiver"))
        } finally { zip.delete() }
    }

    /** An AutoPlay Menu Designer program: its resource header, then property records; a run action is property 1 and 2. */
    private fun autoplay(vararg actions: Pair<String, String?>): ByteArray = ByteArrayOutputStream().apply {
        write("MZ program".toByteArray())
        write("AMD50_RESOURCES_HEADER".toByteArray()); write(0)
        for ((target, arguments) in actions) {
            write(property(1, target))
            if (arguments != null) write(property(2, arguments))
        }
    }.toByteArray()

    private fun property(id: Int, text: String): ByteArray {
        val value = (text + "\u0000").toByteArray(Charsets.UTF_16LE)
        return ByteBuffer.allocate(18 + value.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(14).putInt(18 + value.size).putShort(0).putInt(value.size).putInt(id).put(value).array()
    }

    /** A menu of keyed property records. */
    private fun keyed(vararg values: Pair<String, String>): ByteArray = ByteArrayOutputStream().apply {
        write("MZ program".toByteArray())
        for ((key, text) in values) {
            val name = (key + "\u0000").toByteArray(Charsets.US_ASCII)
            val value = (text + "\u0000").toByteArray(Charsets.ISO_8859_1)
            write(ByteBuffer.allocate(14 + name.size + value.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(14).putInt(14 + name.size + value.size).putShort(name.size.toShort()).putInt(value.size)
                .put(name).put(value).array())
        }
    }.toByteArray()
}
