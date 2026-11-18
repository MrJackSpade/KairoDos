// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.text
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The library's installers, each made into the game it installs on its first launch: an eXoDOS
 * source archive (its files copied to ` - Installed.zip`) or a Collection Chamber installer
 * (DosClickteam: what it installs written to ` - Installed.zip`). The installed archive is an
 * ordinary game of the folder; it keeps the installer's catalog record.
 */
internal class DosInstallers(private val context: Context, private val documents: DosDocuments, private val cache: File,
                             /** Keeps the launch an installer's files declare, under the installer's and its archive's IDs. */
                             private val keepLaunch: (Collection<String>, DosClickteamLaunch.Launch?) -> Unit,
                             /** DosLibrary.prepare: a game's verified copy in [cache]. */
                             private val prepare: (DosLibrary.Game, Uri?, AtomicBoolean) -> File) {

    /** Installs [game] beside it in the games folder [tree] and returns the installed game. */
    fun install(game: DosLibrary.Game, tree: Uri, cancelled: AtomicBoolean, progress: (String) -> Unit): DosLibrary.Game {
        require(game.installer && !game.folder && game.playable) { "Not an installer" }
        progress(context.getString(R.string.preparing_installer))
        return if (DosClickteam.isInstallerId(game.contentId)) installClickteam(game, tree, cancelled, progress)
            else installExodos(game, tree, cancelled, progress)
    }

    /** Materialize eXoDOS's ZIP extraction as a durable, separate archive. */
    private fun installExodos(game: DosLibrary.Game, tree: Uri, cancelled: AtomicBoolean, progress: (String) -> Unit): DosLibrary.Game {
        val source = prepare(game, tree, cancelled)
        return writeInstalled(game, tree, source, game.contentId!!, cancelled, progress)
    }

    /** What a Collection Chamber installer installs, as a ZIP beside it. */
    private fun installClickteam(game: DosLibrary.Game, tree: Uri, cancelled: AtomicBoolean, progress: (String) -> Unit): DosLibrary.Game {
        val parentId = documents.parentOf(tree, game.path, R.string.installer_folder_moved)
        val name = game.path.substringAfterLast('/')
        val parts = DosClickteam.parts(name, documents.files(tree, parentId)).map { it.second }
        val unpacked = File.createTempFile("dos-install-", ".zip.part", cache)
        try {
            val launch = unpack(game, name, parts, unpacked, cancelled, progress)
            val contentId = DosContentHash.zip({ context.text(it) }, unpacked, cancelled)
            keepLaunch(listOf(game.contentId!!, contentId), launch)
            return writeInstalled(game, tree, unpacked, contentId, cancelled, progress)
        } finally { unpacked.delete() }
    }

    /**
     * The installer [game] (its program [name] and [parts]) unpacked to the ZIP [target], which
     * records the installer and its shortcuts as its comment; it must be the installer the scan
     * saw. Returns the launch its shortcuts declare (DosClickteamLaunch), or null.
     */
    private fun unpack(game: DosLibrary.Game, name: String, parts: List<Uri>, target: File, cancelled: AtomicBoolean,
                       progress: (String) -> Unit): DosClickteamLaunch.Launch? =
        DosClickteam.open(context, name, Uri.parse(game.uri), parts).use { installer ->
            require(DosClickteam.contentId(installer) == game.contentId) { context.getString(R.string.game_changed_since_scan) }
            val entries = DosClickteam.entries(installer)
            val launch = DosClickteamLaunch.launch(DosClickteam.files(installer), entries)
            DosClickteam.writeZip(installer, target, DosClickteam.comment(DosClickteam.Installed(game.contentId!!, entries)), cancelled) { percent ->
                progress(context.getString(R.string.installing_unpacked, percent.toInt()))
            }
            launch
        }

    /**
     * Writes [source], the installed archive whose content ID is [contentId], to `<installer> - Installed.zip`
     * beside [game], or checks the one already there is it. [source] stays as the game's prepared copy.
     */
    private fun writeInstalled(game: DosLibrary.Game, tree: Uri, source: File, contentId: String, cancelled: AtomicBoolean,
                               progress: (String) -> Unit): DosLibrary.Game {
        val installedName = game.path.substringAfterLast('/').substringBeforeLast('.') + " - Installed.zip"
        val parentPath = game.path.substringBeforeLast('/', "")
        val parentId = documents.parentOf(tree, game.path, R.string.installer_folder_moved)
        val path = if (parentPath.isEmpty()) installedName else "$parentPath/$installedName"
        val existing = documents.childId(tree, parentId, installedName, false)
        val created = if (existing != null) DocumentsContract.buildDocumentUriUsingTree(tree, existing)
            else DocumentsContract.createDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parentId), "application/zip", installedName)
                ?: error("Could not create installed game archive")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
        try {
            if (existing == null) {
                documents.writeVerified(source, uri, cancelled,
                    { mib -> progress(context.getString(R.string.installing_copied, mib)) },
                    { progress(context.getString(R.string.installed_verifying)) })
            } else {
                progress(context.getString(R.string.installed_checking))
                require(DosContentHash.zipDocument(context, uri, cancelled) == contentId) {
                    context.getString(R.string.installed_name_taken)
                }
            }
            val staged = File(cache, "${GameKey(path).hashName}-${contentId.substringAfter(':').take(16)}.zip")
            if (!staged.isFile) source.renameTo(staged)
            // The scan's fingerprint, so the next scan reuses this verified content ID.
            return DosLibrary.Game(path, uri.toString(), path, false, false,
                documents.fingerprint(uri), contentId, null, false, catalogId = game.catalogId)
        } catch (failure: Exception) {
            if (existing == null) runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            throw failure
        }
    }

    /** Keep the installed copy private when the launcher only grants one source file. */
    fun installExternal(game: DosLibrary.Game, cancelled: AtomicBoolean, progress: (String) -> Unit): DosLibrary.Game {
        require(game.external && game.installer && game.playable) { "Not an external installer" }
        progress(context.getString(R.string.preparing_installer))
        val folder = File(context.filesDir, "installed-dos").apply { mkdirs() }
        val installed = File(folder, "${sha256("external:${game.contentId}")}.zip")
        val clickteam = DosClickteam.isInstallerId(game.contentId)
        val contentId = if (installed.isFile) {
            DosContentHash.zip({ context.text(it) }, installed, cancelled).also { found ->
                // An eXoDOS installed copy is the source's files; an installer's was checked as it was written.
                require(clickteam || found == game.contentId) { context.getString(R.string.installed_changed) }
            }
        } else {
            val pending = File.createTempFile("dos-install-", ".part", folder)
            try {
                val launch = if (clickteam) {
                    // A frontend grants the program alone: an installer in parts cannot be read.
                    unpack(game, game.path.substringAfterLast('/'), emptyList(), pending, cancelled, progress)
                } else null
                if (!clickteam) prepare(game, null, cancelled).inputStream().use { input ->
                    pending.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            require(!cancelled.get()) { "Cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val written = DosContentHash.zip({ context.text(it) }, pending, cancelled)
                require(clickteam || written == game.contentId) { context.getString(R.string.installed_verification_failed) }
                keepLaunch(listOf(game.contentId!!, written), launch)
                require(pending.renameTo(installed)) { "Could not save installed game" }
                written
            } finally { pending.delete() }
        }
        val name = game.path.substringBeforeLast('.') + " - Installed.zip"
        // Outside the library folder, its key is its own file's URI.
        val uri = Uri.fromFile(installed).toString()
        return DosLibrary.Game(uri, uri, name, false, false, "", contentId, null, false, true, game.catalogId)
    }

    /**
     * Removes [game], an installer of the games folder [tree], once [installed] (the game it
     * installed) is there intact: an eXoDOS archive, or an installer's program and its parts.
     */
    fun deleteInstaller(game: DosLibrary.Game, installed: DosLibrary.Game, tree: Uri) {
        require(game.installer) { "Not an installer" }
        val clickteam = DosClickteam.isInstallerId(game.contentId)
        require(!installed.installer && installed.catalogId == game.catalogId &&
            (clickteam || installed.contentId == game.contentId)) {
            context.getString(R.string.installed_unavailable)
        }
        val check = DosContentHash.zipDocument(context, Uri.parse(installed.uri), AtomicBoolean(false))
        require(check == installed.contentId) { context.getString(R.string.installed_unverified) }
        val parts = if (!clickteam) emptyList() else {
            val parentId = documents.parentOf(tree, game.path, R.string.installer_folder_moved)
            DosClickteam.parts(game.path.substringAfterLast('/'), documents.files(tree, parentId)).map { it.second }
        }
        for (uri in listOf(Uri.parse(game.uri)) + parts) require(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            context.getString(R.string.installer_remove_refused)
        }
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
