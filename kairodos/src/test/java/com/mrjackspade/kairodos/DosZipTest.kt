package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DosZipTest {
    /** A refusal as its resource, which the test reads without a Context. */
    private val text: (UiText) -> String = { (it as UiText.Res).id.toString() }
    @get:Rule val folder = TemporaryFolder()

    /** A game ZIP with one plain name, one CP437 name (0xFF, not UTF-8) and one UTF-8-flagged name. */
    private fun gameZip(): File {
        val file = folder.newFile("game.zip")
        val bytes = java.io.ByteArrayOutputStream()
        // CP437 names are written by hand: ZipOutputStream writes every name as UTF-8.
        ZipOutputStream(bytes, Charsets.UTF_8).use { zip ->
            for ((name, body) in listOf("GAME/GAME.EXE" to "exe", "GAME/ÉTÉ.TXT" to "utf8", "GAME/README" to "doc")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        file.writeBytes(bytes.toByteArray())
        return file
    }

    private fun cp437Zip(): File {
        val file = folder.newFile("dos.zip")
        val nameBytes = byteArrayOf('A'.code.toByte(), 0xFF.toByte(), '.'.code.toByte(), 'E'.code.toByte(),
            'X'.code.toByte(), 'E'.code.toByte())
        val body = "x".toByteArray()
        val crc = CRC32().apply { update(body) }.value
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        fun u32(v: Long) { u16((v and 0xffff).toInt()); u16((v shr 16 and 0xffff).toInt()) }
        u32(0x04034b50); u16(10); u16(0); u16(0); u16(0); u16(0); u32(crc); u32(body.size.toLong())
        u32(body.size.toLong()); u16(nameBytes.size); u16(0); out.write(nameBytes); out.write(body)
        val central = out.size()
        u32(0x02014b50); u16(20); u16(10); u16(0); u16(0); u16(0); u16(0); u32(crc); u32(body.size.toLong())
        u32(body.size.toLong()); u16(nameBytes.size); u16(0); u16(0); u16(0); u16(0); u32(0); u32(0)
        out.write(nameBytes)
        val end = out.size()
        u32(0x06054b50); u16(0); u16(0); u16(1); u16(1); u32((end - central).toLong()); u32(central.toLong()); u16(0)
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun throughChannel(file: File) = FileInputStream(file).channel.use { DosZip.members(it) }

    @Test
    fun aDescriptorReadsTheSameMembersAsAFile() {
        for (zip in listOf(gameZip(), cp437Zip())) {
            assertEquals(DosZip.members(zip), throughChannel(zip))
        }
    }

    @Test
    fun aCp437NameReadsAsTheDosCodePage() {
        assertEquals("A .EXE", throughChannel(cp437Zip()).single().name)
    }

    @Test
    fun theContentIdIsTheSameEitherWay() {
        val zip = gameZip()
        val fromFile = DosContentHash.zip(text, zip, AtomicBoolean(false))
        val members = throughChannel(zip)
        assertEquals(DosZip.members(zip), members)
        assertEquals(fromFile, DosContentHash.inspectZip(text, zip, AtomicBoolean(false)).contentId)
    }

    /**
     * The same game as [gameZip], as 7-Zip 24 wrote it (7z a -t7z -mx=9: LZMA2, a CRC for each file,
     * backslashed paths): commons-compress's own writer leaves the CRCs out, which 7-Zip never does.
     */
    private fun game7z(): File = folder.newFile("game.7z").also { file ->
        javaClass.getResourceAsStream("/game-7zip.7z")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test
    fun aGameIn7zHasTheSameMembersAndContentIdAsInAZip() {
        val sevenZ = game7z()
        val zip = gameZip()
        val fromZip = DosZip.members(zip).filterNot { it.directory }
        val from7z = FileInputStream(sevenZ).channel.use { DosZip.sevenZMembers(it) }.filterNot { it.directory }
        assertEquals(fromZip.map { Triple(it.name, it.size, it.crc) }.sortedBy { it.first },
            from7z.map { Triple(it.name, it.size, it.crc) }.sortedBy { it.first })
        val converted = folder.newFile("converted.zip")
        DosZip.sevenZToZip(sevenZ, converted, AtomicBoolean(false))
        assertEquals(DosContentHash.zip(text, zip, AtomicBoolean(false)),
            DosContentHash.zip(text, converted, AtomicBoolean(false)))
    }
}
