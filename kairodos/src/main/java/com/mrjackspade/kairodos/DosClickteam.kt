// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.content.Context
import android.net.Uri
import com.mrjackspade.kairo.frontend.ClickteamInstaller
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
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

    /** The shortcuts [installer] makes, as the files they start and their arguments, in its order. */
    fun entries(installer: ClickteamInstaller): List<DosClickteamLaunch.Entry> = installer.members
        .flatMap { member -> member.shortcuts.map { DosClickteamLaunch.Entry(member.name, it.arguments) } }.distinct()

    /** [installer]'s files, read from it, for DosClickteamLaunch. */
    fun files(installer: ClickteamInstaller): DosClickteamLaunch.Files = object : DosClickteamLaunch.Files {
        private val byPath = installer.members.associateBy { it.name.lowercase(Locale.ROOT) }
        override fun exists(path: String) = path.lowercase(Locale.ROOT) in byPath
        override fun read(path: String) = byPath[path.lowercase(Locale.ROOT)]?.let { installer.open(it).use { input -> input.readBytes() } }
    }

    /**
     * The installed archive's comment (it is not one of its files, so the content ID leaves it out):
     * which installer it came from and the shortcuts that installer makes, so its launch can be
     * derived from the archive alone wherever it is copied.
     */
    data class Installed(val installer: String, val entries: List<DosClickteamLaunch.Entry>)

    private const val COMMENT_FORMAT = "kairo-clickteam-v1"

    fun comment(installed: Installed): String = JSONObject().put("format", COMMENT_FORMAT).put("installer", installed.installer)
        .put("shortcuts", JSONArray().apply {
            installed.entries.forEach { put(JSONObject().put("target", it.target).put("arguments", it.arguments)) }
        }).toString()

    /** [comment] as an installed archive's ([comment]); null for any other comment. */
    fun installed(comment: String?): Installed? {
        val json = comment?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (json.optString("format") != COMMENT_FORMAT) return null
        val shortcuts = json.getJSONArray("shortcuts")
        return Installed(json.getString("installer"), (0 until shortcuts.length()).map { index ->
            shortcuts.getJSONObject(index).let { DosClickteamLaunch.Entry(it.getString("target"), it.getString("arguments")) }
        })
    }

    /** The launch the installed archive at [uri] declares ([installed]), read from its own files. */
    fun launch(context: Context, uri: Uri, installed: Installed): DosClickteamLaunch.Launch? =
        (context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open $uri")).use { document ->
            FileInputStream(document.fileDescriptor).channel.use { channel ->
                DosZip.read(channel) { members, opener ->
                    val byPath = members.filterNot { it.directory }.associateBy { it.name.replace('\\', '/').lowercase(Locale.ROOT) }
                    DosClickteamLaunch.launch(object : DosClickteamLaunch.Files {
                        override fun exists(path: String) = path.lowercase(Locale.ROOT) in byPath
                        override fun read(path: String) = byPath[path.lowercase(Locale.ROOT)]?.let { opener.open(it).use { input -> input.readBytes() } }
                    }, installed.entries)
                }
            }
        }

    /**
     * Writes every file [installer] installs to the ZIP [target], under its path, in the installer's
     * order, with [comment] as its comment. Each file is checked as it is read (its size and its stream's own check).
     */
    fun writeZip(installer: ClickteamInstaller, target: File, comment: String, cancelled: AtomicBoolean, progress: (Long) -> Unit) {
        target.outputStream().buffered(1 shl 16).use { file -> writeZip(installer, file, comment, cancelled, progress) }
    }

    private fun writeZip(installer: ClickteamInstaller, output: OutputStream, comment: String, cancelled: AtomicBoolean,
                         progress: (Long) -> Unit) {
        val total = installer.members.sumOf { it.size }
        var written = 0L
        var reported = 0L
        ZipOutputStream(output).use { zip ->
            zip.setComment(comment)
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
