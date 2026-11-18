// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The games folder's documents, by path: finding, creating and writing them through the folder's grant. */
internal class DosDocuments(private val context: Context) {
    /** The document named [name] (a folder when [directory]) among [parentId]'s children; null when there is none. */
    fun childId(tree: Uri, parentId: String, name: String, directory: Boolean): String? {
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

    /** The files among [parentId]'s children, by lower-case name: each one's name and document. */
    fun files(tree: Uri, parentId: String): Map<String, Pair<String, Uri>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val found = HashMap<String, Pair<String, Uri>>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                found[cursor.getString(1).lowercase(Locale.ROOT)] =
                    cursor.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        } ?: error("Could not read game folder")
        return found
    }

    /** The folder holding [path] (a path in the games folder [tree]), or [missing]'s failure when it has moved. */
    fun parentOf(tree: Uri, path: String, missing: Int): String {
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) for (segment in parentPath.split('/')) {
            parentId = childId(tree, parentId, segment, true) ?: error(context.getString(missing))
        }
        return parentId
    }

    /** The size and modification time the library's scan records for the document at [uri]. */
    fun fingerprint(uri: Uri): String {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "Installed game archive is missing" }
            val size = if (cursor.isNull(0)) -1 else cursor.getLong(0)
            val modified = if (cursor.isNull(1)) 0 else cursor.getLong(1)
            return "$size:$modified"
        } ?: error("Could not read installed game archive")
    }

    /**
     * Writes [source] to the document at [uri], then reads the document back whole and checks it
     * holds exactly those bytes. [copied] hears the MiB written.
     */
    fun writeVerified(source: File, uri: Uri, cancelled: AtomicBoolean, copied: (Long) -> Unit, verifying: () -> Unit) {
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
                        copied(written / (1024 * 1024))
                        nextReport += 64L * 1024 * 1024
                    }
                }
            } ?: error("Could not write installed game archive")
        }
        val expected = digest.digest()
        val actual = MessageDigest.getInstance("SHA-256")
        verifying()
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
    }
}
