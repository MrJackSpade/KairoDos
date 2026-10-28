package com.mrjackspade.kairodos

import android.content.Context
import com.mrjackspade.kairo.frontend.ButtonTone
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameSettingsRows
import com.mrjackspade.kairo.frontend.GameSource
import com.mrjackspade.kairo.frontend.GroupsPage
import com.mrjackspade.kairo.frontend.RowGroup
import com.mrjackspade.kairo.frontend.SettingsRow
import com.mrjackspade.kairo.frontend.TrayButton
import com.mrjackspade.kairo.frontend.TrayInput
import com.mrjackspade.kairo.frontend.TraySpec
import com.mrjackspade.kairo.frontend.R as FrontendR

/**
 * A game's DOSBox settings and startup commands, shown when it runs its Custom version: each
 * starts as its preset's (or the app's), and the player's own value replaces it the next time
 * the game starts.
 */
internal class DosConfigRows(
    private val context: Context,
    private val game: GameKey,
    private val settings: DosPerGameSettings,
    /** The catalog config of the version the game starts, or null when the catalog has none. */
    private val config: () -> DosConfig?,
    /** What the app sets over the catalog config for every game (DosStagingLaunchConfig.hostSettings). */
    private val host: () -> List<Pair<String, List<Pair<String, String>>>>,
    private val source: () -> String,
    private val enabled: () -> Boolean,
    private val scope: () -> String,
    /** Tells the player what is wrong with a setting they typed. */
    private val complain: (String) -> Unit
) {
    private fun own() = settings.configSettings(game)

    /**
     * The page's sections: the catalog config's, then the app's (so a game without a preset still
     * shows what it runs with), then any the player added.
     */
    private fun sections(): Map<String, List<String>> {
        val keys = LinkedHashMap<String, MutableList<String>>()
        fun add(section: String, key: String) {
            val list = keys.getOrPut(section) { ArrayList() }
            if (key !in list) list += key
        }
        config()?.sections?.forEach { (section, values) -> values.forEach { add(section, it.first) } }
        host().forEach { (section, values) -> values.forEach { add(section, it.first) } }
        own().keys.forEach { add(it.substringBefore('.'), it.substringAfter('.')) }
        return keys
    }

    private val title get() = context.getString(R.string.dosbox_settings, "DOSBox")

    fun settingsRow(open: (GroupsPage) -> Unit) = SettingsRow.nav("dosbox-settings", title,
        value = { own().size.let { if (it == 0) "" else context.getString(R.string.dosbox_settings_custom, it) } },
        enabled = enabled, disabledReason = { GameSettingsRows.noHash(context) }) { open(page()) }

    fun commandsRow() = GameSettingsRows.text(context, "startup-commands", context.getString(R.string.startup_commands),
        context.getString(R.string.startup_commands_hint),
        { settings.autoexec(game) ?: config()?.autoexec?.joinToString("\n")?.takeIf(String::isNotBlank) },
        { settings.autoexec(game) != null }, source,
        { settings.setAutoexec(game, it) }, { settings.setAutoexec(game, null) },
        enabled = enabled, disabledReason = { GameSettingsRows.noHash(context) }, multiline = true)

    private fun page() = GroupsPage(title, scope, {
        sections().map { (section, keys) ->
            RowGroup(section.uppercase(), keys.map { key -> settingRow(section, key) })
        } + RowGroup(null, listOf(addRow()))
    })

    private fun settingRow(section: String, key: String): SettingsRow {
        val custom = { "$section.$key" in own() }
        val app = { host().firstOrNull { it.first == section }?.second?.firstOrNull { it.first == key }?.second }
        return GameSettingsRows.text(context, "$section.$key", key, "$section $key",
            { own()["$section.$key"] ?: app() ?: config()?.value(section, key) }, custom,
            { if (app() != null) GameSource.BUILT_IN else source() },
            { settings.setConfigSetting(game, section, key, it) },
            { settings.setConfigSetting(game, section, key, null) }, empty = context.getString(R.string.setting_not_set), enabled = enabled)
    }

    /** A setting the catalog config does not have, written as section.key = value. */
    private fun addRow() = SettingsRow.action("add-setting", context.getString(R.string.setting_add), enabled = enabled, tray = {
        TraySpec(input = TrayInput("section.key = value"), buttons = listOf(TrayButton(context.getString(FrontendR.string.kairo_cancel)),
            TrayButton(context.getString(R.string.add), ButtonTone.PRIMARY) { text -> add(text) }))
    })

    private fun add(text: String) {
        val match = ADDED.matchEntire(text.trim()) ?: return complain(context.getString(R.string.setting_add_invalid))
        settings.setConfigSetting(game, match.groupValues[1].lowercase(), match.groupValues[2].lowercase(),
            match.groupValues[3].trim())
    }

    private companion object {
        val ADDED = Regex("([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)\\s*=\\s*(.*)")
    }
}
