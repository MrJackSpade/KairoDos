// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

/**
 * How a Collection Chamber setup starts, from its own files: the game's default launch, with no
 * catalog record needed (the catalog only adds to it). The installer's shortcuts name what the
 * player starts on Windows: a batch file, DOSBox itself (renamed Run.exe), or a menu program whose
 * buttons run those. Following them to DOSBox gives each way the game starts: the folder DOSBox runs
 * in, which its configuration's relative paths are from, and the configuration it reads, as DOSBox
 * reads it (every -conf there is, else its default there; -c commands after the [autoexec]).
 * tools/clickteam_launch.py is the same derivation, for checking installers on the PC.
 */
internal object DosClickteamLaunch {
    /** The installed files, by their path (forward slashes) in any case. */
    interface Files {
        fun read(path: String): ByteArray?
        fun exists(path: String): Boolean
    }

    /** A shortcut the installer makes: the file it starts and the arguments it starts it with. */
    data class Entry(val target: String, val arguments: String)

    /**
     * The launch: the [folder] DOSBox runs in (empty for the install folder, else one folder in
     * it) and each way it starts, a DOSBox configuration by name; null when nothing starts DOSBox.
     */
    data class Launch(val folder: String, val configs: Map<String, String>)

    /** One DOSBox the entries start: in [folder], with these configuration files and -c commands. */
    private data class Run(val folder: String, val configs: List<String>, val commands: List<String>)

    private val TEXT: Charset = Charset.forName("windows-1252")
    private val PROGRAMS = listOf(".exe", ".bat", ".cmd", ".com")
    private val AUTOPLAY = "AMD50_RESOURCES_HEADER".toByteArray(Charsets.US_ASCII)
    private val MENU_KEY = Regex("P\\d+\\.O\\d+\\.S\\d+\\.RO\\d+\\.([12])")
    private const val DEPTH = 8

    fun launch(files: Files, entries: List<Entry>): Launch? {
        val seen = HashSet<Triple<String, String, String>>()
        val runs = LinkedHashSet<Run>()
        for (entry in entries) runs += follow(files, entry.target, entry.arguments, parent(entry.target), seen, 0)
        if (runs.isEmpty()) return null
        val folders = runs.map { it.folder }.distinct()
        // One launch folder per game: the base drive is laid out for it (DosStagingStorage).
        require(folders.size == 1) { "The setup starts DOSBox in several folders: ${folders.joinToString()}" }
        require('/' !in folders.single()) { "The setup starts DOSBox in a nested folder: ${folders.single()}" }
        val configs = LinkedHashMap<String, String>()
        for (run in runs) {
            val text = config(files, run)
            if (text in configs.values) continue
            val name = if (runs.size == 1) "dosbox.conf" else run.configs.last().substringAfterLast('/')
            configs[unique(configs, name)] = text
        }
        return Launch(folders.single(), configs)
    }

    private fun unique(configs: Map<String, String>, name: String): String {
        if (name !in configs) return name
        val stem = name.substringBeforeLast('.')
        return generateSequence(2) { it + 1 }.map { "${stem}_$it.conf" }.first { it !in configs }
    }

    /** The DOSBox runs starting [target] with [arguments] from [folder] leads to. */
    private fun follow(files: Files, target: String, arguments: String, folder: String,
                       seen: MutableSet<Triple<String, String, String>>, depth: Int): List<Run> {
        if (depth > DEPTH || !seen.add(Triple(target.lowercase(Locale.ROOT), arguments, folder.lowercase(Locale.ROOT)))) return emptyList()
        return when (extension(target)) {
            ".bat", ".cmd" -> batch(files, String(files.read(target) ?: return emptyList(), TEXT), folder, seen, depth)
            ".exe" -> {
                val program = files.read(target) ?: return emptyList()
                if (isDosbox(program)) listOfNotNull(run(files, program, folder, arguments))
                else menuActions(program).flatMap { (action, actionArguments) ->
                    if (extension(action) !in PROGRAMS) return@flatMap emptyList()
                    val path = join(parent(target), action) ?: return@flatMap emptyList()
                    if (!files.exists(path)) emptyList()
                    else follow(files, path, actionArguments, parent(path), seen, depth + 1)
                }
            }
            else -> emptyList()
        }
    }

    /** A batch file's runs: each command that starts a program, from the folder its CD commands reach. */
    private fun batch(files: Files, text: String, folder: String, seen: MutableSet<Triple<String, String, String>>, depth: Int): List<Run> {
        val runs = ArrayList<Run>()
        var current = folder
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('@').trim()
            val lower = line.lowercase(Locale.ROOT)
            if (line.isEmpty() || lower == "rem" || listOf("rem ", "rem\t", "::", ":", "echo", "set ", "cls", "pause", "exit", "goto")
                    .any { lower.startsWith(it) }) continue
            val tokens = tokens(line)
            if (tokens.isEmpty()) continue
            when (tokens[0].lowercase(Locale.ROOT)) {
                "cd", "chdir", "pushd" -> tokens.drop(1).firstOrNull { !it.equals("/d", true) }?.let { to ->
                    if (!Regex("^[A-Za-z]:").containsMatchIn(to)) join(current, to)?.let { current = it }
                }
                "start" -> {
                    // START ["title"] [/options] program arguments
                    var rest = tokens.drop(1)
                    if (line.substring(5).trimStart().startsWith('"')) rest = rest.drop(1)
                    rest = rest.dropWhile { it.startsWith('/') && it.length <= 12 }
                    rest.firstOrNull()?.let { runs += program(files, current, it, rest.drop(1), seen, depth) }
                }
                "call" -> tokens.getOrNull(1)?.let { runs += program(files, current, it, tokens.drop(2), seen, depth) }
                else -> runs += program(files, current, tokens[0], tokens.drop(1), seen, depth)
            }
        }
        return runs
    }

    private fun program(files: Files, folder: String, name: String, arguments: List<String>,
                        seen: MutableSet<Triple<String, String, String>>, depth: Int): List<Run> {
        val written = join(folder, name) ?: return emptyList()
        val path = if (files.exists(written)) written
            else if (extension(name).isEmpty()) PROGRAMS.map { written + it }.firstOrNull(files::exists) ?: return emptyList()
            else return emptyList()
        return follow(files, path, arguments.joinToString(" ") { if (' ' in it) "\"$it\"" else it }, folder, seen, depth + 1)
    }

    /** DOSBox started in [folder] with [arguments]: the configurations it reads, as DOSBox finds them. */
    private fun run(files: Files, program: ByteArray, folder: String, arguments: String): Run? {
        val tokens = tokens(arguments)
        val asked = ArrayList<String>()
        val commands = ArrayList<String>()
        var index = 0
        while (index < tokens.size) {
            when (tokens[index].lowercase(Locale.ROOT)) {
                "-conf" -> tokens.getOrNull(index + 1)?.let { asked += join(folder, it) ?: it }.also { index++ }
                "-c" -> tokens.getOrNull(index + 1)?.let(commands::add).also { index++ }
            }
            index++
        }
        // Every -conf file there is, in order; when none is, its default in its folder (DOSBox-X's own name first).
        val configs = asked.filter(files::exists).ifEmpty {
            val defaults = (if (contains(program, "DOSBox-X")) listOf("dosbox-x.conf") else emptyList()) + "dosbox.conf"
            listOfNotNull(defaults.mapNotNull { join(folder, it) }.firstOrNull(files::exists))
        }
        if (configs.isEmpty()) return null
        return Run(folder, configs, commands)
    }

    /**
     * A run's configuration as one: its files' settings, a later file's over an earlier's, each
     * section in the order first written, then one [autoexec]: every file's, in order, then the -c commands.
     */
    private fun config(files: Files, run: Run): String {
        val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val autoexec = ArrayList<String>()
        for (path in run.configs) {
            var section: String? = null
            val own = ArrayList<String>()
            for (raw in String(checkNotNull(files.read(path)) { "$path is not installed" }, TEXT).lineSequence()) {
                val line = raw.trimEnd('\r')
                val header = Regex("^\\s*\\[([^]]+)]\\s*$").matchEntire(line)
                if (header != null) {
                    section = header.groupValues[1].trim().lowercase(Locale.ROOT)
                    if (section != "autoexec") sections.getOrPut(section) { LinkedHashMap() }
                    continue
                }
                if (section == "autoexec") { own += line; continue }
                val setting = line.substringBefore('#').trim()
                if (section == null || setting.isEmpty() || '=' !in setting) continue
                sections.getValue(section)[setting.substringBefore('=').trim().lowercase(Locale.ROOT)] = setting.substringAfter('=').trim()
            }
            // A file's last blank lines are where it ends, not commands.
            while (own.isNotEmpty() && own.last().isBlank()) own.removeAt(own.lastIndex)
            autoexec += own
        }
        return buildString {
            for ((section, values) in sections) {
                if (values.isEmpty()) continue
                append('[').append(section).append("]\n")
                values.forEach { (key, value) -> append(key).append(" = ").append(value).append('\n') }
            }
            append("[autoexec]\n")
            (autoexec + run.commands).forEach { append(it).append('\n') }
        }
    }

    /** A DOSBox build (0.74, SVN-Daum, ECE, DOSBox-X): its own name and its IMGMOUNT command. */
    fun isDosbox(program: ByteArray) = contains(program, "DOSBox") && contains(program, "IMGMOUNT")

    /** A menu program's run actions: each file (relative to the menu's folder) and its arguments. */
    fun menuActions(program: ByteArray): List<Pair<String, String>> = autoplayActions(program).ifEmpty { keyedActions(program) }

    /**
     * AutoPlay Menu Designer's: after its resource header, property records (u32 kind 14, u32 total,
     * u16, u32 value length, u32 property, value); a run action is property 1, a UTF-16 path under
     * %ROOT%, and the property 2 after it, its arguments.
     */
    private fun autoplayActions(program: ByteArray): List<Pair<String, String>> {
        val start = indexOf(program, AUTOPLAY, 0)
        if (start < 0) return emptyList()
        val buffer = ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN)
        val actions = ArrayList<Pair<String, String>>()
        var at = start
        while (at + 18 <= program.size) {
            if (buffer.getInt(at) == 14 && buffer.getShort(at + 8).toInt() == 0 && buffer.getInt(at + 14) == 1) {
                val length = buffer.getInt(at + 10)
                if (length in 2..4096 && length % 2 == 0 && buffer.getInt(at + 4) == 18 + length && at + 18 + length <= program.size &&
                    program[at + 16 + length].toInt() == 0 && program[at + 17 + length].toInt() == 0) {
                    val target = String(program, at + 18, length - 2, Charsets.UTF_16LE)
                    if (target.startsWith("%ROOT%")) {
                        val next = at + 18 + length
                        val arguments = if (next + 18 <= program.size && buffer.getInt(next) == 14 && buffer.getInt(next + 14) == 2) {
                            val argumentsLength = buffer.getInt(next + 10)
                            if (argumentsLength in 2..4096 && buffer.getInt(next + 4) == 18 + argumentsLength && next + 18 + argumentsLength <= program.size)
                                String(program, next + 18, argumentsLength - 2, Charsets.UTF_16LE) else ""
                        } else ""
                        actions += target.removePrefix("%ROOT%").trimStart('\\', '/') to arguments
                    }
                }
            }
            at++
        }
        return actions
    }

    /**
     * A menu whose properties are keyed records (u32 kind 14, u32 total, u16 key length, u32 value
     * length, key, value): a button's run action is `P<page>.O<object>.S<n>.RO<n>.1`, its file, and `.2`, its arguments.
     */
    private fun keyedActions(program: ByteArray): List<Pair<String, String>> {
        val buffer = ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN)
        val values = LinkedHashMap<String, String>()
        var at = 0
        while (at + 14 <= program.size) {
            if (buffer.getInt(at) == 14) {
                val total = buffer.getInt(at + 4)
                val keyLength = buffer.getShort(at + 8).toInt() and 0xFFFF
                val valueLength = buffer.getInt(at + 10)
                if (keyLength in 2..64 && valueLength in 0..4096 && total == 14 + keyLength + valueLength && at + total <= program.size &&
                    program[at + 13 + keyLength].toInt() == 0) {
                    val key = String(program, at + 14, keyLength - 1, Charsets.US_ASCII)
                    if (MENU_KEY.matches(key)) {
                        var end = at + total
                        while (end > at + 14 + keyLength && program[end - 1].toInt() == 0) end--
                        values[key] = String(program, at + 14 + keyLength, end - at - 14 - keyLength, TEXT)
                    }
                }
            }
            at++
        }
        return values.filterKeys { it.endsWith(".1") }.filterValues { it.isNotEmpty() }
            .map { (key, file) -> file to values[key.dropLast(1) + "2"].orEmpty() }
    }

    /** [path] from [folder], forward slashes, `.` and `..` resolved; null when it leaves the install folder. */
    private fun join(folder: String, path: String): String? {
        val parts = ArrayList(folder.split('/').filter { it.isNotEmpty() })
        for (part in path.replace('\\', '/').trim().split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun parent(path: String) = path.substringBeforeLast('/', "")

    private fun extension(path: String) = path.substringAfterLast('/').let { name ->
        if ('.' in name) "." + name.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    }

    /** A command line's words: quoted ones as one, without their quotes. */
    private fun tokens(line: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    private fun contains(bytes: ByteArray, text: String) = indexOf(bytes, text.toByteArray(Charsets.US_ASCII), 0) >= 0

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty()) return from
        val first = pattern[0]
        var at = from
        val last = bytes.size - pattern.size
        while (at <= last) {
            if (bytes[at] == first) {
                var match = true
                for (index in 1 until pattern.size) if (bytes[at + index] != pattern[index]) { match = false; break }
                if (match) return at
            }
            at++
        }
        return -1
    }
}
