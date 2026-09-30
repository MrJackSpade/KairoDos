// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.util.AtomicFile
import java.io.File

/** Translate catalog launch profiles to ordinary Staging mounts. Catalog
 * commands run in DOS; only MOUNT/IMGMOUNT receive native Android paths. */
internal object DosStagingLaunchConfig {
    private val mount = Regex("(?i)^(\\s*@?mount\\s+)([a-z])(\\s+)(\"[^\"]*\"|\\S+)(.*)$")
    private val mountC = Regex("(?i)^@?mount\\s+c\\s+.+$")
    private val imgC = Regex("(?i)^@?imgmount\\s+c\\s+.+$")

    fun write(config: File, drive: DosStagingStorage.Drive, launch: DosGameCatalog.Launch?,
              name: String, dependencies: Map<String, File>, player: String?,
              singleVoodooThread: Boolean, directTouch: Boolean): File {
        val source = launch?.configs?.get(name)
        val folder = launch?.folder.orEmpty()
        val parentMount = launch?.let { DosLaunchConfig.mountsParent(it, name) } == true
        val wrapped = drive.directory.listFiles().orEmpty().firstOrNull {
            it.isDirectory && it.name.equals(folder, true)
        }
        val onlyChild = drive.directory.listFiles().orEmpty().singleOrNull()
        val gameRoot = wrapped ?: if (folder.isEmpty() && onlyChild?.isDirectory == true)
            onlyChild else drive.directory
        val mountedRoot = if (parentMount) drive.directory else gameRoot
        val marker = Regex("(?im)^\\s*\\[autoexec]\\s*$").find(source.orEmpty())
        val commands = marker?.let { source!!.substring(it.range.last + 1).let { tail ->
            Regex("(?m)^\\s*\\[").find(tail)?.let { tail.substring(0, it.range.first) } ?: tail
        } }.orEmpty()
        val contentDrive = if (!commands.lineSequence().any { mountC.matches(it.trim()) } &&
            commands.lineSequence().any { imgC.matches(it.trim()) }) "X" else "C"
        val dosPrefix = if (parentMount) "$contentDrive:\\$folder\\" else "$contentDrive:\\"
        val sourceGame = Regex("(?i)\\.[\\\\/]eXoDOS[\\\\/]${Regex.escape(folder)}(?:[\\\\/]|(?=\\s|\"|$))")
        val sourceDiscs = Regex("(?i)\\.[\\\\/]discs[\\\\/]")
        val sourceFloppy = Regex("(?i)\\.[\\\\/]floppy[\\\\/]")
        fun nativePath(path: String): String {
            val normalized = path.replace('\\', '/').trimEnd('/')
            val exodos = Regex("(?i)^\\./eXoDOS(?:/(.*))?$").matchEntire(normalized)
            if (exodos != null) {
                val inside = exodos.groupValues[1]
                if (inside.isEmpty()) return drive.directory.absolutePath
                val game = inside.substringBefore('/')
                val root = if (game.equals(folder, true)) gameRoot else
                    dependencies[game.lowercase()] ?: error("Missing eXoDOS dependency: $game")
                return DosStagingStorage.path(root, inside.substringAfter('/', "")).absolutePath
            }
            if (normalized.startsWith("./discs/", true))
                return DosStagingStorage.path(gameRoot, "discs/" + normalized.substring(8)).absolutePath
            if (normalized.startsWith("./floppy/", true))
                return DosStagingStorage.path(gameRoot, "floppy/" + normalized.substring(9)).absolutePath
            if (normalized == ".") return mountedRoot.absolutePath
            return path
        }
        val lines = ArrayList<String>()
        if (marker != null) lines += source!!.substring(0, marker.range.first).trimEnd()
        lines += "[autoexec]"
        lines += "@echo off"
        val initialMount = commands.lineSequence().mapNotNull { mount.matchEntire(it) }
            .firstOrNull { it.groupValues[2].equals("C", true) }
        val initialRoot = initialMount?.let { nativePath(it.groupValues[4].trim('"')) }
            ?: mountedRoot.absolutePath
        lines += "mount $contentDrive \"$initialRoot\"" + (initialMount?.groupValues?.get(5) ?: "")
        lines += "$contentDrive:"
        lines += DosLaunchConfig.setupLines(folder, player)
        if (source == null) {
            if (drive.media != null) {
                when (drive.media.extension.lowercase()) {
                    "iso", "cue" -> lines += "imgmount D \"${drive.media.absolutePath}\" -t iso"
                    "img", "ima" -> lines += "boot \"${drive.media.absolutePath}\""
                    else -> error("DOSBox Staging does not support this standalone media type: ${drive.media.extension}")
                }
            } else {
                val executables = mountedRoot.listFiles().orEmpty().filter {
                    it.isFile && it.extension.lowercase() in setOf("exe", "com", "bat")
                }
                if (executables.size == 1) lines += executables.single().name
                else lines += "dir /w"
            }
        } else for (original in commands.lineSequence()) {
            val line = original.trimEnd('\r')
            val trimmed = line.trim()
            if (mountC.matches(trimmed) || (contentDrive == "C" && trimmed.equals("C:", true))) continue
            val mounted = mount.matchEntire(line)
            if (mounted != null) {
                val originalPath = mounted.groupValues[4].trim('"')
                val path = nativePath(originalPath)
                val prefix = if (File(path).isFile && File(path).extension.lowercase() in
                    setOf("iso", "cue", "img", "ima")) mounted.groupValues[1].replace("mount", "imgmount", true)
                    else mounted.groupValues[1]
                lines += "$prefix${mounted.groupValues[2]} \"$path\"${mounted.groupValues[5]}"
                continue
            }
            if (trimmed.startsWith("imgmount ", true) || trimmed.startsWith("@imgmount ", true)) {
                // Image mounting accepts host paths. Quoted paths can contain
                // spaces; retain the source quoting and convert only operands.
                val originalTokens = Regex("\"[^\"]*\"|\\S+").findAll(line).map { it.value }.toList()
                val tokens = originalTokens.filterIndexed { index, token ->
                    !token.equals("-ide", true) && (index == 0 || !originalTokens[index - 1].equals("-ide", true))
                }
                lines += tokens.mapIndexed { index, token ->
                    if (index < 2 || token.startsWith('-')) token else {
                        val path = nativePath(token.trim('"'))
                        if (path != token.trim('"')) "\"$path\"" else token
                    }
                }.joinToString(" ")
                continue
            }
            var adapted = if (folder.isEmpty()) line else sourceGame.replace(line) { dosPrefix }
            adapted = sourceDiscs.replace(adapted) { dosPrefix + "discs\\" }
            adapted = sourceFloppy.replace(adapted) { dosPrefix + "floppy\\" }
            lines += adapted
        }
        // Product-owned host settings override desktop settings in the source
        // profile. They do not change the emulated VGA refresh rate.
        lines += listOf("[sdl]", "output = texture", "vsync = off",
            "presentation_mode = dos-rate", "window_size = 800x600",
            "fullscreen = false", "pause_when_inactive = false", "[mixer]",
            "rate = 48000", "blocksize = 512", "prebuffer = 30", "negotiate = false",
            "[mouse]", "mouse_capture = ${if (directTouch) "seamless" else "onstart"}",
            "mouse_raw_input = false", "[joystick]", "joysticktype = 2axis")
        lines += listOf("[voodoo]", "voodoo_threads = ${if (singleVoodooThread) "1" else "auto"}")
        val atomic = AtomicFile(config)
        val output = atomic.startWrite()
        try {
            output.write((lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        return config
    }
}
