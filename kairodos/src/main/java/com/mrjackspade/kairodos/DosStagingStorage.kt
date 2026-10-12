// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Staging mounts native folders. Each game has a pristine base drive, extracted
 * once outside the evictable archive cache, and an overlay that holds everything
 * the game writes. Only the overlay is the player's saved data. Original media
 * and Pure saves are never modified; an interrupted import cannot become live. */
internal object DosStagingStorage {
    /** [directory] is the base drive; [overlay] mirrors its layout with the game's writes. */
    data class Drive(val directory: File, val media: File?, val overlay: File)
    private const val MAX_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_FILES = 100_000
    private val DOS_NAME = Regex("[A-Z0-9_\\-!#$%&'(){}@^~`]{1,8}(\\.[A-Z0-9_\\-!#$%&'(){}@^~`]{1,3})?")

    /** Whether [saveDir] still holds a drive from before games had overlays. */
    fun hasLegacyDrive(saveDir: File) = File(saveDir, "staging/drive").isDirectory &&
        !File(saveDir, "staging/base-v2.txt").isFile

    /** The content ID a legacy drive was prepared for. */
    fun legacyContentId(saveDir: File): String = File(saveDir, "staging/drive-v1.txt").readText()

    fun prepare(archive: File, saveDir: File, contentId: String, overlay: File, cancelled: AtomicBoolean,
                gameFolder: String? = null,
                progress: (String) -> Unit = {}): Drive {
        val staging = File(saveDir, "staging").apply { mkdirs() }
        val base = File(staging, "base")
        val marker = File(staging, "base-v2.txt")
        val legacy = File(staging, "drive")
        if (marker.isFile) {
            require(marker.readText() == contentId) {
                "This game's source changed. Its existing writable game files were preserved."
            }
            check(base.isDirectory) { "This game's files are missing; delete its saved data to prepare it again" }
            // A conversion that finished before removing the old drive.
            if (legacy.exists()) check(legacy.deleteRecursively() && File(staging, "drive-v1.txt").delete()) {
                "Could not remove the converted game drive"
            }
            File(staging, "converting.txt").delete()
            require(overlay.isDirectory || overlay.mkdirs()) { "Could not create the game's save folder" }
            return Drive(base, media(base, archive), overlay)
        }
        if (legacy.isDirectory) {
            require(File(staging, "drive-v1.txt").readText() == contentId) {
                "This game's source changed. Its existing writable game files were preserved."
            }
            convert(archive, legacy, staging, overlay, contentId, cancelled, gameFolder, progress)
            return Drive(base, media(base, archive), overlay)
        }
        val pures = pureSaves(saveDir)
        if (pures.isNotEmpty() && (!overlay.exists() || overlay.walkTopDown().none { it.isFile })) {
            // Legacy Pure saves change files inside the drive. Apply them to a full drive, then
            // keep only the differences, exactly as for a drive from before overlays.
            val pending = File(staging, "drive-pending")
            clear(pending, staging)
            try {
                extractPristine(archive, pending, cancelled, gameFolder, progress)
                importPureSave(archive, saveDir, pending, cancelled, progress)
                convert(archive, pending, staging, overlay, contentId, cancelled, gameFolder, progress)
            } finally {
                if (pending.exists()) pending.deleteRecursively()
            }
            return Drive(base, media(base, archive), overlay)
        }
        val pending = File(staging, "base-pending")
        clear(pending, staging)
        try {
            extractPristine(archive, pending, cancelled, gameFolder, progress)
            require(!cancelled.get()) { "Cancelled" }
            require(pending.renameTo(base)) { "Could not publish game files" }
            // The marker follows the directory, so a marker always means a complete base.
            marker.writeText(contentId)
        } finally {
            if (pending.exists()) pending.deleteRecursively()
        }
        require(overlay.isDirectory || overlay.mkdirs()) { "Could not create the game's save folder" }
        return Drive(base, media(base, archive), overlay)
    }

    private fun media(base: File, archive: File) =
        File(base, archive.name).takeIf { !archive.extension.equals("zip", true) }

    private fun clear(directory: File, parent: File) {
        require(directory.canonicalFile.parentFile == parent.canonicalFile)
        if (directory.exists()) require(directory.deleteRecursively()) { "Could not clear interrupted game preparation" }
        require(directory.mkdirs()) { "Could not create writable game folder" }
    }

    /** The archive's files, laid out as every launch variant mounts them. */
    private fun extractPristine(archive: File, into: File, cancelled: AtomicBoolean, gameFolder: String?,
                                progress: (String) -> Unit) {
        if (archive.extension.equals("zip", true)) {
            progress("Preparing game files…")
            extract(archive, into, cancelled, progress)
            // Keep one layout for all catalog launch variants. A parent
            // mount and a game-folder mount must share the same saves.
            if (!gameFolder.isNullOrEmpty()) {
                require('/' !in gameFolder && '\\' !in gameFolder)
                val wrapper = path(into, gameFolder)
                if (!wrapper.isDirectory) {
                    val children = into.listFiles().orEmpty()
                    require(wrapper.mkdirs()) { "Could not create DOS game folder" }
                    for (child in children) require(child.renameTo(File(wrapper, child.name))) {
                        "Could not prepare DOS game folder"
                    }
                }
            }
        } else {
            require(!cancelled.get()) { "Cancelled" }
            copy(archive, File(into, archive.name), cancelled)
        }
    }

    /**
     * Splits a full writable drive into a pristine base and an overlay of the game's changes:
     * changed and new files are copied to the overlay, and removed files get Staging's
     * deletion markers. The old drive stays until the new pair is complete.
     */
    private fun convert(archive: File, drive: File, staging: File, overlay: File, contentId: String,
                        cancelled: AtomicBoolean, gameFolder: String?, progress: (String) -> Unit) {
        val converting = File(staging, "converting.txt")
        if (overlay.exists() && overlay.walkTopDown().any { it.isFile }) {
            // Only an interrupted conversion leaves an overlay beside an old drive.
            require(converting.isFile && converting.readText() == contentId) {
                "This game has saved files from another copy of it. Its existing writable game files were preserved."
            }
            require(overlay.deleteRecursively()) { "Could not clear an interrupted conversion" }
        }
        val base = File(staging, "base-pending")
        clear(base, staging)
        val changes = File(overlay.parentFile, "${overlay.name}.converting")
        try {
            extractPristine(archive, base, cancelled, gameFolder, progress)
            progress("Converting saved game files…")
            if (changes.exists()) require(changes.deleteRecursively()) { "Could not clear an interrupted conversion" }
            require(changes.mkdirs()) { "Could not create the game's save folder" }
            diff(drive, base, changes, "", cancelled)
            require(!cancelled.get()) { "Cancelled" }
            converting.writeText(contentId)
            if (overlay.exists()) require(overlay.deleteRecursively()) { "Could not replace the game's save folder" }
            require(changes.renameTo(overlay)) { "Could not publish the game's saved files" }
            require(base.renameTo(File(staging, "base"))) { "Could not publish game files" }
            File(staging, "base-v2.txt").writeText(contentId)
            if (drive.name == "drive") check(drive.deleteRecursively() && File(staging, "drive-v1.txt").delete()) {
                "Could not remove the converted game drive"
            }
            converting.delete()
        } finally {
            if (base.exists()) base.deleteRecursively()
            if (changes.exists()) changes.deleteRecursively()
        }
    }

    private fun diff(drive: File, base: File, out: File, relative: String, cancelled: AtomicBoolean) {
        require(!cancelled.get()) { "Cancelled" }
        val baseChildren = base.listFiles().orEmpty().associateBy { it.name.lowercase(Locale.ROOT) }
        val driveChildren = drive.listFiles() ?: error("Could not read $relative")
        val seen = HashSet<String>()
        for (child in driveChildren) {
            val key = child.name.lowercase(Locale.ROOT)
            seen += key
            val original = baseChildren[key]
            val target = File(out, child.name)
            if (child.isDirectory) {
                require(original == null || original.isDirectory) { "A file became a folder: $relative${child.name}" }
                if (original == null) copy(child, target, cancelled)
                else diff(child, original, target, "$relative${child.name}/", cancelled)
            } else {
                require(original == null || original.isFile) { "A folder became a file: $relative${child.name}" }
                if (original == null || !sameContents(child, original)) copy(child, target, cancelled)
            }
        }
        for ((key, original) in baseChildren) if (key !in seen) {
            val name = original.name.uppercase(Locale.ROOT)
            require(DOS_NAME.matches(name)) { "Cannot record the removal of $relative${original.name}: not a DOS file name" }
            require(out.isDirectory || out.mkdirs()) { "Could not create the game's save folder" }
            File(out, "DBOVERLAY_${if (original.isDirectory) "RMD" else "DEL"}_$name").writeText("empty")
        }
    }

    private fun sameContents(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        val left = ByteArray(65536)
        val right = ByteArray(65536)
        a.inputStream().use { first -> b.inputStream().use { second ->
            while (true) {
                val count = readFully(first, left)
                if (count == 0) return true
                if (readFully(second, right, count) != count) return false
                for (index in 0 until count) if (left[index] != right[index]) return false
            }
        } }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int = buffer.size): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, total, length - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun pureSaves(saveDir: File) = saveDir.listFiles().orEmpty().filter {
        it.isFile && (it.name.endsWith(".pure.zip", true) || it.name.endsWith(".sav.zip", true))
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
        val saves = pureSaves(saveDir)
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
