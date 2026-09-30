// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Staging mounts native folders. Materialize each game's writable DOS drive
 * once, outside the evictable archive cache. Original media and Pure saves are
 * never modified; an interrupted import cannot become the live drive. */
internal object DosStagingStorage {
    data class Drive(val directory: File, val media: File? = null)
    private const val MAX_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_FILES = 100_000

    fun prepare(archive: File, saveDir: File, contentId: String, cancelled: AtomicBoolean,
                gameFolder: String? = null,
                progress: (String) -> Unit = {}): Drive {
        val staging = File(saveDir, "staging").apply { mkdirs() }
        val drive = File(staging, "drive")
        val marker = File(staging, "drive-v1.txt")
        if (drive.isDirectory) {
            require(marker.isFile && marker.readText() == contentId) {
                "This game's source changed. Its existing writable game files were preserved."
            }
            return Drive(drive, File(drive, archive.name).takeIf { !archive.extension.equals("zip", true) })
        }
        val pending = File(staging, "drive-pending")
        require(pending.canonicalFile.parentFile == staging.canonicalFile)
        if (pending.exists()) require(pending.deleteRecursively()) { "Could not clear interrupted game preparation" }
        require(pending.mkdirs()) { "Could not create writable game folder" }
        try {
            if (archive.extension.equals("zip", true)) {
                progress("Preparing writable game files…")
                extract(archive, pending, cancelled, progress)
                // Keep one layout for all catalog launch variants. A parent
                // mount and a game-folder mount must share the same saves.
                if (!gameFolder.isNullOrEmpty()) {
                    require('/' !in gameFolder && '\\' !in gameFolder)
                    val wrapper = path(pending, gameFolder)
                    if (!wrapper.isDirectory) {
                        val children = pending.listFiles().orEmpty()
                        require(wrapper.mkdirs()) { "Could not create DOS game folder" }
                        for (child in children) require(child.renameTo(File(wrapper, child.name))) {
                            "Could not prepare DOS game folder"
                        }
                    }
                }
                importPureSave(archive, saveDir, pending, cancelled, progress)
            } else {
                require(!cancelled.get()) { "Cancelled" }
                copy(archive, File(pending, archive.name), cancelled)
            }
            require(!cancelled.get()) { "Cancelled" }
            // Commit the identity before publishing the directory. A marker
            // without a directory is harmless and can be retried.
            marker.writeText(contentId)
            require(pending.renameTo(drive)) { "Could not publish writable game folder" }
        } finally {
            if (pending.exists()) pending.deleteRecursively()
        }
        return Drive(drive, File(drive, archive.name).takeIf { !archive.extension.equals("zip", true) })
    }

    /** Resolve DOS's case-insensitive path without creating a second directory
     * when an archive uses several spellings of the same parent folder. */
    fun path(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/').trimEnd('/')
        require(!normalized.startsWith('/') && '\u0000' !in normalized && ':' !in normalized) {
            "Unsafe DOS archive path"
        }
        if (normalized.isEmpty()) return root
        val segments = normalized.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe DOS archive path" }
        var result = root
        for (segment in segments) {
            result = result.listFiles()?.firstOrNull { it.name.equals(segment, true) } ?: File(result, segment)
        }
        require(result.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Unsafe DOS archive path" }
        return result
    }

    private fun extract(archive: File, root: File, cancelled: AtomicBoolean,
                        progress: (String) -> Unit, skipMods: Boolean = false) {
        ZipFile(archive).use { zip ->
            require(zip.size() <= MAX_FILES) { "DOS archive contains too many files" }
            val names = HashMap<String, Boolean>()
            var total = 0L
            var reported = 0L
            val buffer = ByteArray(65536)
            for (entry in zip.entries().asSequence()) {
                require(!cancelled.get()) { "Cancelled" }
                if (skipMods && entry.name.equals("FILEMODS.DBP", true)) continue
                val name = entry.name.replace('\\', '/').trimEnd('/')
                val previous = names.put(name.lowercase(Locale.ROOT), entry.isDirectory)
                require(previous == null || (previous && entry.isDirectory)) {
                    "Ambiguous duplicate DOS archive entry: $name"
                }
                val target = path(root, name)
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "Could not create game directory" }
                    continue
                }
                require(entry.size >= 0 && entry.size <= MAX_BYTES - total) { "DOS archive is too large" }
                require(root.usableSpace > entry.size + 16L * 1024 * 1024) { "Not enough storage for writable game files" }
                require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Could not create game directory" }
                val crc = CRC32()
                var count = 0L
                zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    while (true) {
                        require(!cancelled.get()) { "Cancelled" }
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= entry.size && total + count <= MAX_BYTES) { "Invalid DOS archive size" }
                        crc.update(buffer, 0, size)
                        output.write(buffer, 0, size)
                    }
                } }
                require(count == entry.size && crc.value == entry.crc) { "DOS archive verification failed: $name" }
                if (entry.time > 0) target.setLastModified(entry.time)
                total += count
                if (total - reported >= 64L * 1024 * 1024) {
                    progress("Preparing writable game files: ${total / (1024 * 1024)} MiB")
                    reported = total
                }
            }
        }
    }

    private fun importPureSave(archive: File, saveDir: File, root: File,
                               cancelled: AtomicBoolean, progress: (String) -> Unit) {
        val saves = saveDir.listFiles().orEmpty().filter {
            it.isFile && (it.name.endsWith(".pure.zip", true) || it.name.endsWith(".sav.zip", true))
        }
        if (saves.isEmpty()) return
        val exact = saves.firstOrNull {
            it.name.equals(archive.nameWithoutExtension + ".pure.zip", true) ||
                it.name.equals(archive.nameWithoutExtension + ".sav.zip", true)
        }
        val legacy = exact ?: saves.singleOrNull()
            ?: error("Several legacy game saves were found. They were preserved; the import needs a save selection.")
        progress("Importing existing game saves…")
        // Pure's overlay paths are relative to its mounted C drive. Its
        // '-parent' archive retains the eXoDOS wrapper, other launchers strip it.
        val top = root.listFiles().orEmpty()
        val base = if (!legacy.name.substringBeforeLast(".zip").substringBeforeLast('.').endsWith("-parent") &&
            top.size == 1 && top[0].isDirectory) top[0] else root
        ZipFile(legacy).use { zip ->
            val mods = zip.entries().asSequence().firstOrNull { it.name.equals("FILEMODS.DBP", true) }
                ?.let { entry ->
                    require(entry.size in 0..(4L * 1024 * 1024)) { "Invalid legacy file changes" }
                    zip.getInputStream(entry).bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
                }.orEmpty().lineSequence().filter { it.isNotBlank() }.map { it.trimEnd().split('|') }.toList()
            // Snapshot every rename source before writing any destination.
            // Otherwise a rename chain could overwrite its next source.
            val redirects = mods.filter { it[0] != "DELETE" }
            val snapshots = File(root.parentFile, "legacy-rename-snapshots")
            require(!snapshots.exists() || snapshots.deleteRecursively())
            require(snapshots.mkdirs())
            try {
            for ((index, mod) in redirects.withIndex()) {
                require(mod.size == 3 && mod[0] in setOf("REDIRECTFILE", "REDIRECTDIR")) { "Unsupported legacy file change" }
                val from = path(base, mod[2])
                val to = path(base, mod[1])
                require(from.exists()) { "Legacy rename source was not found: ${mod[2]}" }
                require(mod[1].isNotEmpty() && mod[2].isNotEmpty() &&
                    from.isDirectory == (mod[0] == "REDIRECTDIR")) { "Invalid legacy rename" }
                require(!to.canonicalPath.startsWith(from.canonicalPath + File.separator)) { "Invalid legacy directory rename" }
                copy(from, File(snapshots, index.toString()), cancelled)
            }
            for (mod in mods.filter { it[0] == "DELETE" }) {
                require(mod.size == 2) { "Invalid legacy deletion" }
                require(mod[1].isNotEmpty()) { "Invalid legacy deletion" }
                val target = path(base, mod[1])
                if (target.exists()) require(target.deleteRecursively()) { "Could not apply legacy file deletion" }
            }
            for ((index, mod) in redirects.withIndex())
                copy(File(snapshots, index.toString()), path(base, mod[1]), cancelled)
            } finally { snapshots.deleteRecursively() }
        }
        extract(legacy, base, cancelled, progress, skipMods = true)
        // Pure frontend control files are not DOS game data.
        for (name in listOf("AUTOBOOT.DBP", "PADMAP.DBP", "FRONTEND.DBP")) path(base, name).delete()
    }

    private fun copy(source: File, target: File, cancelled: AtomicBoolean) {
        require(!cancelled.get()) { "Cancelled" }
        if (source.isDirectory) {
            require(target.isDirectory || target.mkdirs()) { "Could not create game directory" }
            for (child in source.listFiles() ?: error("Could not read game directory"))
                copy(child, path(target, child.name), cancelled)
        } else {
            require(source.length() <= MAX_BYTES) { "DOS file is too large" }
            require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            require(target.parentFile!!.usableSpace > source.length() + 16L * 1024 * 1024) { "Not enough storage for writable game files" }
            val buffer = ByteArray(65536)
            source.inputStream().use { input -> target.outputStream().use { output ->
                while (true) {
                    require(!cancelled.get()) { "Cancelled" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            target.setLastModified(source.lastModified())
        }
    }
}
