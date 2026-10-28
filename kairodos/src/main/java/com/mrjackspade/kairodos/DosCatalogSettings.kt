package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.CatalogSettingsRule
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * AGENTS.md's catalog settings rule for the DOS catalog (catalog/core-v4.zip, as the build bundles it): a
 * game's settings are its catalog configs (eXoDOS presets), one per version. The Version row
 * picks one; its Custom version shows DOSBox settings, a row for each setting a config gives, and
 * Startup commands, its [autoexec], so every line a config holds must be one DosConfig reads.
 */
object DosCatalogSettings : CatalogSettingsRule {
    /** Fields that say what a game is or where its files are, not how it runs. */
    private val DESCRIBES = setOf("game", "title", "description", "tags", "heart", "artwork", "hidden")
    /** The game's controls, which its Controller settings change (KairoActivity). */
    private val CONTROLS = "controller"
    /** The folder the game's files are in, and whether eXoDOS ships an exception.bat (unused). */
    private val LAUNCH_DESCRIBES = setOf("folder", "exception")
    private val SECTION = Regex("^\\s*\\[[^]]+]\\s*$")

    override fun problems(root: File): List<String> {
        val catalog = File(root, "catalog/core-v4.zip")
        require(catalog.isFile) { "${catalog.path} is missing" }
        val problems = ZipFile(catalog).use { zip ->
            val documents = zip.entries().asSequence()
                .filter { it.name == "name-index-v1.json" || it.name.matches(Regex("shards/[0-9a-f]{2}\\.json")) }
                .sortedBy { it.name }.toList()
            require(documents.size > 1) { "${catalog.path} has no records" }
            documents.flatMap { entry ->
                val games = JSONObject(zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8))
                    .getJSONObject("games")
                games.keys().asSequence().flatMap { id -> recordProblems(id, games.getJSONObject(id)) }.toList()
            }
        }
        // One line per kind of problem, with how many records have it and the first one.
        return problems.groupBy { it.substringAfter(": ") }.map { (problem, all) ->
            "${all.first().substringBefore(": ")}: $problem" + if (all.size > 1) " (and ${all.size - 1} more)" else ""
        }
    }

    private fun recordProblems(id: String, record: JSONObject): List<String> =
        record.keys().asSequence().flatMap { field ->
            when (field) {
                in DESCRIBES, CONTROLS -> emptyList()
                // A file-name variant's fields are settings like its record's.
                "variants" -> record.getJSONObject(field).let { variants ->
                    variants.keys().asSequence().flatMap { recordProblems(id, variants.getJSONObject(it)) }.toList()
                }
                "launch" -> launchProblems(id, record.getJSONObject(field))
                else -> listOf("$id: $field has no game setting")
            }
        }.toList()

    private fun launchProblems(id: String, launch: JSONObject): List<String> =
        launch.keys().asSequence().flatMap { field ->
            when (field) {
                in LAUNCH_DESCRIBES -> emptyList()
                "configs" -> launch.getJSONObject(field).let { configs ->
                    configs.keys().asSequence().flatMap { name -> configProblems(id, name, configs.getString(name)) }.toList()
                }
                else -> listOf("$id: launch.$field has no game setting")
            }
        }.toList()

    /** Config lines the game's settings would not show: settings outside a section, or lines that set nothing. */
    private fun configProblems(id: String, name: String, text: String): List<String> {
        var section: String? = null
        return text.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val setting = line.substringBefore('#').trim()
            when {
                SECTION.matches(line) -> {
                    section = line.trim().removePrefix("[").removeSuffix("]").trim().lowercase()
                    null
                }
                section == "autoexec" || setting.isEmpty() -> null
                section == null -> "$id: $name has a line before its first section"
                '=' !in setting -> "$id: $name has a line in [$section] that sets nothing"
                else -> null
            }
        }.toList()
    }
}
