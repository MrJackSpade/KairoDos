package com.mrjackspade.kairodos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mrjackspade.kairo.frontend.GuestKeyboardPanel
import com.mrjackspade.kairo.frontend.InputRouter
import com.mrjackspade.kairo.frontend.MouseInputRouter
import com.mrjackspade.kairo.frontend.PixelTextView
import com.mrjackspade.kairo.frontend.TouchInputSettingsDialog
import com.mrjackspade.kairo.frontend.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** DOS library and session UI. Pure is compiled in :backend-dos and hosted via JNI. */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private external fun nativeRun(path: String, saveDir: String, systemDir: String): Boolean
    private external fun nativeStop()
    private external fun nativePause(value: Boolean)
    private external fun nativeReset()
    private external fun nativeStatus(): Int
    private external fun nativeAudioRate(): Int
    private external fun nativeAspect(): Double
    private external fun nativeLastError(): String
    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeKey(code: Int, down: Boolean)
    private external fun nativeJoypad(id: Int, down: Boolean)
    private external fun nativeMouseMove(dx: Int, dy: Int)
    private external fun nativeMouseButton(button: Int, down: Boolean)
    private external fun nativePointer(x: Int, y: Int, pressed: Boolean)
    private external fun nativeConfigure(mouseMode: Int, cyclesMode: Int)
    private external fun nativeReadAudio(buffer: ShortArray, maxFrames: Int): Int

    companion object {
        private const val PICK_GAME = 1001
        private val EXTENSIONS = setOf("zip", "dosz", "exe", "com", "bat", "iso", "chd",
            "img", "ima", "vhd", "jrc")
        init { System.loadLibrary("kairodos_host") }
    }

    private data class Game(val id: String, val title: String, val fileName: String) {
        fun file(root: File) = File(File(root, "games/$id"), fileName)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("kairodos", MODE_PRIVATE) }
    private val keys = InputRouter(::nativeKey, 341)
    private val mouse = MouseInputRouter(::nativeMouseMove, ::nativeMouseButton)
    private var games = mutableListOf<Game>()
    private var currentGame: Game? = null
    private var gameThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var stopAudio = false
    private var surface: SurfaceView? = null
    private var videoFrame: FrameLayout? = null
    private var keyboard: GuestKeyboardPanel? = null
    private var statusLabel: TextView? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFingers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        games = readGames()
        showLibrary()
    }

    private fun readGames(): MutableList<Game> {
        val result = mutableListOf<Game>()
        val stored = JSONArray(preferences.getString("games", "[]"))
        for (index in 0 until stored.length()) {
            val item = stored.optJSONObject(index) ?: continue
            val game = Game(item.optString("id"), item.optString("title"), item.optString("file"))
            if (game.id.isNotEmpty() && game.file(filesDir).isFile) result.add(game)
        }
        return result
    }

    private fun saveGames() {
        val array = JSONArray()
        games.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("file", it.fileName)) }
        preferences.edit().putString("games", array.toString()).apply()
    }

    private fun showLibrary() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentGame = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 24),
                Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 16))
        }
        page.addView(PixelTextView(this).apply {
            text = "KAIRODOS"
            color = Ui.ACCENT
            scale = 2
        })
        page.addView(Ui.text(this, "DOSBox Pure · DOS library", Ui.BODY, Ui.TEXT_MUTED))
        page.addView(Ui.primaryButton(this, "Add DOS game") { pickGame() },
            LinearLayout.LayoutParams(-1, Ui.dp(this, 52)).apply {
                topMargin = Ui.dp(this@MainActivity, 24)
            })
        page.addView(Ui.secondaryButton(this, "Input and machine settings") { showSettings() },
            LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply {
                topMargin = Ui.dp(this@MainActivity, 10)
            })
        page.addView(Ui.sectionLabel(this, "MY GAMES"))
        if (games.isEmpty()) page.addView(Ui.text(this,
            "Choose a DOS game ZIP, DOSZ, executable, or disk image. Files are copied into this app; no games or operating system are included.",
            Ui.BODY, Ui.TEXT_MUTED))
        for (game in games) {
            val row = Ui.actionRow(this, game.title, { game.fileName }) { launch(game) }
            page.addView(row.view)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(page)
        })
    }

    private fun pickGame() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PICK_GAME)
    }

    @Deprecated("The platform Activity uses onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_GAME || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        var name = "game.zip"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            cursor: Cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        val safeName = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ext = safeName.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) {
            AlertDialog.Builder(this).setMessage("Unsupported file type: .$ext. Select a ZIP, DOSZ, DOS executable, or self-contained disk image.")
                .setPositiveButton("OK", null).show()
            return
        }
        val id = UUID.randomUUID().toString()
        val game = Game(id, safeName.substringBeforeLast('.'), safeName)
        Thread {
            val file = game.file(filesDir)
            try {
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { source ->
                    file.outputStream().use { target -> source.copyTo(target) }
                } ?: error("Could not open selected file")
                runOnUiThread {
                    games.add(game)
                    saveGames()
                    showLibrary()
                }
            } catch (e: Exception) {
                file.delete()
                runOnUiThread {
                    AlertDialog.Builder(this).setMessage("Import failed: ${e.message}")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun launch(game: Game) {
        if (!game.file(filesDir).isFile) { showLibrary(); return }
        val oldThread = gameThread
        nativeStop()
        currentGame = game
        showGame()
        val saveDir = File(filesDir, "saves/${game.id}").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        nativeConfigure(if (preferences.getBoolean("direct_touch", false)) 1 else 0,
            preferences.getInt("cycles_mode", 0))
        gameThread = Thread {
            oldThread?.join()
            val success = nativeRun(game.file(filesDir).absolutePath, saveDir.absolutePath,
                systemDir.absolutePath)
            runOnUiThread {
                if (currentGame?.id == game.id) {
                    stopAudio = true
                    if (!success) AlertDialog.Builder(this)
                        .setMessage(nativeLastError().ifBlank { "DOSBox Pure could not start this game." })
                        .setPositiveButton("OK", null).show()
                    showLibrary()
                }
            }
        }.apply { name = "KairoDos-emulation"; start() }
        pollSession(game.id)
    }

    private fun showGame() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val game = currentGame ?: return
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG)
        }
        toolbar.addView(Ui.secondaryButton(this, "Library") { leaveGame() },
            LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        toolbar.addView(Ui.secondaryButton(this, "Keyboard") {
            keyboard?.let { if (it.visibility == View.VISIBLE) it.close()
                else it.visibility = View.VISIBLE }
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        toolbar.addView(Ui.secondaryButton(this, "Menu") { showGameMenu() },
            LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        page.addView(toolbar)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame = frame
        val display = SurfaceView(this)
        surface = display
        frame.addView(display, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
        display.holder.addCallback(this)
        display.setOnTouchListener(::handleGameTouch)
        page.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        statusLabel = Ui.text(this, "Starting ${game.title}…", Ui.SECONDARY, Ui.TEXT_MUTED)
        page.addView(statusLabel, LinearLayout.LayoutParams(-1, Ui.dp(this, 32)))
        val root = FrameLayout(this)
        root.addView(page)
        lateinit var panel: GuestKeyboardPanel
        panel = GuestKeyboardPanel(this, keys, DosKeyboardLayout.value, { panel.close() },
            mouse = mouse, mouseReferenceSize = { 640 to 400 })
        keyboard = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, Ui.dp(this, 280), Gravity.BOTTOM))
        setContentView(root)
    }

    private fun pollSession(id: String) {
        handler.postDelayed({
            if (currentGame?.id != id) return@postDelayed
            when (nativeStatus()) {
                1 -> statusLabel?.text = "Loading DOSBox Pure…"
                2 -> {
                    statusLabel?.text = "${currentGame?.title ?: "Game"} · running"
                    if (audioThread == null || audioThread?.isAlive != true) startAudio()
                    updateSurfaceLayout()
                }
            }
            pollSession(id)
        }, 250)
    }

    private fun updateSurfaceLayout() {
        val frame = videoFrame ?: return
        val display = surface ?: return
        if (frame.width <= 0 || frame.height <= 0) return
        val ratio = nativeAspect().takeIf { it in 0.5..3.0 } ?: 4.0 / 3.0
        var width = frame.width
        var height = (width / ratio).roundToInt()
        if (height > frame.height) { height = frame.height; width = (height * ratio).roundToInt() }
        val params = display.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height) {
            display.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun handleGameTouch(view: View, event: MotionEvent): Boolean {
        if (preferences.getInt("touch_mode", 0) == 1) {
            if (event.actionMasked == MotionEvent.ACTION_UP) keyboard?.visibility = View.VISIBLE
            return true
        }
        val direct = preferences.getBoolean("direct_touch", false)
        if (direct) {
            val x = ((event.x / view.width.coerceAtLeast(1)) * 65534 - 32767).roundToInt()
            val y = ((event.y / view.height.coerceAtLeast(1)) * 65534 - 32767).roundToInt()
            nativePointer(x, y, event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; moved = false; twoFingers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingers = true
                mouse.hold("touch-right", "rightButton")
            }
            MotionEvent.ACTION_MOVE -> if (!twoFingers) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) + abs(dy) > Ui.dp(this, 3)) moved = true
                mouse.moveBy(dx.roundToInt(), dy.roundToInt())
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> mouse.release("touch-right")
            MotionEvent.ACTION_UP -> {
                mouse.release("touch-right")
                if (!moved && !twoFingers) {
                    mouse.hold("touch-tap", "leftButton")
                    handler.postDelayed({ mouse.release("touch-tap") }, 90)
                }
            }
            MotionEvent.ACTION_CANCEL -> mouse.releasePrefix("touch-")
        }
        return true
    }

    private fun showGameMenu() {
        val labels = arrayOf("Resume", "Reset DOS", "Input settings", "Mute audio", "Return to library")
        AlertDialog.Builder(this).setItems(labels) { _, which -> when (which) {
            1 -> nativeReset()
            2 -> showSettings()
            3 -> {
                val muted = !preferences.getBoolean("muted", false)
                preferences.edit().putBoolean("muted", muted).apply()
                audio?.setVolume(if (muted) 0f else 1f)
            }
            4 -> leaveGame()
        } }.show()
    }

    private fun showSettings() {
        val modeNames = listOf("Mouse", "Keyboard")
        val dialog = TouchInputSettingsDialog.builder(this, TouchInputSettingsDialog.Options(
            title = "DOS touch input",
            modeLabels = modeNames,
            modeIndex = preferences.getInt("touch_mode", 0).coerceIn(0, 1),
            directTouch = preferences.getBoolean("direct_touch", false),
            directTouchExplanation = "Touchpad moves the DOS mouse by dragging. Direct tap positions it at your finger; some games require relative movement.",
            onSave = { mode, direct, _ ->
                preferences.edit().putInt("touch_mode", mode).putBoolean("direct_touch", direct).apply()
                nativeConfigure(if (direct) 1 else 0, preferences.getInt("cycles_mode", 0))
            }
        )).setNeutralButton("CPU speed") { _, _ -> showCpuSettings() }.create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun showCpuSettings() {
        val selected = preferences.getInt("cycles_mode", 0).coerceIn(0, 1)
        AlertDialog.Builder(this).setTitle("DOS CPU performance")
            .setSingleChoiceItems(arrayOf("Auto", "Maximum"), selected) { dialog, value ->
                preferences.edit().putInt("cycles_mode", value).apply()
                nativeConfigure(if (preferences.getBoolean("direct_touch", false)) 1 else 0, value)
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startAudio() {
        stopAudio = false
        audioThread = Thread {
            val rate = nativeAudioRate().coerceIn(8000, 96000)
            val minimum = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return@Thread
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            audio = track
            track.setVolume(if (preferences.getBoolean("muted", false)) 0f else 1f)
            track.play()
            val buffer = ShortArray(4096)
            try {
                while (!stopAudio && nativeStatus() == 2) {
                    val frames = nativeReadAudio(buffer, buffer.size / 2)
                    if (frames > 0) track.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    else Thread.sleep(4)
                }
            } finally {
                track.stop()
                track.release()
                audio = null
            }
        }.apply { name = "KairoDos-audio"; start() }
    }

    private fun leaveGame() {
        nativeStop()
        stopAudio = true
        keys.releaseAll()
        mouse.releasePrefix("touch-")
        keyboard?.close()
        nativeSetSurface(null)
        currentGame = null
        showLibrary()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { nativeSetSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        nativeSetSurface(holder.surface)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { nativeSetSurface(null) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentGame != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (keyboard?.visibility == View.VISIBLE) keyboard?.close() else leaveGame()
                return true
            }
            if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
                mapJoypad(keyCode)?.let { nativeJoypad(it, true); return true }
            }
            mapKey(keyCode)?.let { keys.hold("physical:$keyCode", listOf(it)); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (currentGame != null) {
            if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
                mapJoypad(keyCode)?.let { nativeJoypad(it, false); return true }
            }
            mapKey(keyCode)?.let { keys.release("physical:$keyCode"); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (currentGame != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { abs(it) > .1f }
                ?: event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { abs(it) > .1f }
                ?: event.getAxisValue(MotionEvent.AXIS_Y)
            nativeJoypad(6, x < -.5f); nativeJoypad(7, x > .5f)
            nativeJoypad(4, y < -.5f); nativeJoypad(5, y > .5f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun mapJoypad(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_B -> 0
        KeyEvent.KEYCODE_BUTTON_Y -> 1
        KeyEvent.KEYCODE_BUTTON_SELECT -> 2
        KeyEvent.KEYCODE_BUTTON_START -> 3
        KeyEvent.KEYCODE_DPAD_UP -> 4
        KeyEvent.KEYCODE_DPAD_DOWN -> 5
        KeyEvent.KEYCODE_DPAD_LEFT -> 6
        KeyEvent.KEYCODE_DPAD_RIGHT -> 7
        KeyEvent.KEYCODE_BUTTON_A -> 8
        KeyEvent.KEYCODE_BUTTON_X -> 9
        KeyEvent.KEYCODE_BUTTON_L1 -> 10
        KeyEvent.KEYCODE_BUTTON_R1 -> 11
        else -> null
    }

    private fun mapKey(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + code - KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + code - KeyEvent.KEYCODE_0
        KeyEvent.KEYCODE_ENTER -> 13
        KeyEvent.KEYCODE_DEL -> 8
        KeyEvent.KEYCODE_ESCAPE -> 27
        KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_SPACE -> 32
        KeyEvent.KEYCODE_APOSTROPHE -> 39
        KeyEvent.KEYCODE_BACKSLASH -> 92
        KeyEvent.KEYCODE_COMMA -> 44
        KeyEvent.KEYCODE_EQUALS -> 61
        KeyEvent.KEYCODE_GRAVE -> 96
        KeyEvent.KEYCODE_LEFT_BRACKET -> 91
        KeyEvent.KEYCODE_MINUS -> 45
        KeyEvent.KEYCODE_PERIOD -> 46
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 93
        KeyEvent.KEYCODE_SEMICOLON -> 59
        KeyEvent.KEYCODE_SLASH -> 47
        KeyEvent.KEYCODE_FORWARD_DEL -> 127
        KeyEvent.KEYCODE_INSERT -> 277
        KeyEvent.KEYCODE_MOVE_HOME -> 278
        KeyEvent.KEYCODE_MOVE_END -> 279
        KeyEvent.KEYCODE_PAGE_UP -> 280
        KeyEvent.KEYCODE_PAGE_DOWN -> 281
        KeyEvent.KEYCODE_DPAD_UP -> 273
        KeyEvent.KEYCODE_DPAD_DOWN -> 274
        KeyEvent.KEYCODE_DPAD_RIGHT -> 275
        KeyEvent.KEYCODE_DPAD_LEFT -> 276
        KeyEvent.KEYCODE_SHIFT_LEFT -> 304
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 303
        KeyEvent.KEYCODE_CTRL_LEFT -> 306
        KeyEvent.KEYCODE_CTRL_RIGHT -> 305
        KeyEvent.KEYCODE_ALT_LEFT -> 308
        KeyEvent.KEYCODE_ALT_RIGHT -> 307
        KeyEvent.KEYCODE_F1 -> 282
        KeyEvent.KEYCODE_F2 -> 283
        KeyEvent.KEYCODE_F3 -> 284
        KeyEvent.KEYCODE_F4 -> 285
        KeyEvent.KEYCODE_F5 -> 286
        KeyEvent.KEYCODE_F6 -> 287
        KeyEvent.KEYCODE_F7 -> 288
        KeyEvent.KEYCODE_F8 -> 289
        KeyEvent.KEYCODE_F9 -> 290
        KeyEvent.KEYCODE_F10 -> 291
        KeyEvent.KEYCODE_F11 -> 292
        KeyEvent.KEYCODE_F12 -> 293
        else -> null
    }

    override fun onStop() {
        keys.releaseAll()
        mouse.releasePrefix("touch-")
        nativePause(true)
        audio?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (currentGame != null) {
            nativePause(false)
            audio?.play()
        }
    }

    override fun onDestroy() {
        nativeStop()
        stopAudio = true
        nativeSetSurface(null)
        super.onDestroy()
    }
}
