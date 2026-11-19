package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ClickteamInstaller
import java.io.File
import java.util.zip.GZIPInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Collection Chamber launch sweep's boot tests: every installer of KAIRO_SWEEP_SAMPLES (one folder
 * per download, as the blog's MEGA folders name them; narrowed by the KAIRO_SWEEP_FILTER regular
 * expression over "<folder>/<program>"), unpacked into KAIRO_SWEEP_OUT as the app's base drive holds
 * it, with the launch configuration the app writes for it (the version it starts, DosLaunchConfig.startName
 * with the catalog's version for the installer when it has one, then DosStagingLaunchConfig.compose
 * with the default machine settings), as a test of the shared PC boot
 * test (core/test/collection-chamber-sweep.json, run with KAIRO_SWEEP_OUT as its resources folder):
 * each boots for KAIRO_SWEEP_FRAMES frames and keeps its last picture, and one every KAIRO_SWEEP_EVERY
 * frames when that is set. An installer already unpacked
 * there is not unpacked again. Skipped when KAIRO_SWEEP_SAMPLES is not set.
 */
class DosCollectionChamberSweepTest {
    @Test fun everyInstallerHasABootTest() {
        val samples = System.getenv("KAIRO_SWEEP_SAMPLES")
        assumeTrue("KAIRO_SWEEP_SAMPLES is not set", !samples.isNullOrEmpty())
        val out = File(checkNotNull(System.getenv("KAIRO_SWEEP_OUT")) { "KAIRO_SWEEP_OUT is not set" })
        val frames = checkNotNull(System.getenv("KAIRO_SWEEP_FRAMES")) { "KAIRO_SWEEP_FRAMES is not set" }.toInt()
        val filter = System.getenv("KAIRO_SWEEP_FILTER")?.takeIf { it.isNotEmpty() }?.let(::Regex)
        val app = File("..").canonicalFile
        val versions = catalogVersions(File(app, "catalog/research/games-v1.json.gz"))
        val soundFont = File(app, "shared/third_party/generaluser-gs/GeneralUser-GS.sf2")
        check(soundFont.isFile) { "No shipped SoundFont at $soundFont" }
        val programs = File(samples!!).listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { folder ->
            folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".exe", true) }.sortedBy { it.name }
                .map { "${folder.name}/${it.name}" to it }
        }.filter { (name, _) -> filter == null || filter.matches(name) }
        // Unpacked several at a time (KAIRO_SWEEP_THREADS), listed in folder order.
        val pool = Executors.newFixedThreadPool(System.getenv("KAIRO_SWEEP_THREADS")?.toInt() ?: 1)
        val results = try {
            programs.map { (name, program) ->
                pool.submit(Callable {
                    try {
                        sweepTest(program, name, out, frames, soundFont, versions)?.let { it to "TEST" } ?: (null to "NONE no launch")
                    } catch (failure: Throwable) {
                        null to "FAIL ${failure::class.simpleName}: ${failure.message}\n" +
                            failure.stackTrace.take(12).joinToString("\n") { "    at $it" }
                    }
                })
            }.map { it.get() }
        } finally { pool.shutdown() }
        val tests = JSONArray()
        val report = StringBuilder()
        programs.zip(results).forEach { (entry, result) ->
            result.first?.let(tests::put)
            report.append("${entry.first} ${result.second}\n")
        }
        File(app, "core/test/collection-chamber-sweep.json").writeText(JSONObject().put("tests", tests).toString(1) + "\n")
        File("build/collection-chamber-sweep.txt").writeText(report.toString())
    }

    /** The catalog's launch version for each medium that has one (a disk's diskMachine launch). */
    private fun catalogVersions(research: File): Map<String, String> {
        val games = JSONObject(GZIPInputStream(research.inputStream()).bufferedReader().use { it.readText() }).getJSONObject("games")
        val versions = HashMap<String, String>()
        for (key in games.keys()) {
            val disks = games.getJSONObject(key).optJSONObject("diskMachine") ?: continue
            for (disk in disks.keys())
                disks.getJSONObject(disk).optJSONObject("launch")?.optString("version")?.takeIf { it.isNotEmpty() }
                    ?.let { versions[disk] = it }
        }
        return versions
    }

    private fun sweepTest(program: File, name: String, out: File, frames: Int, soundFont: File,
                          versions: Map<String, String>): JSONObject? {
        if (!FileChannel.open(program.toPath()).use(ClickteamInstaller::isInstaller)) return null
        val parts = generateSequence(1) { it + 1 }.map { File(program.parentFile, ClickteamInstaller.partName(program.name, it)) }
            .takeWhile { it.isFile }.toList()
        val game = File(out, name.removeSuffix(".exe").removeSuffix(".EXE"))
        val base = File(game, "base")
        val overlay = File(game, "overlay")
        var contentId = ""
        val launch = ClickteamInstaller.open((listOf(program) + parts).map { FileChannel.open(it.toPath()).let { c -> ClickteamInstaller.Part(c, c) } }).use { installer ->
            val unpacked = File(game, "unpacked.txt")
            if (!unpacked.isFile) {
                base.deleteRecursively()
                for (member in installer.members) {
                    val target = File(base, member.name)
                    target.parentFile.mkdirs()
                    installer.open(member).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
                unpacked.writeText(DosClickteam.contentId(installer) + "\n")
            }
            contentId = DosClickteam.contentId(installer)
            DosClickteamLaunch.launch(DosClickteam.files(installer), DosClickteam.entries(installer))
        } ?: return null
        overlay.mkdirs()
        // The configuration the app starts: the catalog's version for the installer, else its standard one.
        val dosLaunch = DosLaunch(launch.folder, launch.configs, false)
        val version = versions[contentId]
        version?.let { check(it in launch.configs) { "$name: the catalog's version $it is not one of ${launch.configs.keys}" } }
        val configName = DosLaunchConfig.startName(dosLaunch, version)
        val composed = DosStagingLaunchConfig.compose({ id, args -> "string $id ${args.joinToString()}" },
            File(game, "launch.conf"), DosStagingStorage.Drive(base, null, overlay),
            dosLaunch, configName, emptyMap(), mapOf(base to overlay), null,
            DosStagingLaunchConfig.Machine("auto", false, false, null, true, false, soundFont.absolutePath))
        val config = File(game, "launch.conf")
        config.writeText(composed.lines.joinToString("\r\n") + "\r\n")
        val root = out.canonicalFile.toPath()
        return JSONObject().put("name", name).put("firmware", JSONObject())
            .put("home", JSONObject().put("staging/resources", "app:third_party/dosbox-staging/resources"))
            // A path the download does not ship (its configuration mounts a folder or image it left out) is
            // passed on as the app passes it, for Staging to report as DOSBox does.
            .put("media", JSONArray(composed.hostPaths.map { path ->
                val relative = root.relativize(File(path).canonicalFile.toPath()).toString().replace('\\', '/')
                if (File(path).exists()) relative else "absent:$relative"
            }))
            .put("settings", JSONObject().put("config", config.canonicalPath).put("resources", "home:staging"))
            .put("checks", JSONObject().put("boot", JSONObject().put("frames", frames).apply {
                System.getenv("KAIRO_SWEEP_EVERY")?.takeIf { it.isNotEmpty() }?.let { put("every", it.toInt()) }
            }))
    }
}
