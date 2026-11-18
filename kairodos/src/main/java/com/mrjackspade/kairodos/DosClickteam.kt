// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import com.mrjackspade.kairo.frontend.ClickteamInstaller
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Collection Chamber's games, shipped as Clickteam Install Creator installers (shared
 * ClickteamInstaller): a Windows program and its `.Dnn` parts that install a DOSBox setup. The
 * library lists an installer by its program; its first launch writes what it installs to a ZIP
 * (` - Installed.zip`), which is played from then on. Nothing of the installer runs.
 */
internal object DosClickteam {
    /** An installer's content ID: the SHA-256 of its file list (every file's path, sizes, checksum and place). */
    const val PREFIX = "sha256-dos-clickteam-v1:"

    fun isInstallerId(contentId: String?) = contentId?.startsWith(PREFIX) == true

    /** Whether the program at [uri] is an installer; any other program, or a file that is not one, is not. */
    fun isInstaller(context: Context, uri: Uri): Boolean =
        (context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open $uri")).use { document ->
            FileInputStream(document.fileDescriptor).channel.use(ClickteamInstaller::isInstaller)
        }

    /** The parts an installer's program [name] has among [siblings] (names in its folder), in order: `.D01` on, while they run on. */
    fun <T> parts(name: String, siblings: Map<String, T>): List<T> =
        generateSequence(1) { it + 1 }.takeWhile { it <= 99 }
            .map { siblings[ClickteamInstaller.partName(name, it).lowercase(java.util.Locale.ROOT)] }
            .takeWhile { it != null }.map { it!! }.toList()

    /**
     * Opens the installer whose program is at [program] with its [parts] in order. A part its files
     * need that is not there fails, naming it ([name] is the program's file name).
     */
    fun open(context: Context, name: String, program: Uri, parts: List<Uri>): ClickteamInstaller {
        val opened = ArrayList<ClickteamInstaller.Part>()
        try {
            for (uri in listOf(program) + parts) {
                val document = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open $uri")
                opened += ClickteamInstaller.Part(FileInputStream(document.fileDescriptor).channel, document)
            }
        } catch (failure: Throwable) {
            opened.forEach { runCatching { it.closeable.close() } }
            throw failure
        }
        return try {
            ClickteamInstaller.open(opened)
        } catch (missing: ClickteamInstaller.MissingPart) {
            error(context.getString(R.string.installer_part_missing, ClickteamInstaller.partName(name, missing.index)))
        }
    }

    fun contentId(installer: ClickteamInstaller) =
        PREFIX + installer.contentDigest().joinToString("") { "%02x".format(it) }

    /**
     * Writes every file [installer] installs to the ZIP [target], under its path, in the installer's
     * order. Each file is checked as it is read (its size and its stream's own check).
     */
    fun writeZip(installer: ClickteamInstaller, target: File, cancelled: AtomicBoolean, progress: (Long) -> Unit) {
        target.outputStream().buffered(1 shl 16).use { file -> writeZip(installer, file, cancelled, progress) }
    }

    private fun writeZip(installer: ClickteamInstaller, output: OutputStream, cancelled: AtomicBoolean, progress: (Long) -> Unit) {
        val total = installer.members.sumOf { it.size }
        var written = 0L
        var reported = 0L
        ZipOutputStream(output).use { zip ->
            // The quickest level: the installer's files are unpacked once, on the device, at first launch.
            zip.setLevel(Deflater.BEST_SPEED)
            val buffer = ByteArray(1 shl 16)
            for (member in installer.members) {
                zip.putNextEntry(ZipEntry(member.name))
                installer.open(member).use { input ->
                    while (true) {
                        if (cancelled.get()) throw CancellationException("Cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        written += read
                        if (written - reported >= PROGRESS_STEP) {
                            reported = written
                            progress(written * 100 / maxOf(total, 1))
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private const val PROGRESS_STEP = 32L * 1024 * 1024
}
