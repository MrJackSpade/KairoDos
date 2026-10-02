package com.mrjackspade.kairodos

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import java.io.File

/** Exercises the real settings action, writable guest shell, and normal relaunch. */
internal object DosPromptFixture {
    fun verify(test: Instrumentation, uri: String) {
        val intent = test.targetContext.packageManager.getLaunchIntentForPackage(test.targetContext.packageName)!!
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
            .putExtra("kairo98.skipStartupChoices", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = test.startActivitySync(intent)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity)
        fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }.invoke(activity, *args)
        fun ui(action: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
            test.waitForIdleSync()
            failure?.let { throw it }
        }
        fun await(message: String, condition: () -> Boolean) {
            repeat(600) {
                if (condition()) return
                Thread.sleep(100)
            }
            error(message)
        }
        fun descendants(view: View): List<View> = listOf(view) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) } }
                ?: emptyList())
        var marker: File? = null
        try {
            await("Normal game did not start") { call("nativeStatus") == 2 && field("currentGame") != null }
            val game = field("currentGame") as DosLibrary.Game
            val config = File(test.targetContext.filesDir, "saves/${game.id}/staging/launch.conf")
            val normal = config.readText()
            check(!normal.substringAfter("[autoexec]").substringBefore("[sdl]").trim().endsWith("dir /w"))
            val preferences = test.targetContext.getSharedPreferences("kairodos", 0).all.toMap()
            val generation = field("launchGeneration")
            ui { call("showGameDetails", game) }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            ui {
                val label = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                    .filterIsInstance<TextView>().single { it.text.toString() == "Boot to DOS prompt" }
                var row: View = label
                while (!row.isClickable) row = row.parent as View
                check(row.requestFocus()) { "Prompt row cannot receive controller focus" }
                row.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                row.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
            }
            await("Prompt action did not launch") {
                field("launchGeneration") != generation && call("nativeStatus") == 2 &&
                    config.readText().substringAfter("[autoexec]").substringBefore("[sdl]").trim().endsWith("dir /w")
            }
            val prompt = config.readText()
            val path = Regex("(?m)^mount C \"([^\"]+)\"").find(prompt)!!.groupValues[1]
            val file = File(path, "K24TEST.TXT")
            check(!file.exists()) { "Pre-existing fixture marker; refusing to overwrite" }
            marker = file
            Thread.sleep(1500)
            call("nativePause", true)
            try {
                val drives = call("nativeListDirectory", "") as String
                check(drives.startsWith("OK\n") && drives.contains("D\tC:\\\n")) { drives }
                check(!drives.contains("Z:"))
                val pending = java.util.ArrayDeque<String>().apply { add("C:\\") }
                var executable = false
                var visits = 0
                while (pending.isNotEmpty() && !executable && visits++ < 50) {
                    val directory = pending.removeFirst()
                    val listing = call("nativeListDirectory", directory) as String
                    check(listing.startsWith("OK\n")) { listing }
                    for (entry in listing.lineSequence().drop(1).filter(String::isNotEmpty)) {
                        val path = entry.substringAfter('\t')
                        check(path.startsWith("C:\\") && !path.endsWith("\\..") && !path.endsWith("\\."))
                        if (entry.startsWith("D\t")) pending.add(path)
                        if (entry.startsWith("F\t") && path.endsWith(".EXE", true)) executable = true
                    }
                }
                check(executable) { "Could not browse to a game executable" }
                for (invalid in listOf("C:\\..", "C:\\*.EXE", "C:\\MISSING", "Z:\\", "/data"))
                    check((call("nativeListDirectory", invalid) as String).startsWith("ERROR\n"))
            } finally { call("nativePause", false) }
            fun key(code: Int) {
                call("nativeKey", code, true); Thread.sleep(60)
                call("nativeKey", code, false); Thread.sleep(60)
            }
            for (char in "echo kairo24 > k24test.txt") {
                if (char == '>') {
                    call("nativeKey", 304, true); key('.'.code); call("nativeKey", 304, false)
                } else key(char.code)
            }
            key(13)
            await("DOS command did not write to the game's writable drive") {
                file.exists() && file.readText().trim() == "kairo24"
            }
            check(file.delete())
            marker = null
            // An ordinary launch must reconstruct the original autoexec.
            ui { call("launch", game, false) }
            await("Normal launch did not restore startup commands") {
                call("nativeStatus") == 2 && config.readText() == normal
            }
            check(test.targetContext.getSharedPreferences("kairodos", 0).all == preferences)
        } finally {
            marker?.delete()
            ui { activity.finish() }
        }
    }
}
