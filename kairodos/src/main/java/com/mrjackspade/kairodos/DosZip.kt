package com.mrjackspade.kairodos

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Opens a game ZIP with the ZIP specification's name encoding: UTF-8 when an entry is flagged
 * as UTF-8, otherwise the DOS code page (CP437). The catalog generator reads names the same way,
 * so content IDs match, and DOS names using bytes that are not UTF-8 (such as 0xFF) still open.
 */
object DosZip {
    private val DOS_NAMES: Charset = Charset.forName("IBM437")

    /** A member as its central directory records it. */
    data class Member(val name: String, val size: Long, val crc: Long, val directory: Boolean)

    fun open(file: File): ZipFile = ZipFile(file, ZipFile.OPEN_READ, DOS_NAMES)

    fun members(file: File): List<Member> = open(file).use { zip ->
        zip.entries().asSequence().map { Member(it.name, it.size, it.crc, it.isDirectory) }.toList()
    }

    /**
     * The members of the ZIP open on [channel], read in place. A document from a storage provider
     * is read through its own descriptor: opening its /proc/self/fd path again is refused on
     * removable storage. Names are read as [open] reads them; Unicode extra fields are ignored,
     * as java.util.zip ignores them.
     */
    fun members(channel: FileChannel): List<Member> =
        org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(channel)
            .setCharset(DOS_NAMES).setUseUnicodeExtraFields(false).get().use { zip ->
                zip.entries.asSequence().map { Member(it.name, it.size, it.crc, it.isDirectory) }.toList()
            }

    /**
     * The members of the 7z archive open on [channel], as its headers record them, read in place. A
     * member without a CRC gets -1, which the content hash refuses (it needs one, as a ZIP has).
     */
    fun sevenZMembers(channel: FileChannel): List<Member> =
        SevenZFile.builder().setSeekableByteChannel(channel).get().use { archive ->
            archive.entries.map { Member(it.name, it.size, if (it.hasCrc) it.crcValue else -1L, it.isDirectory) }
        }

    /**
     * Writes the 7z archive [sevenZ] as the ZIP [zip], member by member, for DOSBox, which mounts
     * ZIPs. The ZIP's names are UTF-8 and flagged so, which [open] reads as written, so the game's
     * content ID (DosContentHash) is the same from either.
     */
    fun sevenZToZip(sevenZ: File, zip: File, cancelled: AtomicBoolean) {
        SevenZFile.builder().setFile(sevenZ).get().use { input ->
            ZipOutputStream(zip.outputStream()).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (cancelled.get()) throw CancellationException("Cancelled")
                    if (entry.isDirectory) continue
                    output.putNextEntry(ZipEntry(entry.name))
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.closeEntry()
                }
            }
        }
    }
}
