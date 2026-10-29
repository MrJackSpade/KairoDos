package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import android.content.Context
import com.mrjackspade.kairo.frontend.UiText
import com.mrjackspade.kairo.frontend.text
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** One identity for a DOS game tree, whether stored as a ZIP or extracted files. */
object DosContentHash {
    data class ZipInspection(val contentId: String, val exodosSource: Boolean)
    private const val PREFIX = "sha256-dos-manifest-v1:"
    private val HEADER = "kairo-dos-manifest-v1\u0000".toByteArray(Charsets.UTF_8)

    /**
     * Reads a ZIP document, or a 7z one with [sevenZ], in place through its own file descriptor;
     * nothing is copied. Both give one manifest of their members, so the same game has one ID.
     */
    fun inspectZipDocument(context: Context, uri: Uri,
                           cancelled: AtomicBoolean, sevenZ: Boolean = false): ZipInspection =
        (context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open the game archive")).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                inspect({ context.text(it) }, if (sevenZ) DosZip.sevenZMembers(channel) else DosZip.members(channel), cancelled)
            }
        }

    fun zipDocument(context: Context, uri: Uri,
                    cancelled: AtomicBoolean): String =
        inspectZipDocument(context, uri, cancelled).contentId

    /** [text] puts a refusal the player reads in their language. */
    fun zip(text: (UiText) -> String, file: File, cancelled: AtomicBoolean): String =
        inspectZip(text, file, cancelled).contentId

    fun inspectZip(text: (UiText) -> String, file: File, cancelled: AtomicBoolean): ZipInspection =
        inspect(text, DosZip.members(file), cancelled)

    private fun inspect(text: (UiText) -> String, members: List<DosZip.Member>, cancelled: AtomicBoolean): ZipInspection {
        if (cancelled.get()) throw CancellationException("Cancelled")
        val entries = members.filterNot { it.directory }
        val exodosSource = entries.any { entry ->
            entry.size == 0L && entry.name.replace('\\', '/').substringAfterLast('/')
                .endsWith(".exo", true)
        }
        val files = entries.asSequence()
            .map { it to safePath(text, it.name) }.filterNot { it.second.endsWith(".exo", true) }
            .toList()
        require(files.isNotEmpty()) { text(UiText.res(R.string.zip_no_game_files)) }
        val roots = files.map { it.second.substringBefore('/') }.distinct()
        val strip = roots.singleOrNull()?.takeIf { root ->
            files.all { it.second.startsWith("$root/") }
        }?.plus("/") ?: ""
        val sorted = files.map { (item, path) -> item to asciiLower(path.removePrefix(strip)) }
            .sortedBy { it.second }
        require(sorted.map { it.second }.distinct().size == sorted.size) { text(UiText.res(R.string.duplicate_game_file)) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(HEADER)
        sorted.forEach { (item, path) ->
            if (cancelled.get()) throw CancellationException("Cancelled")
            require(item.size in 0..MAX_FILE_BYTES) { text(UiText.res(R.string.game_file_too_large)) }
            require(item.crc in 0..0xffffffffL) { text(UiText.res(R.string.zip_missing_checksum)) }
            frame(digest, path, item.size, item.crc)
        }
        return ZipInspection(PREFIX + hex(digest.digest()), exodosSource)
    }

    fun documents(context: Context, files: List<DocumentTreeWalker.FileEntry>,
                  cancelled: AtomicBoolean): String {
        require(files.isNotEmpty()) { context.getString(R.string.folder_empty) }
        val prefix = files.map { it.path.substringBefore('/') }.distinct().singleOrNull()
            ?.takeIf { root -> files.all { it.path.startsWith("$root/") } }?.plus("/") ?: ""
        val sorted = files.filterNot { it.path.endsWith(".exo", true) }
            .map { it to asciiLower(safePath({ context.text(it) }, it.path.removePrefix(prefix))) }
            .sortedBy { it.second }
        require(sorted.isNotEmpty()) { context.getString(R.string.no_game_files) }
        require(sorted.map { it.second }.distinct().size == sorted.size) { context.getString(R.string.duplicate_game_file) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(HEADER)
        sorted.forEach { (item, path) ->
            require(item.size in 0..MAX_FILE_BYTES) { context.getString(R.string.game_file_too_large) }
            val crc = CRC32()
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                stream({ context.text(it) }, crc, input, item.size, cancelled)
            } ?: error("Unable to open $path")
            frame(digest, path, item.size, crc.value)
        }
        return PREFIX + hex(digest.digest())
    }

    private fun frame(digest: MessageDigest, path: String, size: Long, crc: Long) {
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update("%08x".format(crc).toByteArray(Charsets.US_ASCII))
        digest.update(0)
    }

    private fun stream(text: (UiText) -> String, crc: CRC32, input: InputStream, size: Long,
                       cancelled: AtomicBoolean) {
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            if (cancelled.get()) throw CancellationException("Cancelled")
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= size) { text(UiText.res(R.string.game_file_changed_while_hashing)) }
            crc.update(buffer, 0, read)
        }
        require(count == size) { text(UiText.res(R.string.game_file_changed_while_hashing)) }
    }

    private fun safePath(text: (UiText) -> String, raw: String): String {
        val path = raw.replace('\\', '/')
        require(path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') &&
            !path.contains(':') && !path.contains('\u0000') &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            text(UiText.res(R.string.game_file_path_unsafe))
        }
        return path
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun asciiLower(value: String) = value.map { if (it in 'A'..'Z') it + 32 else it }
        .joinToString("")
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
}
