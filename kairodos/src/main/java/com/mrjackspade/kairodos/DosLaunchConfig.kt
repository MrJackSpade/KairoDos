package com.mrjackspade.kairodos

import android.util.AtomicFile
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Adapts eXoDOS's per-game DOSBox configuration to Pure's mounted game ZIP. */
internal object DosLaunchConfig {
    data class Dependency(val file: File, val stripRoot: Boolean)
    private val mountC = Regex("(?i)^@?mount\\s+c\\s+(.+)$")
    private val mountDrive = Regex("(?i)^(\\s*@?mount\\s+)([a-z])(\\s+)(\"[^\"]*\"|\\S+)(.*)$")
    private val imageC = Regex("(?i)^@?imgmount\\s+c\\s+.+$")
    private val driveC = Regex("(?i)^@?c:\\s*$")

    fun needsPlayer(launch: DosGameCatalog.Launch?): Boolean =
        launch?.folder?.lowercase() in setOf("azalta", "dom_door", "legord")

    private fun setupLines(folder: String, player: String?): List<String> {
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

    fun write(file: File, launch: DosGameCatalog.Launch?, name: String = "dosbox.conf",
              dependencies: Map<String, Dependency> = emptyMap(), player: String? = null) {
        val sidecar = File(file.parentFile, file.nameWithoutExtension + ".conf")
        val source = launch?.configs?.get(name)
        if (source == null) { sidecar.delete(); return }
        val parentMount = mountsParent(launch, name)
        val folder = launch.folder
        val autoexecMarker = Regex("(?im)^\\s*\\[autoexec]\\s*$").find(source)
        val autoexecText = autoexecMarker?.let { source.substring(it.range.last + 1) } ?: ""
        val hasMountC = autoexecText.lineSequence().any { mountC.matches(it.trim()) }
        val contentDrive = if (!hasMountC && autoexecText.lineSequence().any {
                imageC.matches(it.trim()) }) "X" else "C"
        val prefix = if (parentMount) "$contentDrive:\\$folder\\" else "$contentDrive:\\"
        val sourceFolder = ".\\eXoDOS\\$folder"
        val sourceGamePath = Regex("(?i)\\.[\\\\/]eXoDOS[\\\\/]${Regex.escape(folder)}[\\\\/]")
        val sourceGameRoot = Regex("(?i)\\.[\\\\/]eXoDOS[\\\\/]${Regex.escape(folder)}(?=\\s|\"|$)")
        val sourceDiscs = Regex("(?i)\\.[\\\\/]discs[\\\\/]")
        val sourceFloppy = Regex("(?i)\\.[\\\\/]floppy[\\\\/]")
        val ideOption = Regex("(?i)\\s+-ide\\s+\\S+")
        val lines = ArrayList<String>()
        var autoexec = false
        for (original in source.lineSequence()) {
            val line = original.trimEnd('\r')
            if (line.trim().equals("[autoexec]", true)) {
                autoexec = true
                lines += line
                if (contentDrive == "X") lines += "REMOUNT C X"
                lines += setupLines(folder, player)
                continue
            }
            if (!autoexec) { lines += line; continue }
            val trimmed = line.trim()
            if (mountC.matches(trimmed) ||
                (contentDrive == "C" && driveC.matches(trimmed))) continue
            val mount = mountDrive.matchEntire(line)
            if (mount != null) {
                val path = mount.groupValues[4].trim('"')
                val foreign = Regex("(?i)^\\.[\\\\/]eXoDOS[\\\\/]([^\\\\/]+)(?:[\\\\/](.*))?$")
                    .matchEntire(path)
                if (foreign != null && !foreign.groupValues[1].equals(folder, true)) {
                    val other = foreign.groupValues[1].lowercase()
                    val dependency = dependencies[other]
                        ?: error("Missing eXoDOS dependency: $other")
                    val subdir = foreign.groupValues[2].replace('/', '\\').trim('\\')
                    val option = if (dependency.stripRoot) "1" else "0"
                    val root = "KAIROZIP:$option:${dependency.file.absolutePath}|$subdir"
                    lines += "${mount.groupValues[1]}${mount.groupValues[2]}" +
                        "${mount.groupValues[3]}\"$root\"${mount.groupValues[5]}"
                    continue
                }
                if (path.equals(sourceFolder, true) ||
                    path.startsWith("$sourceFolder\\", true)) {
                    if (!path.endsWith(".img", true) && !path.endsWith(".ima", true)) {
                        val subdir = path.substring(sourceFolder.length).trim('\\', '/')
                        val inside = (if (parentMount) "$folder\\$subdir" else subdir)
                            .trimEnd('\\')
                        lines += "${mount.groupValues[1]}${mount.groupValues[2]}${mount.groupValues[3]}" +
                            "KAIRO:$contentDrive:$inside${mount.groupValues[5]}"
                        continue
                    }
                    // A few source launchers use MOUNT for a list of disk images.
                    // Pure handles those with IMGMOUNT instead.
                    val imageLine = line.replaceFirst(Regex("(?i)mount"), "imgmount")
                    lines += sourceGamePath.replace(imageLine) { prefix }
                    continue
                }
            }
            var adapted = sourceGamePath.replace(line) { prefix }
            adapted = sourceGameRoot.replace(adapted) { prefix.trimEnd('\\') }
            adapted = sourceDiscs.replace(adapted) { prefix + "discs\\" }
            adapted = sourceFloppy.replace(adapted) { prefix + "floppy\\" }
            if (imageC.matches(adapted.trim()) ||
                adapted.trim().startsWith("imgmount ", true) ||
                adapted.trim().startsWith("@imgmount ", true)) {
                adapted = ideOption.replace(adapted, "")
            }
            lines += adapted
        }
        val atomic = AtomicFile(sidecar)
        val output = atomic.startWrite()
        try {
            output.write((lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }
}
