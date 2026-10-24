package com.mrjackspade.kairodos

/**
 * A catalog DOSBox config as the game's settings show it: every `key = value` of each section, in
 * order, and the [autoexec] lines, which DOS runs to start the game. A game's own settings replace
 * any of them (DosPerGameSettings), so whatever a catalog config sets the player can change.
 */
internal class DosConfig private constructor(
    /** Each section by name (lower case, without brackets): its settings, by key, in the order written. */
    val sections: Map<String, List<Pair<String, String>>>,
    /** The [autoexec] lines, as written. */
    val autoexec: List<String>
) {
    /** The value of [section].[key], or null when the config does not set it. */
    fun value(section: String, key: String): String? =
        sections[section]?.firstOrNull { it.first == key }?.second

    companion object {
        private val SECTION = Regex("^\\s*\\[([^]]+)]\\s*$")
        private const val AUTOEXEC = "autoexec"

        fun parse(text: String): DosConfig {
            val sections = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            val autoexec = ArrayList<String>()
            var section: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                val header = SECTION.matchEntire(line)
                if (header != null) {
                    section = header.groupValues[1].trim().lowercase()
                    if (section != AUTOEXEC) sections.getOrPut(section) { ArrayList() }
                    continue
                }
                if (section == AUTOEXEC) {
                    autoexec += line
                    continue
                }
                val setting = line.substringBefore('#').trim()
                if (setting.isEmpty() || section == null || '=' !in setting) continue
                val key = setting.substringBefore('=').trim().lowercase()
                val value = setting.substringAfter('=').trim()
                val list = sections.getValue(section)
                list.removeAll { it.first == key }
                list += key to value
            }
            while (autoexec.isNotEmpty() && autoexec.last().isBlank()) autoexec.removeAt(autoexec.lastIndex)
            return DosConfig(sections, autoexec)
        }

        /** What a game's version (one of its catalog configs, named as eXoDOS names them) is called. */
        fun versionLabel(name: String): String {
            val stem = name.lowercase().removeSuffix(".conf").removePrefix("dosbox").trimStart('_')
            val parts = stem.split('_').filter(String::isNotEmpty).map { part ->
                when (part) {
                    "tandy", "t" -> "Tandy"
                    "tga" -> "Tandy graphics"
                    "pcjr" -> "PCjr"
                    "cga", "c" -> "CGA"
                    "ega" -> "EGA"
                    "h" -> "Hercules"
                    "gbc" -> "Game Blaster"
                    "upg" -> "Upgraded"
                    "mods" -> "Mods"
                    "fix" -> "Fixed"
                    "2" -> "Alternate"
                    else -> part.uppercase()
                }
            }
            return if (parts.isEmpty()) "Standard" else parts.joinToString(", ")
        }
    }
}
