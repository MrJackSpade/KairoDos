package com.mrjackspade.kairodos

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import java.io.File

/** Real settings/picker launches; generated programs contain no game assets. */
internal object DosProgramFixture {
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
            var error: Throwable? = null
            test.runOnMainSync { try { action() } catch (failure: Throwable) { error = failure } }
            test.waitForIdleSync(); error?.let { throw it }
        }
        fun await(message: String, condition: () -> Boolean) {
            repeat(600) {
                var ready = false
                ui { ready = condition() }
                if (ready) return
                Thread.sleep(100)
            }
            error(message)
        }
        fun descendants(view: View): List<View> = listOf(view) + ((view as? ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) }
        } ?: emptyList())
        fun views() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
        fun text(value: String) = views().filterIsInstance<TextView>().firstOrNull { it.text.toString() == value }
        fun key(code: Int) { test.sendKeyDownUpSync(code); test.waitForIdleSync() }
        fun choose(label: String) {
            await("Picker entry missing: $label") { views().filterIsInstance<ListView>().any { list ->
                (0 until list.count).any { list.getItemAtPosition(it).toString() == label }
            } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            ui {
                val list = views().filterIsInstance<ListView>().single { list ->
                    (0 until list.count).any { list.getItemAtPosition(it).toString() == label }
                }
                list.requestFocus()
                list.setSelection((0 until list.count).single { list.getItemAtPosition(it).toString() == label })
            }
            key(KeyEvent.KEYCODE_BUTTON_A)
        }
        var fixture: File? = null
        try {
            await("Game did not start") { call("nativeStatus") == 2 && field("currentGame") != null }
            val game = field("currentGame") as DosLibrary.Game
            val config = File(test.targetContext.filesDir, "saves/${game.id}/staging/launch.conf")
            val original = config.readText()
            val root = File(Regex("(?im)^mount C \"([^\"]+)\"").find(original)!!.groupValues[1])
            val folder = File(root, "K25TEST")
            check(!folder.exists()) { "Refusing to overwrite an existing fixture directory" }
            check(folder.mkdir()); fixture = folder
            check(!File(root, "K25RES.TXT").exists())
            File(folder, "RUN.BAT").writeText("@echo off\r\necho bat>K25RES.TXT\r\n")
            File(folder, "RUN.CMD").writeText("@echo off\r\necho cmd>K25RES.TXT\r\n")
            File(folder, "RUN.COM").writeBytes(binary(false))
            File(folder, "RUN.EXE").writeBytes(binary(true))
            File(folder, "IGNORE.TXT").writeText("not executable")
            val preferences = test.targetContext.getSharedPreferences("kairodos", 0).all.toMap()
            fun browse() {
                ui {
                    call("endSessionForLibrary", {
                        call("showLibrary")
                        @Suppress("UNCHECKED_CAST")
                        val screen = (field("libraryFlow") as com.mrjackspade.kairo.frontend.LibraryFlow<*>).screen as com.mrjackspade.kairo.frontend.LibraryScreen<DosLibrary.Game>
                        screen.openDetail(game)
                    })
                }
                await("Command launch button missing") {
                    views().any { it.isShown && it.contentDescription == "DOS prompt or run program" }
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                ui { check(views().any { it.hasFocus() && it.contentDescription == "DOS prompt or run program" }) }
                key(KeyEvent.KEYCODE_BUTTON_A)
                await("Program picker did not open") { field("programPicker") != null }
            }
            browse()
            val promptGeneration = field("launchGeneration")
            choose("Prompt")
            await("Prompt did not resume") { field("programPicker") == null && call("getUserPaused") == false }
            check(field("launchGeneration") == promptGeneration) { "Prompt restarted instead of resuming the mounted session" }
            check(config.readText().substringAfter("[autoexec]").substringBefore("[sdl]")
                .lineSequence().none { it.trim().lowercase().endsWith(".exe") || it.trim().lowercase().endsWith(".bat") })
            // Closing the browser leaves an ordinary, responsive prompt.
            browse()
            ui {
                (field("secondaryDisplay") as com.mrjackspade.kairo.frontend.SecondaryDisplayCoordinator).forwardBack()
                check(field("programPicker") == null) { "Bottom-display Back bypassed the program picker" }
            }
            await("Cancelling picker left the core paused") { field("programPicker") == null && call("getUserPaused") == false }
            for (extension in listOf("BAT", "CMD", "COM", "EXE")) {
                browse()
                choose("C:/")
                ui { check(views().filterIsInstance<ListView>().none { list ->
                    (0 until list.count).any { list.getItemAtPosition(it).toString() == "Prompt" }
                }) { "Prompt must only appear at the root" } }
                choose("K25TEST/")
                ui { check(text("IGNORE.TXT") == null) }
                choose("RUN.$extension")
                val result = File(folder, "K25RES.TXT")
                await("Selected $extension did not run in its own directory") { result.isFile }
                check(result.readText().trim() == extension.lowercase()) { result.readText() }
                check(result.delete())
                check(field("programPicker") == null)
                check(config.readText().contains("cd C:\\K25TEST"))
            }
            ui { call("launch", game, false) }
            await("Normal Play was not restored") { call("nativeStatus") == 2 && config.readText() == original }
            check(test.targetContext.getSharedPreferences("kairodos", 0).all == preferences)
        } finally {
            ui { activity.finish() }
            await("Session did not stop") { call("nativeStatus") in listOf(0, 3) }
            fixture?.let { check(it.deleteRecursively()) }
        }
    }

    // Tiny real-mode DOS executable: create K25RES.TXT in the working directory,
    // write its format name, close, and terminate. EXE uses a minimal MZ header.
    private fun binary(exe: Boolean): ByteArray {
        val code = mutableListOf<Int>()
        fun emit(vararg bytes: Int) { code.addAll(bytes.toList()) }
        emit(0x0e, 0x1f, 0xba, 0, 0, 0x31, 0xc9, 0xb4, 0x3c, 0xcd, 0x21,
            0x89, 0xc3, 0xba, 0, 0, 0xb9, 3, 0, 0xb4, 0x40, 0xcd, 0x21,
            0xb4, 0x3e, 0xcd, 0x21, 0xb8, 0, 0x4c, 0xcd, 0x21)
        val base = if (exe) 0 else 0x100
        val name = base + code.size
        emit(*"K25RES.TXT\u0000".map(Char::code).toIntArray())
        val payload = base + code.size
        emit(*(if (exe) "exe" else "com").map(Char::code).toIntArray())
        code[3] = name and 255; code[4] = name shr 8
        code[14] = payload and 255; code[15] = payload shr 8
        val bytes = code.map(Int::toByte).toByteArray()
        if (!exe) return bytes
        val header = ByteArray(32)
        fun word(offset: Int, value: Int) { header[offset] = value.toByte(); header[offset + 1] = (value shr 8).toByte() }
        word(0, 0x5a4d); word(2, header.size + bytes.size); word(4, 1); word(8, 2)
        word(10, 0x1000); word(12, 0xffff); word(16, 0xfffe); word(24, 0x1c)
        return header + bytes
    }
}
