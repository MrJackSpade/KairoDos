// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Performance comparison in a copy of an existing Staging session. */
internal object DukeResolutionFixture {
    fun run(instrumentation: Instrumentation, sourcePath: String, width: Int, noDouble: Boolean = false): String {
        require(width in setOf(320, 640, 1024))
        val height = when (width) { 320 -> 200; 640 -> 480; else -> 768 }
        val context = instrumentation.targetContext
        val source = File(sourcePath).canonicalFile
        require(source.path.startsWith(File(context.filesDir, "saves").canonicalPath + "/"))
        require(source.name == "staging" && File(source, "launch.conf").isFile)
        val root = File(context.cacheDir, "duke-resolution-$width")
        check(!root.exists()) { "Inspect existing resolution fixture before retrying" }
        val report = File(context.cacheDir, "duke-resolution-reports/$width").apply { mkdirs() }
        File(report, "go").delete()
        File(report, "stop").delete()
        fun phase(value: String) { File(report, "phase.txt").writeText(value) }
        val saves = File(root, "saves").apply { mkdirs() }
        val copied = File(saves, "staging")
        source.copyRecursively(copied, overwrite = false)
        val game = File(copied, "drive/Duke3D/DUKE3D/DUKE3D.CFG")
        check(game.isFile)
        val original = File(source, "drive/Duke3D/DUKE3D/DUKE3D.CFG").readBytes()
        var settings = game.readText()
        for ((key, value) in mapOf("ScreenMode" to if (width == 320) 2 else 1,
                                   "ScreenWidth" to width, "ScreenHeight" to height)) {
            val pattern = Regex("(?m)^${key}\\s*=.*$")
            check(pattern.containsMatchIn(settings)) { "Missing $key" }
            settings = settings.replace(pattern, "$key = $value")
        }
        game.writeText(settings)
        val config = File(copied, "launch.conf")
        config.writeText(config.readText().replace(sourcePath, copied.path).replace(source.path, copied.path))
        // Select either side explicitly, even after native-size output becomes
        // the installed default. Never append "nb" to an existing "texturenb".
        config.writeText(config.readText().replace(Regex("(?m)^output[ \\t]*=[^\\r\\n]*"),
            "output = " + if (noDouble) "texturenb" else "texture"))
        check(config.readText().contains(copied.path + "/drive")) { "Copied drive was not mounted" }
        check(!config.readText().contains(sourcePath) && !config.readText().contains(source.path))
        File(report, "launch.conf").writeText(config.readText())
        File(report, "DUKE3D.CFG").writeText(settings)
        val resources = DosStagingResources.prepare(context, AtomicBoolean(false))
        var host: MainActivity? = null
        instrumentation.runOnMainSync { host = MainActivity() }
        val methods = MainActivity::class.java.declaredMethods.associateBy { it.name }
        fun call(name: String, vararg args: Any?): Any? = methods.getValue(name)
            .apply { isAccessible = true }.invoke(host, *args)
        val display = context.getSystemService(DisplayManager::class.java).displays
            .firstOrNull { it.displayId == 2 }?.displayId ?: 0
        val activity = instrumentation.startActivitySync(Intent(context, VideoPresentationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic()
                .apply { launchDisplayId = display }.toBundle()) as VideoPresentationActivity
        val worker = Executors.newSingleThreadExecutor()
        var sound: Thread? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!activity.video.holder.surface.isValid && System.nanoTime() < deadline) Thread.sleep(20)
            check(activity.video.holder.surface.isValid)
            call("nativeConfigure", 0, 0, 0)
            call("nativeSetSurface", activity.video.holder.surface)
            val core = worker.submit<Boolean> {
                call("nativeRun", config.path, saves.path, resources.path) as Boolean
            }
            val started = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (call("nativeStatus") != 2 && !core.isDone && System.nanoTime() < started) Thread.sleep(20)
            check(call("nativeStatus") == 2) { "Core failed: ${call("nativeLastError")}" }
            sound = Thread {
                val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
                val pcm = ShortArray(4096)
                try {
                    track.play()
                    while (!core.isDone) {
                        val frames = call("nativeReadAudio", pcm, pcm.size / 2) as Int
                        if (frames > 0) track.write(pcm, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                        else Thread.sleep(4)
                    }
                } finally { runCatching { track.stop() }; track.release() }
            }.apply { name = "KairoDos-fixture-audio"; start() }
            // The copied session retains the existing SB16 selection. Let the
            // host verify the Y/N prompt and send N before starting its timer.
            phase("ready")
            val timeout = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            var demoStarted = false
            var lastSize = ""
            while (!File(report, "stop").exists() && !core.isDone && System.nanoTime() < timeout) {
                val size = "${call("nativeVideoWidth")}x${call("nativeVideoHeight")}"
                if (size != lastSize) { File(report, "buffer-size.txt").writeText(size); lastSize = size }
                if (!demoStarted && File(report, "go").exists()) {
                    call("nativeKey", 'n'.code, true); Thread.sleep(100)
                    call("nativeKey", 'n'.code, false)
                    demoStarted = true
                    phase("warmup")
                }
                Thread.sleep(100)
            }
            check(demoStarted) { "Demo was not started" }
            call("nativeStop")
            check(core.get(15, TimeUnit.SECONDS)) { "Core failed: ${call("nativeLastError")}" }
            check(File(source, "drive/Duke3D/DUKE3D/DUKE3D.CFG").readBytes().contentEquals(original))
            phase("complete")
            return "${width}x$height comparison complete; installed configuration unchanged"
        } finally {
            call("nativeStop")
            worker.shutdown()
            check(worker.awaitTermination(15, TimeUnit.SECONDS))
            sound?.join(3000)
            call("nativeSetSurface", null)
            instrumentation.runOnMainSync { activity.finish() }
            root.deleteRecursively()
        }
    }
}
