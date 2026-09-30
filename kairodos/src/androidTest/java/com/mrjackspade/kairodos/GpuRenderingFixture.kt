package com.mrjackspade.kairodos

import android.graphics.PixelFormat
import android.app.Instrumentation
import android.media.ImageReader
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal object GpuRenderingFixture {
    init { System.loadLibrary("kairodos_host") }
    @JvmStatic private external fun nativeVerify(first: Surface, second: Surface, bottomLeft: Boolean): Boolean

    fun verify() {
        for (bottomLeft in listOf(true, false)) {
            val readers = List(2) { ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3) }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val task = worker.submit<Boolean> { nativeVerify(readers[0].surface, readers[1].surface, bottomLeft) }
                val counts = IntArray(2)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!task.isDone && System.nanoTime() < deadline) {
                    readers.forEachIndexed { index, reader ->
                        reader.acquireLatestImage()?.use { image ->
                            val plane = image.planes.single()
                            val buffer = plane.buffer
                            fun channel(x: Int, y: Int, c: Int) = buffer.get(y * plane.rowStride + x * plane.pixelStride + c).toInt() and 255
                            val upper = if (bottomLeft) 2 else 0
                            val lower = if (bottomLeft) 0 else 2
                            check(channel(16, 16, upper) == 255 && channel(16, 16, lower) == 0) { "Upper half orientation/color mismatch" }
                            check(channel(16, 48, lower) == 255 && channel(16, 48, upper) == 0) { "Lower half orientation/color mismatch" }
                            counts[index]++
                        }
                    }
                    Thread.sleep(4)
                }
                check(task.get(1, TimeUnit.SECONDS)) { "Native GLES context, FBO or mailbox failed" }
                check(counts.all { it >= 5 }) { "Both display surfaces must receive frames: ${counts.toList()}" }
            } finally {
                worker.shutdownNow()
                readers.forEach(ImageReader::close)
            }
        }
    }

    // Use a user-supplied installed archive, copied unchanged into test cache.
    // Its saves/configuration and game files remain isolated from normal play.
    fun verifyCore(instrumentation: Instrumentation, archivePath: String) {
        val root = File(instrumentation.targetContext.cacheDir, "gles-core-fixture")
        check(!root.exists()) { "Fixture cache already exists; inspect it before retrying" }
        check(root.mkdirs())
        val worker = Executors.newSingleThreadExecutor()
        var host: MainActivity? = null
        instrumentation.runOnMainSync { host = MainActivity() }
        fun call(name: String, vararg args: Any?): Any? {
            val method = MainActivity::class.java.declaredMethods.single { it.name == name }
            method.isAccessible = true
            return method.invoke(host, *args)
        }
        try {
            val archive = File(root, "game.zip")
            File(archivePath).copyTo(archive)
            val executable = ZipFile(archive).use { zip -> zip.entries().asSequence()
                .first { it.name.substringAfterLast('/').equals("doom.exe", true) }.name }
            val directory = executable.substringBeforeLast('/', "").replace('/', '\\')
            File(root, "game.conf").writeText("[autoexec]\nC:\n" +
                (if (directory.isEmpty()) "" else "cd \\$directory\n") + "doom.exe -nosound\n")
            for (mode in listOf(0, 1)) {
                val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3)
                val frameCount = AtomicInteger()
                val drain = Executors.newSingleThreadExecutor()
                val running = AtomicBoolean(true)
                val images = drain.submit {
                    while (running.get()) {
                        reader.acquireLatestImage()?.use { frameCount.incrementAndGet() }
                        Thread.sleep(4)
                    }
                }
                try {
                    call("nativeConfigure", 0, 0, mode)
                    call("nativeSetSurface", reader.surface)
                    val saves = File(root, "saves-$mode").apply { mkdirs() }
                    val system = File(root, "system-$mode").apply { mkdirs() }
                    val core = worker.submit<Boolean> { call("nativeRun", archive.absolutePath,
                        saves.absolutePath, system.absolutePath, false, true) as Boolean }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (call("nativeStatus") != 2 && !core.isDone && System.nanoTime() < deadline) Thread.sleep(10)
                    check(call("nativeStatus") == 2) { "Core did not start: ${call("nativeLastError")}" }
                    Thread.sleep(1500)
                    fun key(code: Int) { call("nativeKey", code, true); Thread.sleep(70); call("nativeKey", code, false); Thread.sleep(180) }
                    key(27); repeat(3) { key(13) }
                    Thread.sleep(700)
                    call("nativePause", true)
                    val state = File(root, "roundtrip-$mode.state")
                    check(call("nativeSaveState", state.absolutePath) == 0) { "Save state failed in rendering mode $mode" }
                    check(call("nativeLoadState", state.absolutePath) == 0) { "Load state failed in rendering mode $mode" }
                    call("nativePause", false)
                    Thread.sleep(350)
                    call("nativeSetSurface", null)
                    Thread.sleep(150)
                    call("nativeSetSurface", reader.surface)
                    Thread.sleep(350)
                    call("nativeReset")
                    Thread.sleep(700)
                    check(frameCount.get() > 20) { "No sustained game frames in rendering mode $mode" }
                    call("nativeStop")
                    check(core.get(5, TimeUnit.SECONDS)) { "Core failed: ${call("nativeLastError")}" }
                } finally {
                    call("nativeStop")
                    call("nativeSetSurface", null)
                    running.set(false)
                    images.get(2, TimeUnit.SECONDS)
                    drain.shutdownNow()
                    reader.close()
                }
            }
        } finally {
            call("nativeStop")
            worker.shutdown()
            check(worker.awaitTermination(10, TimeUnit.SECONDS)) { "Core did not stop; fixture cache retained for inspection" }
            root.deleteRecursively()
        }
    }
}
