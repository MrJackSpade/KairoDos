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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

internal object VideoPresentationFixture {
    init { System.loadLibrary("kairodos_host") }
    @JvmStatic private external fun nativeProfileReset(active: Boolean)
    @JvmStatic private external fun nativeProfileSnapshot(): String
    @JvmStatic private external fun nativeProfileConfiguration(): String
    @JvmStatic private external fun nativeProfileFrameCopies(active: Boolean)
    @JvmStatic private external fun nativeProfilePresenterDelay(milliseconds: Int)

    fun measure(instrumentation: Instrumentation, archivePath: String, game: String,
                seconds: Int, observeCopies: Boolean = false,
                surfaceScenario: String = "normal", observeTiming: Boolean = true): JSONObject {
        check(game in setOf("doom", "duke"))
        check(surfaceScenario in setOf("normal", "delayed", "unavailable"))
        check(observeTiming || (surfaceScenario == "normal" && !observeCopies))
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
            val cancelled = AtomicBoolean(false)
            val contentId = DosContentHash.zip(archive, cancelled)
            val launch = DosGameCatalog(context).resolve(contentId, archive.name).launch
            val resources = DosStagingResources.prepare(context, cancelled)
            val display = context.getSystemService(DisplayManager::class.java).displays
                .firstOrNull { it.displayId == 2 }?.displayId ?: 0
            // Staging always uses the CPU presenter. Consume audio at its real
            // sample rate so the mixer has the same playback clock as the app.
            for ((index, mode) in listOf(0).withIndex()) {
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
                val drive = DosStagingStorage.prepare(archive, saves, contentId, cancelled, launch?.folder)
                val config = DosStagingLaunchConfig.write(File(saves, "game.conf"), drive,
                    launch, "dosbox.conf", emptyMap(), null, false, false)
                val core = worker.submit<Boolean> { call("nativeRun", config.absolutePath,
                    saves.absolutePath, resources.absolutePath) as Boolean }
                val sound = Thread {
                    val pcm = ShortArray(960)
                    var next = System.nanoTime()
                    while (!core.isDone) {
                        val frames = call("nativeReadAudio", pcm, 480) as Int
                        next = maxOf(next, System.nanoTime()) + frames * 1_000_000_000L / 48000L
                        val wait = next - System.nanoTime()
                        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait) else Thread.sleep(2)
                    }
                }.apply { name = "Staging-profile-audio"; start() }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (call("nativeStatus") != 2 && !core.isDone && System.nanoTime() < deadline) Thread.sleep(20)
                    check(call("nativeStatus") == 2) { "Start failed: ${call("nativeLastError")}" }
                    Thread.sleep(3500)
                    fun key(code: Int) {
                        call("nativeKey", code, true); Thread.sleep(100)
                        call("nativeKey", code, false); Thread.sleep(180)
                    }
                    key(if (game == "doom") '2'.code else '1'.code)
                    key('n'.code)
                    val warmupStart = System.nanoTime()
                    val warmupDeadline = warmupStart + TimeUnit.SECONDS.toNanos(60)
                    while (System.nanoTime() < warmupDeadline) {
                        TimeUnit.NANOSECONDS.sleep(maxOf(1L, warmupDeadline - System.nanoTime()))
                    }
                    if (surfaceScenario == "unavailable") call("nativeSetSurface", null)
                    nativeProfileReset(observeTiming)
                    nativeProfileFrameCopies(observeCopies)
                    nativeProfilePresenterDelay(if (surfaceScenario == "delayed") 100 else 0)
                    val cpuBefore = call("nativeCpuTelemetry") as LongArray
                    val start = System.nanoTime()
                    Log.i("VideoPresentation", "PROFILE_START $game mode=$mode run=$index ns=$start")
                    Thread.sleep(seconds * 1000L)
                    val elapsed = System.nanoTime() - start
                    Log.i("VideoPresentation", "PROFILE_END $game mode=$mode run=$index ns=${start + elapsed}")
                    call("nativePause", true)
                    Thread.sleep(250) // allow the injected 100ms presenter stall to drain
                    nativeProfileResetEnabledOff()
                    val result = JSONObject().put("game", game).put("mode", mode)
                        .put("run", index).put("durationNs", elapsed)
                        .put("observeCopies", observeCopies).put("warmupNs", start - warmupStart)
                        .put("surfaceScenario", surfaceScenario)
                        .put("observeTiming", observeTiming)
                        .put("cpuBefore", JSONArray(cpuBefore.toList()))
                        .put("cpuAfter", JSONArray((call("nativeCpuTelemetry") as LongArray).toList()))
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
                    nativeProfilePresenterDelay(0)
                    call("nativeStop")
                    check(core.get(10, TimeUnit.SECONDS)) { "Core did not exit" }
                    sound.join(2000)
                    check(!sound.isAlive) { "Audio consumer did not exit" }
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
