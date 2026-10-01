// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the actual engine without touching a player's writable drive. */
internal object StagingCoreFixture {
    init { System.loadLibrary("kairodos_host") }

    fun verify(instrumentation: Instrumentation, archivePath: String,
               oplMode: String? = null, oplDcBias: Boolean = false): String {
        check(oplMode == null || oplMode in setOf("opl2", "dualopl2", "opl3", "opl3gold", "esfm"))
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "staging-core-fixture")
        check(!root.exists()) { "Inspect existing core fixture before retrying" }
        check(root.mkdirs())
        val worker = Executors.newSingleThreadExecutor()
        var host: MainActivity? = null
        instrumentation.runOnMainSync { host = MainActivity() }
        fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }.invoke(host, *args)
        val report = File(context.cacheDir, "staging-core-report").apply { mkdirs() }
        var frames = 0
        var samples = 0L
        var peak = 0
        var nextAudio = 0L
        val cpuReports = mutableListOf<String>()
        try {
            val archive = File(root, "game.zip")
            File(archivePath).copyTo(archive)
            val cancelled = AtomicBoolean(false)
            val contentId = DosContentHash.zip(archive, cancelled)
            val launch = DosGameCatalog(context).resolve(contentId, archive.name).launch
            val saves = File(root, "saves").apply { mkdirs() }
            val drive = DosStagingStorage.prepare(archive, saves, contentId, cancelled, launch?.folder)
            val config = DosStagingLaunchConfig.write(File(root, "game.conf"), drive, launch,
                "dosbox.conf", emptyMap(), null, false, false)
            if (oplMode != null) config.appendText(
                "\n[sblaster]\noplmode = $oplMode\nopl_remove_dc_bias = $oplDcBias\n")
            File(report, "launch.conf").writeText(config.readText())
            val resources = DosStagingResources.prepare(context, cancelled)
            val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3)
            fun anonymousExecutableBytes(): Long = File("/proc/self/maps").readLines().sumOf { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 6)
                if (fields.size == 5 && 'x' in fields[1] && fields[3] == "00:00" && fields[4] == "0") {
                    val range = fields[0].split('-')
                    range[1].toLong(16) - range[0].toLong(16)
                } else 0L
            }
            val executableBaseline = anonymousExecutableBytes()
            val pcm = ShortArray(2048)
            fun pump(ms: Long, screenshot: Boolean = false) {
                val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
                while (System.nanoTime() < end) {
                    reader.acquireLatestImage()?.use { image ->
                        frames++
                        if (screenshot || frames % 120 == 0) {
                            val plane = image.planes.single()
                            val buffer = plane.buffer
                            val colors = IntArray(image.width * image.height)
                            for (y in 0 until image.height) for (x in 0 until image.width) {
                                val offset = y * plane.rowStride + x * plane.pixelStride
                                val r = buffer.get(offset).toInt() and 255
                                val g = buffer.get(offset + 1).toInt() and 255
                                val b = buffer.get(offset + 2).toInt() and 255
                                colors[y * image.width + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
                            }
                            val bitmap = Bitmap.createBitmap(colors, image.width, image.height, Bitmap.Config.ARGB_8888)
                            File(report, "game.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                    val now = System.nanoTime()
                    if (now >= nextAudio) {
                        val count = call("nativeReadAudio", pcm, pcm.size / 2) as Int
                        samples += count
                        for (index in 0 until count * 2) peak = maxOf(peak, kotlin.math.abs(pcm[index].toInt()))
                        nextAudio = now + count * 1_000_000_000L / 48000L
                    }
                    Thread.sleep(4)
                }
            }
            fun key(code: Int) {
                call("nativeKey", code, true); pump(100)
                call("nativeKey", code, false); pump(180)
            }
            fun bootGame() {
                if (launch?.folder?.contains("doom", true) == true) {
                    key('2'.code); key('n'.code); pump(12000)
                    key(27); repeat(3) { key(13) }
                } else key('1'.code)
                pump(10000)
            }
            fun verifyCpu(label: String, requireDynamic: Boolean) {
                val before = call("nativeCpuTelemetry") as LongArray
                check(before[0] == 1L) { "ARM64 dynarec is not available" }
                if (requireDynamic) {
                    check(before[2] > 0 && before[3] > 100) {
                        "$label did not translate and execute host code: ${before.toList()}"
                    }
                    pump(1000)
                    val after = call("nativeCpuTelemetry") as LongArray
                    check(after[3] > before[3]) {
                        "$label stopped executing generated host code: ${after.toList()}"
                    }
                    cpuReports += "$label: translated=${after[2]}, executed=${after[3]}"
                }
            }
            try {
                // Two sessions in the same process catch stale core globals and
                // dlclose/thread cleanup errors that a fresh launch can hide.
                for (session in 0..1) {
                    // Session 0 uses the product's actual default. Doom's next
                    // session also verifies upstream automatic mode switches
                    // to dynarec after entering protected mode.
                    if (session == 1 && launch?.folder?.contains("doom", true) == true)
                        config.appendText("\n[cpu]\ncore = auto\n")
                    call("nativeConfigure", 0, 0, 0)
                    call("nativeSetSurface", reader.surface)
                    val core = worker.submit<Boolean> { call("nativeRun", config.absolutePath,
                        saves.absolutePath, resources.absolutePath) as Boolean }
                    try {
                        val start = System.nanoTime()
                        while (call("nativeStatus") != 2 && !core.isDone && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15)) pump(20)
                        check(call("nativeStatus") == 2) { "Start failed: ${call("nativeLastError")}" }
                        pump(3500)
                        bootGame()
                        verifyCpu(if (session == 0) "default" else "second launch", true)
                        call("nativePause", true); Thread.sleep(300)
                        call("nativePause", false); pump(500)
                        call("nativeSetSurface", null); pump(150)
                        call("nativeSetSurface", reader.surface); pump(500)
                        if (session == 0) {
                            call("nativeReset"); pump(4500)
                            bootGame()
                            verifyCpu("reset", true)
                        }
                        pump(60, screenshot = true)
                        call("nativePause", true)
                        call("nativeStop") // stop must wake a paused session
                        check(core.get(10, TimeUnit.SECONDS)) { "Core failed: ${call("nativeLastError")}" }
                    } finally {
                        call("nativeStop")
                        check(core.get(10, TimeUnit.SECONDS)) { "Core did not stop" }
                    }
                    val retained = anonymousExecutableBytes() - executableBaseline
                    check(retained <= 0) { "JIT memory remained after session $session: $retained executable bytes" }
                }
                check(frames > 60) { "Too few video frames: $frames" }
                check(samples > 48000 && peak > 0) { "No sustained audible PCM: $samples frames, peak=$peak" }
            } finally { call("nativeSetSurface", null); reader.close() }
            return "Staging ${launch?.folder ?: archivePath}: frames=$frames, PCM frames=$samples, peak=$peak; pause, surface, reset, sequential launches and paused exit OK; ARM64 dynarec ${cpuReports.joinToString("; ")}; JIT mappings released"
        } finally {
            call("nativeStop"); worker.shutdown()
            check(worker.awaitTermination(15, TimeUnit.SECONDS)) { "Core still running; fixture retained" }
            check(root.deleteRecursively())
        }
    }
}
