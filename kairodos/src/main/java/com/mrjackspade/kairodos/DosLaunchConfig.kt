package com.mrjackspade.kairodos



import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Catalog startup profile and player setup helpers for the DOS backend. */
internal object DosLaunchConfig {

    private val mountC = Regex("(?i)^@?mount\\s+c\\s+(.+)$")
    private val mountDrive = Regex("(?i)^(\\s*@?(?:img)?mount\\s+)([a-z])(\\s+)(\"[^\"]*\"|\\S+)(.*)$")

    fun needsPlayer(launch: DosGameCatalog.Launch?): Boolean =
        launch?.folder?.lowercase() in setOf("azalta", "dom_door", "legord")

    fun setupLines(folder: String, player: String?): List<String> {
        if (folder.lowercase() !in setOf("azalta", "dom_door", "legord")) return emptyList()
        require(player != null && player.matches(Regex("[A-Z0-9_]{1,8}"))) {
            "Choose a DOS player name"
        }
        val marker = "echo $player>$player.USR"
        if (folder.equals("azalta", true)) return listOf(marker,
            "echo AZALTA $player>RUN.BAT")
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("MM-dd-yyyy"))
        val values = if (folder.equals("dom_door", true)) listOf(
            "1", player, player, "", "21", "M", "16097.00", date, "80", "25", "255",
            "0", "0", "1", "1", "9999", "C:\\WWIV\\GFILES\\", "C:\\WWIV\\DATA\\",
            "890519.LOG", "2400", "1", "eXoDOS", "eXo", "37320", "60",
            "0", "0", "0", "0", "8N1", "2400", "7400")
        else listOf("COM0:", "0", "8", "1", "19200", "Y", "N", "Y", "N", player,
            "The Internet", "555-555-5555", "555-555-5555", "password", "110", "1",
            date, "30", "120", "GR", "23", "N", "", "", "", "1", "",
            "0", "0", "0", "0", "01-01-1979", "", "", "eXo DOS", "eXo",
            "none", "Y", "N", "Y", "7", "0", date, "", "", "32768",
            "0", "0", "0", "0", "0", "0")
        val filename = if (folder.equals("dom_door", true)) "CHAIN.TXT" else "DOOR.SYS"
        val commands = values.mapIndexed { index, value ->
            "echo${if (value.isEmpty()) "." else " $value"}" +
                "${if (index == 0) ">" else ">>"}$filename"
        }
        return listOf(marker) + commands + if (folder.equals("dom_door", true))
            listOf("copy CHAIN.TXT 2.0\\DOMIN2\\CHAIN.TXT") else emptyList()
    }

    fun mountsParent(launch: DosGameCatalog.Launch, name: String = "dosbox.conf"): Boolean {
        val config = launch.configs[name] ?: return false
        val marker = Regex("(?im)^\\s*\\[autoexec]\\s*$").find(config) ?: return false
        val autoexec = config.substring(marker.range.last + 1)
        return autoexec.lineSequence().any { line ->
            val path = mountC.matchEntire(line.trim())?.groupValues?.get(1)
                ?.trim()?.substringBefore(' ')?.trim('"')?.trimEnd('\\', '/')
                ?: return@any false
            path.equals(".\\eXoDOS", true) || path.equals("./eXoDOS", true)
        }
    }

    fun requiredFolders(launch: DosGameCatalog.Launch?, name: String): Set<String> {
        val source = launch?.configs?.get(name) ?: return emptySet()
        val marker = Regex("(?im)^\\s*\\[autoexec]\\s*$").find(source) ?: return emptySet()
        return source.substring(marker.range.last + 1).lineSequence().mapNotNull { line ->
            val path = mountDrive.matchEntire(line.trimEnd('\r'))?.groupValues?.get(4)
                ?.trim('"') ?: return@mapNotNull null
            val other = Regex("(?i)^\\.[\\\\/]eXoDOS[\\\\/]([^\\\\/]+)").find(path)
                ?.groupValues?.get(1) ?: return@mapNotNull null
            other.takeUnless { it.equals(launch.folder, true) }?.lowercase()
        }.toSet()
    }

}
