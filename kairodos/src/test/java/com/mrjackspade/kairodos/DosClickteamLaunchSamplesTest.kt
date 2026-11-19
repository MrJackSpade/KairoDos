package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ClickteamInstaller
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Derives the launch of real installers (The Collection Chamber's downloads) from their own files,
 * one installer per folder of KAIRO_CLICKTEAM_SAMPLES (narrowed by the KAIRO_CLICKTEAM_FILTER
 * regular expression), and writes each one's launch folder and configurations to
 * build/clickteam-launch.txt. Skipped when KAIRO_CLICKTEAM_SAMPLES is not set.
 */
class DosClickteamLaunchSamplesTest {
    @Test fun everySampleInstallersLaunchIsDerived() {
        val root = System.getenv("KAIRO_CLICKTEAM_SAMPLES")
        assumeTrue("KAIRO_CLICKTEAM_SAMPLES is not set", !root.isNullOrEmpty())
        val filter = System.getenv("KAIRO_CLICKTEAM_FILTER")?.takeIf { it.isNotEmpty() }?.let(::Regex)
        val programs = File(root!!).listFiles().orEmpty().filter { it.isDirectory && (filter == null || filter.matches(it.name)) }
            .sortedBy { it.name }.mapNotNull { folder ->
                folder.listFiles().orEmpty().singleOrNull { it.isFile && it.name.endsWith(".exe", true) }
            }
        val pool = Executors.newFixedThreadPool(System.getenv("KAIRO_CLICKTEAM_THREADS")?.toInt() ?: 2)
        val lines = try {
            programs.map { program -> pool.submit(Callable { derive(program) }) }.map { it.get() }
        } finally { pool.shutdown() }
        File("build/clickteam-launch.txt").writeText(lines.joinToString("\n") + "\n")
    }

    private fun derive(program: File): String = try {
        val parts = generateSequence(1) { it + 1 }.map { File(program.parentFile, ClickteamInstaller.partName(program.name, it)) }
            .takeWhile { it.isFile }.toList()
        ClickteamInstaller.open((listOf(program) + parts).map { FileChannel.open(it.toPath()).let { c -> ClickteamInstaller.Part(c, c) } }).use { installer ->
            val launch = DosClickteamLaunch.launch(DosClickteam.files(installer), DosClickteam.entries(installer))
            // With KAIRO_CLICKTEAM_INSTALLED set, the content ID of the archive the app installs from it.
            val installed = if (System.getenv("KAIRO_CLICKTEAM_INSTALLED").isNullOrEmpty()) "" else {
                val zip = File.createTempFile("installed-", ".zip")
                try {
                    DosClickteam.writeZip(installer, zip, "", java.util.concurrent.atomic.AtomicBoolean(false)) {}
                    " installed=" + DosContentHash.zip({ it.toString() }, zip, java.util.concurrent.atomic.AtomicBoolean(false))
                } finally { zip.delete() }
            }
            "${program.parentFile.name} " + (launch?.let { "folder=${it.folder} configs=${it.configs.keys.joinToString(",")}" } ?: "none") + installed
        }
    } catch (failure: Throwable) {
        "${program.parentFile.name} FAIL ${failure::class.simpleName}: ${failure.message}"
    }
}
