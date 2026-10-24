package com.mrjackspade.kairodos

import android.content.SharedPreferences
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GameSettingScope
import org.json.JSONObject

/** DOS-only per-game choices, by game key. */
internal class DosPerGameSettings(private val preferences: SharedPreferences,
                                  private val common: GameSettingScope) {
    private fun controllerKey(game: GameKey) = "$CONTROLLER${game.storageName}"
    private fun variantKey(game: GameKey) = "$VARIANT${game.storageName}"
    private fun playerKey(game: GameKey) = "$PLAYER${game.storageName}"
    private fun configKey(game: GameKey) = "$CONFIG${game.storageName}"
    private fun autoexecKey(game: GameKey) = "$AUTOEXEC${game.storageName}"

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

    fun controllerBindings(game: GameKey): String? = preferences.getString(controllerKey(game), null)
    fun setControllerBindings(game: GameKey, json: String) =
        preferences.edit().putString(controllerKey(game), json).apply()
    fun clearControllerBindings(game: GameKey) = preferences.edit().remove(controllerKey(game)).apply()

    /** The catalog config the player picked for the game, or null for its standard one. */
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
        common.clear(game, "touch_mode", "direct_touch", "cycles_mode", "staging_voodoo_threads", DosVideoHardware.setting)
        preferences.edit().remove(controllerKey(game)).remove(variantKey(game))
            .remove(playerKey(game)).remove(configKey(game)).remove(autoexecKey(game)).apply()
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
        val common = "${GameSettingScope.PREFIX}${from.storageName}_"
        for (name in values.keys.filter { it.startsWith(common) })
            move(name, "${GameSettingScope.PREFIX}${to.storageName}_${name.removePrefix(common)}")
        move(controllerKey(from), controllerKey(to))
        move(variantKey(from), variantKey(to))
        move(playerKey(from), playerKey(to))
        move(configKey(from), configKey(to))
        move(autoexecKey(from), autoexecKey(to))
        check(editor.commit()) { "Could not move the game's settings" }
    }

    companion object {
        private const val CONTROLLER = "game_controller_v2_"
        private const val VARIANT = "game_launch_variant_v2_"
        private const val PLAYER = "game_dos_player_v2_"
        private const val CONFIG = "game_dos_config_v1_"
        private const val AUTOEXEC = "game_dos_autoexec_v1_"

        /** Settings from before games had keys, named by content ID. */
        val LEGACY = listOf(
            GameKeyMigration.PreferenceFamily(GameSettingScope.CONTENT_ID_PREFIX, GameSettingScope.PREFIX, true),
            GameKeyMigration.PreferenceFamily("controller_game_", CONTROLLER, false),
            GameKeyMigration.PreferenceFamily("launch_variant_", VARIANT, false),
            GameKeyMigration.PreferenceFamily("dos_player_", PLAYER, false))
    }
}
