package com.mrjackspade.kairodos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mrjackspade.kairo.frontend.GuestKeyboardPanel
import com.mrjackspade.kairo.frontend.InputRouter
import com.mrjackspade.kairo.frontend.MouseInputRouter
import com.mrjackspade.kairo.frontend.TouchInputSettingsDialog
import com.mrjackspade.kairo.frontend.Ui
import com.mrjackspade.kairo.frontend.LibraryScreen
import com.mrjackspade.kairo.frontend.LibraryStrings
import com.mrjackspade.kairo.frontend.SettingsEntry
import com.mrjackspade.kairo.frontend.SessionAction
import com.mrjackspade.kairo.frontend.SessionDrawer
import com.mrjackspade.kairo.frontend.GameSettingsRow
import com.mrjackspade.kairo.frontend.GameSettingsSheet
import com.mrjackspade.kairo.frontend.JoystickInputRouter
import com.mrjackspade.kairo.frontend.GamepadMapper
import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.OnScreenControls
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.EditText
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
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
        private const val PICK_FOLDER = 1001
        init { System.loadLibrary("kairodos_host") }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("kairodos", MODE_PRIVATE) }
    private val keys = InputRouter(::nativeKey, 341)
    private val mouse = MouseInputRouter(::nativeMouseMove, ::nativeMouseButton)
    private val joystick = JoystickInputRouter(::nativeJoypad,
        listOf("b", "y", "select", "start", "up", "down", "left", "right",
            "a", "x", "l1", "r1"))
    private val gamepad = GamepadMapper(keys, joystick, mouse,
        { action -> if (action == "menu") openMenu() }, {}, listOf(
            ControllerBinding("virtual:up", joystick = "up"),
            ControllerBinding("virtual:down", joystick = "down"),
            ControllerBinding("virtual:left", joystick = "left"),
            ControllerBinding("virtual:right", joystick = "right"),
            ControllerBinding("virtual:lsup", joystick = "up"),
            ControllerBinding("virtual:lsdown", joystick = "down"),
            ControllerBinding("virtual:lsleft", joystick = "left"),
            ControllerBinding("virtual:lsright", joystick = "right"),
            ControllerBinding("virtual:a", joystick = "a"),
            ControllerBinding("virtual:b", joystick = "b"),
            ControllerBinding("virtual:x", joystick = "x"),
            ControllerBinding("virtual:y", joystick = "y"),
            ControllerBinding("virtual:l1", joystick = "l1"),
            ControllerBinding("virtual:r1", joystick = "r1"),
            ControllerBinding("virtual:start", joystick = "start"),
            ControllerBinding("virtual:select", joystick = "select"),
            ControllerBinding("virtual:menu", action = "menu")
        ))
    private var onScreenControls: OnScreenControls? = null
    private val dosLibrary by lazy { DosLibrary(this) }
    private val catalog by lazy { DosGameCatalog(this) }
    private lateinit var libraryScreen: LibraryScreen<DosLibrary.Game>
    private var tree: Uri? = null
    private var scanCancelled = AtomicBoolean(false)
    private var prepareCancelled = AtomicBoolean(false)
    private var games = emptyList<DosLibrary.Game>()
    private var currentGame: DosLibrary.Game? = null
    private var gameThread: Thread? = null
    @Volatile private var launchGeneration = 0
    private var audioThread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var stopAudio = false
    private var surface: SurfaceView? = null
    private var videoFrame: FrameLayout? = null
    private var keyboard: GuestKeyboardPanel? = null
    private var statusLabel: TextView? = null
    private var sessionDrawer: SessionDrawer? = null
    private var userPaused = false
    private var menuSwipeX: Float? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFingers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        tree = preferences.getString("rom_tree", null)?.let(Uri::parse)
        libraryScreen = LibraryScreen(this, catalog, LibraryStrings("KAIRODOS",
            "Select DOS folder", "Choose where DOS games and ZIP archives are stored",
            "No DOS folder selected"), ::chooseFolder, { refreshLibrary(false) },
            { refreshLibrary(true) },
            { libraryScreen.showStatus("The game catalog is included in this build") },
            null, {}, settingsEntries(),
            { preferences.getString("last_played_entry", null) }, ::launch,
            ::previewGame, ::showGameDetails, {})
        games = tree?.let(dosLibrary::cached) ?: emptyList()
        showLibrary()
        if (tree != null) refreshLibrary(false)
    }

    private fun showLibrary() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentGame = null
        setContentView(libraryScreen)
        libraryScreen.showFolder(tree?.lastPathSegment)
        libraryScreen.showEntries(games)
        libraryScreen.showStatus(if (tree == null) "Choose a DOS folder to find games"
            else "${games.count { it.playable }} games ready")
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK_FOLDER)
    }

    @Deprecated("The platform Activity uses onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK) return
        val chosen = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            tree = chosen
            preferences.edit().putString("rom_tree", chosen.toString()).apply()
            games = emptyList()
            libraryScreen.showEntries(games)
            libraryScreen.showFolder(chosen.lastPathSegment)
            refreshLibrary(false)
        } catch (failure: Exception) {
            libraryScreen.showStatus("Cannot keep folder access: ${failure.message}")
        }
    }

    private fun refreshLibrary(forceHash: Boolean) {
        val selected = tree ?: run {
            libraryScreen.showStatus("Select a DOS folder first")
            return
        }
        scanCancelled.set(true)
        val cancelled = AtomicBoolean(false)
        scanCancelled = cancelled
        libraryScreen.showStatus(if (forceHash) "Rehashing DOS folder…" else "Scanning DOS folder…")
        Thread {
            try {
                val entries = dosLibrary.scan(selected, forceHash, cancelled) { message ->
                    runOnUiThread { if (!cancelled.get()) libraryScreen.showStatus(message) }
                }
                runOnUiThread {
                    if (!cancelled.get() && tree == selected) {
                        games = entries
                        libraryScreen.showEntries(games)
                        libraryScreen.showStatus("${games.count { it.playable }} games ready")
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread { if (!cancelled.get())
                    libraryScreen.showStatus("Scan failed: ${failure.message}") }
            }
        }.apply { name = "KairoDos-library-scan"; start() }
    }

    private fun previewGame(game: DosLibrary.Game) {
        val art = catalog.resolve(game.contentId ?: "", game.displayName).preview ?: return
        val bitmap = runCatching { catalog.openArtwork(art).use(BitmapFactory::decodeStream) }.getOrNull()
            ?: return
        AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(bitmap) })
            .setPositiveButton("Close", null).show()
    }

    private fun showGameDetails(entry: DosLibrary.Game) {
        val record = catalog.resolve(entry.contentId ?: "", entry.displayName)
        GameSettingsSheet.show(this, record.title, entry.playable, entry.contentId != null,
            listOf(
                "CONTROLS" to listOf(
                    GameSettingsRow("Touch input", if (preferences.getBoolean("direct_touch", false))
                        "Direct tap · Global" else "Touchpad · Global", false) { showSettings() }),
                "MACHINE" to listOf(
                    GameSettingsRow("DOS CPU speed", if (preferences.getInt("cycles_mode", 0) == 0)
                        "Auto · Global" else "Maximum · Global", false) { showCpuSettings() }),
                "LIBRARY" to listOf(
                    GameSettingsRow("Title", record.title, true) {
                        val input = EditText(this).apply { setText(record.title) }
                        AlertDialog.Builder(this).setTitle("Game title").setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                runCatching { catalog.setTitle(entry.contentId!!,
                                    input.text.toString().trim()) }
                                    .onSuccess { libraryScreen.showEntries(games) }
                                    .onFailure { Ui.message(this, it.message ?: "Could not save title") }
                            }.setNegativeButton("Cancel", null).show()
                    },
                    GameSettingsRow("View screenshot", if (record.preview == null)
                        "No screenshot available" else "Open full size", false) {
                        previewGame(entry)
                    },
                    GameSettingsRow("File information", "Path and content ID", false) {
                        AlertDialog.Builder(this).setTitle("File information")
                            .setMessage("${entry.path}\n\n${entry.contentId ?: entry.error ?: "Not hashed"}")
                            .setPositiveButton("Close", null).show()
                    })
            ), { launch(entry) }, entry.contentId?.let { id -> {{
                catalog.setTitle(id, null)
                libraryScreen.showEntries(games)
            }} }, { Ui.message(this, "Hash this game before saving settings") })
    }

    private fun launch(game: DosLibrary.Game) {
        val selected = tree ?: return
        if (!game.playable) return
        val generation = ++launchGeneration
        prepareCancelled.set(true)
        val cancelled = AtomicBoolean(false)
        prepareCancelled = cancelled
        preferences.edit().putString("last_played_entry", game.id).apply()
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
            var message: String? = null
            val success = try {
                val file = dosLibrary.prepare(game, selected, cancelled)
                if (generation != launchGeneration) return@Thread
                val started = nativeRun(file.absolutePath, saveDir.absolutePath,
                    systemDir.absolutePath)
                if (!started) message = nativeLastError().ifBlank {
                    "DOSBox Pure could not start this game."
                }
                started
            } catch (failure: Exception) {
                message = failure.message ?: "Could not prepare this game"
                false
            }
            runOnUiThread {
                if (generation == launchGeneration && currentGame?.id == game.id) {
                    leaveGame()
                    if (!success) AlertDialog.Builder(this).setMessage(message)
                        .setPositiveButton("OK", null).show()
                }
            }
        }.apply { name = "KairoDos-emulation"; start() }
        pollSession(game.id)
    }

    private fun settingsEntries() = listOf(
        SettingsEntry("Touch input", { if (preferences.getBoolean("direct_touch", false))
            "Direct tap" else "Touchpad" }, ::showSettings),
        SettingsEntry("DOS CPU speed", { if (preferences.getInt("cycles_mode", 0) == 0)
            "Auto" else "Maximum" }, ::showCpuSettings),
        SettingsEntry("On-screen controls", { "Button layout and visibility" }) {
            if (onScreenControls == null) Ui.message(this, "Open a game to arrange controls")
            else {
                closeMenu()
                onScreenControls?.show()
            }
        },
        SettingsEntry("Sound", { if (preferences.getBoolean("muted", false)) "Muted" else "On" }) {
            val muted = !preferences.getBoolean("muted", false)
            preferences.edit().putBoolean("muted", muted).apply()
            audio?.setVolume(if (muted) 0f else 1f)
            sessionDrawer?.refreshValues()
            libraryScreen.refreshSettingValues()
        }
    )

    private fun showGame() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val game = currentGame ?: return
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame = frame
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        val display = SurfaceView(this)
        surface = display
        frame.addView(display, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
        display.holder.addCallback(this)
        display.setOnTouchListener(::handleGameTouch)
        onScreenControls = OnScreenControls(this, root, gamepad, preferences) {
            onScreenControls?.refreshVisibility(currentGame != null &&
                sessionDrawer?.isOpen != true)
        }
        lateinit var panel: GuestKeyboardPanel
        panel = GuestKeyboardPanel(this, keys, DosKeyboardLayout.value, { panel.close() },
            mouse = mouse, mouseReferenceSize = { 640 to 400 })
        keyboard = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, Ui.dp(this, 280), Gravity.BOTTOM))
        sessionDrawer = SessionDrawer(this, root, "KAIRODOS", ::closeMenu, {}, listOf(
            SessionAction("Resume", com.mrjackspade.kairo.frontend.R.drawable.ic_play) {
                userPaused = false
                closeMenu()
            },
            SessionAction("Restart", com.mrjackspade.kairo.frontend.R.drawable.ic_restart) {
                nativeReset()
                closeMenu()
            },
            SessionAction("Library", com.mrjackspade.kairo.frontend.R.drawable.ic_library) {
                leaveGame()
            }
        ), listOf(
            SettingsEntry("Pause", { if (userPaused) "On" else "Off" }) {
                userPaused = !userPaused
                closeMenu()
            },
            SettingsEntry("Keyboard", { "Show the DOS keyboard" }) {
                closeMenu()
                panel.visibility = View.VISIBLE
            }
        ), settingsEntries())
        statusLabel = sessionDrawer?.status
        statusLabel?.text = "Starting ${game.title}…"
        setContentView(root)
        onScreenControls?.refreshVisibility(true)
    }

    private fun openMenu() {
        val game = currentGame ?: return
        if (sessionDrawer?.isOpen == true) return
        keyboard?.close()
        keys.releaseAll()
        mouse.releasePrefix("touch-")
        nativePause(true)
        onScreenControls?.refreshVisibility(false)
        sessionDrawer?.open(catalog.resolve(game.contentId ?: "", game.displayName).title,
            statusLabel?.text?.toString() ?: "DOS game")
    }

    private fun closeMenu() {
        sessionDrawer?.close()
        nativePause(userPaused)
        onScreenControls?.refreshVisibility(!userPaused)
        surface?.requestFocus()
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            menuSwipeX = event.x.takeIf { it <= Ui.dp(this, 28).toFloat() }
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            menuSwipeX != null && event.x - menuSwipeX!! > Ui.dp(this, 60)) {
            menuSwipeX = null
            openMenu()
            return true
        } else if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) menuSwipeX = null
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
        launchGeneration++
        prepareCancelled.set(true)
        nativeStop()
        stopAudio = true
        gamepad.releaseAll()
        keys.releaseAll()
        mouse.releasePrefix("touch-")
        onScreenControls?.refreshVisibility(false)
        onScreenControls?.close()
        onScreenControls = null
        keyboard?.close()
        sessionDrawer?.close()
        sessionDrawer = null
        userPaused = false
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
        if (currentGame == null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (libraryScreen.actionsOpen)
                    libraryScreen.moveActionSelection(1) else libraryScreen.moveSelection(1)
                KeyEvent.KEYCODE_DPAD_UP -> if (libraryScreen.actionsOpen)
                    libraryScreen.moveActionSelection(-1) else libraryScreen.moveSelection(-1)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    if (libraryScreen.actionsOpen) libraryScreen.activateAction()
                    else if (libraryScreen.detailOpen) libraryScreen.activateDetail()
                    else libraryScreen.activateSelection()
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE -> libraryScreen.openActions()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B ->
                    if (!libraryScreen.closeDetail()) libraryScreen.closeActions()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        if (currentGame != null) {
            if (onScreenControls?.isOpen == true) {
                if (onScreenControls?.handleKey(event) == true) return true
                return super.onKeyDown(keyCode, event)
            }
            if (sessionDrawer?.isOpen == true) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT ->
                        sessionDrawer?.focus((sessionDrawer?.selectedIndex ?: 0) + 1)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT ->
                        sessionDrawer?.focus((sessionDrawer?.selectedIndex ?: 0) - 1)
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                        sessionDrawer?.activateSelected()
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE -> closeMenu()
                    else -> return super.onKeyDown(keyCode, event)
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                openMenu()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (keyboard?.visibility == View.VISIBLE) keyboard?.close() else openMenu()
                return true
            }
            if (gamepad.key(event)) return true
            mapKey(keyCode)?.let { keys.hold("physical:$keyCode", listOf(it)); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (currentGame != null) {
            if (gamepad.key(event)) return true
            mapKey(keyCode)?.let { keys.release("physical:$keyCode"); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (currentGame != null && sessionDrawer?.isOpen != true && gamepad.motion(event)) return true
        return super.onGenericMotionEvent(event)
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
        scanCancelled.set(true)
        launchGeneration++
        prepareCancelled.set(true)
        nativeStop()
        stopAudio = true
        nativeSetSurface(null)
        super.onDestroy()
    }
}
