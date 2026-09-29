package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import com.mrjackspade.kairo.frontend.ExternalGameFile
import com.mrjackspade.kairo.frontend.LibraryItem
import com.mrjackspade.kairo.frontend.VersionedLibraryCache
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** User selected DOS files and folders; no game data is packaged with the app. */
class DosLibrary(private val context: Context) {
    @Volatile var hashCount = 0
        private set
    companion object {
        private val installedSuffix = Regex(" - Installed(?:\\.zip)?$", RegexOption.IGNORE_CASE)

        private fun archiveStem(name: String) = name.substringBeforeLast('.')

        private fun isInstalledArchive(name: String) =
            installedSuffix.containsMatchIn(archiveStem(name))
    }

    data class Game(
        override val id: String,
        val uri: String,
        override val path: String,
        val folder: Boolean,
        val rootFolder: Boolean = false,
        val fingerprint: String,
        override val contentId: String?,
        override val error: String? = null,
        val installer: Boolean = false,
        val external: Boolean = false
    ) : LibraryItem {
        override val zipEntry: String? = null
        override val displayName: String get() = path.substringAfterLast('/').let {
            val stem = if (folder) it else archiveStem(it)
            if (installer && !isInstalledArchive(it)) "$stem - Installer"
            else stem.replace(installedSuffix, "")
        }
        val title: String get() = displayName
        override val playable: Boolean get() = contentId != null && error == null
        override val mediaLabel: String get() = "DOS game"
    }

    private val store = File(context.filesDir, "dos-library-v1.json")
    private val storeCache = VersionedLibraryCache(store, 2, "games", 64 * 1024 * 1024,
        50_000, ::decodeCacheEntry, ::encodeCacheEntry)
    private val walker = DocumentTreeWalker(context.contentResolver)
    private val cache = File(context.cacheDir, "dos-archives").apply { mkdirs() }
    private val archiveExtensions = setOf("zip", "dosz")
    private val executableExtensions = setOf("exe", "com", "bat")
    private val otherExtensions = setOf("iso", "chd", "img", "ima", "vhd", "jrc")

    fun cached(tree: Uri): List<Game> =
        storeCache.readForTree(tree.toString())?.entries.orEmpty()

    /** Inspect a launcher-provided file using the same content IDs as a library scan. */
    fun inspectExternal(file: ExternalGameFile, cancelled: AtomicBoolean): Game {
        val type = extension(file.name)
        require(type in archiveExtensions + executableExtensions + otherExtensions) {
            "Unsupported DOS game file: ${file.name}"
        }
        val source = DocumentTreeWalker.FileEntry(file.uri, file.name, file.size, file.modified)
        val inspection = if (type in archiveExtensions) {
            DosContentHash.inspectZipDocument(context.contentResolver, file.uri, cancelled)
                ?: copySource(source, cancelled).let { temp ->
                    try { DosContentHash.inspectZip(temp, cancelled) }
                    finally { temp.delete() }
                }
        } else null
        val contentId = inspection?.contentId ?: hashDocument(source, cancelled)
        val installer = inspection?.exodosSource == true &&
            !isInstalledArchive(file.name)
        return Game(sha256(file.uri.toString()), file.uri.toString(), file.name,
            false, false, "${file.size}:${file.modified}", contentId, null, installer, true)
    }

    fun scan(tree: Uri, forceHash: Boolean, cancelled: AtomicBoolean,
             progress: (String) -> Unit): List<Game> {
        var hashes = 0
        val prior = if (forceHash) emptyMap() else cached(tree).associateBy(Game::id)
        val source = walker.scan(tree, cancelled, { true }, progress)
        val all = source.files
        val rootFiles = all.filter { !it.path.contains('/') }
        val containsArchives = all.any { extension(it.path) in archiveExtensions }
        val rootGame = (rootFiles.size > 1 || all.any { it.path.contains('/') }) &&
            rootFiles.any { extension(it.path) in executableExtensions } &&
            !containsArchives &&
            rootFiles.none { extension(it.path) in otherExtensions }
        val nested = all.filter { it.path.contains('/') }.groupBy { it.path.substringBefore('/') }
        val folderGroups = if (rootGame) mapOf("@root" to all) else nested.filterValues {
            members -> members.any { extension(it.path) in executableExtensions } &&
                members.none { extension(it.path) in archiveExtensions } &&
                members.asSequence().map { it.path.substringAfter('/').substringBefore('/') }
                    .distinct().take(513).count() <= 512
        }
        val archives = if (rootGame) emptyList() else all.filter { item ->
            val type = extension(item.path)
            (type in archiveExtensions ||
                (type in otherExtensions + executableExtensions &&
                 (!containsArchives || !item.path.contains('/')))) &&
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
            var installer = old?.installer == true &&
                !isInstalledArchive(item.path.substringAfterLast('/'))
            if (old != null && old.fingerprint == fingerprint && item.modified > 0 &&
                old.contentId != null && old.error == null &&
                (if (extension(item.path) in archiveExtensions)
                    old.contentId.startsWith("sha256-dos-manifest-v1:") else
                    old.contentId.startsWith("sha256-dos-file-v1:"))) {
                contentId = old.contentId; failure = null
            } else try {
                hashes++
                contentId = if (extension(item.path) in archiveExtensions) {
                    val inspection = DosContentHash.inspectZipDocument(
                        context.contentResolver, item.uri, cancelled)
                        ?: copySource(item, cancelled).let { temp ->
                            try { DosContentHash.inspectZip(temp, cancelled) }
                            finally { temp.delete() }
                        }
                    installer = inspection.exodosSource &&
                        !isInstalledArchive(item.path.substringAfterLast('/'))
                    inspection.contentId
                } else hashDocument(item, cancelled)
                failure = null
            } catch (error: Exception) {
                contentId = null; failure = error.message ?: "Unreadable DOS game"
            }
            output.add(Game(id, item.uri.toString(), item.path, false, false,
                fingerprint, contentId, failure, installer))
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
                hashes++
                contentId = DosContentHash.documents(context.contentResolver, members, cancelled)
                failure = null
            } catch (error: Exception) {
                contentId = null; failure = error.message ?: "Unreadable DOS folder"
            }
            val display = if (root == "@root")
                tree.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "DOS game"
                else root
            val sourceUri = if (root == "@root") tree else {
                val child = childId(tree, DocumentsContract.getTreeDocumentId(tree), root, true)
                    ?: error("Game folder was moved; refresh the library")
                DocumentsContract.buildDocumentUriUsingTree(tree, child)
            }
            output.add(Game(id, sourceUri.toString(), display, true, root == "@root",
                fingerprint, contentId, failure))
        }
        source.folders.forEach { folder ->
            output.add(Game(sha256(folder.uri.toString()), folder.uri.toString(), folder.path,
                true, false, "", null, "Unreadable folder: ${folder.message}"))
        }
        if (cancelled.get()) return emptyList()
        val sorted = output.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
        saveStore(tree, sorted)
        hashCount = hashes
        return sorted
    }

    /** Materialize eXoDOS's ZIP extraction as a durable, separate archive. */
    fun install(game: Game, tree: Uri, cancelled: AtomicBoolean,
                progress: (String) -> Unit = {}): Game {
        require(game.installer && !game.folder && game.playable) { "Not an eXoDOS installer" }
        progress("Preparing installer archive…")
        val source = prepare(game, tree, cancelled)
        val originalName = game.path.substringAfterLast('/')
        val installedName = originalName.substringBeforeLast('.') + " - Installed.zip"
        val parentPath = game.path.substringBeforeLast('/', "")
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        if (parentPath.isNotEmpty()) for (segment in parentPath.split('/')) {
            parentId = childId(tree, parentId, segment, true)
                ?: error("Installer folder was moved; refresh the library")
        }
        val path = if (parentPath.isEmpty()) installedName else "$parentPath/$installedName"
        val existing = childId(tree, parentId, installedName, false)
        val created = if (existing != null)
            DocumentsContract.buildDocumentUriUsingTree(tree, existing)
        else DocumentsContract.createDocument(context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
            "application/zip", installedName)
            ?: error("Could not create installed game archive")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree,
            DocumentsContract.getDocumentId(created))
        try {
            if (existing == null) {
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var nextReport = 64L * 1024 * 1024
                source.inputStream().use { input ->
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            require(!cancelled.get()) { "Cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            written += count
                            if (written >= nextReport) {
                                progress("Installing game: ${written / (1024 * 1024)} MiB copied")
                                nextReport += 64L * 1024 * 1024
                            }
                        }
                    } ?: error("Could not write installed game archive")
                }
                val expected = digest.digest()
                val actual = MessageDigest.getInstance("SHA-256")
                progress("Verifying installed game archive…")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        require(!cancelled.get()) { "Cancelled" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        actual.update(buffer, 0, count)
                    }
                } ?: error("Could not verify installed game archive")
                require(expected.contentEquals(actual.digest())) {
                    "Installed game archive failed verification"
                }
            } else {
                progress("Checking installed game archive…")
                val checked = DosContentHash.inspectZipDocument(
                    context.contentResolver, uri, cancelled)?.contentId
                    ?: copySource(DocumentTreeWalker.FileEntry(uri, path, -1, 0), cancelled)
                        .let { temp ->
                            try { DosContentHash.zip(temp, cancelled) }
                            finally { temp.delete() }
                        }
                require(checked == game.contentId) {
                    "An installed archive with this name already exists and differs"
                }
            }
            val installedId = sha256(uri.toString())
            val staged = File(cache,
                "$installedId-${game.contentId!!.substringAfter(':').take(16)}.zip")
            if (!staged.isFile) source.renameTo(staged)
            return Game(installedId, uri.toString(), path, false, false,
                "", game.contentId, null, false)
        } catch (failure: Exception) {
            if (existing == null) runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            throw failure
        }
    }

    /** Keep the installed copy private when the launcher only grants one source file. */
    fun installExternal(game: Game, cancelled: AtomicBoolean,
                        progress: (String) -> Unit = {}): Game {
        require(game.external && game.installer && game.playable) { "Not an external installer" }
        progress("Preparing installer archive…")
        val source = prepare(game, null, cancelled)
        val installedId = sha256("external:${game.contentId}")
        val folder = File(context.filesDir, "installed-dos").apply { mkdirs() }
        val installed = File(folder, "$installedId.zip")
        if (installed.isFile) {
            require(DosContentHash.zip(installed, cancelled) == game.contentId) {
                "Installed game archive changed"
            }
        } else {
            val pending = File.createTempFile("dos-install-", ".part", folder)
            try {
                source.inputStream().use { input -> pending.outputStream().use { output ->
                    copyChecked(input, output, cancelled)
                } }
                require(DosContentHash.zip(pending, cancelled) == game.contentId) {
                    "Installed game archive failed verification"
                }
                require(pending.renameTo(installed)) { "Could not save installed game" }
            } finally { pending.delete() }
        }
        val name = game.path.substringBeforeLast('.') + " - Installed.zip"
        return Game(installedId, Uri.fromFile(installed).toString(), name, false,
            false, "", game.contentId, null, false, true)
    }

    fun deleteInstaller(game: Game, installed: Game) {
        require(game.installer) { "Not an installer" }
        require(!installed.installer && installed.contentId == game.contentId) {
            "Installed game is not available"
        }
        val check = DosContentHash.inspectZipDocument(context.contentResolver,
            Uri.parse(installed.uri), AtomicBoolean(false))?.contentId
            ?: copySource(DocumentTreeWalker.FileEntry(Uri.parse(installed.uri),
                installed.path, -1, 0), AtomicBoolean(false)).let { temp ->
                try { DosContentHash.zip(temp, AtomicBoolean(false)) }
                finally { temp.delete() }
            }
        require(check == game.contentId) { "Installed game could not be verified" }
        require(DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(game.uri))) {
            "The document provider could not remove the installer"
        }
    }

    /** Remove the selected source document, rather than only hiding its catalog entry. */
    fun deleteSource(game: Game) {
        require(!game.external && !game.rootFolder) {
            "This game is not a removable library document"
        }
        val uri = Uri.parse(game.uri)
        require(DocumentsContract.isDocumentUri(context, uri)) {
            "This game is not a removable library document"
        }
        require(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            "The document provider could not delete the game"
        }
        cache.listFiles()?.filter { it.name.startsWith("${game.id}-") }?.forEach { it.delete() }
    }

    private fun childId(tree: Uri, parentId: String, name: String,
                        directory: Boolean): String? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name &&
                    (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) == directory)
                    return cursor.getString(0)
            }
        } ?: error("Could not read game folder")
        return null
    }

    fun prepare(game: Game, tree: Uri?, cancelled: AtomicBoolean,
                wrapFolder: String? = null): File {
        require(game.playable) { "Game is not ready" }
        val type = extension(game.path)
        val outputType = if (game.folder || type in archiveExtensions + executableExtensions)
            "zip" else type
        if (wrapFolder != null) require(wrapFolder.isNotEmpty() &&
            wrapFolder.none { it == '/' || it == '\\' || it == ':' } &&
            wrapFolder != "." && wrapFolder != "..") { "Invalid game folder" }
        val wrapping = if (wrapFolder != null) "-parent" else ""
        val target = File(cache,
            "${game.id}-${game.contentId!!.substringAfter(':').take(16)}$wrapping.$outputType")
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
                    if (type in archiveExtensions && wrapFolder != null &&
                        !hasOuterFolder(copied, wrapFolder)) {
                        val pending = File.createTempFile("dos-", ".zip.part", cache)
                        try {
                            ZipFile(copied).use { input ->
                                ZipOutputStream(pending.outputStream()).use { output ->
                                    input.entries().asSequence().filterNot { it.isDirectory }
                                        .forEach { entry ->
                                            val name = entry.name.replace('\\', '/')
                                            require(!name.startsWith('/') &&
                                                name.split('/').all { it.isNotEmpty() &&
                                                    it != "." && it != ".." }) {
                                                "Unsafe game archive"
                                            }
                                            output.putNextEntry(ZipEntry("$wrapFolder/$name"))
                                            input.getInputStream(entry).use {
                                                copyChecked(it, output, cancelled)
                                            }
                                            output.closeEntry()
                                        }
                                }
                            }
                            require(DosContentHash.zip(pending, cancelled) == game.contentId) {
                                "Game changed while preparing; refresh the library"
                            }
                            require(pending.renameTo(target)) { "Could not prepare game" }
                        } finally { pending.delete() }
                        return target
                    }
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
            val files = walker.scan(tree ?: error("Choose a DOS folder for this game"),
                cancelled, { true }, {}).files.filter {
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
                            val packaged = if (wrapFolder == null) name else "$wrapFolder/$name"
                            zip.putNextEntry(ZipEntry(packaged))
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

    fun hasOuterFolder(file: File, folder: String): Boolean = ZipFile(file).use { archive ->
        val names = archive.entries().asSequence().filterNot { it.isDirectory }
            .map { it.name.replace('\\', '/') }.toList()
        names.isNotEmpty() && names.all { it.startsWith("$folder/", true) }
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

    private fun decodeCacheEntry(item: JSONObject): Game? {
        val id = item.optString("id")
        val uri = item.optString("uri")
        val path = item.optString("path")
        val fingerprint = item.optString("fingerprint")
        val contentId = item.optString("contentId").takeIf { it.isNotEmpty() }
        val error = item.optString("error").takeIf { it.isNotEmpty() }
        if (!id.matches(Regex("[0-9a-f]{64}")) || !uri.startsWith("content://") ||
            path.isBlank() || path.length > 4096 || fingerprint.length > 256 ||
            (contentId != null && !contentId.matches(
                Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}"))) ||
            (error != null && error.length > 1024)) return null
        return Game(id, uri, path, item.optBoolean("folder"),
            item.optBoolean("rootFolder"), fingerprint, contentId, error,
            item.optBoolean("installer"))
    }

    private fun encodeCacheEntry(game: Game) = JSONObject().put("id", game.id)
        .put("uri", game.uri).put("path", game.path).put("folder", game.folder)
        .put("rootFolder", game.rootFolder).put("fingerprint", game.fingerprint)
        .put("contentId", game.contentId ?: "").put("error", game.error ?: "")
        .put("installer", game.installer)

    private fun saveStore(tree: Uri, games: List<Game>) {
        storeCache.write(tree.toString(), games)
    }

    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
