package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ArtworkOverridePath
import com.mrjackspade.kairo.frontend.CatalogGame
import com.mrjackspade.kairo.frontend.CatalogSetting
import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerLayout
import com.mrjackspade.kairo.frontend.LayeredGameCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * KairoDos's catalog records beyond the common fields (shared LayeredGameCatalog, built by
 * shared/tools/app_catalog.py from tools/import_catalog.py's research): how a game starts
 * (`launch`: the `folder` its files are in, the eXoDOS DOSBox configuration of each of its
 * versions in `configs` by file name, and whether eXoDOS ships an `exception` for it). One archive
 * holding several games names each by its file's name (`variants`); an installer's file shows
 * "- Installer". Games are named by the manifest hash of their files.
 */
object DosCatalog : LayeredGameCatalog.Schema {
    override val platform = "dos"
    override val catalogUrl = "https://raw.githubusercontent.com/MrJackSpade/KairoDos/main/catalog/"
    override val selfHostedArt = "/MrJackSpade/KairoDos/main/catalog/artwork/art/catalog/"
    override val contentId = Regex("sha256-dos-manifest-v1:[0-9a-f]{64}")
    override val mediaExtensions = Regex("\\.(?:zip|dosz)$", RegexOption.IGNORE_CASE)
    // A game's DOSBox settings and version are KairoDos's own settings rows (MainActivity's
    // Version row and Custom version), which DosCatalogSettings checks the catalog against.
    override val settings: List<CatalogSetting> = emptyList()
    override val fields: Map<String, Set<String>?> = mapOf(LAUNCH to setOf(FOLDER, CONFIGS, EXCEPTION))

    override fun validMedia(role: String, contentId: String) = false

    override fun validField(field: String, value: Any): Boolean = field == LAUNCH && value is JSONObject &&
        value.keys().asSequence().all { it in setOf(FOLDER, CONFIGS, EXCEPTION) } &&
        (!value.has(FOLDER) || value.opt(FOLDER) is String) &&
        (!value.has(EXCEPTION) || value.opt(EXCEPTION) is Boolean) &&
        (!value.has(CONFIGS) || (value.opt(CONFIGS) as? JSONObject)?.let { configs ->
            configs.keys().asSequence().all { configs.opt(it) is String }
        } == true)

    override fun validBindings(bindings: JSONArray) = DosControllerBindings.valid(bindings)

    /** An installer's file is named after the game it installs, with " - Installer". */
    override fun variantName(fileName: String) =
        super.variantName(fileName.removeSuffix(INSTALLER)).removeSuffix(INSTALLER.lowercase(Locale.ROOT))

    override fun title(title: String, fileName: String) =
        if (fileName.endsWith(INSTALLER, true) && !title.endsWith(INSTALLER, true)) "$title$INSTALLER" else title

    /**
     * KairoDos's compact references (DosArtworkReferences): each image is downloaded from LaunchBox
     * where the record names LaunchBox's, and from this repository's copy otherwise.
     */
    override fun expandArtwork(art: JSONObject): JSONObject = DosArtworkReferences.expand(art).apply {
        for (kind in listOf("boxArt", "preview")) {
            val path = optString(kind).takeIf { it.isNotEmpty() } ?: continue
            if (!has("${kind}Url")) put("${kind}Url", ARTWORK_ROOT + path)
        }
    }

    /** A player's own artwork file, under the folder catalog images use. */
    fun validArtPath(path: String?) =
        path != null && ArtworkOverridePath.valid(path, "art/catalog/dos/", asciiOnly = true, minLength = 17)

    const val LAUNCH = "launch"
    const val FOLDER = "folder"
    const val CONFIGS = "configs"
    const val EXCEPTION = "exception"
    /** The launch folders index (tools/catalog_config.py DOCUMENTS). */
    const val FOLDERS_DOCUMENT = "folders-v1.json"
    private const val INSTALLER = " - Installer"
    private const val ARTWORK_ROOT = "https://raw.githubusercontent.com/MrJackSpade/KairoDos/main/catalog/artwork/"
}

/** How a game starts: the folder its files are in, its eXoDOS configuration per version, and its exception. */
data class DosLaunch(val folder: String, val configs: Map<String, String>, val exception: Boolean)

/** The game's launch, or null for a record without one (a dependency's own folder, an unknown game). */
val CatalogGame.launch: DosLaunch? get() = section(DosCatalog.LAUNCH)?.let { source ->
    val configs = source.optJSONObject(DosCatalog.CONFIGS) ?: JSONObject()
    DosLaunch(source.optString(DosCatalog.FOLDER), configs.keys().asSequence().associateWith { configs.optString(it) },
        source.optBoolean(DosCatalog.EXCEPTION))
}

/** The game's catalog controls for [layout]: its own for that layout, else its layout without sticks. */
fun CatalogGame.controllerLayer(layout: ControllerLayout): List<ControllerBinding> =
    controllerDefaultBindings?.let(::JSONObject)?.let { defaults ->
        (defaults.optJSONArray(layout.key) ?: defaults.optJSONArray(ControllerLayout.WITHOUT_STICKS.key))
            ?.let { DosControllerBindings.parse(it.toString()) }
    } ?: emptyList()

/** Whether the game's catalog controls have no layout of their own for sticks. */
val CatalogGame.controllerFallsBack: Boolean get() =
    controllerDefaultBindings?.let(::JSONObject)?.has(ControllerLayout.WITH_STICKS.key) == false

/** The content IDs of the games that provide a launch [folder], from every catalog layer. */
fun LayeredGameCatalog.contentIdsForFolder(folder: String): List<String> =
    documents(DosCatalog.FOLDERS_DOCUMENT).flatMap { document ->
        document.optJSONObject("folders")?.optJSONArray(folder.lowercase(Locale.ROOT))?.let { ids ->
            (0 until ids.length()).map(ids::getString)
        } ?: emptyList()
    }.distinct()
