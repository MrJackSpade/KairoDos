package com.mrjackspade.kairodos

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The player's catalog data from before KairoDos read the shared catalog (KairoDos#87), moved
 * once to the shared catalog's files and formats before it opens them.
 */
internal object DosCatalogMigration {
    /**
     * The player's own changes (dos-overrides-v1.json, keyed by game) and catalog file
     * (user-dos-catalog-v1.json) become the shared overrides-v1.json and user-catalog-v1.json; each
     * installed catalog of the old format (records with folders and controllers) becomes one the
     * shared catalog reads: its records' artwork, with its images, as the shared packages carry.
     */
    fun moveLocalFiles(directory: File) {
        move(File(directory, "dos-overrides-v1.json"), File(directory, "overrides-v1.json")) { old ->
            JSONObject().put("schemaVersion", 1).put("games", old)
        }
        move(File(directory, "user-dos-catalog-v1.json"), File(directory, "user-catalog-v1.json")) { old ->
            JSONObject().put("schemaVersion", 1).put("games", old.optJSONObject("games") ?: JSONObject())
        }
        File(directory, "installed-catalogs").listFiles { file -> file.name.endsWith(".zip") }
            ?.forEach(::convertInstalled)
    }

    private fun move(from: File, to: File, convert: (JSONObject) -> JSONObject) {
        if (!AtomicFile(from).baseFile.exists() && !File(from.path + ".bak").exists()) return
        check(!to.exists()) { "Both ${from.name} and ${to.name} exist" }
        val old = JSONObject(AtomicFile(from).readFully().toString(Charsets.UTF_8))
        val atomic = AtomicFile(to)
        val output = atomic.startWrite()
        try {
            output.write(convert(old).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        AtomicFile(from).delete()
    }

    /** Rewrites an old-format installed catalog's data.json in place; other entries are copied as they are. */
    private fun convertInstalled(file: File) {
        val data = ZipFile(file).use { zip ->
            val header = zip.getEntry("runtime.json") ?: zip.getEntry("catalog.json") ?: return
            val product = JSONObject(zip.getInputStream(header).use { it.readBytes() }.toString(Charsets.UTF_8))
            if (product.optString("product") != DosCatalog.platform) return
            JSONObject(zip.getInputStream(zip.getEntry("data.json") ?: return).use { it.readBytes() }.toString(Charsets.UTF_8))
        }
        if (data.has("nameIndex")) return
        val games = JSONObject()
        val old = data.optJSONObject("games") ?: JSONObject()
        for (id in old.keys()) artworkOnly(old.getJSONObject(id))?.let { games.put(id, it) }
        val converted = JSONObject().put("schemaVersion", 1).put("games", games)
            .put("nameIndex", JSONObject().put("schemaVersion", 1).put("games", JSONObject()).put("names", JSONObject()))
        val bytes = converted.toString().toByteArray(Charsets.UTF_8)
        val temporary = File(file.path + ".converting")
        ZipFile(file).use { zip ->
            ZipOutputStream(temporary.outputStream()).use { output ->
                for (entry in zip.entries()) {
                    val replaced = entry.name == "data.json"
                    // A stored entry names its size and checksum up front: the new data's, for data.json.
                    output.putNextEntry(ZipEntry(entry.name).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            size = if (replaced) bytes.size.toLong() else entry.size
                            compressedSize = size
                            crc = if (replaced) java.util.zip.CRC32().apply { update(bytes) }.value else entry.crc
                        }
                    })
                    if (replaced) output.write(bytes) else zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        android.system.Os.rename(temporary.absolutePath, file.absolutePath)
    }

    /**
     * A record's artwork, or null when it has none. The old format's file-name variants have no
     * counterpart: the catalog never picks a game by its file's name, so their artwork is left behind.
     */
    private fun artworkOnly(record: JSONObject): JSONObject? =
        record.optJSONObject("artwork")?.takeIf { it.length() > 0 }?.let { JSONObject().put("artwork", it) }
}
