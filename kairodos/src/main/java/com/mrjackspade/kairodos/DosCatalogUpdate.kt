package com.mrjackspade.kairodos

import android.content.Context
import com.mrjackspade.kairo.frontend.CatalogSnapshotStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Validated online snapshot of the bundled, sharded DOS metadata. */
internal class DosCatalogUpdate(context: Context) {
    private val store = CatalogSnapshotStore(context, "dos-core-update-v2.zip",
        UPDATE_URL, METADATA_URL, MAX_ARCHIVE_BYTES, ::validateFile)
    private var archive: ZipFile? = store.activeFile()?.let(::ZipFile)

    @Synchronized fun read(name: String): JSONObject? = archive?.let { readEntry(it, name) }

    /** Call from a worker thread. A failed fetch or validation keeps the current catalog. */
    fun download(task: com.mrjackspade.kairo.frontend.CatalogUpdateTask = com.mrjackspade.kairo.frontend.CatalogUpdateTask()): Boolean {
        if (!store.download(task)) return false
        synchronized(this) {
            archive?.close()
            archive = store.activeFile()?.let(::ZipFile)
        }
        return true
    }

    internal fun validateFile(file: File) { ZipFile(file).use(::validate) }

    private fun validate(zip: ZipFile) {
        // CI audits the contents. Shards are parsed only when a game needs them.
        require(readEntry(zip, "core-v2.json").optInt("schemaVersion") == 2)
    }

    private fun readEntry(zip: ZipFile, name: String): JSONObject {
        val entry = zip.getEntry(name) ?: error("Catalog lacks $name")
        require(!entry.isDirectory && entry.size in 0..MAX_ENTRY_BYTES) { "Invalid catalog entry" }
        return JSONObject(zip.getInputStream(entry).use { input ->
            readBounded(input, MAX_ENTRY_BYTES).toString(Charsets.UTF_8)
        })
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Catalog entry is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
        private const val UPDATE_URL =
            "https://raw.githubusercontent.com/MrJackSpade/Kairo/main/catalog/dos/core-v2.zip"
        private const val METADATA_URL =
            "https://raw.githubusercontent.com/MrJackSpade/Kairo/main/catalog/dos/core-v2.json"
    }
}
