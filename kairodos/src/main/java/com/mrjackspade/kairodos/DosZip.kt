package com.mrjackspade.kairodos

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.zip.ZipFile

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
}
