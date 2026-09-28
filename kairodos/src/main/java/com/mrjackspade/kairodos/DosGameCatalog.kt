package com.mrjackspade.kairodos

import android.content.Context
import android.util.LruCache
import android.util.AtomicFile
import com.mrjackspade.kairo.frontend.LibraryCatalog
import com.mrjackspade.kairo.frontend.LibraryGame
import com.mrjackspade.kairo.frontend.CatalogArtworkStore
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.util.Locale
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

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
        val launch: Launch?,
        val controllerProfile: String?,
        val boxArtCatalogPath: String?,
        val previewCatalogPath: String?
    ) : LibraryGame

    data class ArtworkSource(val path: String, val url: String)

    private val cache = object : LruCache<String, JSONObject>(8) {}
    private val online = DosCatalogUpdate(context)
    private val artworkStore = CatalogArtworkStore(context, ::validArtworkUrl,
        "art/catalog/dos/")
    private var folderIndex = readCatalog("folders.json")
    private val id = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
    private var doomContentIds = readDoomContentIds()
    private val overridesFile = File(context.filesDir, "dos-overrides-v1.json")
    private var overrides = runCatching {
        JSONObject(AtomicFile(overridesFile).readFully().toString(Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    /** Call off the main thread; a failed download leaves the active catalog in place. */
    fun downloadUpdate(): Boolean {
        if (!online.download()) return false
        synchronized(this) {
            cache.evictAll()
            folderIndex = readCatalog("folders.json")
            doomContentIds = readDoomContentIds()
        }
        return true
    }

    private fun readCatalog(name: String): JSONObject = online.read(name) ?: runCatching {
        context.assets.open("catalog/dos/$name").use { input ->
            JSONObject(input.bufferedReader().readText())
        }
    }.getOrDefault(JSONObject())

    private fun readDoomContentIds(): Set<String> = runCatching {
        val games = readCatalog("controller-profiles-v1.json")
            .getJSONObject("profiles").getJSONArray("doom-v1")
        (0 until games.length()).map(games::getString).toSet()
    }.getOrDefault(emptySet())

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
        val boxArtPath = safeArtPath(art?.optString("boxArt"))
        val previewPath = safeArtPath(art?.optString("preview"))
        val launch = record?.optJSONObject("launch")?.let { source ->
            val configs = source.optJSONObject("configs") ?: JSONObject()
            Launch(source.optString("folder"), configs.keys().asSequence()
                .associateWith { configs.optString(it) }, source.optBoolean("exception"))
        }
        return Game(title, record?.optString("description")?.takeIf { it.isNotBlank() },
            artworkStore.availablePath(boxArtPath),
            artworkStore.availablePath(previewPath),
            record?.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            } ?: emptyList(), launch,
            "doom-v1".takeIf { found != null && contentId in doomContentIds },
            boxArtPath, previewPath)
    }

    override fun hiddenFromLibrary(contentId: String) = false

    @Synchronized fun contentIdsForFolder(folder: String): List<String> {
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

    override fun openArtwork(path: String): InputStream = artworkStore.open(path)

    fun missingArtworkFor(entries: List<DosLibrary.Game>): List<ArtworkSource> = entries.asSequence()
        .filter { it.playable }
        .flatMap { entry ->
            val game = resolve(entry.contentId ?: "", entry.displayName)
            listOfNotNull(game.boxArtCatalogPath, game.previewCatalogPath).asSequence()
        }
        .distinct()
        .filter { artworkStore.availablePath(it) == null }
        .map { ArtworkSource(it, "$ART_BASE_URL$it") }
        .toList()

    fun downloadArtwork(source: ArtworkSource, cancelled: AtomicBoolean) =
        artworkStore.download(source.path, source.url, cancelled)

    private fun safeArtPath(path: String?): String? = path?.takeIf {
        it.length in 17..256 && it.startsWith("art/catalog/dos/") &&
            it.matches(Regex("[a-zA-Z0-9/._-]+")) && !it.contains("..")
    }

    private fun validArtworkUrl(value: String): Boolean = try {
        if (value.length !in 1..512) false else URI(value).let { uri ->
            uri.scheme == "https" && uri.host == "raw.githubusercontent.com" &&
                uri.port == -1 && uri.userInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null && uri.rawPath.startsWith(ART_BASE_PATH)
        }
    } catch (_: Exception) { false }

    private fun shard(prefix: String): JSONObject? {
        cache.get(prefix)?.let { return it }
        val parsed = readCatalog("$prefix.json")
        cache.put(prefix, parsed)
        return parsed
    }

    companion object {
        private const val ART_BASE_PATH =
            "/MrJackSpade/KairoDos/main/kairodos/src/main/assets/"
        private const val ART_BASE_URL = "https://raw.githubusercontent.com$ART_BASE_PATH"
    }
}
