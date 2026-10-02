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
        require(zip.size() == 259) { "Invalid catalog file count" }
        val expected = (0..255).map { "%02x.json".format(it) } +
            listOf("folders.json", "controller-profiles-v1.json", "hidden-index-v1.json")
        val actual = zip.entries().asSequence().map { it.name }.toList()
        require(actual.size == actual.distinct().size && actual.toSet() == expected.toSet()) {
            "Invalid catalog file names"
        }
        val contentId = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
        val visibility = readEntry(zip, "hidden-index-v1.json")
        require(visibility.optInt("schemaVersion") == 1)
        val hidden = visibility.getJSONObject("hidden")
        val seenHidden = HashSet<String>()
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
                    root.optJSONObject("presets")?.let { presets ->
                        for (profile in presets.keys()) {
                            val preset = presets.getJSONObject(profile)
                            preset.optJSONArray("bindings")?.let {
                                require(DosControllerBindings.valid(it)) { "Invalid controller bindings: $profile" }
                            }
                            if (preset.has("defaults")) {
                                val defaults = preset.getJSONObject("defaults")
                                require(defaults.has("withoutSticks") && defaults.keys().asSequence().all {
                                    it in setOf("withoutSticks", "withSticks") &&
                                        defaults.optJSONArray(it)?.let(DosControllerBindings::valid) == true
                                }) { "Invalid controller defaults: $profile" }
                            }
                            require(preset.has("bindings") || preset.has("defaults"))
                        }
                        root.optJSONObject("assignments")?.let { assignments ->
                            require(assignments.keys().asSequence().all {
                                contentId.matches(it) && assignments.opt(it) is String && presets.has(assignments.optString(it))
                            }) { "Invalid controller assignments" }
                        }
                    }
                }
                "hidden-index-v1.json" -> Unit
                else -> {
                    require(root.optInt("schemaVersion") == 1)
                    val games = root.getJSONObject("games")
                    val prefix = name.take(2)
                    for (id in games.keys()) {
                        require(contentId.matches(id) && id.substringAfter(':').startsWith(prefix) &&
                            games.optJSONObject(id) != null) { "Invalid catalog game record" }
                        val record = games.getJSONObject(id)
                        val invalid = DosCatalogFields.invalidPath(record)
                        require(invalid == null) {
                            "Invalid catalog field $id:$invalid"
                        }
                        val flag = record.opt("hidden")
                        if (flag is Boolean) {
                            require(hidden.opt(id) == flag) { "Catalog hidden index differs from $id" }
                            seenHidden += id
                        }
                    }
                }
            }
        }
        require(hidden.keys().asSequence().toSet() == seenHidden) {
            "Catalog hidden index contains unknown entries"
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
