package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** One identity for a DOS game tree, whether stored as a ZIP or extracted files. */
object DosContentHash {
    data class ZipInspection(val contentId: String, val exodosSource: Boolean)
    private const val PREFIX = "sha256-dos-manifest-v1:"
    private val HEADER = "kairo-dos-manifest-v1\u0000".toByteArray(Charsets.UTF_8)

    /** Local document providers expose seekable descriptors; others use the copy fallback. */
    fun inspectZipDocument(resolver: ContentResolver, uri: Uri,
                           cancelled: AtomicBoolean): ZipInspection? = try {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            inspectZip(File("/proc/self/fd/${descriptor.fd}"), cancelled)
        }
    } catch (_: Exception) { null }

    fun zipDocument(resolver: ContentResolver, uri: Uri,
                    cancelled: AtomicBoolean): String? =
        inspectZipDocument(resolver, uri, cancelled)?.contentId

    fun zip(file: File, cancelled: AtomicBoolean): String =
        inspectZip(file, cancelled).contentId

    fun inspectZip(file: File, cancelled: AtomicBoolean): ZipInspection = ZipFile(file).use { archive ->
        if (cancelled.get()) throw CancellationException("Cancelled")
        val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
        val exodosSource = entries.any { entry ->
            entry.size == 0L && entry.name.replace('\\', '/').substringAfterLast('/')
                .endsWith(".exo", true)
        }
        val members = entries.asSequence()
            .map { it to safePath(it.name) }.filterNot { it.second.endsWith(".exo", true) }
            .toList()
        require(members.isNotEmpty()) { "No game files in ZIP" }
        val roots = members.map { it.second.substringBefore('/') }.distinct()
        val strip = roots.singleOrNull()?.takeIf { root ->
            members.all { it.second.startsWith("$root/") }
        }?.plus("/") ?: ""
        val sorted = members.map { (item, path) -> item to asciiLower(path.removePrefix(strip)) }
            .sortedBy { it.second }
        require(sorted.map { it.second }.distinct().size == sorted.size) { "Duplicate game file" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(HEADER)
        sorted.forEach { (item, path) ->
            if (cancelled.get()) throw CancellationException("Cancelled")
            require(item.size in 0..MAX_FILE_BYTES) { "Game file is too large" }
            require(item.crc in 0..0xffffffffL) { "Missing ZIP member checksum" }
            frame(digest, path, item.size, item.crc)
        }
        ZipInspection(PREFIX + hex(digest.digest()), exodosSource)
    }

    fun documents(resolver: ContentResolver, files: List<DocumentTreeWalker.FileEntry>,
                  cancelled: AtomicBoolean): String {
        require(files.isNotEmpty()) { "Empty game folder" }
        val prefix = files.map { it.path.substringBefore('/') }.distinct().singleOrNull()
            ?.takeIf { root -> files.all { it.path.startsWith("$root/") } }?.plus("/") ?: ""
        val sorted = files.filterNot { it.path.endsWith(".exo", true) }
            .map { it to asciiLower(safePath(it.path.removePrefix(prefix))) }
            .sortedBy { it.second }
        require(sorted.isNotEmpty()) { "No game files" }
        require(sorted.map { it.second }.distinct().size == sorted.size) { "Duplicate game file" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(HEADER)
        sorted.forEach { (item, path) ->
            require(item.size in 0..MAX_FILE_BYTES) { "Game file is too large" }
            val crc = CRC32()
            resolver.openInputStream(item.uri)?.use { input ->
                stream(crc, input, item.size, cancelled)
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

    private fun stream(crc: CRC32, input: InputStream, size: Long,
                       cancelled: AtomicBoolean) {
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            if (cancelled.get()) throw CancellationException("Cancelled")
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= size) { "Game file changed while hashing" }
            crc.update(buffer, 0, read)
        }
        require(count == size) { "Game file changed while hashing" }
    }

    private fun safePath(raw: String): String {
        val path = raw.replace('\\', '/')
        require(path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') &&
            !path.contains(':') && !path.contains('\u0000') &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Unsafe game file path"
        }
        return path
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun asciiLower(value: String) = value.map { if (it in 'A'..'Z') it + 32 else it }
        .joinToString("")
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
}
