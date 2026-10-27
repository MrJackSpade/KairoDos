package com.mrjackspade.kairodos

import android.content.SharedPreferences
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GamePathMigration
import com.mrjackspade.kairo.frontend.GameSettingScope
import com.mrjackspade.kairo.frontend.LibraryItem
import org.json.JSONObject

/** DOS-only per-game choices: each `<prefix><game key>`, after the prefix the game's whole [GameKey.path]. */
internal class DosPerGameSettings(private val preferences: SharedPreferences,
                                  private val common: GameSettingScope) {
    private fun controllerKey(game: GameKey) = "$CONTROLLER${game.path}"
    private fun controllerLayerKey(game: GameKey) = "$CONTROLLER_LAYER${game.path}"
    private fun variantKey(game: GameKey) = "$VARIANT${game.path}"
    private fun playerKey(game: GameKey) = "$PLAYER${game.path}"
    private fun configKey(game: GameKey) = "$CONFIG${game.path}"
    private fun autoexecKey(game: GameKey) = "$AUTOEXEC${game.path}"
    private fun customKey(game: GameKey) = "$CUSTOM${game.path}"

    /**
     * Whether the game runs its Custom version: its preset ([version]) with the player's own
     * DOSBox settings and startup commands. Other versions keep those but do not use them.
     */
    fun custom(game: GameKey): Boolean = preferences.getBoolean(customKey(game), false)
    fun setCustom(game: GameKey, custom: Boolean) = preferences.edit().apply {
        if (custom) putBoolean(customKey(game), true) else remove(customKey(game))
    }.apply()

    /** The game's own DOSBox settings, which replace its catalog config's: {"section.key": value}. */
    fun configSettings(game: GameKey): Map<String, String> =
        preferences.getString(configKey(game), null)?.let { text ->
            val json = JSONObject(text)
            json.keys().asSequence().associateWith(json::getString)
        } ?: emptyMap()

    /** Sets [section].[key] for the game, or with null goes back to its catalog config's value. */
    fun setConfigSetting(game: GameKey, section: String, key: String, value: String?) {
        val settings = configSettings(game).toMutableMap()
        if (value == null) settings.remove("$section.$key") else settings["$section.$key"] = value
        preferences.edit().apply {
            if (settings.isEmpty()) remove(configKey(game))
            else putString(configKey(game), JSONObject(settings as Map<*, *>).toString())
        }.apply()
    }

    /** The game's own startup commands, which replace its catalog config's [autoexec], or null. */
    fun autoexec(game: GameKey): String? = preferences.getString(autoexecKey(game), null)
    fun setAutoexec(game: GameKey, lines: String?) = preferences.edit().apply {
        if (lines == null) remove(autoexecKey(game)) else putString(autoexecKey(game), lines)
    }.apply()

    /** The player's own controller layer for [game] (ControllerDefaults.cascade): what they changed. */
    fun controllerLayer(game: GameKey): String? = preferences.getString(controllerLayerKey(game), null)
    /** The whole mapping the player saved for [game] before mappings were layers. */
    fun legacyControllerBindings(game: GameKey): String? = preferences.getString(controllerKey(game), null)
    fun hasOwnController(game: GameKey) = preferences.contains(controllerLayerKey(game)) || preferences.contains(controllerKey(game))
    fun setControllerLayer(game: GameKey, json: String) =
        preferences.edit().putString(controllerLayerKey(game), json).remove(controllerKey(game)).apply()
    fun clearControllerBindings(game: GameKey) =
        preferences.edit().remove(controllerLayerKey(game)).remove(controllerKey(game)).apply()

    /** The catalog config (preset) the player picked for the game, or null for its standard one. */
    fun version(game: GameKey): String? = preferences.getString(variantKey(game), null)
    fun setVersion(game: GameKey, config: String?) = preferences.edit().apply {
        if (config == null) remove(variantKey(game)) else putString(variantKey(game), config)
    }.apply()

    fun playerName(game: GameKey): String? = preferences.getString(playerKey(game), null)
    fun setPlayerName(game: GameKey, name: String) =
        preferences.edit().putString(playerKey(game), name).apply()

    fun videoHardware(game: GameKey? = null): Int = DosVideoHardware.normalize(
        common.int(game, DosVideoHardware.setting, preferences.getInt(DosVideoHardware.setting, 0)))

    fun reset(game: GameKey) {
        common.clear(game, "touch_mode", "direct_touch", "cycles_mode", "staging_voodoo_threads", DosVideoHardware.setting,
            anchorSetting("landscape"), anchorSetting("portrait"))
        preferences.edit().remove(controllerKey(game)).remove(controllerLayerKey(game)).remove(variantKey(game))
            .remove(playerKey(game)).remove(configKey(game)).remove(autoexecKey(game)).remove(customKey(game)).apply()
    }

    /** An installer's settings belong to the game it installs. */
    fun move(from: GameKey, to: GameKey) {
        val values = preferences.all
        val editor = preferences.edit()
        fun move(old: String, new: String) {
            val value = values[old] ?: return
            when (value) {
                is Int -> editor.putInt(new, value)
                is Boolean -> editor.putBoolean(new, value)
                is String -> editor.putString(new, value)
                else -> error("Unsupported setting $old")
            }
            editor.remove(old)
        }
        common.move(from, to, editor)
        move(controllerKey(from), controllerKey(to))
        move(controllerLayerKey(from), controllerLayerKey(to))
        move(variantKey(from), variantKey(to))
        move(playerKey(from), playerKey(to))
        move(configKey(from), configKey(to))
        move(autoexecKey(from), autoexecKey(to))
        move(customKey(from), customKey(to))
        check(editor.commit()) { "Could not move the game's settings" }
    }

    companion object {
        /** The game's own anchor in an orientation (Kairo#54), in the common game settings. */
        fun anchorSetting(orientation: String) = "${orientation}_anchor"
        private const val CONTROLLER = "game_controller_v2_"
        private const val CONTROLLER_LAYER = "game_controller_layer_v3_"
        private const val VARIANT = "game_launch_variant_v2_"
        private const val PLAYER = "game_dos_player_v2_"
        private const val CONFIG = "game_dos_config_v1_"
        private const val AUTOEXEC = "game_dos_autoexec_v1_"
        private const val CUSTOM = "game_dos_custom_v1_"

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
                listOf(CONTROLLER, CONTROLLER_LAYER, VARIANT, PLAYER, CONFIG, AUTOEXEC, CUSTOM).map { prefix ->
                    GamePathMigration.PreferenceFamily(prefix, false) { game, _ -> prefix + game.path }
                })
    }
}
