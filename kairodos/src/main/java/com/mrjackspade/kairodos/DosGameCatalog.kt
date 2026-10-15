package com.mrjackspade.kairodos

import android.content.Context
import android.util.LruCache
import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import com.mrjackspade.kairo.frontend.LocalCatalogFile
import com.mrjackspade.kairo.frontend.LibraryCatalog
import com.mrjackspade.kairo.frontend.LibraryGame
import com.mrjackspade.kairo.frontend.CatalogArtworkStore
import com.mrjackspade.kairo.frontend.GameMetadataOverrides
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.util.Locale
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
        val controllerProfile: String?
    ) : LibraryGame

    private val parts = com.mrjackspade.kairo.frontend.CatalogParts(context)
    private val cache = object : LruCache<String, JSONObject>(8) {}
    private val bundledCache = object : LruCache<String, JSONObject>(8) {}
    // A game's merged record and resolved metadata are reused until a catalog, override or
    // artwork change; settings pages read them for every row and every focus move.
    private val layeredMemo = HashMap<Pair<String, String>, CatalogFieldLayers.Result>()
    private val resolvedMemo = HashMap<Pair<String, String>, Game>()
    private var profileByGame: Map<String, String>? = null
    private fun invalidate() {
        layeredMemo.clear()
        resolvedMemo.clear()
        profileByGame = null
    }

    /** Only this game's overrides changed. */
    private fun invalidate(contentId: String) {
        layeredMemo.keys.removeAll { it.first == contentId }
        resolvedMemo.keys.removeAll { it.first == contentId }
    }
    override val installedCatalogs by lazy {
        com.mrjackspade.kairo.frontend.InstalledCatalogs(context, "dos", ::validateInstalled) {
            synchronized(this) {
                cache.evictAll(); folderIndex = null; combinedControllerProfiles = null
                invalidate()
            }
        }
    }
    private fun validateInstalled(root: JSONObject) {
        require(root.optInt("schemaVersion") == 1)
        root.getJSONObject("games")
        root.getJSONObject("folders")
        root.getJSONObject("controllers")
    }
    private val online = DosCatalogUpdate(context)
    private val bundledHidden = readAssetCatalog("hidden-index-v1.json")
        .optJSONObject("hidden") ?: JSONObject()
    private var onlineHidden = online.read("hidden-index-v1.json")
        ?.optJSONObject("hidden") ?: JSONObject()
    private val bundledControllerProfiles by lazy { readAssetCatalog("controller-profiles-v1.json") }
    private var onlineControllerProfiles = online.read("controller-profiles-v1.json")
    val artworkStore = CatalogArtworkStore(context, { url ->
        url.startsWith(ARTWORK_ROOT) && remoteArtworkPath.matches(url.removePrefix(ARTWORK_ROOT))
    },
        "art/catalog/dos/")
    // Dependency folder lookup is only needed when preparing a game launch.
    // Parsing this large index while opening the library delays the first frame.
    private var folderIndex: JSONObject? = null
    private val id = Regex("sha256-dos-(?:manifest|file)-v1:[0-9a-f]{64}")
    private val overridesFile = File(context.filesDir, "dos-overrides-v1.json")
    private val overrides = GameMetadataOverrides(overridesFile)
    private val userCatalogFile = File(context.filesDir, "user-dos-catalog-v1.json")
    private var userCatalog = readUserCatalog()

    @Synchronized fun reloadUserCatalog() {
        userCatalog = readUserCatalog()
        invalidate()
    }

    /** Call off the main thread; a failed download leaves the active catalog in place. */
    fun downloadUpdate(task: com.mrjackspade.kairo.frontend.CatalogUpdateTask = com.mrjackspade.kairo.frontend.CatalogUpdateTask()): Boolean {
        if (!online.download(task)) return false
        synchronized(this) {
            cache.evictAll()
            combinedControllerProfiles = null
            folderIndex = null
            invalidate()
            onlineHidden = online.read("hidden-index-v1.json")
                ?.optJSONObject("hidden") ?: JSONObject()
            onlineControllerProfiles = online.read("controller-profiles-v1.json")
        }
        return true
    }

    private fun readAssetCatalog(name: String): JSONObject = parts.read("dos/$name")

    private fun readFolderIndex(): JSONObject {
        val combined = JSONObject(readAssetCatalog("folders.json").toString())
        online.read("folders.json")?.let { update ->
            for (folder in update.keys()) combined.put(folder, update.get(folder))
        }
        for (catalog in installedCatalogs.catalogs()) catalog.data.optJSONObject("folders")?.let { folders ->
            for (folder in folders.keys()) combined.put(folder, folders.get(folder))
        }
        return combined
    }

    private var combinedControllerProfiles: JSONObject? = null
    private fun controllerProfileCatalog(): JSONObject {
        val installed = installedCatalogs.catalogs()
        if (installed.isEmpty()) return onlineControllerProfiles ?: bundledControllerProfiles
        combinedControllerProfiles?.let { return it }
        val result = JSONObject((onlineControllerProfiles ?: bundledControllerProfiles).toString())
        for (catalog in installed) {
            val extra = catalog.data.getJSONObject("controllers")
            for (field in listOf("profiles", "assignments", "presets")) {
                val target = result.optJSONObject(field) ?: JSONObject().also { result.put(field, it) }
                val values = extra.optJSONObject(field) ?: continue
                for (key in values.keys()) target.put(key, values.get(key))
            }
        }
        combinedControllerProfiles = result
        return result
    }

    fun controllerBindings(profileId: String, layout: com.mrjackspade.kairo.frontend.ControllerLayout =
        com.mrjackspade.kairo.frontend.ControllerLayout.WITHOUT_STICKS): String? = runCatching {
        controllerProfileCatalog().optJSONObject("presets")
            ?.optJSONObject(profileId)?.let { preset ->
                val defaults = preset.optJSONObject("defaults")
                defaults?.optJSONArray(layout.key)
                    ?: defaults?.optJSONArray("withoutSticks") ?: preset.optJSONArray("bindings")
            }?.toString()
    }.getOrNull()

    fun controllerFallback(profileId: String): Boolean =
        controllerProfileCatalog().optJSONObject("presets")?.optJSONObject(profileId)
            ?.optJSONObject("defaults")?.optJSONArray("withSticks") == null

    /** Only explicitly saved controls can supersede catalog data. Defaults are never saved here. */
    fun gameControllerBindings(contentId: String, fileName: String,
                               layout: com.mrjackspade.kairo.frontend.ControllerLayout,
                               userBindings: String? = null): List<com.mrjackspade.kairo.frontend.ControllerBinding>? =
        userBindings?.let(DosControllerBindings::parse)
            ?: resolve(contentId, fileName).controllerProfile?.let {
                controllerBindings(it, layout)?.let(DosControllerBindings::parse)
            }

    @Synchronized override fun resolve(contentId: String, fileName: String): Game =
        resolvedMemo.getOrPut(contentId to fileName) { resolveUncached(contentId, fileName) }

    /** Which controller profile lists a game: built once instead of scanning every profile per game. */
    private fun profileFor(contentId: String): String? {
        val index = profileByGame ?: HashMap<String, String>().also { map ->
            controllerProfileCatalog().optJSONObject("profiles")?.let { profiles ->
                for (profile in profiles.keys()) {
                    val ids = profiles.optJSONArray(profile) ?: continue
                    for (i in 0 until ids.length()) map.putIfAbsent(ids.optString(i), profile)
                }
            }
            profileByGame = map
        }
        return index[contentId]
    }

    private fun resolveUncached(contentId: String, fileName: String): Game {
        val record = layered(contentId, fileName).record
        val description = record.optString("description").takeIf { it.isNotBlank() }
        val baseTitle = record.optString("title").takeIf { it.isNotBlank() }
            ?: fileName.substringAfterLast('/')
        val title = if (fileName.endsWith(" - Installer", true) &&
            !baseTitle.endsWith(" - Installer", true)) "$baseTitle - Installer" else baseTitle
        val art = record.optJSONObject("artwork")
        // Paths are opened when shown; an image that is absent or unusable fails there alone.
        val boxArtPath = art?.optString("boxArt")?.takeIf { it.isNotEmpty() }
        val previewPath = art?.optString("preview")?.takeIf { it.isNotEmpty() }
        val launch = record.optJSONObject("launch")?.let { source ->
            val configs = source.optJSONObject("configs") ?: JSONObject()
            Launch(source.optString("folder"), configs.keys().asSequence()
                .associateWith { configs.optString(it) }, source.optBoolean("exception"))
        }
        val controllerProfile = controllerProfileCatalog().optJSONObject("assignments")
            ?.optString(contentId)?.takeIf { it.isNotBlank() } ?: profileFor(contentId)
        return Game(title, description,
            boxArtPath, previewPath,
            record.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            } ?: emptyList(), launch,
            controllerProfile)
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
        if (contentId.isEmpty()) return false
        // The generated index avoids opening hundreds of catalog shards merely
        // to decide which cached rows should be visible at startup.
        return CatalogFieldLayers.hidden(
            bundledHidden.opt(contentId),
            onlineHidden.opt(contentId),
            *installedCatalogs.sources(contentId).map { it.record?.opt("hidden") }.toTypedArray(),
            userCatalog.optJSONObject("games")?.optJSONObject(contentId)?.opt("hidden"),
            overrides.record(contentId)?.opt("hidden")
        )
    }

    private fun layered(contentId: String, fileName: String): CatalogFieldLayers.Result =
        layeredMemo.getOrPut(contentId to fileName) { layeredUncached(contentId, fileName) }

    private fun layeredUncached(contentId: String, fileName: String): CatalogFieldLayers.Result {
        val sources = ArrayList<CatalogFieldLayers.Source>()
        if (contentId.isNotEmpty()) {
            val prefix = contentId.substringAfter(':').take(2)
            fun add(name: String, value: JSONObject?) {
                val expanded = DosArtworkReferences.record(value)
                sources += CatalogFieldLayers.Source(name, expanded)
                selectVariant(expanded, fileName)?.let {
                    sources += CatalogFieldLayers.Source(name, it)
                }
            }
            add("Shipped catalog", bundledShard(prefix)
                ?.optJSONObject("games")?.optJSONObject(contentId))
            add("Updated catalog", shard(prefix)
                ?.optJSONObject("games")?.optJSONObject(contentId))
            for (source in installedCatalogs.sources(contentId)) add(source.name, source.record)
            add("User catalog", userCatalog.optJSONObject("games")
                ?.optJSONObject(contentId))
            add("User override", overrides.record(contentId))
        }
        return CatalogFieldLayers.merge(sources, DosCatalogFields::objectField)
    }

    @Synchronized fun sourceOf(contentId: String, fileName: String,
                               vararg path: String): String =
        layered(contentId, fileName).sourceOf(*path)
            ?: if (path.size == 1 && path[0] == "title") "Filename" else "App default"

    private fun readUserCatalog(): JSONObject =
        LocalCatalogFile.read(userCatalogFile, 8 * 1024 * 1024) ?: JSONObject()

    @Synchronized fun contentIdsForFolder(folder: String): List<String> {
        val index = folderIndex ?: readFolderIndex().also { folderIndex = it }
        val matches = index.optJSONArray(folder.lowercase(Locale.ROOT)) ?: return emptyList()
        return (0 until matches.length()).map { matches.optString(it) }
    }

    @Synchronized fun setTitle(contentId: String, title: String?) {
        require(id.matches(contentId)) { "Hash this game first" }
        require(title == null || title.length in 1..160) { "Invalid title" }
        overrides.set(contentId, "title", title)
        invalidate(contentId)
    }

    @Synchronized fun setArtworkOverride(contentId: String, kind: String, path: String?) {
        require(id.matches(contentId)) { "Hash this game first" }
        require(kind == "boxArt" || kind == "preview") { "Invalid artwork kind" }
        require(path == null || DosCatalogFields.safeArtPath(path) != null) {
            "Invalid DOS artwork path"
        }
        overrides.setSubfield(contentId, "artwork", kind, path)
        invalidate(contentId)
    }

    @Synchronized fun resetOverrides(contentId: String) {
        require(id.matches(contentId)) { "Hash this game first" }
        overrides.clear(contentId)
        invalidate(contentId)
    }

    override fun openArtwork(path: String): InputStream = installedCatalogs.openArtwork(path) ?: artworkStore.open(path)

    data class ArtworkSource(val path: String, val url: String)

    /** Use catalog paths even when images are absent from the smaller APK. */
    @Synchronized fun missingArtworkFor(entries: List<DosLibrary.Game>): List<ArtworkSource> =
        entries.asSequence().filter { it.playable }.flatMap { entry ->
            val record = layered(entry.contentId ?: "", entry.displayName)
            val artwork = record.record.optJSONObject("artwork")
            listOf("boxArt", "preview").mapNotNull { kind ->
                val path = DosCatalogFields.safeArtPath(artwork?.optString(kind))
                path?.takeIf { remoteArtworkPath.matches(it) &&
                    record.sourceOf("artwork", kind) in setOf("Shipped catalog", "Updated catalog") }
            }.asSequence()
        }.distinct().filter { artworkStore.availablePath(it) == null }
            .map { ArtworkSource(it, ARTWORK_ROOT + it) }.toList()

    fun downloadArtwork(source: ArtworkSource, cancelled: AtomicBoolean) =
        artworkStore.download(source.path, source.url, cancelled)

    companion object {
        private const val ARTWORK_ROOT =
            "https://raw.githubusercontent.com/MrJackSpade/KairoDos/main/catalog/artwork/"
        private val remoteArtworkPath = Regex(
            "art/catalog/dos/[0-9a-f]{2}/[0-9a-f]{64}/[0-9a-f]{12}/(?:boxArt|preview)\\.webp")
    }

    private fun shard(prefix: String): JSONObject? {
        cache.get(prefix)?.let { return it }
        val parsed = online.read("$prefix.json") ?: JSONObject()
        cache.put(prefix, parsed)
        return parsed
    }

    private fun bundledShard(prefix: String): JSONObject? {
        bundledCache.get(prefix)?.let { return it }
        val parsed = readAssetCatalog("$prefix.json")
        bundledCache.put(prefix, parsed)
        return parsed
    }

}
