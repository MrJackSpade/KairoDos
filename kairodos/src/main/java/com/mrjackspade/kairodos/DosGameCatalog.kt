package com.mrjackspade.kairodos

import android.content.Context
import android.util.LruCache
import android.util.AtomicFile
import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import com.mrjackspade.kairo.frontend.LibraryCatalog
import com.mrjackspade.kairo.frontend.LibraryGame
import com.mrjackspade.kairo.frontend.CatalogArtworkStore
import com.mrjackspade.kairo.frontend.GameMetadataOverrides
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.io.ByteArrayOutputStream
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
        val launch: Launch?,
        val controllerProfile: String?
    ) : LibraryGame

    private val cache = object : LruCache<String, JSONObject>(8) {}
    private val bundledCache = object : LruCache<String, JSONObject>(8) {}
    private val online = DosCatalogUpdate(context)
    private val bundledHidden = readAssetCatalog("hidden-index-v1.json")
        .optJSONObject("hidden") ?: JSONObject()
    private var onlineHidden = online.read("hidden-index-v1.json")
        ?.optJSONObject("hidden") ?: JSONObject()
    private val artworkStore = CatalogArtworkStore(context, { false },
        "art/catalog/dos/")
    // Dependency folder lookup is only needed when preparing a game launch.
    // Parsing this large index while opening the library delays the first frame.
    private var folderIndex: JSONObject? = null
    private val id = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
    private var doomContentIds = readDoomContentIds()
    private val overridesFile = File(context.filesDir, "dos-overrides-v1.json")
    private val overrides = GameMetadataOverrides(overridesFile)
    private val userCatalogFile = File(context.filesDir, "user-dos-catalog-v1.json")
    private var userCatalog = readUserCatalog()

    @Synchronized fun reloadUserCatalog() { userCatalog = readUserCatalog() }

    /** Call off the main thread; a failed download leaves the active catalog in place. */
    fun downloadUpdate(): Boolean {
        if (!online.download()) return false
        synchronized(this) {
            cache.evictAll()
            folderIndex = null
            onlineHidden = online.read("hidden-index-v1.json")
                ?.optJSONObject("hidden") ?: JSONObject()
            doomContentIds = readDoomContentIds()
        }
        return true
    }

    private fun readAssetCatalog(name: String): JSONObject = runCatching {
        context.assets.open("catalog/dos/$name").use { input ->
            JSONObject(input.bufferedReader().readText())
        }
    }.getOrDefault(JSONObject())

    private fun readFolderIndex(): JSONObject {
        val combined = JSONObject(readAssetCatalog("folders.json").toString())
        online.read("folders.json")?.let { update ->
            for (folder in update.keys()) combined.put(folder, update.get(folder))
        }
        return combined
    }

    private fun readDoomContentIds(): Set<String> = runCatching {
        val games = online.read("controller-profiles-v1.json")
            ?.optJSONObject("profiles")?.optJSONArray("doom-v1")
            ?: readAssetCatalog("controller-profiles-v1.json")
                .getJSONObject("profiles").getJSONArray("doom-v1")
        (0 until games.length()).map(games::getString).toSet()
    }.getOrDefault(emptySet())

    @Synchronized override fun resolve(contentId: String, fileName: String): Game {
        val record = layered(contentId, fileName).record
        val description = record.optString("description").takeIf { it.isNotBlank() }
        val baseTitle = record.optString("title").takeIf { it.isNotBlank() }
            ?: fileName.substringAfterLast('/')
        val title = if (fileName.endsWith(" - Installer", true) &&
            !baseTitle.endsWith(" - Installer", true)) "$baseTitle - Installer" else baseTitle
        val art = record.optJSONObject("artwork")
        val boxArtPath = DosCatalogFields.safeArtPath(art?.optString("boxArt"))
        val previewPath = DosCatalogFields.safeArtPath(art?.optString("preview"))
        val launch = record.optJSONObject("launch")?.let { source ->
            val configs = source.optJSONObject("configs") ?: JSONObject()
            Launch(source.optString("folder"), configs.keys().asSequence()
                .associateWith { configs.optString(it) }, source.optBoolean("exception"))
        }
        return Game(title, description,
            artworkStore.availablePath(boxArtPath),
            artworkStore.availablePath(previewPath),
            record.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            } ?: emptyList(), launch,
            "doom-v1".takeIf { contentId in doomContentIds })
    }

    private fun selectVariant(found: JSONObject?, fileName: String): JSONObject? {
        if (fileName.isBlank()) return null
        val variants = found?.optJSONObject("variants")
        val sourceName = fileName.removeSuffix(" - Installer").lowercase(Locale.ROOT)
        return variants?.optJSONObject(sourceName)
            ?: variants?.optJSONObject("$sourceName.zip")
            ?: variants?.optJSONObject("$sourceName.dosz")
    }

    @Synchronized override fun hiddenFromLibrary(contentId: String): Boolean {
        if (!id.matches(contentId)) return false
        // The generated index avoids opening hundreds of catalog shards merely
        // to decide which cached rows should be visible at startup.
        val values = listOf(
            bundledHidden.opt(contentId),
            onlineHidden.opt(contentId),
            userCatalog.optJSONObject("games")?.optJSONObject(contentId)?.opt("hidden"),
            overrides.record(contentId)?.opt("hidden")
        )
        return values.filterIsInstance<Boolean>().lastOrNull() ?: false
    }

    private fun layered(contentId: String, fileName: String): CatalogFieldLayers.Result {
        val sources = ArrayList<CatalogFieldLayers.Source>()
        if (id.matches(contentId)) {
            val prefix = contentId.substringAfter(':').take(2)
            fun add(name: String, value: JSONObject?) {
                sources += CatalogFieldLayers.Source(name, value)
                selectVariant(value, fileName)?.let {
                    sources += CatalogFieldLayers.Source(name, it)
                }
            }
            add("Shipped catalog", bundledShard(prefix)
                ?.optJSONObject("games")?.optJSONObject(contentId))
            add("Updated catalog", shard(prefix)
                ?.optJSONObject("games")?.optJSONObject(contentId))
            add("User catalog", userCatalog.optJSONObject("games")
                ?.optJSONObject(contentId))
            add("User override", overrides.record(contentId))
        }
        return CatalogFieldLayers.merge(sources, DosCatalogFields::objectField,
            DosCatalogFields::validValue)
    }

    @Synchronized fun sourceOf(contentId: String, fileName: String,
                               vararg path: String): String =
        layered(contentId, fileName).sourceOf(*path)
            ?: if (path.size == 1 && path[0] == "title") "Filename" else "App default"

    private fun readUserCatalog(): JSONObject = runCatching {
        val output = ByteArrayOutputStream()
        AtomicFile(userCatalogFile).openRead().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) {
                    "User catalog is too large"
                }
                output.write(buffer, 0, count)
            }
        }
        JSONObject(output.toString(Charsets.UTF_8.name())).takeIf {
            it.optInt("schemaVersion") == 1 && it.optJSONObject("games") != null
        } ?: JSONObject()
    }.getOrDefault(JSONObject())

    @Synchronized fun contentIdsForFolder(folder: String): List<String> {
        val index = folderIndex ?: readFolderIndex().also { folderIndex = it }
        val matches = index.optJSONArray(folder.lowercase(Locale.ROOT)) ?: return emptyList()
        return (0 until matches.length()).map { matches.optString(it) }
    }

    @Synchronized fun setTitle(contentId: String, title: String?) {
        require(id.matches(contentId)) { "Hash this game first" }
        require(title == null || title.length in 1..160) { "Invalid title" }
        overrides.set(contentId, "title", title)
    }

    fun setArtworkOverride(contentId: String, kind: String, path: String?) {
        require(id.matches(contentId)) { "Hash this game first" }
        require(kind == "boxArt" || kind == "preview") { "Invalid artwork kind" }
        require(path == null || DosCatalogFields.safeArtPath(path) != null) {
            "Invalid DOS artwork path"
        }
        overrides.setSubfield(contentId, "artwork", kind, path)
    }

    fun resetOverrides(contentId: String) {
        require(id.matches(contentId)) { "Hash this game first" }
        overrides.clear(contentId)
    }

    override fun openArtwork(path: String): InputStream = artworkStore.open(path)

    private fun shard(prefix: String): JSONObject? {
        cache.get(prefix)?.let { return it }
        val parsed = online.read("$prefix.json") ?: JSONObject()
        cache.put(prefix, parsed)
        return parsed
    }

    private fun bundledShard(prefix: String): JSONObject? {
        bundledCache.get(prefix)?.let { return it }
        val parsed = runCatching { context.assets.open("catalog/dos/$prefix.json").use {
            JSONObject(it.bufferedReader().readText())
        } }.getOrNull() ?: return null
        bundledCache.put(prefix, parsed)
        return parsed
    }

}
