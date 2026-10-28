// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Translate catalog launch profiles to ordinary Staging mounts. Catalog
 * commands run in DOS; only MOUNT/IMGMOUNT receive native Android paths. */
internal object DosStagingLaunchConfig {
    private val mount = Regex("(?i)^(\\s*@?mount\\s+)([a-z])(\\s+)(\"[^\"]*\"|\\S+)(.*)$")
    private val mountC = Regex("(?i)^@?mount\\s+c\\s+.+$")
    private val imgC = Regex("(?i)^@?imgmount\\s+c\\s+.+$")
    private val WRITABLE_IMAGES = setOf("img", "ima")

    private fun cpuCore(source: String?): String {
        var inCpu = false
        var core = "auto"
        var type = "auto"
        for (original in source.orEmpty().lineSequence()) {
            val line = original.substringBefore('#').substringBefore(';').trim()
            if (line.startsWith('[')) inCpu = line.equals("[cpu]", true)
            else if (inCpu && '=' in line) {
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').trim().lowercase()
                if (key == "core") core = value
                if (key == "cputype") type = value
            }
        }
        // Prefetch CPU types require their interpreter. Explicit compatibility
        // cores stay intact; ordinary profiles use ARM64 dynarec in real mode
        // as well as protected mode. ARM dynarec already uses software x87.
        if (type.endsWith("_prefetch") && core in setOf("", "auto", "dynamic", "dynamic_nodhfpu")) return "auto"
        return if (core in setOf("", "auto", "dynamic_nodhfpu")) "dynamic" else core
    }

    /**
     * The settings the app gives every game over its catalog config, by section in the order
     * written; a game's own DOSBox settings follow them.
     */
    fun hostSettings(source: String?, singleVoodooThread: Boolean, directTouch: Boolean,
                     videoHardware: Int): List<Pair<String, List<Pair<String, String>>>> = listOfNotNull(
        // Product-owned host settings override desktop settings in the source
        // profile. They do not change the emulated VGA refresh rate.
        // Disable CPU pixel/scan doubling: Android scales the native-size surface.
        // Plain texture enables a redundant 320x200 -> 640x400 CPU expansion.
        "sdl" to listOf("output" to "texturenb", "vsync" to "off", "presentation_mode" to "dos-rate",
            "window_size" to "800x600", "fullscreen" to "false", "pause_when_inactive" to "false"),
        "mixer" to listOf("rate" to "48000", "blocksize" to "512", "prebuffer" to "30", "negotiate" to "false"),
        "mouse" to listOf("mouse_capture" to if (directTouch) "seamless" else "onstart", "mouse_raw_input" to "false"),
        "joystick" to listOf("joysticktype" to "2axis"),
        // Desktop catalog cycle counts can exceed the handheld's capacity and
        // slow the DOS clock itself. Auto keeps real-mode compatibility and
        // adapts protected-mode cycles to the host instead of inheriting those
        // fixed desktop budgets. Keep this after the imported CPU settings.
        "cpu" to listOf("core" to cpuCore(source), "cycles" to "auto"),
        "voodoo" to listOf("voodoo_threads" to if (singleVoodooThread) "1" else "auto"),
        // Automatic preserves the catalog's machine (Staging defaults to S3).
        // Explicit user choices follow imported settings so they take precedence.
        DosVideoHardware.machine(videoHardware)?.let { "dosbox" to listOf("machine" to it) })

    /**
     * A launch configuration and the host paths it names: AUTOEXEC.BAT is read in the DOS code
     * page, so it names each host path by reference (`kairo-host:<n>` is [hostPaths]`[n]`), which
     * DOSBox's MOUNT, IMGMOUNT and BOOT resolve (KairoStagingHostPath) with every character intact.
     */
    class Written(val config: File, val hostPaths: List<String>)

    /** The reference prefix, KAIRO_HOST_PATH_PREFIX in staging_bridge.h. */
    private const val HOST_PATH_PREFIX = "kairo-host:"

    /** [context] puts a failure the player reads in their language. */
    fun write(context: Context, config: File, drive: DosStagingStorage.Drive, launch: DosGameCatalog.Launch?,
              name: String, dependencies: Map<String, File>, overlays: Map<File, File>, player: String?,
              singleVoodooThread: Boolean, directTouch: Boolean, videoHardware: Int = 0,
              target: DosLaunchTarget = DosLaunchTarget.Normal, settings: Map<String, String> = emptyMap(),
              autoexec: String? = null): Written {
        val hostPaths = ArrayList<String>()
        // A mount operand: a host path by reference, quoted; a DOS path as it is.
        fun operand(path: String): String {
            if (!File(path).isAbsolute) return "\"$path\""
            hostPaths += path
            return "\"$HOST_PATH_PREFIX${hostPaths.size - 1}\""
        }
        val manualProgram = target == DosLaunchTarget.Browse || target is DosLaunchTarget.Program
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
        // The game's own startup commands replace its config's.
        val commands = autoexec ?: marker?.let { source!!.substring(it.range.last + 1).let { tail ->
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
                    dependencies[game.lowercase()] ?: error(context.getString(R.string.exodos_dependency_missing, game))
                return DosStagingStorage.path(root, inside.substringAfter('/', "")).absolutePath
            }
            if (normalized.startsWith("./discs/", true))
                return DosStagingStorage.path(gameRoot, "discs/" + normalized.substring(8)).absolutePath
            if (normalized.startsWith("./floppy/", true))
                return DosStagingStorage.path(gameRoot, "floppy/" + normalized.substring(9)).absolutePath
            if (normalized == ".") return mountedRoot.absolutePath
            return path
        }
        val layers = overlays.map { (base, layer) -> base.canonicalFile to layer }
        // A path inside a game's base drive, mapped to the same place in its overlay.
        fun layerOf(path: String): File? {
            val file = File(path).canonicalFile
            return layers.firstNotNullOfOrNull { (base, layer) ->
                when {
                    file == base -> layer
                    file.path.startsWith(base.path + File.separator) ->
                        File(layer, file.path.substring(base.path.length + 1))
                    else -> null
                }
            }
        }
        // Staging sends every write to a mounted game folder into its overlay.
        fun overlayMount(letter: String, path: String, options: String): String? {
            if (!File(path).isDirectory) return null
            val layer = layerOf(path) ?: return null
            val type = Regex("(?i)-t\\s+(\\S+)").find(options)?.groupValues?.get(1)?.lowercase()
            if (type != null && type !in setOf("dir", "floppy")) return null // CD-ROM folders are read-only.
            require(layer.isDirectory || layer.mkdirs()) { "Could not create the game's save folder" }
            return "mount $letter ${operand(layer.absolutePath)} -t overlay" + options.replace(Regex("(?i)\\s*-t\\s+\\S+"), "")
        }
        // Disk images are written in place, so the game uses its overlay's copy of each one.
        fun writableImage(path: String): String {
            if (File(path).extension.lowercase() !in WRITABLE_IMAGES) return path
            val layer = layerOf(path) ?: return path
            if (!layer.isFile) {
                require(layer.parentFile!!.isDirectory || layer.parentFile!!.mkdirs()) { "Could not create the game's save folder" }
                File(path).copyTo(layer)
                layer.setLastModified(File(path).lastModified())
            }
            return layer.absolutePath
        }
        val lines = ArrayList<String>()
        if (marker != null) lines += source!!.substring(0, marker.range.first).trimEnd()
        lines += "[autoexec]"
        lines += "@echo off"
        // This is a one-session launch choice, not a saved startup variant.
        // Do not execute catalog setup scripts, BOOT, or a lone executable.
        if (target == DosLaunchTarget.Prompt) {
            lines += "mount C ${operand(gameRoot.absolutePath)}"
            overlayMount("C", gameRoot.absolutePath, "")?.let(lines::add)
            lines += "C:"
            lines += "dir /w"
        } else {
            val initialMount = commands.lineSequence().mapNotNull { mount.matchEntire(it) }
                .firstOrNull { it.groupValues[2].equals("C", true) }
            val initialRoot = initialMount?.let { nativePath(it.groupValues[4].trim('"')) }
                ?: mountedRoot.absolutePath
            val initialOptions = initialMount?.groupValues?.get(5) ?: ""
            lines += "mount $contentDrive ${operand(initialRoot)}" + initialOptions
            overlayMount(contentDrive, initialRoot, initialOptions)?.let(lines::add)
            lines += "$contentDrive:"
            if (!manualProgram) lines += DosLaunchConfig.setupLines(folder, player)
            if (source == null && autoexec == null) {
                if (drive.media != null) {
                    when (drive.media.extension.lowercase()) {
                        "iso", "cue" -> lines += "imgmount D ${operand(drive.media.absolutePath)} -t iso"
                        "img", "ima" -> if (manualProgram) {
                            val floppy = drive.media.length() <= 2_949_120
                            lines += "imgmount ${if (floppy) "A" else "D"} ${operand(writableImage(drive.media.absolutePath))} -t ${if (floppy) "floppy" else "hdd"}"
                        } else lines += "boot ${operand(writableImage(drive.media.absolutePath))}"
                        else -> error(context.getString(R.string.media_type_unsupported, "DOSBox Staging", drive.media.extension))
                    }
                } else {
                    val executables = mountedRoot.listFiles().orEmpty().filter {
                        it.isFile && it.extension.lowercase() in setOf("exe", "com", "bat")
                    }
                    if (executables.size == 1 && !manualProgram) lines += executables.single().name
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
                    val image = File(path).isFile && File(path).extension.lowercase() in setOf("iso", "cue", "img", "ima")
                    val prefix = if (image) mounted.groupValues[1].replace("mount", "imgmount", true)
                        else mounted.groupValues[1]
                    lines += "$prefix${mounted.groupValues[2]} ${operand(if (image) writableImage(path) else path)}${mounted.groupValues[5]}"
                    if (!image) overlayMount(mounted.groupValues[2], path, mounted.groupValues[5])?.let(lines::add)
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
                            val path = writableImage(nativePath(token.trim('"')))
                            if (path != token.trim('"')) operand(path) else token
                        }
                    }.joinToString(" ")
                    continue
                }
                if (manualProgram) continue // Mounts only; never execute the game's startup script.
                var adapted = if (folder.isEmpty()) line else sourceGame.replace(line) { dosPrefix }
                adapted = sourceDiscs.replace(adapted) { dosPrefix + "discs\\" }
                adapted = sourceFloppy.replace(adapted) { dosPrefix + "floppy\\" }
                lines += adapted
            }
        }
        if (target is DosLaunchTarget.Program) {
            val program = target.path
            val directory = program.substringBeforeLast('\\').ifEmpty { "\\" }
                .let { if (it.length == 2) "$it\\" else it }
            lines += program.take(2)
            // These are short paths returned by DOS, not host filenames. The
            // DOS CD handler treats quotes literally (unlike MOUNT/COPY).
            lines += "cd ${directory.replace("%", "%%")}"
            if (program.endsWith(".cmd", true)) {
                // DOS-compatible CMD text is run as a batch file on an app-owned
                // scratch drive. Never rename/overwrite a file in the game drive.
                require(target.occupiedDrives.isNotEmpty()) { context.getString(R.string.command_script_needs_drives) }
                val used = target.occupiedDrives + lines.mapNotNull { Regex("(?i)^\\s*@?(?:img)?mount\\s+([a-z])\\s+").find(it)
                    ?.groupValues?.get(1)?.uppercase()?.single() }.toSet()
                val letter = ('Y' downTo 'A').firstOrNull { it !in used }
                    ?: error(context.getString(R.string.command_script_no_drive))
                val scratch = File(config.parentFile, "command-script").apply {
                    require(isDirectory || mkdirs()) { "Could not prepare command script drive" }
                }
                File(scratch, "KAIRO.BAT").writeText("@echo Could not read command script.\r\n")
                lines += "mount $letter ${operand(scratch.absolutePath)}"
                lines += "copy ${DosLaunchTarget.batchQuote(program)} $letter:\\KAIRO.BAT >NUL"
                lines += "call $letter:\\KAIRO.BAT"
            } else lines += program.replace("%", "%%")
        }
        hostSettings(source, singleVoodooThread, directTouch, videoHardware).forEach { (section, values) ->
            lines += "[$section]"
            values.forEach { (key, value) -> lines += "$key = $value" }
        }
        // The game's own DOSBox settings come last, over the catalog's and the app's.
        settings.entries.groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.') to it.value })
            .forEach { (section, values) ->
                lines += "[$section]"
                values.forEach { (key, value) -> lines += "$key = $value" }
            }
        val atomic = AtomicFile(config)
        val output = atomic.startWrite()
        try {
            output.write((lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        return Written(config, hostPaths)
    }
}
