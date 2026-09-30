package com.mrjackspade.kairodos

import android.content.SharedPreferences
import com.mrjackspade.kairo.frontend.GameSettingScope

/** DOS-only per-game choices; keys remain stable for existing installations. */
internal class DosPerGameSettings(private val preferences: SharedPreferences,
                                  private val common: GameSettingScope) {
    private fun controllerKey(id: String) = "controller_game_$id"
    private fun variantKey(id: String) = "launch_variant_$id"
    private fun playerKey(id: String) = "dos_player_$id"

    fun controllerBindings(id: String): String? = preferences.getString(controllerKey(id), null)
    fun setControllerBindings(id: String, json: String) =
        preferences.edit().putString(controllerKey(id), json).apply()
    fun clearControllerBindings(id: String) = preferences.edit().remove(controllerKey(id)).apply()

    fun startupVariant(id: String): String? = preferences.getString(variantKey(id), null)
    fun setStartupVariant(id: String, variant: String) =
        preferences.edit().putString(variantKey(id), variant).apply()

    fun playerName(id: String): String? = preferences.getString(playerKey(id), null)
    fun setPlayerName(id: String, name: String) =
        preferences.edit().putString(playerKey(id), name).apply()

    fun reset(id: String) {
        common.clear(id, "touch_mode", "direct_touch", "cycles_mode", "voodoo_mode")
        preferences.edit().remove(controllerKey(id)).remove(variantKey(id))
            .remove(playerKey(id)).apply()
    }
}
