package com.mrjackspade.kairodos

import android.content.SharedPreferences
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GameSettingScope

/** DOS-only per-game choices, by game key. */
internal class DosPerGameSettings(private val preferences: SharedPreferences,
                                  private val common: GameSettingScope) {
    private fun controllerKey(game: GameKey) = "$CONTROLLER${game.storageName}"
    private fun variantKey(game: GameKey) = "$VARIANT${game.storageName}"
    private fun playerKey(game: GameKey) = "$PLAYER${game.storageName}"

    fun controllerBindings(game: GameKey): String? = preferences.getString(controllerKey(game), null)
    fun setControllerBindings(game: GameKey, json: String) =
        preferences.edit().putString(controllerKey(game), json).apply()
    fun clearControllerBindings(game: GameKey) = preferences.edit().remove(controllerKey(game)).apply()

    fun startupVariant(game: GameKey): String? = preferences.getString(variantKey(game), null)
    fun setStartupVariant(game: GameKey, variant: String) =
        preferences.edit().putString(variantKey(game), variant).apply()

    fun playerName(game: GameKey): String? = preferences.getString(playerKey(game), null)
    fun setPlayerName(game: GameKey, name: String) =
        preferences.edit().putString(playerKey(game), name).apply()

    fun videoHardware(game: GameKey? = null): Int = DosVideoHardware.normalize(
        common.int(game, DosVideoHardware.setting, preferences.getInt(DosVideoHardware.setting, 0)))

    fun reset(game: GameKey) {
        common.clear(game, "touch_mode", "direct_touch", "cycles_mode", "voodoo_mode", DosVideoHardware.setting)
        preferences.edit().remove(controllerKey(game)).remove(variantKey(game))
            .remove(playerKey(game)).apply()
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
        check(editor.commit()) { "Could not move the game's settings" }
    }

    companion object {
        private const val CONTROLLER = "game_controller_v2_"
        private const val VARIANT = "game_launch_variant_v2_"
        private const val PLAYER = "game_dos_player_v2_"

        /** Settings from before games had keys, named by content ID. */
        val LEGACY = listOf(
            GameKeyMigration.PreferenceFamily(GameSettingScope.CONTENT_ID_PREFIX, GameSettingScope.PREFIX, true),
            GameKeyMigration.PreferenceFamily("controller_game_", CONTROLLER, false),
            GameKeyMigration.PreferenceFamily("launch_variant_", VARIANT, false),
            GameKeyMigration.PreferenceFamily("dos_player_", PLAYER, false))
    }
}
