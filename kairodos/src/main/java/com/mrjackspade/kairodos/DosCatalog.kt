package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ArtworkOverridePath
import com.mrjackspade.kairo.frontend.CatalogGame
import com.mrjackspade.kairo.frontend.CatalogSetting
import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerLayout
import com.mrjackspade.kairo.frontend.LayeredGameCatalog
import com.mrjackspade.kairo.frontend.MachineOption
import com.mrjackspade.kairo.frontend.ChoiceOption
import com.mrjackspade.kairo.frontend.UiText
import com.mrjackspade.kairo.frontend.R as FrontendR
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
    /** The DOS CPU's speed: the configuration's own (Automatic), or as fast as the device can run it. */
    val cpu = MachineOption("cpu", UiText.res(R.string.cpu_speed), listOf(
        ChoiceOption(AUTO, UiText.res(FrontendR.string.kairo_auto)),
        ChoiceOption(CPU_MAX, UiText.res(R.string.cpu_maximum))), AUTO)
    /** The emulated video card, by DOSBox's machine name (DosVideoHardware); Automatic keeps the configuration's. */
    val video = MachineOption("video", UiText.res(R.string.video_card),
        DosVideoHardware.choices.map { (id, label) -> ChoiceOption(id, label) }, AUTO)
    /** The threads 3dfx rendering runs on. */
    val voodoo = MachineOption("voodoo", UiText.res(R.string.voodoo), listOf(
        ChoiceOption(AUTO, UiText.res(R.string.voodoo_automatic)),
        ChoiceOption(VOODOO_SINGLE, UiText.res(R.string.voodoo_single))), AUTO)
    val options = listOf(cpu, video, voodoo)

    /**
     * The game's version (`launch.version`, one of its catalog `configs`), or its Custom version
     * (`launch.custom`): that version with the player's own DOSBox settings ([dosboxSettings]) and
     * startup commands ([startupCommands]).
     */
    val version = CatalogSetting.Custom("version", LAUNCH, setOf(CONFIGS, VERSION, CUSTOM))
    /** The Custom version's DOSBox settings, `launch.settings`: {"section.key": value}. */
    val dosboxSettings = CatalogSetting.Custom("dosboxSettings", LAUNCH, setOf(SETTINGS))
    /** The Custom version's startup commands, `launch.autoexec`, which replace its configuration's [autoexec]. */
    val startupCommands = CatalogSetting.Custom("startupCommands", LAUNCH, setOf(AUTOEXEC))
    /** The DOS player name a game asks for (DosLaunchConfig.needsPlayer), `launch.player`. */
    val player = CatalogSetting.Custom("player", LAUNCH, setOf(PLAYER))

    override val settings: List<CatalogSetting> = options.map(CatalogSetting::Choice) +
        listOf(version, dosboxSettings, startupCommands, player)
    override val fields: Map<String, Set<String>?> = CatalogSetting.fields(settings).let { fields ->
        fields + (LAUNCH to fields.getValue(LAUNCH)!! + setOf(FOLDER, EXCEPTION))
    }
    /** The folder a game's files are in, and whether eXoDOS ships an exception.bat (unused). */
    override val describes = setOf("$LAUNCH.$FOLDER", "$LAUNCH.$EXCEPTION")
    /** DOSBox places its pointer where the screen is touched (seamless mouse capture). */
    override val directTouch = true

    override fun validMedia(role: String, contentId: String) = false

    override fun validField(field: String, value: Any): Boolean = when (field) {
        MachineOption.SECTION -> CatalogSetting.valid(settings, contentId, field, value)
        LAUNCH -> value is JSONObject && value.length() > 0 &&
            value.keys().asSequence().all { it in fields.getValue(LAUNCH)!! } &&
            (!value.has(FOLDER) || value.opt(FOLDER) is String) &&
            (!value.has(EXCEPTION) || value.opt(EXCEPTION) is Boolean) &&
            (!value.has(CONFIGS) || (value.opt(CONFIGS) as? JSONObject)?.let { configs ->
                configs.keys().asSequence().all { (configs.opt(it) as? String)?.let(::validConfig) == true }
            } == true) &&
            (!value.has(VERSION) || (value.opt(VERSION) as? String)?.length in 1..128) &&
            (!value.has(CUSTOM) || value.opt(CUSTOM) is Boolean) &&
            (!value.has(SETTINGS) || (value.opt(SETTINGS) as? JSONObject)?.let { own ->
                own.keys().asSequence().all { key -> SETTING_KEY.matches(key) && own.opt(key) is String }
            } == true) &&
            (!value.has(AUTOEXEC) || (value.opt(AUTOEXEC) as? String)?.length in 0..16000) &&
            (!value.has(PLAYER) || (value.opt(PLAYER) as? String)?.matches(PLAYER_NAME) == true)
        else -> false
    }

    /**
     * Whether every line of a DOSBox [config] is one its game's settings show (DosConfig): a
     * setting inside a section, or the [autoexec] lines.
     */
    fun validConfig(config: String): Boolean {
        var section: String? = null
        return config.lineSequence().all { raw ->
            val line = raw.trimEnd('\r')
            val setting = line.substringBefore('#').trim()
            when {
                CONFIG_SECTION.matches(line) -> {
                    section = line.trim().removePrefix("[").removeSuffix("]").trim().lowercase()
                    true
                }
                section == "autoexec" || setting.isEmpty() -> true
                else -> section != null && '=' in setting
            }
        }
    }

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
    const val VERSION = "version"
    const val CUSTOM = "custom"
    const val SETTINGS = "settings"
    const val AUTOEXEC = "autoexec"
    const val PLAYER = "player"
    const val AUTO = "auto"
    const val CPU_MAX = "max"
    const val VOODOO_SINGLE = "single"
    val PLAYER_NAME = Regex("[A-Z0-9_]{1,8}")
    private val SETTING_KEY = Regex("[a-z0-9_]+\\.[a-z0-9_]+")
    private val CONFIG_SECTION = Regex("^\\s*\\[[^]]+]\\s*$")
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
