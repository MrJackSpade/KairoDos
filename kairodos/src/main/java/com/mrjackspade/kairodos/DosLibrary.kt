package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.mrjackspade.kairo.frontend.ChdImage
import com.mrjackspade.kairo.frontend.DiscDescriptors
import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import com.mrjackspade.kairo.frontend.DurableFiles
import com.mrjackspade.kairo.frontend.R as FrontendR
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
class DosLibrary(private val context: Context,
                 /** What games' own files give them: a Collection Chamber game's launch (DosFilesRecords). */
                 private val filesRecords: DosFilesRecords,
                 /** A record was added to [filesRecords]: the catalog resolves games again. */
                 private val filesChanged: () -> Unit) {
    val hashCount: Int get() = scanPipeline.hashCount
    companion object {
        /** A new game's path in the games folder: the zip its drive is saved to (saveBlank). */
        fun blankPath(name: String) = "$name.zip"

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
        override val member: String? = null,
        /** A new game on an empty C: drive (MainActivity.startBlankGame), not in the games folder until it holds a file. */
        val blank: Boolean = false
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
    private val documents = DosDocuments(context)
    private val installers = DosInstallers(context, documents, cache, ::keepFilesRecord) { game, tree, cancelled -> prepare(game, tree, cancelled) }

    /** Keeps [launch], what a game's files declare, under each of [contentIds]. */
    private fun keepFilesRecord(contentIds: Collection<String>, launch: DosClickteamLaunch.Launch?) {
        if (launch == null) return
        filesRecords.put(contentIds, DosFilesRecords.record(launch))
        filesChanged()
    }
    /** Which programs of the folder are Collection Chamber installers, by path, size and time (planScan). */
    private val installerProbes = DosInstallerProbes(File(context.filesDir, "installer-probes-v1.json"))
    // The Disks page's drives (prepareMedia), beside the game's own prepared copy: kept, as other
    // apps keep a disk's working copy, so a disk keeps what a game writes to it.
    private val mediaCache = File(context.filesDir, "dos-media")
    // A 7z archive is listed as a ZIP is, and runs from a ZIP made from it (prepare).
    private val archiveExtensions = setOf("zip", "dosz", "7z")
    /** A [blank] game's content: none. */
    private val BLANK_CONTENT = "sha256-dos-manifest-v1:" + "0".repeat(64)
    private val executableExtensions = setOf("exe", "com", "bat")
    private val otherExtensions = setOf("iso", "cue", "chd", "img", "ima", "vhd", "jrc")
    /** A CUE sheet's track data is the BIN beside it under its name; the sheet's content ID hashes it. */
    private val discs = DiscDescriptors(mapOf("cue" to listOf("bin")))

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
        require(!discs.isDescriptor(file.name)) {
            context.getString(FrontendR.string.kairo_disk_needs_track_data, file.name)
        }
        val source = DocumentTreeWalker.FileEntry(file.uri, file.name, file.size, file.modified)
        val inspection = if (type in archiveExtensions) {
            DosContentHash.inspectZipDocument(context, file.uri, cancelled, sevenZ = type == "7z")
        } else null
        // A Collection Chamber installer granted alone: its parts, if it has any, are not readable here.
        val clickteam = type == "exe" && DosClickteam.isInstaller(context, file.uri)
        val contentId = inspection?.contentId ?: if (clickteam)
            DosClickteam.open(context, file.name, file.uri, emptyList()).use(DosClickteam::contentId)
            else hashSource(source, cancelled)
        val installer = clickteam || inspection?.exodosSource == true &&
            !isInstalledArchive(file.name)
        // What a Collection Chamber installer installed keeps the installer's catalog record.
        val installed = DosClickteam.installed(inspection?.comment)
        if (installed != null && !filesRecords.has(contentId))
            keepFilesRecord(listOf(installed.installer, contentId), DosClickteam.launch(context, file.uri, installed))
        // Keyed by its path relative to the library folder, or its URI where it has none (Kairo#56).
        return Game(file.key, file.uri.toString(), file.name,
            false, false, "${file.size}:${file.modified}", contentId, null, installer, true,
            catalogId = installed?.installer ?: contentId)
    }

    /** A new game [name] on an empty C: drive, which [saveBlank] writes to the games folder as `<name>.zip`. */
    fun blank(name: String) = Game(blankPath(name), "", blankPath(name), folder = false, fingerprint = "",
        contentId = BLANK_CONTENT, blank = true)

    /**
     * Writes the files a [blank] game's C: drive holds ([drive], its overlay) to the games folder
     * as its `<name>.zip`, the game the library lists from now on; null when the drive holds none.
     */
    fun saveBlank(game: Game, drive: File, tree: Uri, cancelled: AtomicBoolean): Game? {
        require(game.blank) { "${game.path} is not a new game" }
        val files = drive.walkTopDown().filter { it.isFile && !it.name.startsWith("DBOVERLAY", true) }.toList()
        if (files.isEmpty()) return null
        val root = DocumentsContract.getTreeDocumentId(tree)
        check(childId(tree, root, game.path, false) == null) { context.getString(R.string.blank_game_exists, game.path) }
        val created = DocumentsContract.createDocument(context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, root), "application/zip", game.path)
            ?: error(context.getString(R.string.blank_game_not_saved, game.path))
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            ZipOutputStream(output).use { zip ->
                files.sortedBy { it.path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(drive).invariantSeparatorsPath))
                    file.inputStream().use { copyChecked(it, zip, cancelled) }
                    zip.closeEntry()
                }
            }
        } ?: error(context.getString(R.string.blank_game_not_saved, game.path))
        return Game(game.path, uri.toString(), game.path, folder = false, fingerprint = "",
            contentId = DosContentHash.zipDocument(context, uri, cancelled))
    }

    /** A folder the player picked for a Disks drive (MainActivity.mediaSlots), read as a folder game is. */
    fun inspectExternalFolder(tree: Uri, cancelled: AtomicBoolean): Game {
        val files = walker.scan(tree, cancelled, { true }, {}).files
        val name = DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
            .ifEmpty { DocumentsContract.getTreeDocumentId(tree) }
        return Game(tree.toString(), tree.toString(), name, folder = true, fingerprint = "",
            contentId = DosContentHash.documents(context, files, cancelled), external = true)
    }

    /**
     * What a Disks drive opens for [game], kept apart from the running game's copy (prepare): an
     * image as itself, an archive or a folder as a folder of its files. Made again when its source changed.
     */
    fun prepareMedia(game: Game, tree: Uri?, cancelled: AtomicBoolean): File {
        require(game.playable) { "Game is not ready" }
        val type = extension(game.path)
        val image = !game.folder && type in otherExtensions
        if (image && isDisc(game.path)) return prepareDisc(game, tree,
            File(mediaCache, "${game.key.hashName}-${game.contentId!!.substringAfter(':').take(16)}-disc"), cancelled)
        val target = File(mediaCache, "${game.key.hashName}-${game.contentId!!.substringAfter(':').take(16)}" +
            if (image) ".$type" else "")
        if (target.exists()) return target
        target.parentFile!!.mkdirs()
        if (image) {
            val copied = copySource(DocumentTreeWalker.FileEntry(Uri.parse(game.uri), game.path, -1, 0), cancelled)
            try {
                require(hashFile(copied, cancelled) == game.contentId) { context.getString(R.string.game_changed_since_scan) }
                require(copied.renameTo(target)) { "Could not prepare ${game.path}" }
            } finally { copied.delete() }
            return target
        }
        val pending = File(mediaCache, target.name + ".part")
        pending.deleteRecursively()
        try {
            if (game.folder) {
                val files = if (game.external) walker.scan(Uri.parse(game.uri), cancelled, { true }, {}).files
                    else walker.scan(tree ?: error(context.getString(R.string.choose_dos_folder_for_game)),
                        cancelled, { true }, {}).files.filter { game.rootFolder || it.path.startsWith("${game.path}/") }
                require(DosContentHash.documents(context, files, cancelled) == game.contentId) {
                    context.getString(R.string.game_changed_since_scan)
                }
                val prefix = files.map { it.path.substringBefore('/') }.distinct().singleOrNull()
                    ?.takeIf { root -> files.all { it.path.startsWith("$root/") } }?.plus("/") ?: ""
                files.forEach { item ->
                    val file = File(pending, item.path.removePrefix(prefix))
                    require(file.canonicalPath.startsWith(pending.canonicalPath + File.separator)) {
                        context.getString(R.string.game_archive_unsafe)
                    }
                    file.parentFile!!.mkdirs()
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        file.outputStream().use { copyChecked(input, it, cancelled) }
                    } ?: error("Unable to read ${item.path}")
                }
            } else {
                // Not through prepare, which keeps only one prepared copy: the running game's.
                val copied = copySource(DocumentTreeWalker.FileEntry(Uri.parse(game.uri), game.path, -1, 0), cancelled)
                val archive = if (type != "7z") copied else File.createTempFile("dos-", ".zip.part", mediaCache).also { zip ->
                    try { DosZip.sevenZToZip(copied, zip, cancelled) } finally { copied.delete() }
                }
                try {
                    require(DosContentHash.zip({ context.text(it) }, archive, cancelled) == game.contentId) {
                        context.getString(R.string.game_changed_since_scan)
                    }
                    DosZip.open(archive).use { zip ->
                        zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                            val file = File(pending, entry.name.replace('\\', '/'))
                            require(file.canonicalPath.startsWith(pending.canonicalPath + File.separator)) {
                                context.getString(R.string.game_archive_unsafe)
                            }
                            file.parentFile!!.mkdirs()
                            zip.getInputStream(entry).use { input -> file.outputStream().use { copyChecked(input, it, cancelled) } }
                        }
                    }
                } finally { archive.delete() }
            }
            require(pending.renameTo(target)) { "Could not prepare ${game.path}" }
        } finally { pending.deleteRecursively() }
        return target
    }

    private sealed interface ScanWork {
        /** A file game; a CUE sheet's [data] is its track data in order, empty when it is not there. */
        data class Archive(val file: DocumentTreeWalker.FileEntry,
                           val data: List<DocumentTreeWalker.FileEntry> = emptyList()) : ScanWork
        data class Folder(val tree: Uri, val root: String,
                          val members: List<DocumentTreeWalker.FileEntry>) : ScanWork
        /** A Collection Chamber installer: its [program] and its [parts] (`.D01` on), in order. */
        data class Installer(val program: DocumentTreeWalker.FileEntry,
                             val parts: List<DocumentTreeWalker.FileEntry>) : ScanWork
    }

    fun scan(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean,
             progress: (String) -> Unit): List<Game> =
        scanPipeline.scan(tree, depth, cancelled, progress)

    private fun planScan(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean,
                         progress: (String) -> Unit): LibraryScanPipeline.Plan<ScanWork, Game> {
        val source = walker.scan(tree, cancelled, { true }, progress, folderListings, depth)
        // Installers, with their parts, are games of their own; the rest of the folder is laid out as before.
        val installerWork = installers(source.files, cancelled)
        val claimed = installerWork.flatMap { listOf(it.program) + it.parts }.mapTo(HashSet()) { it.path }
        val all = source.files.filter { it.path !in claimed }
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
        val work = ArrayList<ScanWork>(installerWork)
        val byPath = all.associateBy { it.path.lowercase(Locale.ROOT) }
        work.addAll(archives.map { item ->
            ScanWork.Archive(item, if (discs.isDescriptor(item.path)) trackData(item, byPath) else emptyList())
        })
        folderGroups.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (root, members) ->
            work.add(ScanWork.Folder(tree, root, members))
        }
        val folderErrors = source.folders.map { folder ->
            Game(folder.path, folder.uri.toString(), folder.path,
                true, false, "", null, context.getString(R.string.unreadable_folder, folder.message))
        }
        return LibraryScanPipeline.Plan(work, finalEntries = folderErrors)
    }

    /**
     * The Collection Chamber installers among [files], with their parts beside them. A program is
     * read only when it could be one: the only program in its folder, or one with a `.D01` part
     * beside it; its bytes decide ([installerProbes] keeps the answer).
     */
    private fun installers(files: List<DocumentTreeWalker.FileEntry>, cancelled: AtomicBoolean): List<ScanWork.Installer> {
        val found = ArrayList<ScanWork.Installer>()
        for (members in files.groupBy { it.path.substringBeforeLast('/', "") }.values) {
            val siblings = members.associateBy { it.path.substringAfterLast('/').lowercase(Locale.ROOT) }
            val programs = members.filter { extension(it.path) in executableExtensions }
            for (program in programs) {
                if (extension(program.path) != "exe") continue
                val parts = DosClickteam.parts(program.path.substringAfterLast('/'), siblings)
                if (programs.size > 1 && parts.isEmpty()) continue
                require(!cancelled.get()) { "Cancelled" }
                // A program that cannot be read is listed as an installer, whose inspection then
                // fails with why, on its own entry; it is not kept, so the next scan reads it again.
                val installer = runCatching {
                    installerProbes.isInstaller(program) { DosClickteam.isInstaller(context, program.uri) }
                }.getOrElse { true }
                if (installer) found += ScanWork.Installer(program, parts)
            }
        }
        installerProbes.save()
        return found
    }

    private fun installerFingerprint(work: ScanWork.Installer) =
        (listOf(work.program) + work.parts).joinToString(":") { "${it.size}:${it.modified}" }

    /** An installer, named by its file list; one missing a part its files need fails with that part's name. */
    private fun inspectInstaller(work: ScanWork.Installer, scan: LibraryScanPipeline.ScanContext<Game>): List<Game> {
        val item = work.program
        val fingerprint = installerFingerprint(work)
        val contentId = knownContent(scan.prior[item.path], fingerprint, DosClickteam.PREFIX) ?: run {
            scan.hashStarted(item.path)
            DosClickteam.open(context, item.path.substringAfterLast('/'), item.uri, work.parts.map { it.uri })
                .use(DosClickteam::contentId)
        }
        return listOf(Game(item.path, item.uri.toString(), item.path, false, false, fingerprint, contentId, null,
            installer = true, catalogId = catalogId(scan, item.path, contentId)))
    }

    private fun describe(work: ScanWork): String = when (work) {
        is ScanWork.Archive -> work.file.path
        is ScanWork.Folder -> folderDisplay(work)
        is ScanWork.Installer -> work.program.path
    }

    /** A game the library has scanned before keeps its identity; only Recheck all files hashes it again. */
    private fun known(work: ScanWork, scan: LibraryScanPipeline.ScanContext<Game>): Boolean = when (work) {
        is ScanWork.Archive -> scan.prior[work.file.path] != null
        is ScanWork.Folder -> scan.prior[folderId(work)] != null
        is ScanWork.Installer -> scan.prior[work.program.path] != null
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
            is ScanWork.Archive -> if (discs.isDescriptor(work.file.path)) inspectDisc(work, scan)
                else inspectArchive(work.file, scan)
            is ScanWork.Folder -> inspectFolder(work, scan)
            is ScanWork.Installer -> inspectInstaller(work, scan)
        }

    private fun inspectArchive(item: DocumentTreeWalker.FileEntry,
                               scan: LibraryScanPipeline.ScanContext<Game>): List<Game> {
        val id = item.path
        val fingerprint = "${item.size}:${item.modified}"
        val old = scan.prior[id]
        var installer = old?.installer == true &&
            !isInstalledArchive(item.path.substringAfterLast('/'))
        // What a Collection Chamber installer installed: it keeps the installer's catalog record.
        var installedFrom: String? = null
        val contentId = knownContent(old, fingerprint, if (extension(item.path) in archiveExtensions)
                "sha256-dos-manifest-v1:" else "sha256-dos-file-v1:") ?: run {
            scan.hashStarted(item.path)
            if (extension(item.path) in archiveExtensions) {
                val inspection = DosContentHash.inspectZipDocument(
                    context, item.uri, scan.cancelled, sevenZ = extension(item.path) == "7z")
                installer = inspection.exodosSource &&
                    !isInstalledArchive(item.path.substringAfterLast('/'))
                DosClickteam.installed(inspection.comment)?.let { installed ->
                    installedFrom = installed.installer
                    if (!filesRecords.has(inspection.contentId))
                        keepFilesRecord(listOf(installed.installer, inspection.contentId), DosClickteam.launch(context, item.uri, installed))
                }
                inspection.contentId
            } else hashSource(item, scan.cancelled)
        }
        return listOf(Game(id, item.uri.toString(), item.path, false, false,
            fingerprint, contentId, null, installer,
            catalogId = scan.earlier[id]?.catalogId ?: installedFrom ?: contentId))
    }

    /** A CUE sheet, named by its track data's content; an error entry when the data is not beside it. */
    private fun inspectDisc(work: ScanWork.Archive, scan: LibraryScanPipeline.ScanContext<Game>): List<Game> {
        val item = work.file
        if (work.data.isEmpty()) return listOf(Game(item.path, item.uri.toString(), item.path, false, false, "", null,
            context.getString(FrontendR.string.kairo_disk_needs_track_data, item.path.substringAfterLast('/'))))
        val fingerprint = "${item.size}:${item.modified}" + work.data.joinToString("") { ":${it.size}:${it.modified}" }
        val contentId = knownContent(scan.prior[item.path], fingerprint, "sha256-dos-file-v1:") ?: run {
            scan.hashStarted(item.path)
            hashDocuments(work.data, scan.cancelled)
        }
        return listOf(Game(item.path, item.uri.toString(), item.path, false, false,
            fingerprint, contentId, null, catalogId = catalogId(scan, item.path, contentId)))
    }

    /**
     * [game]'s CUE sheet and its track data, copied into [target] under their own names, where the
     * sheet's FILE lines find the data; returns the sheet. The data is verified against the content ID.
     */
    private fun prepareDisc(game: Game, tree: Uri?, target: File, cancelled: AtomicBoolean): File {
        if (isChd(game.path)) return prepareChd(game, target, cancelled)
        val sheetName = game.path.substringAfterLast('/')
        if (target.isDirectory) return File(target, sheetName)
        val data = trackData(tree ?: error(context.getString(R.string.choose_dos_folder_for_game)), game.path)
        val pending = File(target.parentFile, target.name + ".part")
        pending.deleteRecursively()
        try {
            pending.mkdirs()
            val hash = FileHash(cancelled)
            data.forEach { (dataPath, dataUri) ->
                context.contentResolver.openInputStream(dataUri)?.use { input ->
                    File(pending, dataPath.substringAfterLast('/')).outputStream().use { hash.add(input, it) }
                } ?: error("Unable to read $dataPath")
            }
            require(hash.id() == game.contentId) { context.getString(R.string.game_changed_since_scan) }
            val sheet = copySource(DocumentTreeWalker.FileEntry(Uri.parse(game.uri), game.path, -1, 0), cancelled)
            try {
                require(sheet.renameTo(File(pending, sheetName))) { "Could not prepare ${game.path}" }
            } finally { sheet.delete() }
            DurableFiles.sync(pending)
            require(pending.renameTo(target)) { "Could not prepare ${game.path}" }
        } finally { pending.deleteRecursively() }
        return File(target, sheetName)
    }

    /** A disc: a CUE sheet with its track data, or a CD kept as a CHD (KairoDos#146). */
    private fun isDisc(path: String) = discs.isDescriptor(path) || isChd(path)

    private fun isChd(path: String) = extension(path) == "chd"

    /** The cue sheet a disc's prepared folder holds: its own, or the one written for a CHD. */
    private fun sheetName(path: String) = path.substringAfterLast('/').let { name ->
        if (isChd(name)) name.substringBeforeLast('.') + ".cue" else name
    }

    /**
     * [game], a CHD, as the disc image it was made from (the BIN its content ID hashes, shared
     * ChdImage) and that image's cue sheet in [target], which DOSBox mounts as it mounts a BIN/CUE;
     * returns the sheet. The image is verified against the content ID as it is written.
     */
    private fun prepareChd(game: Game, target: File, cancelled: AtomicBoolean): File {
        val sheet = File(target, sheetName(game.path))
        if (target.isDirectory) return sheet
        val binName = sheet.name.substringBeforeLast('.') + ".bin"
        val pending = File(target.parentFile, target.name + ".part")
        pending.deleteRecursively()
        try {
            pending.mkdirs()
            openChd(Uri.parse(game.uri), game.path) { chd ->
                val written = File(pending, binName).outputStream().use { output -> fileId(chd.inputStream(), cancelled, output) }
                require(written.first == game.contentId) { context.getString(R.string.game_changed_since_scan) }
                File(pending, sheet.name).writeText(chd.cue(binName))
            }
            DurableFiles.sync(pending)
            require(pending.renameTo(target)) { "Could not prepare ${game.path}" }
        } finally { pending.deleteRecursively() }
        return sheet
    }

    private fun <T> openChd(uri: Uri, path: String, read: (ChdImage) -> T): T =
        (context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to read $path")).use { document ->
            ChdImage.open(document).use(read)
        }

    /** A source's content ID: its bytes, or a CHD's disc image, which its BIN/CUE has too. */
    private fun hashSource(source: DocumentTreeWalker.FileEntry, cancelled: AtomicBoolean): String =
        if (isChd(source.path)) openChd(source.uri, source.path) { fileId(it.inputStream(), cancelled).first }
        else hashDocument(source, cancelled)

    /**
     * A CUE sheet's track data among the scanned files [byPath] (lower-case paths): the BIN under its
     * own name, else the files its FILE lines name, in order; empty when it has none, which fails its entry alone.
     */
    private fun trackData(sheet: DocumentTreeWalker.FileEntry,
                          byPath: Map<String, DocumentTreeWalker.FileEntry>): List<DocumentTreeWalker.FileEntry> {
        discs.dataOf(sheet.path, byPath)?.let { return listOf(it) }
        if (!discs.isCue(sheet.path)) return emptyList()
        val text = context.contentResolver.openInputStream(sheet.uri)?.use { String(it.readBytes()) } ?: return emptyList()
        return discs.filesOf(sheet.path, text, byPath).orEmpty()
    }

    /** The track data of the CUE sheet at [sheet] in the games folder [tree], in order: each one's path and document. */
    private fun trackData(tree: Uri, sheet: String): List<Pair<String, Uri>> {
        val folder = sheet.substringBeforeLast('/', "")
        var parent = DocumentsContract.getTreeDocumentId(tree)
        if (folder.isNotEmpty()) folder.split('/').forEach { name ->
            parent = childId(tree, parent, name, true) ?: error(context.getString(R.string.game_folder_moved))
        }
        val children = HashMap<String, Pair<String, String>>()
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) children[cursor.getString(1).lowercase(Locale.ROOT)] = cursor.getString(1) to cursor.getString(0)
        } ?: error(context.getString(R.string.game_folder_moved))
        val sheetName = sheet.substringAfterLast('/')
        val files = discs.dataOf(sheetName, children)?.let { listOf(it) } ?: if (!discs.isCue(sheetName)) null else {
            val (_, sheetId) = children[sheetName.lowercase(Locale.ROOT)] ?: error(context.getString(R.string.game_folder_moved))
            val text = context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, sheetId))
                ?.use { String(it.readBytes()) } ?: error(context.getString(R.string.game_folder_moved))
            discs.filesOf(sheetName, text, children)
        } ?: error(context.getString(FrontendR.string.kairo_disk_needs_track_data, sheetName))
        return files.map { (name, id) ->
            (if (folder.isEmpty()) name else "$folder/$name") to DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
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
        is ScanWork.Installer -> listOf(Game(work.program.path, work.program.uri.toString(), work.program.path,
            false, false, installerFingerprint(work), null, error.message ?: context.getString(R.string.unreadable_game),
            installer = true, catalogId = scan.earlier[work.program.path]?.catalogId))
    }

    /** Installs [game], an installer of the games folder [tree], beside it (DosInstallers). */
    fun install(game: Game, tree: Uri, cancelled: AtomicBoolean,
                progress: (String) -> Unit = {}): Game = installers.install(game, tree, cancelled, progress)

    /** Keep the installed copy private when the launcher only grants one source file. */
    fun installExternal(game: Game, cancelled: AtomicBoolean,
                        progress: (String) -> Unit = {}): Game = installers.installExternal(game, cancelled, progress)

    /** Removes the installer [game] of the games folder [tree], once [installed] is there intact. */
    fun deleteInstaller(game: Game, installed: Game, tree: Uri) = installers.deleteInstaller(game, installed, tree)

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

    private fun childId(tree: Uri, parentId: String, name: String, directory: Boolean): String? =
        documents.childId(tree, parentId, name, directory)

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
        val disc = !game.folder && isDisc(game.path)
        val discTarget = File(cache, target.name.substringBeforeLast('.') + "-disc")
        if (target.isFile) return target
        if (disc && discTarget.isDirectory) return File(discTarget, sheetName(game.path))
        // Only the game being started keeps a prepared copy; every other one is made again when played.
        cache.listFiles()?.filter { it.name != target.name && it.name != discTarget.name && !it.name.endsWith(".part") }
            ?.forEach { it.deleteRecursively() }
        if (disc) return prepareDisc(game, tree, discTarget, cancelled)
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

    private fun hashFile(file: File, cancelled: AtomicBoolean): String =
        file.inputStream().use { fileId(it, cancelled).first }

    private fun hashDocument(source: DocumentTreeWalker.FileEntry,
                             cancelled: AtomicBoolean): String = hashDocuments(listOf(source), cancelled)

    /** The content ID of [sources]' bytes joined in order: a CUE's track files (KairoTowns#100). */
    private fun hashDocuments(sources: List<DocumentTreeWalker.FileEntry>, cancelled: AtomicBoolean): String {
        require(sources.sumOf { it.size } <= MAX_ARCHIVE_BYTES) { context.getString(R.string.game_too_large) }
        val hash = FileHash(cancelled)
        sources.forEach { source ->
            val read = context.contentResolver.openInputStream(source.uri)?.use { hash.add(it) }
                ?: error("Unable to read ${source.path}")
            if (source.size >= 0) require(read == source.size) { context.getString(R.string.game_changed_while_hashing) }
        }
        return hash.id()
    }

    /** The `sha256-dos-file-v1` ID of [input]'s bytes and their count, written to [output] as they are read. */
    private fun fileId(input: java.io.InputStream, cancelled: AtomicBoolean,
                       output: java.io.OutputStream? = null): Pair<String, Long> {
        val hash = FileHash(cancelled)
        val total = hash.add(input, output)
        return hash.id() to total
    }

    /** A `sha256-dos-file-v1` ID over the streams [add]ed, read one after another as one file. */
    private inner class FileHash(private val cancelled: AtomicBoolean) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(65536)
        private var total = 0L

        /** Reads [input] into the ID, writing it to [output] as it is read; returns its byte count. */
        fun add(input: java.io.InputStream, output: java.io.OutputStream? = null): Long {
            var read = 0L
            while (true) {
                require(!cancelled.get()) { "Cancelled" }
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                total += count
                require(total <= MAX_ARCHIVE_BYTES) { context.getString(R.string.game_too_large) }
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
            return read
        }

        fun id() = "sha256-dos-file-v1:${digest.digest().joinToString("") { "%02x".format(it) }}"
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
