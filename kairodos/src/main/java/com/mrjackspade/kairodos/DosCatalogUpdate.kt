package com.mrjackspade.kairodos

import android.content.Context
import com.mrjackspade.kairo.frontend.CatalogSnapshotStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Validated online snapshot of the bundled, sharded DOS metadata. */
internal class DosCatalogUpdate(context: Context) {
    private val store = CatalogSnapshotStore(context, "dos-catalog-update-v1.zip",
        UPDATE_URL, METADATA_URL, MAX_ARCHIVE_BYTES, ::validateFile)
    private var archive: ZipFile? = store.activeFile()?.let(::ZipFile)

    @Synchronized fun read(name: String): JSONObject? = archive?.let { readEntry(it, name) }

    /** Call from a worker thread. A failed fetch or validation keeps the current catalog. */
    fun download(): Boolean {
        if (!store.download()) return false
        synchronized(this) {
            archive?.close()
            archive = store.activeFile()?.let(::ZipFile)
        }
        return true
    }

    private fun validateFile(file: File) { ZipFile(file).use(::validate) }

    private fun validate(zip: ZipFile) {
        require(zip.size() == 258) { "Invalid catalog file count" }
        val expected = (0..255).map { "%02x.json".format(it) } +
            listOf("folders.json", "controller-profiles-v1.json")
        val actual = zip.entries().asSequence().map { it.name }.toList()
        require(actual.size == actual.distinct().size && actual.toSet() == expected.toSet()) {
            "Invalid catalog file names"
        }
        val contentId = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
        for (name in expected) {
            val root = readEntry(zip, name)
            when (name) {
                "folders.json" -> for (folder in root.keys()) {
                    require(folder.length in 1..128 && root.optJSONArray(folder) != null) {
                        "Invalid catalog folder index"
                    }
                    val ids = root.getJSONArray(folder)
                    require(ids.length() <= 128 && (0 until ids.length()).all {
                        contentId.matches(ids.optString(it))
                    }) { "Invalid catalog folder IDs" }
                }
                "controller-profiles-v1.json" -> {
                    require(root.optInt("schemaVersion") == 1)
                    val profiles = root.getJSONObject("profiles")
                    for (profile in profiles.keys()) {
                        require(profile.matches(Regex("[a-z0-9-]{1,40}")))
                        val ids = profiles.getJSONArray(profile)
                        require(ids.length() <= 2000 && (0 until ids.length()).all {
                            contentId.matches(ids.optString(it))
                        }) { "Invalid controller profile IDs" }
                    }
                }
                else -> {
                    require(root.optInt("schemaVersion") == 1)
                    val games = root.getJSONObject("games")
                    val prefix = name.take(2)
                    for (id in games.keys()) {
                        require(contentId.matches(id) && id.substringAfter(':').startsWith(prefix) &&
                            games.optJSONObject(id) != null) { "Invalid catalog game record" }
                    }
                }
            }
        }
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
            "https://raw.githubusercontent.com/MrJackSpade/Kairo/main/catalog/dos/online-v1.zip"
        private const val METADATA_URL =
            "https://raw.githubusercontent.com/MrJackSpade/Kairo/main/catalog/dos/online-v1.json"
    }
}
