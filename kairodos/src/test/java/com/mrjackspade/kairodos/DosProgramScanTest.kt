package com.mrjackspade.kairodos

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DosProgramScan reads a program in overlapping pieces: what straddles two pieces is found once. */
class DosProgramScanTest {
    private val piece = 1 shl 20

    /** An AutoPlay run action: property 1, its %ROOT% path, and property 2, its arguments. */
    private fun autoplay(path: String, arguments: String): ByteArray {
        fun record(property: Int, value: String): ByteArray {
            val text = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
            return ByteBuffer.allocate(18 + text.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(14).putInt(18 + text.size).putShort(0).putInt(text.size).putInt(property).put(text).array()
        }
        return record(1, "%ROOT%\\$path") + record(2, arguments)
    }

    /** [parts] at those offsets of a program of [size] zero bytes. */
    private fun program(size: Int, vararg parts: Pair<Int, ByteArray>): ByteArray {
        val bytes = ByteArray(size)
        for ((at, part) in parts) part.copyInto(bytes, at)
        return bytes
    }

    /** A stream that hands out a few bytes per read, as a decompressing one may. */
    private fun trickle(bytes: ByteArray): InputStream = object : InputStream() {
        var at = 0
        override fun read() = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return -1
            val count = minOf(length, 7, bytes.size - at)
            bytes.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }
    }

    @Test fun markersAcrossPiecesMakeADosboxBuild() {
        val boundary = piece
        val bytes = program(3 * piece,
            boundary - 3 to "DOSBox-X".toByteArray(), 2 * piece + 5 to "IMGMOUNT".toByteArray())
        val scan = DosProgramScan.of(trickle(bytes))
        assertTrue(scan.dosbox)
        assertTrue(scan.dosboxX)
    }

    @Test fun oneMarkerIsNoDosboxBuild() {
        val scan = DosProgramScan.of(program(2 * piece, piece - 2 to "DOSBox".toByteArray()).inputStream())
        assertFalse(scan.dosbox)
    }

    @Test fun anAutoplayActionAcrossPiecesIsFoundOnce() {
        val header = "AMD50_RESOURCES_HEADER".toByteArray()
        val action = autoplay("GAME\\RUN.BAT", "-fast")
        val bytes = program(2 * piece + 100, 10 to header, piece - 20 to action)
        assertEquals(listOf("GAME\\RUN.BAT" to "-fast"), DosProgramScan.of(trickle(bytes)).menuActions())
    }

    @Test fun anAutoplayActionBeforeTheHeaderIsNotOne() {
        val action = autoplay("GAME\\RUN.BAT", "")
        val bytes = program(piece, 10 to action, 4000 to "AMD50_RESOURCES_HEADER".toByteArray())
        assertEquals(emptyList<Pair<String, String>>(), DosProgramScan.of(bytes.inputStream()).menuActions())
    }

    @Test fun keyedActionsAcrossPieces() {
        fun keyed(key: String, value: String): ByteArray {
            val out = ByteArrayOutputStream()
            val keyBytes = (key + "\u0000").toByteArray(Charsets.US_ASCII)
            val valueBytes = value.toByteArray(Charsets.US_ASCII) + ByteArray(2)
            out.write(ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putInt(14)
                .putInt(14 + keyBytes.size + valueBytes.size).putShort(keyBytes.size.toShort()).putInt(valueBytes.size).array())
            out.write(keyBytes)
            out.write(valueBytes)
            return out.toByteArray()
        }
        val file = keyed("P1.O2.S0.RO0.1", "RUN.EXE")
        val arguments = keyed("P1.O2.S0.RO0.2", "-conf a.conf")
        val bytes = program(2 * piece, piece - 9 to file, piece + 400 to arguments)
        assertEquals(listOf("RUN.EXE" to "-conf a.conf"), DosProgramScan.of(trickle(bytes)).menuActions())
    }
}
