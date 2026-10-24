package com.mrjackspade.kairodos

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

/**
 * A game's DOSBox settings and startup commands: each starts as its catalog config's, and the
 * player's own value replaces it the next time the game starts.
 */
internal class DosConfigRows(
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

    /** The page's sections: the catalog config's, then any the player added. */
    private fun sections(): Map<String, List<String>> {
        val keys = LinkedHashMap<String, MutableList<String>>()
        config()?.sections?.forEach { (section, values) -> keys.getOrPut(section) { ArrayList() } += values.map { it.first } }
        own().keys.forEach { name ->
            val section = name.substringBefore('.')
            val key = name.substringAfter('.')
            val list = keys.getOrPut(section) { ArrayList() }
            if (key !in list) list += key
        }
        return keys
    }

    fun settingsRow(open: (GroupsPage) -> Unit) = SettingsRow.nav("dosbox-settings", "DOSBox settings",
        value = { own().size.let { if (it == 0) "" else "$it custom" } },
        enabled = enabled, disabledReason = { GameSettingsRows.NO_HASH }) { open(page()) }

    fun commandsRow() = GameSettingsRows.text("startup-commands", "Startup commands", "One DOS command per line",
        { settings.autoexec(game) ?: config()?.autoexec?.joinToString("\n")?.takeIf(String::isNotBlank) },
        { settings.autoexec(game) != null }, source,
        { settings.setAutoexec(game, it) }, { settings.setAutoexec(game, null) },
        enabled = enabled, disabledReason = { GameSettingsRows.NO_HASH }, multiline = true)

    private fun page() = GroupsPage("DOSBox settings", scope, {
        sections().map { (section, keys) ->
            RowGroup(section.uppercase(), keys.map { key -> settingRow(section, key) })
        } + RowGroup(null, listOf(addRow()))
    })

    private fun settingRow(section: String, key: String): SettingsRow {
        val custom = { "$section.$key" in own() }
        val app = { host().firstOrNull { it.first == section }?.second?.firstOrNull { it.first == key }?.second }
        return GameSettingsRows.text("$section.$key", key, "$section $key",
            { own()["$section.$key"] ?: app() ?: config()?.value(section, key) }, custom,
            { if (app() != null) GameSource.BUILT_IN else source() },
            { settings.setConfigSetting(game, section, key, it) },
            { settings.setConfigSetting(game, section, key, null) }, empty = "Not set", enabled = enabled)
    }

    /** A setting the catalog config does not have, written as section.key = value. */
    private fun addRow() = SettingsRow.action("add-setting", "Add setting", enabled = enabled, tray = {
        TraySpec(input = TrayInput("section.key = value"), buttons = listOf(TrayButton("Cancel"),
            TrayButton("Add", ButtonTone.PRIMARY) { text -> add(text) }))
    })

    private fun add(text: String) {
        val match = ADDED.matchEntire(text.trim()) ?: return complain("Write the setting as section.key = value")
        settings.setConfigSetting(game, match.groupValues[1].lowercase(), match.groupValues[2].lowercase(),
            match.groupValues[3].trim())
    }

    private companion object {
        val ADDED = Regex("([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)\\s*=\\s*(.*)")
    }
}
