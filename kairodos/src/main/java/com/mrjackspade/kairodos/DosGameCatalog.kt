package com.mrjackspade.kairodos

import android.content.Context
import android.util.LruCache
import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import com.mrjackspade.kairo.frontend.LocalCatalogFile
import com.mrjackspade.kairo.frontend.LibraryCatalog
import com.mrjackspade.kairo.frontend.LibraryGame
import com.mrjackspade.kairo.frontend.CatalogArtworkStore
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GameMetadataOverrides
import com.mrjackspade.kairo.frontend.LibraryItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data-only game metadata. Catalog records are named by content ID; the user's own overrides
 * belong to a game's key. Game media is never read from these records.
 */
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
    private data class MemoKey(val matchId: String, val game: GameKey?, val fileName: String)
    private val layeredMemo = HashMap<MemoKey, CatalogFieldLayers.Result>()
    private val resolvedMemo = HashMap<MemoKey, Game>()
    private var profileByGame: Map<String, String>? = null
    private fun invalidate() {
        layeredMemo.clear()
        resolvedMemo.clear()
        profileByGame = null
    }

    /** Only this game's overrides changed. */
    private fun invalidate(game: GameKey) {
        layeredMemo.keys.removeAll { it.game == game }
        resolvedMemo.keys.removeAll { it.game == game }
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
        DosArtworkReferences.isLaunchBoxImage(url) ||
            url.startsWith(ARTWORK_ROOT) && remoteArtworkPath.matches(url.removePrefix(ARTWORK_ROOT))
    },
        "art/catalog/dos/")
    // Dependency folder lookup is only needed when preparing a game launch.
    // Parsing this large index while opening the library delays the first frame.
    private var folderIndex: JSONObject? = null
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

    /** The game's catalog layer: its controller profile's bindings for [layout], over the system's mapping. */
    fun gameControllerLayer(item: LibraryItem,
                            layout: com.mrjackspade.kairo.frontend.ControllerLayout): List<com.mrjackspade.kairo.frontend.ControllerBinding> =
        resolve(item).controllerProfile?.let { controllerBindings(it, layout)?.let(DosControllerBindings::parse) }
            ?: emptyList()

    /** The system's controller mapping from the catalog's system definition (dos/system-v1.json). */
    fun systemControllerBindings(): JSONArray =
        readAssetCatalog(com.mrjackspade.kairo.frontend.LayeredGameCatalog.SYSTEM_DOCUMENT)
            .getJSONObject("controller").getJSONArray("bindings")

    @Synchronized override fun resolve(item: LibraryItem): Game =
        resolve(matchId(item) ?: "", item.key, item.displayName)

    /** A catalog record alone, as for a dependency folder that is not a library game. */
    @Synchronized fun resolve(contentId: String, fileName: String): Game = resolve(contentId, null, fileName)

    private fun resolve(matchId: String, game: GameKey?, fileName: String): Game =
        MemoKey(matchId, game, fileName).let { key ->
            resolvedMemo.getOrPut(key) { resolveUncached(key) }
        }

    // An unedited game needs no lookup, so startup reads no shards for it.
    @Synchronized override fun matchId(item: LibraryItem): String? {
        val current = item.contentId ?: return item.catalogId
        val first = item.catalogId ?: return current
        return if (current == first || known(current)) current else first
    }

    /** Whether any catalog has a record for [contentId]. */
    private fun known(contentId: String): Boolean {
        val prefix = contentId.substringAfter(':').take(2)
        return bundledShard(prefix)?.optJSONObject("games")?.has(contentId) == true ||
            shard(prefix)?.optJSONObject("games")?.has(contentId) == true ||
            installedCatalogs.sources(contentId).any { it.record != null } ||
            userCatalog.optJSONObject("games")?.has(contentId) == true
    }

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

    private fun resolveUncached(key: MemoKey): Game {
        val contentId = key.matchId
        val fileName = key.fileName
        val record = layered(key).record
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

    @Synchronized override fun hiddenFromLibrary(item: LibraryItem): Boolean {
        val contentId = matchId(item) ?: return false
        // The generated index avoids opening hundreds of catalog shards merely
        // to decide which cached rows should be visible at startup.
        return CatalogFieldLayers.hidden(
            bundledHidden.opt(contentId),
            onlineHidden.opt(contentId),
            *installedCatalogs.sources(contentId).map { it.record?.opt("hidden") }.toTypedArray(),
            userCatalog.optJSONObject("games")?.optJSONObject(contentId)?.opt("hidden"),
            overrides.record(item.key)?.opt("hidden")
        )
    }

    private fun layered(key: MemoKey): CatalogFieldLayers.Result =
        layeredMemo.getOrPut(key) { layeredUncached(key) }

    private fun layeredUncached(key: MemoKey): CatalogFieldLayers.Result {
        val contentId = key.matchId
        val fileName = key.fileName
        val sources = ArrayList<CatalogFieldLayers.Source>()
        fun add(name: String, value: JSONObject?) {
            val expanded = DosArtworkReferences.record(value)
            sources += CatalogFieldLayers.Source(name, expanded)
            selectVariant(expanded, fileName)?.let {
                sources += CatalogFieldLayers.Source(name, it)
            }
        }
        if (contentId.isNotEmpty()) {
            val prefix = contentId.substringAfter(':').take(2)
            add("Shipped catalog", bundledShard(prefix)
                ?.optJSONObject("games")?.optJSONObject(contentId))
            add("Updated catalog", shard(prefix)
                ?.optJSONObject("games")?.optJSONObject(contentId))
            for (source in installedCatalogs.sources(contentId)) add(source.name, source.record)
            add("User catalog", userCatalog.optJSONObject("games")
                ?.optJSONObject(contentId))
        }
        key.game?.let { add("User override", overrides.record(it)) }
        return CatalogFieldLayers.merge(sources, DosCatalogFields::objectField)
    }

    @Synchronized fun sourceOf(item: LibraryItem, vararg path: String): String =
        layered(MemoKey(matchId(item) ?: "", item.key, item.displayName)).sourceOf(*path)
            ?: if (path.size == 1 && path[0] == "title") "Filename" else "App default"

    private fun readUserCatalog(): JSONObject =
        LocalCatalogFile.read(userCatalogFile, 8 * 1024 * 1024) ?: JSONObject()

    @Synchronized fun contentIdsForFolder(folder: String): List<String> {
        val index = folderIndex ?: readFolderIndex().also { folderIndex = it }
        val matches = index.optJSONArray(folder.lowercase(Locale.ROOT)) ?: return emptyList()
        return (0 until matches.length()).map { matches.optString(it) }
    }

    @Synchronized fun setTitle(game: GameKey, title: String?) {
        require(title == null || title.length in 1..160) { "Invalid title" }
        overrides.set(game, "title", title)
        invalidate(game)
    }

    @Synchronized fun setArtworkOverride(game: GameKey, kind: String, path: String?) {
        require(kind == "boxArt" || kind == "preview") { "Invalid artwork kind" }
        require(path == null || DosCatalogFields.safeArtPath(path) != null) {
            "Invalid DOS artwork path"
        }
        overrides.setSubfield(game, "artwork", kind, path)
        invalidate(game)
    }

    @Synchronized fun resetOverrides(game: GameKey) {
        overrides.clear(game)
        invalidate(game)
    }

    /** An installer's overrides belong to the game it installs. */
    @Synchronized fun moveOverrides(from: GameKey, to: GameKey) {
        overrides.move(from, to)
        invalidate(from)
        invalidate(to)
    }

    /** Overrides from before games had keys, for [GameKeyMigration]. */
    fun legacyOverrides() = GameKeyMigration.OverrideStore(overrides) { synchronized(this) { invalidate() } }

    override fun openArtwork(path: String): InputStream = installedCatalogs.openArtwork(path) ?: artworkStore.open(path)

    data class ArtworkSource(val path: String, val url: String)

    /**
     * Use catalog paths even when images are absent from the smaller APK. Each downloads from
     * LaunchBox when the record names LaunchBox's image, and from the repository's copy otherwise.
     */
    @Synchronized fun missingArtworkFor(entries: List<DosLibrary.Game>): List<ArtworkSource> =
        entries.asSequence().filter { it.playable }.flatMap { entry ->
            val record = layered(MemoKey(matchId(entry) ?: "", entry.key, entry.displayName))
            val artwork = record.record.optJSONObject("artwork")
            listOf("boxArt", "preview").mapNotNull { kind ->
                val path = DosCatalogFields.safeArtPath(artwork?.optString(kind))
                path?.takeIf { remoteArtworkPath.matches(it) &&
                    record.sourceOf("artwork", kind) in setOf("Shipped catalog", "Updated catalog") }
                    ?.let { ArtworkSource(it, artwork?.optString(kind + "Url")
                        ?.takeIf(DosArtworkReferences::isLaunchBoxImage) ?: (ARTWORK_ROOT + it)) }
            }.asSequence()
        }.distinctBy { it.path }.filter { artworkStore.availablePath(it.path) == null }.toList()

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
