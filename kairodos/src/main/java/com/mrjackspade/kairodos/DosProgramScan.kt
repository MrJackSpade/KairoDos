// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import java.io.InputStream
import java.nio.charset.Charset

/**
 * What a Collection Chamber setup's program says about how it starts (DosClickteamLaunch): whether
 * it is a DOSBox build, and the run actions of a menu program. The program is read in overlapping
 * pieces, so one of any size (a native port's executable runs to hundreds of megabytes) is never
 * held whole: every position is looked at once, with what follows it up to [OVERLAP] bytes, which
 * holds the longest marker and the longest record with the one after it.
 */
internal class DosProgramScan private constructor() {
    private var named = false
    private var imgmount = false

    /** DOSBox-X, whose own configuration name comes first (DosClickteamLaunch.run). */
    var dosboxX = false
        private set

    /** A DOSBox build (0.74, SVN-Daum, ECE, DOSBox-X): its own name and its IMGMOUNT command. */
    val dosbox get() = named && imgmount

    private var autoplay = false
    private val autoplayActions = ArrayList<Pair<String, String>>()
    private val keyedValues = LinkedHashMap<String, String>()

    /** A menu program's run actions: each file (relative to the menu's folder) and its arguments. */
    fun menuActions(): List<Pair<String, String>> = autoplayActions.toList().ifEmpty {
        keyedValues.filterKeys { it.endsWith(".1") }.filterValues { it.isNotEmpty() }
            .map { (key, file) -> file to keyedValues[key.dropLast(1) + "2"].orEmpty() }
    }

    /** Everything that starts at [at] in [bytes], whose first [size] bytes are read. */
    private fun look(bytes: ByteArray, at: Int, size: Int) {
        if (!named && matches(bytes, at, size, DOSBOX)) named = true
        if (!imgmount && matches(bytes, at, size, IMGMOUNT)) imgmount = true
        if (!dosboxX && matches(bytes, at, size, DOSBOX_X)) dosboxX = true
        if (!autoplay && matches(bytes, at, size, AUTOPLAY)) autoplay = true
        if (autoplay) autoplayAction(bytes, at, size)
        keyedValue(bytes, at, size)
    }

    /**
     * AutoPlay Menu Designer's: after its resource header, property records (u32 kind 14, u32 total,
     * u16, u32 value length, u32 property, value); a run action is property 1, a UTF-16 path under
     * %ROOT%, and the property 2 after it, its arguments.
     */
    private fun autoplayAction(b: ByteArray, at: Int, size: Int) {
        if (at + 18 > size || int(b, at) != 14 || short(b, at + 8) != 0 || int(b, at + 14) != 1) return
        val length = int(b, at + 10)
        if (length !in 2..4096 || length % 2 != 0 || int(b, at + 4) != 18 + length || at + 18 + length > size ||
            b[at + 16 + length].toInt() != 0 || b[at + 17 + length].toInt() != 0) return
        val target = String(b, at + 18, length - 2, Charsets.UTF_16LE)
        if (!target.startsWith("%ROOT%")) return
        val next = at + 18 + length
        val arguments = if (next + 18 <= size && int(b, next) == 14 && int(b, next + 14) == 2) {
            val argumentsLength = int(b, next + 10)
            if (argumentsLength in 2..4096 && int(b, next + 4) == 18 + argumentsLength && next + 18 + argumentsLength <= size)
                String(b, next + 18, argumentsLength - 2, Charsets.UTF_16LE) else ""
        } else ""
        autoplayActions += target.removePrefix("%ROOT%").trimStart('\\', '/') to arguments
    }

    /**
     * A menu whose properties are keyed records (u32 kind 14, u32 total, u16 key length, u32 value
     * length, key, value): a button's run action is `P<page>.O<object>.S<n>.RO<n>.1`, its file, and `.2`, its arguments.
     */
    private fun keyedValue(b: ByteArray, at: Int, size: Int) {
        if (at + 14 > size || int(b, at) != 14) return
        val total = int(b, at + 4)
        val keyLength = short(b, at + 8)
        val valueLength = int(b, at + 10)
        if (keyLength !in 2..64 || valueLength !in 0..4096 || total != 14 + keyLength + valueLength || at + total > size ||
            b[at + 13 + keyLength].toInt() != 0) return
        val key = String(b, at + 14, keyLength - 1, Charsets.US_ASCII)
        if (!MENU_KEY.matches(key)) return
        var end = at + total
        while (end > at + 14 + keyLength && b[end - 1].toInt() == 0) end--
        keyedValues[key] = String(b, at + 14 + keyLength, end - at - 14 - keyLength, TEXT)
    }

    companion object {
        private val TEXT: Charset = Charset.forName("windows-1252")
        private val DOSBOX = "DOSBox".toByteArray(Charsets.US_ASCII)
        private val IMGMOUNT = "IMGMOUNT".toByteArray(Charsets.US_ASCII)
        private val DOSBOX_X = "DOSBox-X".toByteArray(Charsets.US_ASCII)
        private val AUTOPLAY = "AMD50_RESOURCES_HEADER".toByteArray(Charsets.US_ASCII)
        private val MENU_KEY = Regex("P\\d+\\.O\\d+\\.S\\d+\\.RO\\d+\\.([12])")
        private const val PIECE = 1 shl 20

        /** Bytes after a position that its marker or record may take: an AutoPlay record and the one after it, 2 × (18 + 4096). */
        const val OVERLAP = 1 shl 14

        /** Scans [input] to its end, and closes it. */
        fun of(input: InputStream): DosProgramScan = input.use(::scan)

        private fun scan(stream: InputStream): DosProgramScan {
            val scan = DosProgramScan()
            val buffer = ByteArray(PIECE + OVERLAP)
            var size = 0
            var end = false
            while (true) {
                while (!end && size < buffer.size) {
                    val read = stream.read(buffer, size, buffer.size - size)
                    if (read < 0) end = true else size += read
                }
                // Each position once: those with OVERLAP bytes after them, or every one left at the end.
                val looked = if (end) size else size - OVERLAP
                for (at in 0 until looked) scan.look(buffer, at, size)
                if (end) return scan
                System.arraycopy(buffer, looked, buffer, 0, size - looked)
                size -= looked
            }
        }

        private fun matches(bytes: ByteArray, at: Int, size: Int, pattern: ByteArray): Boolean {
            if (at + pattern.size > size || bytes[at] != pattern[0]) return false
            for (index in 1 until pattern.size) if (bytes[at + index] != pattern[index]) return false
            return true
        }

        private fun int(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        private fun short(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }
}
