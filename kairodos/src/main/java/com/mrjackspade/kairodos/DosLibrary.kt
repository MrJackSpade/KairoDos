package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import com.mrjackspade.kairo.frontend.ExternalGameFile
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.LibraryScanPipeline
import com.mrjackspade.kairo.frontend.LibraryItem
import com.mrjackspade.kairo.frontend.text
import com.mrjackspade.kairo.frontend.VersionedLibraryCache
import com.mrjackspade.kairo.frontend.readOptionalText
import com.mrjackspade.kairo.frontend.readText
import com.mrjackspade.kairo.frontend.writeOptionalText
import com.mrjackspade.kairo.frontend.writeText
import org.json.JSONObject
import com.mrjackspade.kairo.frontend.FolderListings
import com.mrjackspade.kairo.frontend.ScanDepth
import com.mrjackspade.kairo.frontend.UiText
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** User selected DOS files and folders; no game data is packaged with the app. */
class DosLibrary(private val context: Context) {
    val hashCount: Int get() = scanPipeline.hashCount
    companion object {
        private val installedSuffix = Regex(" - Installed(?:\\.zip)?$", RegexOption.IGNORE_CASE)

        private fun archiveStem(name: String) = name.substringBeforeLast('.')

        private fun isInstalledArchive(name: String) =
            installedSuffix.containsMatchIn(archiveStem(name))
    }

    data class Game(
        override val id: String,
        override val uri: String,
        override val path: String,
        val folder: Boolean,
        val rootFolder: Boolean = false,
        val fingerprint: String,
        override val contentId: String?,
        override val error: String? = null,
        val installer: Boolean = false,
        val external: Boolean = false,
        override val catalogId: String? = contentId,
        /** The game picked from a collection; never cached, a launch picks it. */
        override val member: String? = null
    ) : LibraryItem {
        override val zipEntry: String? = null
        // Rows, search and comparisons read the name constantly; it is derived once.
        override val displayName: String by lazy {
            path.substringAfterLast('/').let {
                val stem = if (folder) it else archiveStem(it)
                if (installer && !isInstalledArchive(it)) "$stem - Installer"
                else stem.replace(installedSuffix, "")
            }
        }
        val title: String get() = displayName
        override val playable: Boolean get() = contentId != null && error == null
        override val mediaLabel: UiText get() = UiText.res(R.string.dos_game)
    }

    private val storeCache = VersionedLibraryCache(File(context.filesDir, "dos-library-v2.bin"), 4,
        64 * 1024 * 1024, 50_000, ::writeCacheRecord, ::readCacheRecord,
        VersionedLibraryCache.Legacy(File(context.filesDir, "dos-library-v1.json"), 2, "games",
            ::decodeLegacyEntry), mapOf(3 to ::readCacheRecordV3))
    private val walker = DocumentTreeWalker(context)
    /** The games folder's listings, so a launch lists only the folders that changed (ScanDepth). */
    private val folderListings = FolderListings(File(context.filesDir, "folder-listings-v1.json"))
    private val scanPipeline by lazy {
        LibraryScanPipeline(context, storeCache, Game::id, ::planScan, ::inspectScanWork,
            ::failedScanWork, ::describe, ::known, Game::path, cacheOrder = { entries -> entries.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.path }) })
    }
    private val cache = File(context.cacheDir, "dos-archives").apply { mkdirs() }
    // A 7z archive is listed as a ZIP is, and runs from a ZIP made from it (prepare).
    private val archiveExtensions = setOf("zip", "dosz", "7z")
    private val executableExtensions = setOf("exe", "com", "bat")
    private val otherExtensions = setOf("iso", "chd", "img", "ima", "vhd", "jrc")

    fun cached(tree: Uri): List<Game> = scanPipeline.cached(tree)

    /** Saves a library edited without a scan, such as after an install. */
    fun store(tree: Uri, entries: List<Game>) = scanPipeline.store(tree, entries)

    /** [entries] with [game] added or replaced, in scan order. */
    fun withGame(entries: List<Game>, game: Game): List<Game> =
        scanPipeline.inScanOrder(entries.filter { it.id != game.id } + game)

    /** Inspect a launcher-provided file using the same content IDs as a library scan. */
    fun inspectExternal(file: ExternalGameFile, cancelled: AtomicBoolean): Game {
        val type = extension(file.name)
        require(type in archiveExtensions + executableExtensions + otherExtensions) {
            context.getString(R.string.unsupported_file, file.name)
        }
        val source = DocumentTreeWalker.FileEntry(file.uri, file.name, file.size, file.modified)
        val inspection = if (type in archiveExtensions) {
            DosContentHash.inspectZipDocument(context, file.uri, cancelled, sevenZ = type == "7z")
        } else null
        val contentId = inspection?.contentId ?: hashDocument(source, cancelled)
        val installer = inspection?.exodosSource == true &&
            !isInstalledArchive(file.name)
        // Keyed by its path relative to the library folder, or its URI where it has none (Kairo#56).
        return Game(file.key, file.uri.toString(), file.name,
            false, false, "${file.size}:${file.modified}", contentId, null, installer, true)
    }

    private sealed interface ScanWork {
        data class Archive(val file: DocumentTreeWalker.FileEntry) : ScanWork
        data class Folder(val tree: Uri, val root: String,
                          val members: List<DocumentTreeWalker.FileEntry>) : ScanWork
    }

    fun scan(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean,
             progress: (String) -> Unit): List<Game> =
        scanPipeline.scan(tree, depth, cancelled, progress)

    private fun planScan(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean,
                         progress: (String) -> Unit): LibraryScanPipeline.Plan<ScanWork, Game> {
        val source = walker.scan(tree, cancelled, { true }, progress, folderListings, depth)
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
        val work = ArrayList<ScanWork>()
        work.addAll(archives.map(ScanWork::Archive))
        folderGroups.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (root, members) ->
            work.add(ScanWork.Folder(tree, root, members))
        }
        val folderErrors = source.folders.map { folder ->
            Game(folder.path, folder.uri.toString(), folder.path,
                true, false, "", null, context.getString(R.string.unreadable_folder, folder.message))
        }
        return LibraryScanPipeline.Plan(work, finalEntries = folderErrors)
    }

    private fun describe(work: ScanWork): String = when (work) {
        is ScanWork.Archive -> work.file.path
        is ScanWork.Folder -> folderDisplay(work)
    }

    /** A game the library has scanned before keeps its identity; only Recheck all files hashes it again. */
    private fun known(work: ScanWork, scan: LibraryScanPipeline.ScanContext<Game>): Boolean = when (work) {
        is ScanWork.Archive -> scan.prior[work.file.path] != null
        is ScanWork.Folder -> scan.prior[folderId(work)] != null
    }

    /** [old]'s content ID, when it is a game this scan need not read again. */
    private fun knownContent(old: Game?, fingerprint: String, prefix: String): String? {
        if (old == null || old.contentId == null || old.error != null || !old.contentId.startsWith(prefix)) return null
        if (old.fingerprint != fingerprint) android.util.Log.w("KairoLibrary",
            "Known game changed: ${old.path}: ${old.fingerprint} -> $fingerprint")
        return old.contentId
    }

    private fun inspectScanWork(work: ScanWork,
                                scan: LibraryScanPipeline.ScanContext<Game>): List<Game> =
        when (work) {
            is ScanWork.Archive -> inspectArchive(work.file, scan)
            is ScanWork.Folder -> inspectFolder(work, scan)
        }

    private fun inspectArchive(item: DocumentTreeWalker.FileEntry,
                               scan: LibraryScanPipeline.ScanContext<Game>): List<Game> {
        val id = item.path
        val fingerprint = "${item.size}:${item.modified}"
        val old = scan.prior[id]
        var installer = old?.installer == true &&
            !isInstalledArchive(item.path.substringAfterLast('/'))
        val contentId = knownContent(old, fingerprint, if (extension(item.path) in archiveExtensions)
                "sha256-dos-manifest-v1:" else "sha256-dos-file-v1:") ?: run {
            scan.hashStarted(item.path)
            if (extension(item.path) in archiveExtensions) {
                val inspection = DosContentHash.inspectZipDocument(
                    context, item.uri, scan.cancelled, sevenZ = extension(item.path) == "7z")
                installer = inspection.exodosSource &&
                    !isInstalledArchive(item.path.substringAfterLast('/'))
                inspection.contentId
            } else hashDocument(item, scan.cancelled)
        }
        return listOf(Game(id, item.uri.toString(), item.path, false, false,
            fingerprint, contentId, null, installer, catalogId = catalogId(scan, id, contentId)))
    }

    /** A folder game's key is its folder; a game that is the whole library folder has the empty path. */
    private fun folderId(work: ScanWork.Folder) = if (work.root == "@root") "" else work.root

    /** The content this key was first scanned with, kept through later edits for its catalog record. */
    private fun catalogId(scan: LibraryScanPipeline.ScanContext<Game>, id: String, contentId: String?) =
        scan.earlier[id]?.catalogId ?: contentId

    private fun folderFingerprint(work: ScanWork.Folder) =
        sha256(work.members.sortedBy { it.path }.joinToString("\u0000") {
            "${it.path}:${it.size}:${it.modified}"
        })

    private fun folderDisplay(work: ScanWork.Folder) = if (work.root == "@root")
        work.tree.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "DOS game"
        else work.root

    private fun folderUri(work: ScanWork.Folder): Uri = if (work.root == "@root") work.tree else {
        val child = childId(work.tree, DocumentsContract.getTreeDocumentId(work.tree),
            work.root, true) ?: error(context.getString(R.string.game_folder_moved))
        DocumentsContract.buildDocumentUriUsingTree(work.tree, child)
    }

    private fun inspectFolder(work: ScanWork.Folder,
                              scan: LibraryScanPipeline.ScanContext<Game>): List<Game> {
        val id = folderId(work)
        val fingerprint = folderFingerprint(work)
        val old = scan.prior[id]
        val contentId = knownContent(old, fingerprint, "sha256-dos-manifest-v1:") ?: run {
            scan.hashStarted(folderDisplay(work))
            DosContentHash.documents(context, work.members, scan.cancelled)
        }
        return listOf(Game(id, folderUri(work).toString(), folderDisplay(work), true,
            work.root == "@root", fingerprint, contentId, catalogId = catalogId(scan, id, contentId)))
    }

    private fun failedScanWork(work: ScanWork, scan: LibraryScanPipeline.ScanContext<Game>,
                               error: Exception): List<Game> = when (work) {
        is ScanWork.Archive -> {
            val item = work.file
            val installer = scan.prior[item.path]?.installer == true &&
                !isInstalledArchive(item.path.substringAfterLast('/'))
            listOf(Game(item.path, item.uri.toString(), item.path,
                false, false, "${item.size}:${item.modified}", null,
                error.message ?: context.getString(R.string.unreadable_game), installer,
                catalogId = scan.earlier[item.path]?.catalogId))
        }
        is ScanWork.Folder -> listOf(Game(folderId(work),
            runCatching { folderUri(work) }.getOrDefault(work.tree).toString(),
            folderDisplay(work), true, work.root == "@root", folderFingerprint(work),
            null, error.message ?: context.getString(R.string.unreadable_game_folder),
            catalogId = scan.earlier[folderId(work)]?.catalogId))
    }

    /** Materialize eXoDOS's ZIP extraction as a durable, separate archive. */
    fun install(game: Game, tree: Uri, cancelled: AtomicBoolean,
                progress: (String) -> Unit = {}): Game {
        require(game.installer && !game.folder && game.playable) { "Not an eXoDOS installer" }
        progress(context.getString(R.string.preparing_installer))
        val source = prepare(game, tree, cancelled)
        val originalName = game.path.substringAfterLast('/')
        val installedName = originalName.substringBeforeLast('.') + " - Installed.zip"
        val parentPath = game.path.substringBeforeLast('/', "")
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        if (parentPath.isNotEmpty()) for (segment in parentPath.split('/')) {
            parentId = childId(tree, parentId, segment, true)
                ?: error(context.getString(R.string.installer_folder_moved))
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
                                progress(context.getString(R.string.installing_copied, written / (1024 * 1024)))
                                nextReport += 64L * 1024 * 1024
                            }
                        }
                    } ?: error("Could not write installed game archive")
                }
                val expected = digest.digest()
                val actual = MessageDigest.getInstance("SHA-256")
                progress(context.getString(R.string.installed_verifying))
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
                    context.getString(R.string.installed_verification_failed)
                }
            } else {
                progress(context.getString(R.string.installed_checking))
                val checked = DosContentHash.zipDocument(context, uri, cancelled)
                require(checked == game.contentId) {
                    context.getString(R.string.installed_name_taken)
                }
            }
            val staged = File(cache,
                "${GameKey(path).hashName}-${game.contentId!!.substringAfter(':').take(16)}.zip")
            if (!staged.isFile) source.renameTo(staged)
            // The scan's fingerprint, so the next scan reuses this verified content ID.
            return Game(path, uri.toString(), path, false, false,
                documentFingerprint(uri), game.contentId, null, false, catalogId = game.catalogId)
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
        progress(context.getString(R.string.preparing_installer))
        val source = prepare(game, null, cancelled)
        val installedId = sha256("external:${game.contentId}")
        val folder = File(context.filesDir, "installed-dos").apply { mkdirs() }
        val installed = File(folder, "$installedId.zip")
        if (installed.isFile) {
            require(DosContentHash.zip({ context.text(it) }, installed, cancelled) == game.contentId) {
                context.getString(R.string.installed_changed)
            }
        } else {
            val pending = File.createTempFile("dos-install-", ".part", folder)
            try {
                source.inputStream().use { input -> pending.outputStream().use { output ->
                    copyChecked(input, output, cancelled)
                } }
                require(DosContentHash.zip({ context.text(it) }, pending, cancelled) == game.contentId) {
                    context.getString(R.string.installed_verification_failed)
                }
                require(pending.renameTo(installed)) { "Could not save installed game" }
            } finally { pending.delete() }
        }
        val name = game.path.substringBeforeLast('.') + " - Installed.zip"
        // Outside the library folder, its key is its own file's URI.
        val uri = Uri.fromFile(installed).toString()
        return Game(uri, uri, name, false, false, "", game.contentId, null, false, true, game.catalogId)
    }

    fun deleteInstaller(game: Game, installed: Game) {
        require(game.installer) { "Not an installer" }
        require(!installed.installer && installed.contentId == game.contentId) {
            context.getString(R.string.installed_unavailable)
        }
        val check = DosContentHash.zipDocument(context,
            Uri.parse(installed.uri), AtomicBoolean(false))
        require(check == game.contentId) { context.getString(R.string.installed_unverified) }
        require(DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(game.uri))) {
            context.getString(R.string.installer_remove_refused)
        }
    }

    /** Remove the selected source document, rather than only hiding its catalog entry. */
    fun deleteSource(game: Game) {
        require(!game.external && !game.rootFolder) {
            context.getString(R.string.game_not_removable)
        }
        val uri = Uri.parse(game.uri)
        require(DocumentsContract.isDocumentUri(context, uri)) {
            context.getString(R.string.game_not_removable)
        }
        require(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            context.getString(R.string.game_delete_refused)
        }
        cache.listFiles()?.filter { it.name.startsWith("${game.key.hashName}-") }?.forEach { it.delete() }
    }

    private fun documentFingerprint(uri: Uri): String {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "Installed game archive is missing" }
            val size = if (cursor.isNull(0)) -1 else cursor.getLong(0)
            val modified = if (cursor.isNull(1)) 0 else cursor.getLong(1)
            return "$size:$modified"
        } ?: error("Could not read installed game archive")
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
            "${game.key.hashName}-${game.contentId!!.substringAfter(':').take(16)}$wrapping.$outputType")
        if (target.isFile) return target
        // Only the game being started keeps a prepared copy; every other one is made again when played.
        cache.listFiles()?.filter { it.name != target.name && !it.name.endsWith(".part") }?.forEach { it.delete() }
        try { if (!game.folder) {
            val source = DocumentTreeWalker.FileEntry(Uri.parse(game.uri), game.path, -1, 0)
            val copied = copySource(source, cancelled).let { copy ->
                if (type != "7z") copy else {
                    val zip = File.createTempFile("dos-", ".zip.part", cache)
                    try {
                        DosZip.sevenZToZip(copy, zip, cancelled)
                    } catch (failure: Throwable) {
                        zip.delete()
                        throw failure
                    } finally { copy.delete() }
                    zip
                }
            }
            try {
                require(if (extension(game.path) in archiveExtensions)
                    DosContentHash.zip({ context.text(it) }, copied, cancelled) == game.contentId
                    else hashFile(copied, cancelled) == game.contentId) {
                    context.getString(R.string.game_changed_since_scan)
                }
                if (type in archiveExtensions + otherExtensions) {
                    if (type in archiveExtensions && wrapFolder != null &&
                        !hasOuterFolder(copied, wrapFolder)) {
                        val pending = File.createTempFile("dos-", ".zip.part", cache)
                        try {
                            DosZip.open(copied).use { input ->
                                ZipOutputStream(pending.outputStream()).use { output ->
                                    input.entries().asSequence().filterNot { it.isDirectory }
                                        .forEach { entry ->
                                            val name = entry.name.replace('\\', '/')
                                            require(!name.startsWith('/') &&
                                                name.split('/').all { it.isNotEmpty() &&
                                                    it != "." && it != ".." }) {
                                                context.getString(R.string.game_archive_unsafe)
                                            }
                                            output.putNextEntry(ZipEntry("$wrapFolder/$name"))
                                            input.getInputStream(entry).use {
                                                copyChecked(it, output, cancelled)
                                            }
                                            output.closeEntry()
                                        }
                                }
                            }
                            require(DosContentHash.zip({ context.text(it) }, pending, cancelled) == game.contentId) {
                                context.getString(R.string.game_changed_while_preparing)
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
            val files = walker.scan(tree ?: error(context.getString(R.string.choose_dos_folder_for_game)),
                cancelled, { true }, {}).files.filter {
                if (game.rootFolder) true
                else it.path.startsWith("${game.path}/")
            }
            require(DosContentHash.documents(context, files, cancelled) == game.contentId) {
                context.getString(R.string.game_changed_since_scan)
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
                require(DosContentHash.zip({ context.text(it) }, pending, cancelled) == game.contentId) {
                    context.getString(R.string.game_changed_while_preparing)
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

    fun hasOuterFolder(file: File, folder: String): Boolean = DosZip.open(file).use { archive ->
        val names = archive.entries().asSequence().filterNot { it.isDirectory }
            .map { it.name.replace('\\', '/') }.toList()
        names.isNotEmpty() && names.all { it.startsWith("$folder/", true) }
    }

    private fun copySource(source: DocumentTreeWalker.FileEntry,
                           cancelled: AtomicBoolean): File {
        require(source.size <= MAX_ARCHIVE_BYTES) { context.getString(R.string.game_too_large) }
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
                        require(total <= MAX_ARCHIVE_BYTES) { context.getString(R.string.game_too_large) }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Unable to read ${source.path}")
            if (source.size >= 0) require(temp.length() == source.size) { context.getString(R.string.game_changed_while_copying) }
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
        require(source.size <= MAX_ARCHIVE_BYTES) { context.getString(R.string.game_too_large) }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                require(!cancelled.get()) { "Cancelled" }
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ARCHIVE_BYTES) { context.getString(R.string.game_too_large) }
                digest.update(buffer, 0, count)
            }
        } ?: error("Unable to read ${source.path}")
        if (source.size >= 0) require(total == source.size) { context.getString(R.string.game_changed_while_hashing) }
        return "sha256-dos-file-v1:${digest.digest().joinToString("") { "%02x".format(it) }}"
    }

    private fun writeCacheRecord(output: java.io.DataOutputStream, game: Game) = with(output) {
        writeText(game.id); writeText(game.uri); writeText(game.path)
        writeBoolean(game.folder); writeBoolean(game.rootFolder); writeText(game.fingerprint)
        writeOptionalText(game.contentId); writeOptionalText(game.error); writeBoolean(game.installer)
        writeOptionalText(game.catalogId)
    }

    private fun readCacheRecord(input: java.io.DataInputStream) = with(input) {
        Game(readText(), readText(), readText(), readBoolean(), readBoolean(), readText(),
            readOptionalText(), readOptionalText(), readBoolean(), catalogId = readOptionalText())
    }

    /**
     * Layout 3, from before games had keys: its ID hashed the entry's document. The key is the
     * path, and the content it was scanned with names its catalog record.
     */
    private fun readCacheRecordV3(input: java.io.DataInputStream) = with(input) {
        readText()
        keyed(Game("", readText(), readText(), readBoolean(), readBoolean(), readText(),
            readOptionalText(), readOptionalText(), readBoolean()))
    }

    /** The JSON cache that version 1.0.3 and earlier wrote. */
    private fun decodeLegacyEntry(item: JSONObject) = keyed(Game("", item.getString("uri"),
        item.getString("path"), item.optBoolean("folder"), item.optBoolean("rootFolder"),
        item.optString("fingerprint"), item.optString("contentId").takeIf { it.isNotEmpty() },
        item.optString("error").takeIf { it.isNotEmpty() }, item.optBoolean("installer")))

    private fun keyed(game: Game) = game.copy(id = if (game.rootFolder) "" else game.path)

    /** The ID [game]'s entry had before games had keys, which named its local save folder. */
    fun legacyEntryId(game: Game, tree: Uri?): String = when {
        game.folder -> sha256("$tree\u0000${if (game.rootFolder) "@root" else game.path}")
        // An external installed copy's file was named by that ID.
        game.external && game.uri.startsWith("file:") -> File(Uri.parse(game.uri).path!!).nameWithoutExtension
        else -> sha256(game.uri)
    }

    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
