package com.mrjackspade.kairodos

import android.content.Context
import android.util.LruCache
import android.util.AtomicFile
import com.mrjackspade.kairo.frontend.LibraryCatalog
import com.mrjackspade.kairo.frontend.LibraryGame
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.util.Locale

/** Hash-keyed, data-only game metadata. Game media is never read from these records. */
class DosGameCatalog(private val context: Context) : LibraryCatalog {
    data class Launch(val folder: String, val configs: Map<String, String>,
                      val exception: Boolean)

    data class Game(
        override val title: String,
        override val description: String?,
        override val boxArt: String?,
        override val preview: String?,
        override val tags: List<String>,
        val launch: Launch?
    ) : LibraryGame

    private val cache = object : LruCache<String, JSONObject>(8) {}
    private val folderIndex by lazy { runCatching {
        context.assets.open("catalog/dos/folders.json").use { input ->
            JSONObject(input.bufferedReader().readText())
        }
    }.getOrDefault(JSONObject()) }
    private val id = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
    private val overridesFile = File(context.filesDir, "dos-overrides-v1.json")
    private var overrides = runCatching {
        JSONObject(AtomicFile(overridesFile).readFully().toString(Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    @Synchronized override fun resolve(contentId: String, fileName: String): Game {
        val found = if (id.matches(contentId)) shard(contentId.substringAfter(':').take(2))
            ?.optJSONObject("games")?.optJSONObject(contentId) else null
        val variants = found?.optJSONObject("variants")
        val sourceName = fileName.removeSuffix(" - Installer").lowercase(Locale.ROOT)
        val record = variants?.optJSONObject(sourceName)
            ?: variants?.optJSONObject("$sourceName.zip")
            ?: variants?.optJSONObject("$sourceName.dosz")
            ?: variants?.keys()?.asSequence()?.firstOrNull()?.let(variants::optJSONObject)
            ?: found
        val baseTitle = overrides.optJSONObject(contentId)?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: record?.optString("title")?.takeIf { it.isNotBlank() }
            ?: fileName.substringAfterLast('/')
        val title = if (fileName.endsWith(" - Installer", true) &&
            !baseTitle.endsWith(" - Installer", true)) "$baseTitle - Installer" else baseTitle
        val art = record?.optJSONObject("artwork")
        val launch = record?.optJSONObject("launch")?.let { source ->
            val configs = source.optJSONObject("configs") ?: JSONObject()
            Launch(source.optString("folder"), configs.keys().asSequence()
                .associateWith { configs.optString(it) }, source.optBoolean("exception"))
        }
        return Game(title, record?.optString("description")?.takeIf { it.isNotBlank() },
            art?.optString("boxArt")?.takeIf(::artExists),
            art?.optString("preview")?.takeIf(::artExists),
            record?.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            } ?: emptyList(), launch)
    }

    override fun hiddenFromLibrary(contentId: String) = false

    fun contentIdsForFolder(folder: String): List<String> {
        val matches = folderIndex.optJSONArray(folder.lowercase(Locale.ROOT)) ?: return emptyList()
        return (0 until matches.length()).map { matches.optString(it) }
    }

    @Synchronized fun setTitle(contentId: String, title: String?) {
        require(id.matches(contentId)) { "Hash this game first" }
        require(title == null || title.length in 1..160) { "Invalid title" }
        if (title == null) overrides.remove(contentId)
        else overrides.put(contentId, JSONObject().put("title", title))
        val atomic = AtomicFile(overridesFile)
        val output = atomic.startWrite()
        try {
            output.write(overrides.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    override fun openArtwork(path: String): InputStream {
        require(path.startsWith("art/catalog/dos/") && !path.contains("..") &&
            !path.contains('\\') && !path.contains(':')) { "Invalid artwork path" }
        return context.assets.open(path)
    }

    private fun artExists(path: String): Boolean = path.startsWith("art/catalog/dos/") &&
        !path.contains("..") && runCatching { context.assets.open(path).close() }.isSuccess

    private fun shard(prefix: String): JSONObject? {
        cache.get(prefix)?.let { return it }
        val parsed = runCatching { context.assets.open("catalog/dos/$prefix.json").use {
            JSONObject(it.bufferedReader().readText())
        } }.getOrNull() ?: return null
        cache.put(prefix, parsed)
        return parsed
    }
}
