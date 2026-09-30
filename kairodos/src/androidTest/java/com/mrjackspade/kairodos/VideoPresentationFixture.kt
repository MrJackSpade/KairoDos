package com.mrjackspade.kairodos

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal object VideoPresentationFixture {
    init { System.loadLibrary("kairodos_host") }
    @JvmStatic private external fun nativeProfileReset(active: Boolean)
    @JvmStatic private external fun nativeProfileSnapshot(): String
    @JvmStatic private external fun nativeProfileConfiguration(): String

    fun measure(instrumentation: Instrumentation, archivePath: String, game: String,
                seconds: Int): JSONObject {
        check(game in setOf("doom", "duke"))
        check(seconds in 10..180)
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "video-presentation-fixture")
        check(!root.exists()) { "Inspect existing fixture cache before retrying" }
        check(root.mkdirs())
        val reports = File(context.cacheDir, "video-presentation-reports").apply { mkdirs() }
        val worker = Executors.newSingleThreadExecutor()
        var host: MainActivity? = null
        instrumentation.runOnMainSync { host = MainActivity() }
        fun call(name: String, vararg args: Any?): Any? {
            val method = MainActivity::class.java.declaredMethods.single { it.name == name }
            method.isAccessible = true
            return method.invoke(host, *args)
        }
        var activity: VideoPresentationActivity? = null
        val results = JSONArray()
        try {
            val archive = File(root, "game.zip")
            File(archivePath).copyTo(archive)
            val executable = if (game == "doom") "doom.exe" else "duke3d.exe"
            val directory = ZipFile(archive).use { zip -> zip.entries().asSequence()
                .first { it.name.substringAfterLast('/').equals(executable, true) }.name
                .substringBeforeLast('/', "").replace('/', '\\') }
            // Private sidecar only. Original game binaries, ZIP, settings and saves
            // are untouched. Duke uses the supplied SB16 files, as the normal app does.
            File(root, "game.conf").writeText("[autoexec]\nC:\n" +
                (if (directory.isEmpty()) "" else "cd \\$directory\n") +
                (if (game == "duke") "copy SB16\\*.* .\n" else "") +
                "$executable\n")
            val display = context.getSystemService(DisplayManager::class.java).displays
                .firstOrNull { it.displayId == 2 }?.displayId ?: 0
            // ABBA reduces order/temperature bias. Identical attract sequences and
            // settings; sound runs normally although this fixture does not consume PCM.
            for ((index, mode) in listOf(1, 0, 0, 1).withIndex()) {
                // Match showGame()/showLibrary(): every session owns a fresh surface.
                // ANativeWindow_lock connects the CPU API until the SurfaceView is
                // destroyed; reusing it for EGL gives EGL_BAD_ALLOC and stale pixels.
                activity = instrumentation.startActivitySync(Intent(context, VideoPresentationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().apply { launchDisplayId = display }.toBundle()) as VideoPresentationActivity
                val screen = activity.video
                val ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ((!screen.holder.surface.isValid || screen.width == 0) && System.nanoTime() < ready) Thread.sleep(20)
                check(screen.holder.surface.isValid && screen.width > 0)
                call("nativeConfigure", 0, 0, mode)
                call("nativeSetSurface", screen.holder.surface)
                val saves = File(root, "saves-$index").apply { mkdirs() }
                val system = File(root, "system-$index").apply { mkdirs() }
                val core = worker.submit<Boolean> { call("nativeRun", archive.absolutePath,
                    saves.absolutePath, system.absolutePath, false, true) as Boolean }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (call("nativeStatus") != 2 && !core.isDone && System.nanoTime() < deadline) Thread.sleep(20)
                    check(call("nativeStatus") == 2) { "Start failed: ${call("nativeLastError")}" }
                    Thread.sleep(12000) // intro/loading excluded; native game attract mode
                    nativeProfileReset(true)
                    val start = System.nanoTime()
                    Log.i("VideoPresentation", "PROFILE_START $game mode=$mode run=$index ns=$start")
                    Thread.sleep(seconds * 1000L)
                    val elapsed = System.nanoTime() - start
                    Log.i("VideoPresentation", "PROFILE_END $game mode=$mode run=$index ns=${start + elapsed}")
                    call("nativePause", true)
                    Thread.sleep(80) // drain a pending presenter frame before snapshot
                    nativeProfileResetEnabledOff()
                    val result = JSONObject().put("game", game).put("mode", mode)
                        .put("run", index).put("durationNs", elapsed)
                        .put("startNs", start).put("endNs", start + elapsed)
                        .put("width", screen.width).put("height", screen.height)
                        .put("videoWidth", call("nativeVideoWidth")).put("videoHeight", call("nativeVideoHeight"))
                        .put("metrics", JSONObject(nativeProfileSnapshot()))
                    results.put(result)
                    val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
                    val copied = CountDownLatch(1)
                    var copyResult = -1
                    PixelCopy.request(screen, bitmap, { copyResult = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    check(copied.await(3, TimeUnit.SECONDS) && copyResult == PixelCopy.SUCCESS)
                    File(reports, "$game-$index-mode$mode.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    call("nativeStop")
                    check(core.get(8, TimeUnit.SECONDS)) { "Core failed: ${call("nativeLastError")}" }
                } finally {
                    call("nativeStop")
                    check(core.get(10, TimeUnit.SECONDS)) { "Core did not exit" }
                    nativeProfileReset(false)
                    call("nativeSetSurface", null)
                    instrumentation.runOnMainSync { activity?.finish() }
                    val destroyed = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (screen.holder.surface.isValid && System.nanoTime() < destroyed) Thread.sleep(20)
                    check(!screen.holder.surface.isValid) { "Previous measurement surface was not destroyed" }
                    activity = null
                }
            }
            return JSONObject().put("schemaVersion", 1).put("game", game)
                .put("configuration", nativeProfileConfiguration()).put("runs", results)
                .also { File(reports, "$game.json").writeText(it.toString(2)) }
        } finally {
            call("nativeStop")
            worker.shutdown()
            check(worker.awaitTermination(10, TimeUnit.SECONDS)) { "Core still running; fixture cache retained" }
            nativeProfileReset(false)
            instrumentation.runOnMainSync { activity?.finish() }
            root.deleteRecursively()
        }
    }

    @JvmStatic private external fun nativeProfileResetEnabledOff()
}
