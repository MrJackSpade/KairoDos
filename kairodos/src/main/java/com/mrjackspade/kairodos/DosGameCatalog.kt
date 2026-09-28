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
    data class Game(
        override val title: String,
        override val description: String?,
        override val boxArt: String?,
        override val preview: String?,
        override val tags: List<String>
    ) : LibraryGame

    private val cache = object : LruCache<String, JSONObject>(8) {}
    private val id = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
    private val overridesFile = File(context.filesDir, "dos-overrides-v1.json")
    private var overrides = runCatching {
        JSONObject(AtomicFile(overridesFile).readFully().toString(Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    @Synchronized override fun resolve(contentId: String, fileName: String): Game {
        val found = if (id.matches(contentId)) shard(contentId.substringAfter(':').take(2))
            ?.optJSONObject("games")?.optJSONObject(contentId) else null
        val record = found?.optJSONObject("variants")
            ?.optJSONObject(fileName.lowercase(Locale.ROOT)) ?: found
        val title = overrides.optJSONObject(contentId)?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: record?.optString("title")?.takeIf { it.isNotBlank() }
            ?: fileName.substringAfterLast('/')
        val art = record?.optJSONObject("artwork")
        return Game(title, record?.optString("description")?.takeIf { it.isNotBlank() },
            art?.optString("boxArt")?.takeIf(::artExists),
            art?.optString("preview")?.takeIf(::artExists),
            record?.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            } ?: emptyList())
    }

    override fun hiddenFromLibrary(contentId: String) = false

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
