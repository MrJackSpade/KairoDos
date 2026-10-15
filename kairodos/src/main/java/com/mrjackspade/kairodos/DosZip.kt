package com.mrjackspade.kairodos

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * Opens a game ZIP with the ZIP specification's name encoding: UTF-8 when an entry is flagged
 * as UTF-8, otherwise the DOS code page (CP437). The catalog generator reads names the same way,
 * so content IDs match, and DOS names using bytes that are not UTF-8 (such as 0xFF) still open.
 */
object DosZip {
    private val DOS_NAMES: Charset = Charset.forName("IBM437")

    fun open(file: File): ZipFile = ZipFile(file, ZipFile.OPEN_READ, DOS_NAMES)
}
