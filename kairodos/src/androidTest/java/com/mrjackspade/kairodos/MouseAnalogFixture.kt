package com.mrjackspade.kairodos

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.mrjackspade.kairo.frontend.GamepadMapper
import java.io.File

/** The real activity/mapper/native bridge, sampled by a generated INT 33h DOS program. */
internal object MouseAnalogFixture {
    fun verify(test: Instrumentation, uri: String, hardware: Boolean = false): String {
        if (hardware) {
            check(android.os.Build.MODEL in setOf("RG DS", "RG_DS")) { "Kernel fixture is RGDS-specific" }
            check(File("/sys/class/input/event9/device/name").readText().trim() == "retrogame_joypad")
        }
        val activity = test.startActivitySync(test.targetContext.packageManager
            .getLaunchIntentForPackage(test.targetContext.packageName)!!
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
            .putExtra("kairo98.skipStartupChoices", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }.invoke(activity, *args)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity)
        fun ui(action: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
            test.waitForIdleSync(); failure?.let { throw it }
        }
        fun await(message: String, condition: () -> Boolean) {
            repeat(600) { if (condition()) return; Thread.sleep(50) }
            error(message)
        }
        fun key(code: Int) {
            call("nativeKey", code, true); Thread.sleep(80)
            call("nativeKey", code, false); Thread.sleep(120)
        }
        fun motion(value: Float) {
            if (hardware) {
                // executeShellCommand executes one process, not shell compound syntax.
                for (command in listOf("sendevent /dev/input/event9 3 3 ${(value * 16384).toInt()}",
                                       "sendevent /dev/input/event9 0 0 0")) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use {
                        val output = it.bufferedReader().readText()
                        check(output.isBlank()) { "Kernel input injection failed: $output" }
                    }
                }
                Thread.sleep(40)
                return
            }
            val now = SystemClock.uptimeMillis()
            val props = MotionEvent.PointerProperties().apply { id = 0 }
            val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_Z, value) }
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 42, 0, InputDevice.SOURCE_JOYSTICK, 0)
            try { ui { check(activity.dispatchGenericMotionEvent(event)) } } finally { event.recycle() }
        }
        var owned: File? = null
        val report = StringBuilder()
        try {
            await("Game did not start") { call("nativeStatus") == 2 && field("currentGame") != null }
            val game = field("currentGame") as DosLibrary.Game
            val configName = call("manualConfig", game) as String
            ui { call("startGame", game, configName, false, DosLaunchTarget.Prompt) }
            await("Prompt did not start") { call("nativeStatus") == 2 &&
                (call("nativeInputTelemetry") as LongArray)[3] == 1L }
            val config = File(test.targetContext.filesDir, "saves/${game.id}/staging/launch.conf").readText()
            val root = File(Regex("(?im)^mount C \"([^\"]+)\"").find(config)!!.groupValues[1])
            val folder = File(root, "KAIROANA")
            check(!folder.exists()); check(folder.mkdir()); owned = folder
            File(folder, "READ.COM").writeBytes(probe())
            ui { call("startGame", game, configName, false, DosLaunchTarget.Program("C:\\KAIROANA\\READ.COM")) }
            val result = File(folder, "COUNTS.BIN")
            await("Mouse probe did not start") { result.exists() }
            Thread.sleep(700)
            val mapper = field("gamepad") as GamepadMapper
            ui {
                report.append("hardware=$hardware deadZone=${mapper.deadZone} right=${mapper.bindings.single { it.input == "virtual:rsright" }}\n")
            }
            val positions = listOf(0f, .145f, .19f, .325f, .55f, 1f)
            val durations = ArrayList<Long>()
            for (position in positions) {
                key(32) // discard counts before the controlled interval
                val start = SystemClock.elapsedRealtime()
                motion(position)
                Thread.sleep(2000)
                motion(0f)
                durations += SystemClock.elapsedRealtime() - start
                Thread.sleep(100)
                key(32) // append INT 33h motion counters
            }
            key('q'.code)
            await("Probe did not close its result") { result.length() == positions.size * 8L }
            val bytes = result.readBytes()
            fun word(offset: Int) = ((bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)).toShort().toInt()
            for ((index, position) in positions.withIndex()) {
                report.append("stick=$position ms=${durations[index]} guestX=${word(index * 8 + 4)} guestY=${word(index * 8 + 6)}\n")
            }
            check(word((positions.size - 1) * 8 + 4) > 0) { "No guest motion; input injection was not verified: $report" }
            return report.toString()
        } finally {
            if (hardware) motion(0f)
            ui { (field("gamepad") as GamepadMapper).releaseAll(); activity.finish() }
            await("Core did not stop") { call("nativeStatus") in listOf(0, 3) }
            owned?.let { check(it.deleteRecursively()) }
        }
    }

    // Reset mouse, create output, then read one signed CX/DX counter pair for
    // each space; Q closes the file. No game binaries or settings are modified.
    private fun probe(): ByteArray {
        val bytes = mutableListOf<Int>()
        fun emit(vararg value: Int) { bytes.addAll(value.toList()) }
        emit(0x31,0xc0,0xcd,0x33, 0xba,0,0, 0x31,0xc9,0xb4,0x3c,0xcd,0x21,0x89,0xc7)
        val loop = bytes.size
        emit(0x31,0xc0,0xcd,0x16,0x3c,0x71,0x74,0)
        val endJump = bytes.lastIndex
        emit(0xb8,0x0b,0,0xcd,0x33,0x52,0x51,0x89,0xe2,0x89,0xfb,0xb9,4,0,0xb4,0x40,0xcd,0x21,0x83,0xc4,4,0xeb,0)
        bytes[bytes.lastIndex] = (loop - bytes.size) and 255
        bytes[endJump] = (bytes.size - endJump - 1) and 255
        emit(0x89,0xfb,0xb4,0x3e,0xcd,0x21,0xb8,0,0x4c,0xcd,0x21)
        val name = 0x100 + bytes.size
        bytes[5] = name and 255; bytes[6] = name shr 8
        emit(*"COUNTS.BIN\u0000".map(Char::code).toIntArray())
        return bytes.map(Int::toByte).toByteArray()
    }
}
