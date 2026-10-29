package com.mrjackspade.kairodos

import android.content.SharedPreferences
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GamePathMigration
import com.mrjackspade.kairo.frontend.GameSettingScope
import com.mrjackspade.kairo.frontend.InputModeDecider
import com.mrjackspade.kairo.frontend.LayeredGameCatalog
import com.mrjackspade.kairo.frontend.LibraryItem
import com.mrjackspade.kairo.frontend.MachineOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * KairoDos kept a game's settings in preferences until they became catalog overrides, as every
 * machine app keeps them (KairoDos#122): each moves once to the game's override, and the all-games
 * choices to the preferences the shared machine activity reads. [run] moves whatever is still in
 * the old places: the library's older migrations (GameKeyMigration, GamePathMigration) bring
 * settings from before games had keys or paths to them first.
 */
internal object DosSettingsMigration {
    /** Before games had paths: each `<prefix><game key path>`. */
    private const val CONTROLLER = "game_controller_v2_"
    private const val CONTROLLER_LAYER = "game_controller_layer_v3_"
    private const val VARIANT = "game_launch_variant_v2_"
    private const val PLAYER = "game_dos_player_v2_"
    private const val CONFIG = "game_dos_config_v1_"
    private const val AUTOEXEC = "game_dos_autoexec_v1_"
    private const val CUSTOM = "game_dos_custom_v1_"
    private val PREFIXES = listOf(CONTROLLER, CONTROLLER_LAYER, VARIANT, PLAYER, CONFIG, AUTOEXEC, CUSTOM)

    /** The common game settings (GameSettingScope) KairoDos kept. */
    private const val TOUCH_MODE = "touch_mode"
    private const val DIRECT_TOUCH = "direct_touch"
    private const val CYCLES = "cycles_mode"
    private const val VOODOO = "staging_voodoo_threads"
    private const val VIDEO = "dos_video_hardware"
    private val ANCHORS = mapOf("landscape_anchor" to "landscapeAnchor", "portrait_anchor" to "portraitAnchor")

    /** Settings from before games had keys, named by content ID, moved to where v1.4.0 kept them. */
    val LEGACY = listOf(
        GameKeyMigration.PreferenceFamily(GameSettingScope.CONTENT_ID_PREFIX, GameSettingScope.V1_PREFIX, true),
        GameKeyMigration.PreferenceFamily("controller_game_", CONTROLLER, false),
        GameKeyMigration.PreferenceFamily("launch_variant_", VARIANT, false),
        GameKeyMigration.PreferenceFamily("dos_player_", PLAYER, false))

    /**
     * Settings v1.4.0 and earlier named by games' hash names, `<prefix><hash name>`, for
     * [GamePathMigration]; the common game settings are the shared scope's.
     */
    fun <E : LibraryItem> v1Settings(preferences: SharedPreferences) = GamePathMigration.PreferenceStore<E>(preferences,
        listOf(GamePathMigration.PreferenceFamily(GameSettingScope.V1_PREFIX, true) { game, name -> GameSettingScope.key(game, name!!) }) +
            PREFIXES.map { prefix -> GamePathMigration.PreferenceFamily(prefix, false) { game, _ -> prefix + game.path } })

    /** Moves every setting still kept in preferences; each preference goes once its value is in place. */
    fun run(preferences: SharedPreferences, catalog: LayeredGameCatalog) {
        val all = preferences.all
        val moved = ArrayList<String>()
        for ((name, value) in all) {
            if (value == null) continue
            if (name.startsWith(GameSettingScope.PREFIX) && ':' in name) {
                val setting = name.removePrefix(GameSettingScope.PREFIX).substringBefore(':')
                val game = GameKey(name.substringAfter(':'))
                if (moveCommon(catalog, game, setting, value)) moved += name
                continue
            }
            val prefix = PREFIXES.firstOrNull { name.startsWith(it) } ?: continue
            moveOwn(catalog, GameKey(name.removePrefix(prefix)), prefix, value)
            moved += name
        }
        val editor = preferences.edit()
        moveGlobal(all, editor)
        moved.forEach(editor::remove)
        check(editor.commit()) { "Could not finish moving KairoDos's game settings" }
    }

    /** A common game setting KairoDos kept; false for one that is not KairoDos's (none, today). */
    private fun moveCommon(catalog: LayeredGameCatalog, game: GameKey, setting: String, value: Any): Boolean {
        when (setting) {
            TOUCH_MODE -> catalog.setOverrideSubfield(game, "input", "mode", InputModeDecider.storageValue(touchMode(value as Int)))
            DIRECT_TOUCH -> catalog.setOverrideSubfield(game, "input", "touch", if (value as Boolean) "direct" else "touchpad")
            CYCLES -> catalog.setOverrideSubfield(game, MachineOption.SECTION, DosCatalog.cpu.id, cpu(value as Int))
            VOODOO -> catalog.setOverrideSubfield(game, MachineOption.SECTION, DosCatalog.voodoo.id, voodoo(value as Int))
            VIDEO -> catalog.setOverrideSubfield(game, MachineOption.SECTION, DosCatalog.video.id, DosVideoHardware.fromIndex(value as Int))
            in ANCHORS -> catalog.setOverrideSubfield(game, "display", ANCHORS.getValue(setting), value as String)
            else -> return false
        }
        return true
    }

    /** One of KairoDos's own game settings: text, but the Custom version's flag, a boolean. */
    private fun moveOwn(catalog: LayeredGameCatalog, game: GameKey, prefix: String, value: Any) {
        when (prefix) {
            CONTROLLER -> catalog.updateOverrideSubfields(game, "controller", mapOf("bindings" to JSONArray(value as String), "layer" to null))
            CONTROLLER_LAYER -> catalog.updateOverrideSubfields(game, "controller", mapOf("layer" to JSONArray(value as String), "bindings" to null))
            VARIANT -> catalog.setOverrideSubfield(game, DosCatalog.LAUNCH, DosCatalog.VERSION, value as String)
            PLAYER -> catalog.setOverrideSubfield(game, DosCatalog.LAUNCH, DosCatalog.PLAYER, value as String)
            CONFIG -> catalog.setOverrideSubfield(game, DosCatalog.LAUNCH, DosCatalog.SETTINGS, JSONObject(value as String))
            AUTOEXEC -> catalog.setOverrideSubfield(game, DosCatalog.LAUNCH, DosCatalog.AUTOEXEC, value as String)
            // Only a Custom version was kept (true); a game that left it had the preference removed.
            CUSTOM -> catalog.setOverrideSubfield(game, DosCatalog.LAUNCH, DosCatalog.CUSTOM, value as Boolean)
        }
    }

    /** The all-games choices, to the preferences the shared machine activity reads ([MachineOption.id], input). */
    private fun moveGlobal(all: Map<String, *>, editor: SharedPreferences.Editor) {
        (all[TOUCH_MODE] as? Int)?.let {
            editor.putString("input_mode", InputModeDecider.storageValue(touchMode(it))).remove(TOUCH_MODE)
        }
        (all[DIRECT_TOUCH] as? Boolean)?.let {
            editor.putString("touch_mouse", if (it) "direct" else "touchpad").remove(DIRECT_TOUCH)
        }
        (all[CYCLES] as? Int)?.let { editor.putString(DosCatalog.cpu.id, cpu(it)).remove(CYCLES) }
        (all[VOODOO] as? Int)?.let { editor.putString(DosCatalog.voodoo.id, voodoo(it)).remove(VOODOO) }
        (all[VIDEO] as? Int)?.let { editor.putString(DosCatalog.video.id, DosVideoHardware.fromIndex(it)).remove(VIDEO) }
    }

    /** Touch mode as KairoDos stored it: 0 Mouse, 1 Keyboard, 2 Auto. */
    private fun touchMode(value: Int) = when (value) {
        0 -> InputModeDecider.Mode.MOUSE
        1 -> InputModeDecider.Mode.KEYBOARD
        else -> InputModeDecider.Mode.AUTO
    }

    private fun cpu(value: Int) = if (value == 1) DosCatalog.CPU_MAX else DosCatalog.AUTO
    private fun voodoo(value: Int) = if (value == 1) DosCatalog.VOODOO_SINGLE else DosCatalog.AUTO
}
