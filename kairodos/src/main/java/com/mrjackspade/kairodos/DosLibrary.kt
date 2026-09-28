package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import com.mrjackspade.kairo.frontend.LibraryItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** User selected DOS files and folders; no game data is packaged with the app. */
class DosLibrary(private val context: Context) {
    data class Game(
        override val id: String,
        val uri: String,
        override val path: String,
        val folder: Boolean,
        val rootFolder: Boolean = false,
        val fingerprint: String,
        override val contentId: String?,
        override val error: String? = null
    ) : LibraryItem {
        override val zipEntry: String? = null
        override val displayName: String get() = path.substringAfterLast('/').let {
            if (folder) it else it.substringBeforeLast('.')
        }
        val title: String get() = displayName
        override val playable: Boolean get() = contentId != null && error == null
        override val mediaLabel: String get() = "DOS game"
    }

    private val store = File(context.filesDir, "dos-library-v1.json")
    private val walker = DocumentTreeWalker(context.contentResolver)
    private val cache = File(context.cacheDir, "dos-archives").apply { mkdirs() }
    private val archiveExtensions = setOf("zip", "dosz")
    private val executableExtensions = setOf("exe", "com", "bat")
    private val otherExtensions = setOf("iso", "chd", "img", "ima", "vhd", "jrc")

    fun cached(tree: Uri): List<Game> = readStore().let { (source, entries) ->
        if (source == tree.toString()) entries else emptyList()
    }

    fun scan(tree: Uri, forceHash: Boolean, cancelled: AtomicBoolean,
             progress: (String) -> Unit): List<Game> {
        val prior = if (forceHash) emptyMap() else cached(tree).associateBy(Game::id)
        val source = walker.scan(tree, cancelled, { true }, progress)
        val all = source.files
        val rootFiles = all.filter { !it.path.contains('/') }
        val rootGame = (rootFiles.size > 1 || all.any { it.path.contains('/') }) &&
            rootFiles.any { extension(it.path) in executableExtensions } &&
            rootFiles.none { extension(it.path) in archiveExtensions + otherExtensions }
        val nested = all.filter { it.path.contains('/') }.groupBy { it.path.substringBefore('/') }
        val folderGroups = if (rootGame) mapOf("@root" to all) else nested.filterValues {
            members -> members.any { extension(it.path) in executableExtensions }
        }
        val archives = if (rootGame) emptyList() else all.filter { item ->
            val type = extension(item.path)
            type in archiveExtensions + otherExtensions + executableExtensions &&
                (!item.path.contains('/') || item.path.substringBefore('/') !in folderGroups)
        }
        val output = ArrayList<Game>()
        archives.forEachIndexed { index, item ->
            if (cancelled.get()) return@forEachIndexed
            progress("Scanning ${index + 1}/${archives.size + folderGroups.size}: ${item.path}")
            val id = sha256(item.uri.toString())
            val fingerprint = "${item.size}:${item.modified}"
            val old = prior[id]
            var contentId: String?
            var failure: String?
            if (old != null && old.fingerprint == fingerprint && item.modified > 0 &&
                old.contentId != null && old.error == null &&
                (if (extension(item.path) in archiveExtensions)
                    old.contentId.startsWith("sha256-dos-manifest-v1:") else
                    old.contentId.startsWith("sha256-dos-file-v1:"))) {
                contentId = old.contentId; failure = null
            } else try {
                contentId = if (extension(item.path) in archiveExtensions) {
                    DosContentHash.zipDocument(context.contentResolver, item.uri, cancelled)
                        ?: copySource(item, cancelled).let { temp ->
                            try { DosContentHash.zip(temp, cancelled) }
                            finally { temp.delete() }
                        }
                } else hashDocument(item, cancelled)
                failure = null
            } catch (error: Exception) {
                contentId = null; failure = error.message ?: "Unreadable DOS game"
            }
            output.add(Game(id, item.uri.toString(), item.path, false, false,
                fingerprint, contentId, failure))
        }
        folderGroups.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (root, members) ->
            progress("Hashing $root")
            val id = sha256("${tree}\u0000$root")
            val fingerprint = sha256(members.sortedBy { it.path }.joinToString("\u0000") {
                "${it.path}:${it.size}:${it.modified}"
            })
            val old = prior[id]
            var contentId: String?
            var failure: String?
            if (old != null && old.fingerprint == fingerprint &&
                members.all { it.size >= 0 && it.modified > 0 } && old.contentId != null &&
                old.contentId.startsWith("sha256-dos-manifest-v1:") && old.error == null) {
                contentId = old.contentId; failure = null
            } else try {
                contentId = DosContentHash.documents(context.contentResolver, members, cancelled)
                failure = null
            } catch (error: Exception) {
                contentId = null; failure = error.message ?: "Unreadable DOS folder"
            }
            val display = if (root == "@root")
                tree.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "DOS game"
                else root
            output.add(Game(id, tree.toString(), display, true, root == "@root",
                fingerprint, contentId, failure))
        }
        source.folders.forEach { folder ->
            output.add(Game(sha256(folder.uri.toString()), folder.uri.toString(), folder.path,
                true, false, "", null, "Unreadable folder: ${folder.message}"))
        }
        if (cancelled.get()) return emptyList()
        val sorted = output.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
        saveStore(tree, sorted)
        return sorted
    }

    fun prepare(game: Game, tree: Uri, cancelled: AtomicBoolean): File {
        require(game.playable) { "Game is not ready" }
        val type = extension(game.path)
        val outputType = if (game.folder || type in archiveExtensions + executableExtensions)
            "zip" else type
        val target = File(cache, "${game.id}-${game.contentId!!.substringAfter(':').take(16)}.$outputType")
        if (target.isFile) return target
        try { if (!game.folder) {
            val source = DocumentTreeWalker.FileEntry(Uri.parse(game.uri), game.path, -1, 0)
            val copied = copySource(source, cancelled)
            try {
                require(if (extension(game.path) in archiveExtensions)
                    DosContentHash.zip(copied, cancelled) == game.contentId
                    else hashFile(copied, cancelled) == game.contentId) {
                    "Game changed since scan; refresh the library"
                }
                if (type in archiveExtensions + otherExtensions) {
                    require(copied.renameTo(target)) { "Could not prepare game" }
                    return target
                }
                val pending = File.createTempFile("dos-", ".zip.part", cache)
                try {
                    ZipOutputStream(pending.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry(game.path.substringAfterLast('/')))
                        copied.inputStream().use { copyChecked(it, zip, cancelled) }
                        zip.closeEntry()
                    }
                    require(pending.renameTo(target)) { "Could not prepare game" }
                } finally { pending.delete() }
            } finally { copied.delete() }
        } else {
            val files = walker.scan(tree, cancelled, { true }, {}).files.filter {
                if (game.rootFolder) true
                else it.path.startsWith("${game.path}/")
            }
            require(DosContentHash.documents(context.contentResolver, files, cancelled) == game.contentId) {
                "Game changed since scan; refresh the library"
            }
            val pending = File.createTempFile("dos-", ".zip.part", cache)
            try {
                ZipOutputStream(pending.outputStream()).use { zip ->
                    files.sortedBy { it.path }.forEach { item ->
                        require(!cancelled.get()) { "Cancelled" }
                        val name = if (game.rootFolder) item.path else
                            item.path.removePrefix("${game.path}/")
                        if (!name.endsWith(".exo", true)) {
                            zip.putNextEntry(ZipEntry(name))
                            context.contentResolver.openInputStream(item.uri)?.use {
                                copyChecked(it, zip, cancelled)
                            } ?: error("Unable to read $name")
                            zip.closeEntry()
                        }
                    }
                }
                require(DosContentHash.zip(pending, cancelled) == game.contentId) {
                    "Game changed while preparing; refresh the library"
                }
                require(pending.renameTo(target)) { "Could not prepare game" }
            } finally { pending.delete() }
        }
        return target
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    private fun copySource(source: DocumentTreeWalker.FileEntry,
                           cancelled: AtomicBoolean): File {
        require(source.size <= MAX_ARCHIVE_BYTES) { "DOS game exceeds size limit" }
        val temp = File.createTempFile("dos-", ".part", cache)
        try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        require(!cancelled.get()) { "Cancelled" }
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ARCHIVE_BYTES) { "DOS game exceeds size limit" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Unable to read ${source.path}")
            if (source.size >= 0) require(temp.length() == source.size) { "Game changed while copying" }
            return temp
        } catch (failure: Exception) { temp.delete(); throw failure }
    }

    private fun copyChecked(input: java.io.InputStream, output: java.io.OutputStream,
                            cancelled: AtomicBoolean) {
        val buffer = ByteArray(65536)
        while (true) {
            require(!cancelled.get()) { "Cancelled" }
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun hashFile(file: File, cancelled: AtomicBoolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (cancelled.get()) error("Cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "sha256-dos-file-v1:${digest.digest().joinToString("") { "%02x".format(it) }}"
    }

    private fun hashDocument(source: DocumentTreeWalker.FileEntry,
                             cancelled: AtomicBoolean): String {
        require(source.size <= MAX_ARCHIVE_BYTES) { "DOS game exceeds size limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                require(!cancelled.get()) { "Cancelled" }
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ARCHIVE_BYTES) { "DOS game exceeds size limit" }
                digest.update(buffer, 0, count)
            }
        } ?: error("Unable to read ${source.path}")
        if (source.size >= 0) require(total == source.size) { "Game changed while hashing" }
        return "sha256-dos-file-v1:${digest.digest().joinToString("") { "%02x".format(it) }}"
    }

    private fun readStore(): Pair<String?, List<Game>> = try {
        val json = JSONObject(AtomicFile(store).readFully().toString(Charsets.UTF_8))
        if (json.optInt("schemaVersion") != 1) null to emptyList() else {
            val array = json.optJSONArray("games") ?: JSONArray()
            json.optString("treeUri") to (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    Game(item.optString("id"), item.optString("uri"), item.optString("path"),
                        item.optBoolean("folder"), item.optBoolean("rootFolder"),
                        item.optString("fingerprint"),
                        item.optString("contentId").takeIf { it.isNotEmpty() },
                        item.optString("error").takeIf { it.isNotEmpty() })
                }
            }
        }
    } catch (_: Exception) { null to emptyList() }

    private fun saveStore(tree: Uri, games: List<Game>) {
        val array = JSONArray()
        games.forEach { game -> array.put(JSONObject().put("id", game.id).put("uri", game.uri)
            .put("path", game.path).put("folder", game.folder)
            .put("rootFolder", game.rootFolder)
            .put("fingerprint", game.fingerprint).put("contentId", game.contentId ?: "")
            .put("error", game.error ?: "")) }
        val bytes = JSONObject().put("schemaVersion", 1).put("treeUri", tree.toString())
            .put("games", array).toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(store)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }

    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
