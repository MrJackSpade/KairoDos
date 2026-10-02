package com.mrjackspade.kairodos

import android.app.Instrumentation
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Generated FAT12/ISO9660 images exercise the real mounted guest filesystem. */
internal object DosMediaFixture {
    fun verify(test: Instrumentation) {
        val root = File(test.targetContext.cacheDir, "dos-program-media-fixture")
        check(!root.exists()); check(root.mkdirs())
        var activity: MainActivity? = null
        test.runOnMainSync { activity = MainActivity() }
        fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }.invoke(activity, *args)
        val worker = Executors.newSingleThreadExecutor()
        val audio = ShortArray(2048)
        fun await(message: String, condition: () -> Boolean) {
            repeat(1500) {
                if (condition()) return
                call("nativeReadAudio", audio, audio.size / 2)
                Thread.sleep(10)
            }
            error("$message: ${call("nativeLastError")}")
        }
        try {
            val fatBytes = fat12()
            val isoBytes = iso9660()
            val fat = File(root, "fixture.img").apply { writeBytes(fatBytes) }
            val iso = File(root, "fixture.iso").apply { writeBytes(isoBytes) }
            val directory = File(root, "drive").apply { mkdirs() }
            val resources = DosStagingResources.prepare(test.targetContext, AtomicBoolean(false))
            val launch = DosGameCatalog.Launch("", mapOf("dosbox.conf" to
                "[autoexec]\nmount c .\nimgmount a \"${fat.absolutePath}\" -t floppy\n" +
                "imgmount d \"${iso.absolutePath}\" -t iso\nSHOULDNOT.EXE\n"), false)
            fun run(drive: DosStagingStorage.Drive, profile: DosGameCatalog.Launch?,
                    target: DosLaunchTarget, inspect: () -> Unit) {
                val config = DosStagingLaunchConfig.write(File(root, "launch.conf"), drive, profile,
                    "dosbox.conf", emptyMap(), null, false, false, target = target)
                val future = worker.submit<Boolean> {
                    call("nativeRun", config.absolutePath, root.absolutePath, resources.absolutePath) as Boolean
                }
                try {
                    await("DOS shell did not start") {
                        call("nativeStatus") == 2 && (call("nativeInputTelemetry") as LongArray)[3] == 1L
                    }
                    inspect()
                } finally {
                    call("nativeStop")
                    check(future.get(15, TimeUnit.SECONDS))
                }
            }
            val drive = DosStagingStorage.Drive(directory)
            run(drive, launch, DosLaunchTarget.Browse) {
                call("nativePause", true)
                val roots = call("nativeListDirectory", "") as String
                check(roots.contains("D\tA:\\\n") && roots.contains("D\tD:\\\n")) { roots }
                for (letter in listOf("A", "D")) {
                    val listing = call("nativeListDirectory", "$letter:\\") as String
                    check(listing.contains("D\t$letter:\\SETUP\n")) { listing }
                    val inside = call("nativeListDirectory", "$letter:\\SETUP") as String
                    check(inside.contains("F\t$letter:\\SETUP\\RUN.BAT\n")) { inside }
                }
            }
            for ((letter, kind) in listOf("A" to "FAT", "D" to "ISO")) {
                run(drive, launch, DosLaunchTarget.Program("$letter:\\SETUP\\RUN.BAT")) {
                    val marker = File(directory, "${kind}PASS.TXT")
                    await("Program inside $kind image did not execute") { marker.isFile }
                    check(marker.readText().trim() == kind.lowercase())
                }
            }
            // Also exercise uncatalogued standalone image launch preparation.
            for ((original, letter, kind) in listOf(Triple(fat, "A", "FAT"), Triple(iso, "D", "ISO"))) {
                val standalone = File(root, kind).apply { mkdirs() }
                val image = original.copyTo(File(standalone, original.name))
                run(DosStagingStorage.Drive(standalone, image), null,
                    DosLaunchTarget.Program("$letter:\\SETUP\\RUN.BAT")) {
                    val marker = File(standalone, "${kind}PASS.TXT")
                    await("Standalone $kind program did not execute") { marker.isFile }
                    check(marker.readText().trim() == kind.lowercase())
                    check(image.readBytes().contentEquals(original.readBytes()))
                }
            }
            check(fat.readBytes().contentEquals(fatBytes) && iso.readBytes().contentEquals(isoBytes))
        } finally {
            call("nativeStop")
            worker.shutdown()
            check(worker.awaitTermination(20, TimeUnit.SECONDS))
            check(root.deleteRecursively())
        }
    }

    private fun ByteArray.le16(at: Int, value: Int) {
        this[at] = value.toByte(); this[at + 1] = (value shr 8).toByte()
    }
    private fun ByteArray.le32(at: Int, value: Int) {
        repeat(4) { this[at + it] = (value shr (8 * it)).toByte() }
    }
    private fun ByteArray.both16(at: Int, value: Int) {
        le16(at, value); this[at + 2] = (value shr 8).toByte(); this[at + 3] = value.toByte()
    }
    private fun ByteArray.both32(at: Int, value: Int) {
        le32(at, value); repeat(4) { this[at + 4 + it] = (value shr (8 * (3 - it))).toByte() }
    }
    private fun ByteArray.text(at: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(this, at)

    private fun fat12(): ByteArray = ByteArray(2880 * 512).apply {
        this[0] = 0xeb.toByte(); this[1] = 0x3c; this[2] = 0x90.toByte(); text(3, "KAIRODOS")
        le16(11, 512); this[13] = 1; le16(14, 1); this[16] = 2; le16(17, 224)
        le16(19, 2880); this[21] = 0xf0.toByte(); le16(22, 9); le16(24, 18); le16(26, 2)
        this[38] = 0x29; le32(39, 1234); text(43, "KAIRO TEST "); text(54, "FAT12   "); le16(510, 0xaa55)
        for (fat in listOf(512, 10 * 512)) {
            this[fat] = 0xf0.toByte()
            for (index in 1..5) this[fat + index] = 0xff.toByte() // clusters 2/3 end-of-chain
        }
        fun entry(at: Int, name: String, cluster: Int, directory: Boolean, size: Int = 0) {
            text(at, name); this[at + 11] = (if (directory) 0x10 else 0x20).toByte()
            le16(at + 26, cluster); le32(at + 28, size)
        }
        val script = "@echo off\r\necho fat>C:\\FATPASS.TXT\r\n"
        entry(19 * 512, "SETUP      ", 2, true)
        entry(33 * 512, ".          ", 2, true)
        entry(33 * 512 + 32, "..         ", 0, true)
        entry(33 * 512 + 64, "RUN     BAT", 3, false, script.length)
        text(34 * 512, script)
    }

    private fun iso9660(): ByteArray = ByteArray(25 * 2048).apply {
        fun record(at: Int, sector: Int, size: Int, directory: Boolean, name: ByteArray): Int {
            val length = 33 + name.size + if (name.size % 2 == 0) 1 else 0
            this[at] = length.toByte(); both32(at + 2, sector); both32(at + 10, size)
            this[at + 18] = 126; this[at + 19] = 10; this[at + 20] = 2
            this[at + 25] = (if (directory) 2 else 0).toByte(); both16(at + 28, 1)
            this[at + 32] = name.size.toByte(); name.copyInto(this, at + 33)
            return at + length
        }
        val pvd = 16 * 2048
        this[pvd] = 1; text(pvd + 1, "CD001"); this[pvd + 6] = 1
        text(pvd + 8, "KAIRO".padEnd(32)); text(pvd + 40, "KAIRO_TEST".padEnd(32))
        both32(pvd + 80, 25); both16(pvd + 120, 1); both16(pvd + 124, 1)
        both16(pvd + 128, 2048); both32(pvd + 132, 24); le32(pvd + 140, 20)
        this[pvd + 151] = 21 // big-endian path table sector
        record(pvd + 156, 22, 2048, true, byteArrayOf(0)); this[pvd + 881] = 1
        val end = 17 * 2048; this[end] = 0xff.toByte(); text(end + 1, "CD001"); this[end + 6] = 1
        val little = 20 * 2048; this[little] = 1; le32(little + 2, 22); le16(little + 6, 1)
        val big = 21 * 2048; this[big] = 1; this[big + 5] = 22; this[big + 7] = 1
        this[little + 10] = 5; le32(little + 12, 23); le16(little + 16, 1); text(little + 18, "SETUP")
        this[big + 10] = 5; this[big + 15] = 23; this[big + 17] = 1; text(big + 18, "SETUP")
        var at = record(22 * 2048, 22, 2048, true, byteArrayOf(0))
        at = record(at, 22, 2048, true, byteArrayOf(1))
        record(at, 23, 2048, true, "SETUP".toByteArray())
        at = record(23 * 2048, 23, 2048, true, byteArrayOf(0))
        at = record(at, 22, 2048, true, byteArrayOf(1))
        val script = "@echo off\r\necho iso>C:\\ISOPASS.TXT\r\n"
        record(at, 24, script.length, false, "RUN.BAT;1".toByteArray())
        text(24 * 2048, script)
    }
}
