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

    // Emulator lifecycle acceptance now uses Staging's software renderer.
    fun verifyCore(instrumentation: Instrumentation, archivePath: String) {
        StagingCoreFixture.verify(instrumentation, archivePath)
    }
}