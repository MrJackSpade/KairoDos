package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ClickteamInstaller
import java.io.File
import java.nio.channels.FileChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Collection Chamber launch sweep's boot tests: every installer of KAIRO_SWEEP_SAMPLES (one folder
 * per download, as the blog's MEGA folders name them; narrowed by the KAIRO_SWEEP_FILTER regular
 * expression over "<folder>/<program>"), unpacked into KAIRO_SWEEP_OUT as the app's base drive holds
 * it, with the launch configuration the app writes for it (DosClickteamLaunch's first configuration,
 * DosStagingLaunchConfig.compose with the default machine settings), as a test of the shared PC boot
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
        val soundFont = File(app, "shared/third_party/generaluser-gs/GeneralUser-GS.sf2")
        check(soundFont.isFile) { "No shipped SoundFont at $soundFont" }
        val tests = JSONArray()
        val report = StringBuilder()
        for (folder in File(samples!!).listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            for (program in folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".exe", true) }.sortedBy { it.name }) {
                val name = "${folder.name}/${program.name}"
                if (filter != null && !filter.matches(name)) continue
                val line = try {
                    sweepTest(program, name, out, frames, soundFont)?.let { tests.put(it); "TEST" } ?: "NONE no launch"
                } catch (failure: Throwable) {
                    "FAIL ${failure::class.simpleName}: ${failure.message}"
                }
                report.append("$name $line\n")
            }
        }
        File(app, "core/test/collection-chamber-sweep.json").writeText(JSONObject().put("tests", tests).toString(1) + "\n")
        File("build/collection-chamber-sweep.txt").writeText(report.toString())
    }

    private fun sweepTest(program: File, name: String, out: File, frames: Int, soundFont: File): JSONObject? {
        if (!FileChannel.open(program.toPath()).use(ClickteamInstaller::isInstaller)) return null
        val parts = generateSequence(1) { it + 1 }.map { File(program.parentFile, ClickteamInstaller.partName(program.name, it)) }
            .takeWhile { it.isFile }.toList()
        val game = File(out, name.removeSuffix(".exe").removeSuffix(".EXE"))
        val base = File(game, "base")
        val overlay = File(game, "overlay")
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
            DosClickteamLaunch.launch(DosClickteam.files(installer), DosClickteam.entries(installer))
        } ?: return null
        overlay.mkdirs()
        val configName = launch.configs.keys.first()
        val composed = DosStagingLaunchConfig.compose({ id, args -> "string $id ${args.joinToString()}" },
            File(game, "launch.conf"), DosStagingStorage.Drive(base, null, overlay),
            DosLaunch(launch.folder, launch.configs, false), configName, emptyMap(), mapOf(base to overlay), null,
            DosStagingLaunchConfig.Machine("auto", false, false, null, true, false, soundFont.absolutePath))
        val config = File(game, "launch.conf")
        config.writeText(composed.lines.joinToString("\r\n") + "\r\n")
        val root = out.canonicalFile.toPath()
        return JSONObject().put("name", name).put("firmware", JSONObject())
            .put("home", JSONObject().put("staging/resources", "app:third_party/dosbox-staging/resources"))
            .put("media", JSONArray(composed.hostPaths.map { root.relativize(File(it).canonicalFile.toPath()).toString().replace('\\', '/') }))
            .put("settings", JSONObject().put("config", config.canonicalPath).put("resources", "home:staging"))
            .put("checks", JSONObject().put("boot", JSONObject().put("frames", frames).apply {
                System.getenv("KAIRO_SWEEP_EVERY")?.takeIf { it.isNotEmpty() }?.let { put("every", it.toInt()) }
            }))
    }
}
