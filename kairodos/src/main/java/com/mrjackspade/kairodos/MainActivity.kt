package com.mrjackspade.kairodos

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
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
import com.mrjackspade.kairo.frontend.InputModeDecider
import com.mrjackspade.kairo.frontend.MouseInputRouter
import com.mrjackspade.kairo.frontend.TouchInputSettingsDialog
import com.mrjackspade.kairo.frontend.Ui
import com.mrjackspade.kairo.frontend.LibraryScreen
import com.mrjackspade.kairo.frontend.LibraryFlow
import com.mrjackspade.kairo.frontend.ExternalGameIntent
import com.mrjackspade.kairo.frontend.FirstRunScreen
import com.mrjackspade.kairo.frontend.FrontendNavigation
import com.mrjackspade.kairo.frontend.FrontendBackCoordinator
import com.mrjackspade.kairo.frontend.ControllerDeviceMonitor
import com.mrjackspade.kairo.frontend.ImmersiveWindow
import com.mrjackspade.kairo.frontend.EdgeSwipeNavigation
import com.mrjackspade.kairo.frontend.GameSettingScope
import com.mrjackspade.kairo.frontend.LibraryStrings
import com.mrjackspade.kairo.frontend.SettingsEntry
import com.mrjackspade.kairo.frontend.SessionAction
import com.mrjackspade.kairo.frontend.SessionDrawer
import com.mrjackspade.kairo.frontend.SessionFlow
import com.mrjackspade.kairo.frontend.SessionStatusDialog
import com.mrjackspade.kairo.frontend.GameSettingsRow
import com.mrjackspade.kairo.frontend.GameSettingsSheet
import com.mrjackspade.kairo.frontend.GameSettingsResetDialog
import com.mrjackspade.kairo.frontend.JoystickInputRouter
import com.mrjackspade.kairo.frontend.GamepadMapper
import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerEditor
import com.mrjackspade.kairo.frontend.ControllerProfileStore
import com.mrjackspade.kairo.frontend.CatalogUpdateController
import com.mrjackspade.kairo.frontend.PhysicalControllerBinding
import com.mrjackspade.kairo.frontend.PhysicalControllerBindings
import com.mrjackspade.kairo.frontend.OnScreenControls
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.EditText
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** DOS library and session UI. Pure is compiled in :backend-dos and hosted via JNI. */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private external fun nativeRun(path: String, saveDir: String, systemDir: String,
                                   enterSoloRoot: Boolean, useOutsideConf: Boolean): Boolean
    private external fun nativeStop()
    private external fun nativePause(value: Boolean)
    private external fun nativeReset()
    private external fun nativeSaveState(path: String): Int
    private external fun nativeLoadState(path: String): Int
    private external fun nativeStatus(): Int
    private external fun nativeInputTelemetry(): LongArray
    private external fun nativeAudioRate(): Int
    private external fun nativeAspect(): Double
    private external fun nativeVideoWidth(): Int
    private external fun nativeVideoHeight(): Int
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
    private val gameSettings by lazy { GameSettingScope(preferences) }
    private val controllerProfiles by lazy {
        ControllerProfileStore(preferences, DosControllerBindings::parse,
            { DosControllerBindings.toJson(it).toString() })
    }
    private val keys = InputRouter(::nativeKey, 341)
    private val inputModeDecider = InputModeDecider()
    private val mouse = MouseInputRouter(::nativeMouseMove, ::nativeMouseButton)
    private val joystick = JoystickInputRouter(::nativeJoypad,
        DosControllerBindings.joystick)
    private val gamepad = GamepadMapper(keys, joystick, mouse,
        ::controllerAction, {}, DosControllerBindings.defaults())
    private var onScreenControls: OnScreenControls? = null
    private val dosLibrary by lazy { DosLibrary(this) }
    private val catalog by lazy { DosGameCatalog(this) }
    private val catalogUpdates by lazy {
        CatalogUpdateController(this, catalog::downloadUpdate, libraryScreen::showStatus,
            { libraryScreen.showEntries(games) }, {
                if (libraryScreen.visibility == View.VISIBLE)
                    android.widget.Toast.makeText(this, "Game catalog updated",
                        android.widget.Toast.LENGTH_SHORT).show()
            })
    }
    private lateinit var libraryFlow: LibraryFlow<DosLibrary.Game>
    private lateinit var firstRunScreen: FirstRunScreen
    private val controllerDevices by lazy { ControllerDeviceMonitor(this, gamepad) }
    private val backCoordinator by lazy {
        FrontendBackCoordinator(this, listOf(
            {
                if (!firstRunScreen.isOpen) false else {
                    firstRunScreen.back()
                    true
                }
            },
            {
                if (onScreenControls?.isOpen != true) false else {
                    onScreenControls?.back()
                    true
                }
            },
            {
                if (!controllerEditor.isOpen) false else {
                    controllerEditor.back()
                    true
                }
            },
            {
                if (libraryScreen.visibility != View.VISIBLE) false else {
                    when {
                        libraryScreen.closeDetail() -> Unit
                        libraryScreen.closeActions() -> Unit
                        currentGame != null -> resumeGameFromLibrary()
                        else -> finish()
                    }
                    true
                }
            },
            {
                if (sessionFlow?.isOpen != true) false else {
                    closeMenu()
                    true
                }
            },
            {
                if (keyboard?.visibility != View.VISIBLE) false else {
                    keyboard?.close()
                    true
                }
            }
        ), { if (currentGame != null) openMenu() else finish() })
    }
    private val libraryScreen: LibraryScreen<DosLibrary.Game> get() = libraryFlow.screen
    private lateinit var appRoot: FrameLayout
    private var gameRoot: FrameLayout? = null
    private lateinit var controllerEditor: ControllerEditor<DosLibrary.Game>
    private val tree: Uri? get() = libraryFlow.tree
    private var prepareCancelled = AtomicBoolean(false)
    private val games: List<DosLibrary.Game> get() = libraryFlow.entries
    private var currentGame: DosLibrary.Game? = null
    private var sessionFromFrontend = false
    private var installerPromptOpen = false
    private var finishAfterInstallerPrompt = false
    private var gameThread: Thread? = null
    private var stateBusy = false
    @Volatile private var launchGeneration = 0
    @Volatile private var externalLaunchGeneration = 0
    private var audioThread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var audioGeneration = 0
    private var surface: SurfaceView? = null
    private var videoFrame: FrameLayout? = null
    private var integerScaling = true
    private var integerCrop = false
    private var portraitNotchPadding = 0
    private var keyboard: GuestKeyboardPanel? = null
    private var statusLabel: TextView? = null
    private var loadingStatus: TextView? = null
    private var sessionGameTitle: String? = null
    private var sessionDrawer: SessionDrawer? = null
    private var sessionFlow: SessionFlow? = null
    private var userPaused = false
    private val edgeSwipes by lazy { EdgeSwipeNavigation(resources.displayMetrics.density) }
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFingers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadGraphicsSettings()
        ImmersiveWindow.apply(this)
        appRoot = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(appRoot)
        val libraryPage = LibraryScreen(this, catalog, LibraryStrings("KAIRODOS",
            "Select DOS folder", "Choose a writable folder for DOS games and ZIP archives",
            "No DOS folder selected"), ::chooseFolder, { refreshLibrary(false) },
            { refreshLibrary(true) },
            { catalogUpdates.check(false) },
            null, {}, settingsEntries(),
            { preferences.getString("last_played_entry", null) }, ::launch,
            ::previewGame, ::showGameDetails, {})
        libraryFlow = LibraryFlow(this, preferences, libraryPage, PICK_FOLDER,
            dosLibrary::cached, dosLibrary::scan,
            { uri -> uri.lastPathSegment ?: "DOS folder" },
            "Choose a DOS folder to find games",
            "DOS folder needs read and write access. Select it again.",
            { found ->
                val errors = found.count { it.error != null }
                "${found.count { it.playable }} games" +
                    (if (errors == 0) "" else " · $errors unreadable") +
                    " · ${dosLibrary.hashCount} hashes this scan"
            },
            onFolderSelected = { finishFirstRun() },
            onFolderError = { message -> if (firstRunScreen.isOpen) Ui.message(this, message) },
            writable = true)
        appRoot.addView(libraryPage, FrameLayout.LayoutParams(-1, -1))
        firstRunScreen = FirstRunScreen(this)
        appRoot.addView(firstRunScreen, FrameLayout.LayoutParams(-1, -1))
        onScreenControls = OnScreenControls(this, appRoot, gamepad, preferences,
            ::refreshControllerUi)
        controllerEditor = ControllerEditor(this, appRoot,
            ::loadControllerBindings, ::saveControllerBindings, ::resetControllerBindings,
            ::physicalControllerBindings, ::savePhysicalControllerBindings,
            ::resetPhysicalControllerBindings,
            { gamepad.deadZone }, { value ->
                gamepad.deadZone = value
                controllerProfiles.deadZone = value
            }, ::refreshControllerUi, ::showOnScreenControls,
            { onScreenControls?.eightWayDpad ?: false },
            { onScreenControls?.eightWayDpad = it },
            DosLibrary.Game::id, DosControllerBindings.spec,
            { DosControllerBindings.toJson(it) })
        gamepad.physicalBindings = physicalControllerBindings()
        gamepad.bindings = globalControllerBindings()
        gamepad.deadZone = controllerProfiles.deadZone
        controllerDevices.register(handler)
        libraryFlow.restore()
        showLibrary()
        backCoordinator.register()
        val externallyRequested = savedInstanceState == null &&
            (intent.data != null || intent.hasExtra("ROM"))
        if (externallyRequested)
            dispatchExternalGame(intent)
        else {
            if (tree != null) refreshLibrary(false)
            if (!preferences.getBoolean("onboarding_complete_v1", tree != null)) showFirstRun()
        }
        catalogUpdates.check(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchExternalGame(intent)
    }

    private fun dispatchExternalGame(intent: Intent) {
        if (::firstRunScreen.isInitialized) closeFirstRun()
        val request = try { ExternalGameIntent.file(intent, contentResolver) }
        catch (failure: Exception) {
            libraryScreen.showStatus(failure.message ?: "Invalid game file")
            return
        } ?: return
        val matching = games.firstOrNull {
            ExternalGameIntent.sameDocument(Uri.parse(it.uri), request.uri)
        }
        if (matching != null) { launch(matching, true); return }
        val generation = ++externalLaunchGeneration
        libraryScreen.showStatus("Opening ${request.name}…")
        Thread {
            val inspected = runCatching { dosLibrary.inspectExternal(request, AtomicBoolean(false)) }
            runOnUiThread {
                if (generation != externalLaunchGeneration || isDestroyed) return@runOnUiThread
                inspected.onSuccess { launch(it, true) }.onFailure { failure ->
                    libraryScreen.showStatus("Could not open ${request.name}: ${failure.message}")
                }
            }
        }.apply { name = "KairoDos-external-game"; start() }
    }

    private fun showLibrary() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentGame = null
        gameRoot?.let(appRoot::removeView)
        gameRoot = null
        libraryScreen.visibility = View.VISIBLE
        libraryFlow.show()
    }

    private fun openLibraryOverGame() {
        if (currentGame == null) return
        if (sessionFromFrontend) { leaveGame(); return }
        edgeSwipes.reset()
        libraryScreen.visibility = View.VISIBLE
        refreshControllerUi()
        sessionFlow?.reset()
        keyboard?.close()
        releaseGuestInputs()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        libraryFlow.show()
    }

    private fun resumeGameFromLibrary() {
        libraryScreen.dismissSystemKeyboard()
        libraryScreen.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshControllerUi()
        surface?.requestFocus()
    }

    private fun chooseFolder() = libraryFlow.chooseFolder()

    private fun showFirstRun() {
        firstRunScreen.show(FirstRunScreen.Page("KAIRODOS", "SETUP  ·  1 OF 1",
            "Choose a DOS folder",
            "Select a writable folder containing your DOS games and ZIP archives. You can add one later from the library.",
            listOf(
                FirstRunScreen.Action("Select DOS folder", "Find games on this device",
                    primary = true, onClick = ::chooseFolder),
                FirstRunScreen.Action("Skip for now", "Open the game library",
                    onClick = ::finishFirstRun)
            )), ::finishFirstRun)
    }

    private fun finishFirstRun() {
        preferences.edit().putBoolean("onboarding_complete_v1", true).apply()
        closeFirstRun()
        if (tree != null) refreshLibrary(false)
    }

    private fun closeFirstRun() {
        firstRunScreen.close()
    }

    @Deprecated("Legacy Back path; API 33+ uses OnBackInvokedDispatcher")
    override fun onBackPressed() = backCoordinator.handle()

    @Deprecated("The platform Activity uses onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        libraryFlow.handleActivityResult(requestCode, resultCode, data)
    }

    private fun refreshLibrary(forceHash: Boolean) = libraryFlow.refresh(forceHash)

    private fun previewGame(game: DosLibrary.Game) {
        val art = catalog.resolve(game.contentId ?: "", game.displayName).preview ?: return
        val bitmap = runCatching { catalog.openArtwork(art).use(BitmapFactory::decodeStream) }.getOrNull()
            ?: return
        AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(bitmap) })
            .setPositiveButton("Close", null).show()
    }

    private fun artworkSettingsLabel(game: DosLibrary.Game, kind: String, path: String?): String {
        val source = catalog.sourceOf(game.contentId ?: "", game.displayName,
            "artwork", kind)
        return "${if (path == null) "None" else "Available"} · $source"
    }

    private fun editGameArtwork(game: DosLibrary.Game, kind: String) {
        val id = game.contentId ?: return
        val current = catalog.resolve(id, game.displayName)
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(if (kind == "boxArt") current.boxArt ?: "" else current.preview ?: "")
            hint = "art/catalog/dos/example.webp"
        }
        val label = if (kind == "boxArt") "Box art" else "Screenshot"
        val dialog = AlertDialog.Builder(this).setTitle(label)
            .setMessage("Use a packaged DOS artwork path. Missing art falls back to the game title.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val path = input.text.toString().trim().takeIf { it.isNotEmpty() }
                runCatching { catalog.setArtworkOverride(id, kind, path) }
                    .onSuccess { libraryScreen.showEntries(games) }
                    .onFailure { Ui.message(this, it.message ?: "Could not save artwork") }
            }.setNeutralButton("Reset $label") { _, _ ->
                runCatching { catalog.setArtworkOverride(id, kind, null) }
                    .onSuccess { libraryScreen.showEntries(games) }
                    .onFailure { Ui.message(this, it.message ?: "Could not reset artwork") }
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun showGameDetails(entry: DosLibrary.Game) {
        val record = catalog.resolve(entry.contentId ?: "", entry.displayName)
        val variants = record.launch?.configs?.keys.orEmpty()
        GameSettingsSheet.show(this, record.title, entry.playable, entry.contentId != null,
            listOf(
                "CONTROLS" to listOf(
                    GameSettingsRow("Controller", "Gamepad and on-screen controls", true) {
                        showControllerScope(entry)
                    },
                    GameSettingsRow("Touch input", touchSettingsLabel(entry),
                        true) { showGameTouchSettings(entry) }),
                "MACHINE" to listOf(
                    GameSettingsRow("DOS CPU speed", cpuSettingsLabel(entry),
                        true) { showGameCpuSettings(entry) }) +
                    (if (variants.size > 1) listOf(GameSettingsRow("Startup variant",
                        preferences.getString("launch_variant_${entry.contentId}", null)
                            ?: "Choose on first play", false) {
                        chooseLaunchVariant(entry, false)
                    }) else emptyList()) +
                    (if (DosLaunchConfig.needsPlayer(record.launch)) listOf(
                        GameSettingsRow("DOS player name",
                            preferences.getString("dos_player_${entry.contentId}", null)
                                ?: "Choose on first play", false) {
                            choosePlayerName(entry, null)
                        }) else emptyList()),
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
                    GameSettingsRow("Box art", artworkSettingsLabel(entry, "boxArt", record.boxArt),
                        true) { editGameArtwork(entry, "boxArt") },
                    GameSettingsRow("Screenshot", artworkSettingsLabel(entry, "preview", record.preview),
                        true) { editGameArtwork(entry, "preview") },
                    GameSettingsRow("View screenshot", if (record.preview == null)
                        "No screenshot available" else "Open full size", false) {
                        previewGame(entry)
                    },
                    GameSettingsRow("File information", "Path and content ID", false) {
                        AlertDialog.Builder(this).setTitle("File information")
                            .setMessage("${entry.path}\n\n${entry.contentId ?: entry.error ?: "Not hashed"}")
                            .setPositiveButton("Close", null).show()
                    }) + (if (!entry.external && !entry.rootFolder) listOf(
                    GameSettingsRow(if (entry.folder) "Delete game folder" else "Delete game file",
                        "Permanently remove from device storage",
                        false, destructive = true) { confirmDeleteGame(entry) }) else emptyList())
            ), { launch(entry) }, entry.contentId?.let { id -> {{
                GameSettingsResetDialog.show(this, {
                    catalog.resetOverrides(id)
                    gameSettings.clear(id, "touch_mode", "direct_touch", "cycles_mode")
                    preferences.edit().remove("controller_game_$id")
                        .remove("launch_variant_$id").remove("dos_player_$id").apply()
                    if (currentGame?.contentId == id) {
                        gamepad.bindings = loadControllerBindings(currentGame)
                        inputModeDecider.reset()
                        configureGuest()
                    }
                    libraryScreen.showEntries(games)
                }, { failure -> Ui.message(this,
                    failure.message ?: "Could not reset game settings") })
            }} }, { Ui.message(this, "Hash this game before saving settings") })
    }

    private fun launch(game: DosLibrary.Game, fromFrontend: Boolean = false) {
        val launch = catalog.resolve(game.contentId ?: "", game.displayName).launch
        val variants = launch?.configs?.keys.orEmpty()
        if (variants.size > 1) {
            val saved = preferences.getString("launch_variant_${game.contentId}", null)
            if (saved !in variants) {
                chooseLaunchVariant(game, true, fromFrontend)
                return
            }
            startGame(game, saved!!, fromFrontend)
        } else startGame(game, "dosbox.conf", fromFrontend)
    }

    private fun confirmDeleteGame(entry: DosLibrary.Game) {
        if (currentGame?.uri == entry.uri) {
            Ui.message(this, "Exit this game before deleting its source")
            return
        }
        val kind = if (entry.folder) "folder and everything inside it" else "file"
        val dialog = AlertDialog.Builder(this).setTitle("Permanently delete game $kind?")
            .setMessage("Delete ${entry.path} from device storage?\n\n" +
                "This permanently removes the $kind. This cannot be undone. " +
                "Game settings and saves are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                libraryScreen.showStatus("Deleting ${entry.path}…")
                Thread {
                    val result = runCatching { dosLibrary.deleteSource(entry) }
                    runOnUiThread {
                        result.onSuccess {
                            libraryScreen.closeDetail()
                            refreshLibrary(false)
                        }.onFailure { failure ->
                            Ui.message(this, failure.message ?: "Could not delete game file")
                        }
                    }
                }.apply { name = "KairoDos-delete-game"; start() }
            }.create()
        dialog.show()
        Ui.styleDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.DANGER)
    }

    private fun chooseLaunchVariant(game: DosLibrary.Game, play: Boolean,
                                    fromFrontend: Boolean = false) {
        val launch = catalog.resolve(game.contentId ?: "", game.displayName).launch ?: return
        val names = launch.configs.keys.sortedWith(compareBy<String> { it != "dosbox.conf" }
            .thenBy { it })
        AlertDialog.Builder(this).setTitle("Startup variant")
            .setItems(names.map { if (it == "dosbox.conf") "Default" else it }.toTypedArray())
            { _, index ->
                val selected = names[index]
                preferences.edit().putString("launch_variant_${game.contentId}", selected).apply()
                if (play) startGame(game, selected, fromFrontend)
                else showGameDetails(game)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startGame(game: DosLibrary.Game, configName: String,
                          fromFrontend: Boolean = false) {
        val selected = tree
        if (!game.external && selected == null) {
            libraryScreen.showStatus("Choose a DOS folder for this game")
            return
        }
        if (!game.playable) return
        val launch = catalog.resolve(game.contentId ?: "", game.displayName).launch
        val playerKey = "dos_player_${game.contentId}"
        if (DosLaunchConfig.needsPlayer(launch) && preferences.getString(playerKey, null) == null) {
            choosePlayerName(game, configName, fromFrontend)
            return
        }
        val playerName = preferences.getString(playerKey, null)
        val mountsParent = launch?.let { DosLaunchConfig.mountsParent(it, configName) } == true
        val availableGames = games.toList()
        val generation = ++launchGeneration
        prepareCancelled.set(true)
        val cancelled = AtomicBoolean(false)
        prepareCancelled = cancelled
        preferences.edit().putString("last_played_entry", game.id).apply()
        val oldThread = gameThread
        nativeStop()
        if (currentGame != null) {
            audioGeneration++
            runCatching { audio?.pause() }
            audioThread = null
            releaseGuestInputs()
            keyboard?.close()
            sessionFlow?.reset()
            onScreenControls?.close()
            gameRoot?.let(appRoot::removeView)
            gameRoot = null
            nativeSetSurface(null)
            surface = null
            videoFrame = null
            sessionFlow = null
            sessionDrawer = null
        }
        inputModeDecider.reset()
        userPaused = false
        currentGame = game
        sessionFromFrontend = fromFrontend
        gamepad.bindings = loadControllerBindings(game)
        showGame()
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        configureGuest()
        gameThread = Thread {
            oldThread?.join()
            var message: String? = null
            val success = try {
                val playableGame = if (game.installer) {
                    val progress: (String) -> Unit = { status ->
                        runOnUiThread {
                            if (generation == launchGeneration) loadingStatus?.text = status
                        }
                    }
                    if (game.external) dosLibrary.installExternal(game, cancelled, progress)
                    else dosLibrary.install(game, selected!!, cancelled, progress)
                } else game
                val saveDir = File(filesDir, "saves/${playableGame.id}")
                if (game.installer) {
                    val oldSaveDir = File(filesDir, "saves/${game.id}")
                    if (oldSaveDir.isDirectory && !saveDir.exists())
                        require(oldSaveDir.renameTo(saveDir)) {
                            "Could not move existing game saves to the installed archive"
                        }
                }
                require(saveDir.isDirectory || saveDir.mkdirs()) { "Could not create game save folder" }
                val dependencies = DosLaunchConfig.requiredFolders(launch, configName)
                    .associateWith { folder ->
                        val ids = catalog.contentIdsForFolder(folder).toSet()
                        val dependency = availableGames.firstOrNull { it.contentId in ids }
                            ?: error("This game also needs the $folder archive in your DOS library")
                        val dependencyFile = dosLibrary.prepare(dependency, selected, cancelled)
                        DosLaunchConfig.Dependency(dependencyFile, !dependency.folder &&
                            dosLibrary.hasOuterFolder(dependencyFile, folder))
                    }
                val file = dosLibrary.prepare(playableGame, selected, cancelled,
                    if (mountsParent) launch?.folder else null)
                if (generation != launchGeneration) return@Thread
                runOnUiThread {
                    if (generation == launchGeneration) loadingStatus?.text = "Starting DOSBox Pure…"
                }
                DosLaunchConfig.write(file, launch, configName, dependencies, playerName)
                if (game.installer && !game.external) runOnUiThread {
                    if (generation == launchGeneration) {
                        sessionGameTitle = catalog.resolve(playableGame.contentId ?: "",
                            playableGame.displayName).title
                        preferences.edit().putString("last_played_entry", playableGame.id).apply()
                        showInstallerRemovalPrompt(game, playableGame)
                    }
                }
                val enterOuterFolder = launch != null && !mountsParent && !playableGame.folder &&
                    dosLibrary.hasOuterFolder(file, launch.folder)
                val started = nativeRun(file.absolutePath, saveDir.absolutePath,
                    systemDir.absolutePath, enterOuterFolder, launch != null)
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
                    leaveGame(success)
                    if (!success) AlertDialog.Builder(this).setMessage(message)
                        .setPositiveButton("OK", null).show()
                }
            }
        }.apply { name = "KairoDos-emulation"; start() }
        pollSession(game.id)
    }

    private fun choosePlayerName(game: DosLibrary.Game, playConfig: String?,
                                 fromFrontend: Boolean = false) {
        val key = "dos_player_${game.contentId}"
        val input = EditText(this).apply {
            hint = "1–8 letters, digits, or underscores"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(preferences.getString(key, "PLAYER"))
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("DOS player name").setView(input)
            .setPositiveButton(if (playConfig == null) "Save" else "Play") { _, _ ->
                val name = input.text.toString().trim().uppercase(java.util.Locale.ROOT)
                if (name.matches(Regex("[A-Z0-9_]{1,8}"))) {
                    preferences.edit().putString(key, name).apply()
                    if (playConfig == null) showGameDetails(game)
                    else startGame(game, playConfig, fromFrontend)
                } else Ui.message(this, "Use 1–8 letters, digits, or underscores")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun settingsEntries() = listOf(
        SettingsEntry("Touch input", {
            when (preferences.getInt("touch_mode", 2)) {
                2 -> "Auto"
                1 -> "Keyboard"
                else -> if (preferences.getBoolean("direct_touch", false))
                    "Mouse · direct tap" else "Mouse · touchpad"
            }
        }, ::showSettings),
        SettingsEntry("DOS CPU speed", { if (preferences.getInt("cycles_mode", 0) == 0)
            "Auto" else "Maximum" }, ::showCpuSettings),
        SettingsEntry("Graphics", { scalingLabel() + if (isPortrait())
            " · notch $portraitNotchPadding dp" else "" }, ::showGraphics),
        SettingsEntry("On-screen controls", { "Button layout and visibility" }) {
            closeMenu()
            showOnScreenControls()
        },
        SettingsEntry("Controller", { "Gamepad and on-screen controls" }) {
            showControllerScope(currentGame?.takeIf { it.contentId != null })
        },
        SettingsEntry("Sound", { if (preferences.getBoolean("muted", false)) "Muted" else "On" }) {
            val muted = !preferences.getBoolean("muted", false)
            preferences.edit().putBoolean("muted", muted).apply()
            audio?.setVolume(if (muted) 0f else 1f)
            sessionDrawer?.refreshValues()
            libraryScreen.refreshSettingValues()
        },
        SettingsEntry("About", { "Version, shortcuts, and licenses" }, ::showAbout)
    )

    private fun showGame() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val game = currentGame ?: return
        sessionGameTitle = catalog.resolve(game.contentId ?: "", game.displayName).title
        libraryScreen.dismissSystemKeyboard()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        gameRoot = root
        libraryScreen.visibility = View.GONE
        appRoot.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame = frame
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        loadingStatus = TextView(this).apply {
            text = if (game.installer) "Preparing installer archive…" else "Preparing DOS game…"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 16),
                Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 16))
            setBackgroundColor(0xCC101820.toInt())
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }
        val display = SurfaceView(this)
        surface = display
        frame.addView(display, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
        display.holder.addCallback(this)
        display.setOnTouchListener(::handleGameTouch)
        lateinit var panel: GuestKeyboardPanel
        panel = GuestKeyboardPanel(this, keys, DosKeyboardLayout.value, { panel.close() },
            mouse = mouse, mouseReferenceSize = { 640 to 400 },
            onVisibilityChanged = {
                refreshControllerUi()
                if (panel.visibility == View.VISIBLE) handler.postDelayed({
                    if (panel.visibility == View.VISIBLE) ImmersiveWindow.hideBars(this)
                }, 350)
            })
        keyboard = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, Ui.dp(this, 280), Gravity.BOTTOM))
        sessionDrawer = SessionDrawer(this, root, "KAIRODOS", ::closeMenu,
            ::showSessionStatus, listOf(
            SessionAction("Resume", com.mrjackspade.kairo.frontend.R.drawable.ic_play) {
                userPaused = false
                closeMenu()
            },
            SessionAction("Save", R.drawable.ic_save) {
                showStateSlots(saving = true)
            },
            SessionAction("Load", R.drawable.ic_load) {
                showStateSlots(saving = false)
            },
            SessionAction("Restart", com.mrjackspade.kairo.frontend.R.drawable.ic_restart) {
                confirmRestart()
            },
            SessionAction("Library", com.mrjackspade.kairo.frontend.R.drawable.ic_library) {
                openLibraryOverGame()
            }
        ), listOf(
            SettingsEntry("Pause", { if (userPaused) "On · tap to let the game run again"
                else "Close the menu with the game stopped" }) {
                userPaused = !userPaused
                closeMenu()
            },
            SettingsEntry("Keyboard", { "Show the DOS keyboard" }) {
                closeMenu()
                panel.open()
            },
            SettingsEntry("Exit", { "Stop the game and close KairoDos" }) {
                confirmExit()
            }
        ), settingsEntries())
        sessionFlow = SessionFlow(sessionDrawer!!,
            { sessionGameTitle ?: game.displayName },
            { statusLabel?.text?.toString() ?: "DOS game" },
            {
                keyboard?.close()
                keys.releaseAll()
                mouse.releasePrefix("touch-")
            },
            { open ->
                refreshControllerUi()
            },
            { surface?.requestFocus() })
        statusLabel = sessionDrawer?.status
        statusLabel?.text = "Starting ${sessionGameTitle}…"
        onScreenControls?.refreshVisibility(true)
    }

    private fun openMenu() {
        if (currentGame != null && libraryScreen.visibility != View.VISIBLE) sessionFlow?.open()
    }

    private fun closeMenu() { sessionFlow?.close() }

    private fun showSessionStatus() {
        val state = when (nativeStatus()) {
            1 -> "Loading"
            2 -> if (userPaused || sessionFlow?.isOpen == true ||
                libraryScreen.visibility == View.VISIBLE) "Paused" else "Running"
            3 -> "Failed"
            else -> "Stopped"
        }
        val details = mutableListOf(
            "Game" to (sessionGameTitle ?: "Unknown"),
            "Core" to "DOSBox Pure",
            "State" to state,
            "DOS CPU speed" to if (effectiveCycles() == 0) "Auto" else "Maximum",
            "Touch input" to configuredTouchMode().name.lowercase()
        )
        if (nativeStatus() == 2) {
            details += "Video" to "${nativeVideoWidth()} × ${nativeVideoHeight()}"
            details += "Audio" to "${nativeAudioRate()} Hz"
        }
        SessionStatusDialog.show(this, "Session details", details)
    }

    private fun controllerAction(action: String) {
        when (action) {
            "menu" -> if (sessionFlow?.isOpen == true) closeMenu() else openMenu()
            "pause" -> {
                userPaused = !userPaused
                refreshControllerUi()
            }
            "restart" -> confirmRestart()
            "exit" -> confirmExit()
        }
    }

    private fun globalControllerBindings() = controllerProfiles.global()

    private fun physicalControllerBindings() = controllerProfiles.physical()

    private fun savePhysicalControllerBindings(bindings: List<PhysicalControllerBinding>) {
        controllerProfiles.savePhysical(bindings)
        gamepad.physicalBindings = bindings
    }

    private fun resetPhysicalControllerBindings() {
        controllerProfiles.resetPhysical()
        gamepad.physicalBindings = physicalControllerBindings()
    }

    private fun controllerKey(game: DosLibrary.Game) = "controller_game_${game.contentId}"

    private fun loadControllerBindings(game: DosLibrary.Game?): List<ControllerBinding> =
        if (game?.contentId == null) globalControllerBindings()
        else preferences.getString(controllerKey(game), null)?.let(DosControllerBindings::parse)
            ?: when (catalog.resolve(game.contentId, game.displayName).controllerProfile) {
                "doom-v1" -> DosControllerBindings.doom()
                else -> globalControllerBindings()
            }

    private fun saveControllerBindings(game: DosLibrary.Game?, bindings: List<ControllerBinding>) {
        val key = game?.takeIf { it.contentId != null }?.let(::controllerKey)
        if (key == null) controllerProfiles.saveGlobal(bindings)
        else preferences.edit().putString(key, DosControllerBindings.toJson(bindings).toString()).apply()
        gamepad.bindings = loadControllerBindings(currentGame)
    }

    private fun resetControllerBindings(game: DosLibrary.Game?) {
        val key = game?.takeIf { it.contentId != null }?.let(::controllerKey)
        if (key == null) controllerProfiles.resetGlobal()
        else preferences.edit().remove(key).apply()
        gamepad.bindings = loadControllerBindings(currentGame)
    }

    private fun showControllerScope(game: DosLibrary.Game? = null) {
        if (game != null && game.contentId == null) {
            Ui.message(this, "Hash this game before editing its controls")
            return
        }
        closeMenu()
        gamepad.releaseAll()
        keys.releaseAll()
        keyboard?.close()
        controllerEditor.show(game)
    }

    private fun showOnScreenControls() {
        controllerEditor.close()
        onScreenControls?.show()
    }

    private fun refreshControllerUi() {
        val blocked = controllerEditor.isOpen || onScreenControls?.isOpen == true ||
            sessionFlow?.isOpen == true || libraryScreen.visibility == View.VISIBLE
        if (currentGame != null) nativePause(blocked || userPaused)
        onScreenControls?.refreshVisibility(currentGame != null && !blocked && !userPaused &&
            keyboard?.visibility != View.VISIBLE)
    }

    private fun pollSession(id: String) {
        handler.postDelayed({
            if (currentGame?.id != id) return@postDelayed
            if (nativeStatus() == 2) {
                InputModeDecider.GuestInput.fromNative(nativeInputTelemetry())?.let {
                    inputModeDecider.observe(it, android.os.SystemClock.elapsedRealtime())
                }
            }
            when (nativeStatus()) {
                1 -> statusLabel?.text = "Loading DOSBox Pure…"
                2 -> {
                    loadingStatus?.visibility = View.GONE
                    statusLabel?.text = "${sessionGameTitle ?: "Game"} · running"
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
        val keyboardHeight = keyboard?.takeIf { it.visibility == View.VISIBLE }
            ?.layoutParams?.height ?: 0
        val topPadding = if (isPortrait()) Ui.dp(this, portraitNotchPadding) else 0
        val availableHeight = (frame.height - keyboardHeight - topPadding).coerceAtLeast(1)
        val sourceWidth = nativeVideoWidth().coerceAtLeast(1)
        val sourceHeight = nativeVideoHeight().coerceAtLeast(1)
        val ratio = nativeAspect().takeIf { it in 0.5..3.0 }
            ?: sourceWidth.toDouble() / sourceHeight
        val correctedHeight = sourceWidth / ratio
        val fit = minOf(frame.width / sourceWidth.toDouble(), availableHeight / correctedHeight)
        if (fit <= 0.0) return
        val scale = if (integerScaling && fit >= 1.0) {
            if (integerCrop) ceil(fit) else floor(fit)
        } else fit
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (correctedHeight * scale).roundToInt().coerceAtLeast(1)
        val top = topPadding + ((availableHeight - height) / 2).coerceAtLeast(0)
        val params = display.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.topMargin != top ||
            params.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
            display.layoutParams = FrameLayout.LayoutParams(width, height,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top }
        }
    }

    private fun isPortrait() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadGraphicsSettings()
        videoFrame?.post { updateSurfaceLayout() }
    }

    private fun scalingLabel() = when {
        !integerScaling -> "Fit display"
        integerCrop -> "Integer crop"
        else -> "Integer full image"
    }

    private fun loadGraphicsSettings() {
        val suffix = if (isPortrait()) "portrait" else "landscape"
        integerScaling = preferences.getBoolean("integer_scaling_$suffix",
            preferences.getBoolean("integer_scaling", true))
        integerCrop = preferences.getBoolean("integer_crop_$suffix",
            preferences.getBoolean("integer_crop", false))
        portraitNotchPadding = preferences.getInt("portrait_notch_padding", 0).coerceIn(0, 240)
    }

    private fun showGraphics() {
        val orientation = if (isPortrait()) "portrait" else "landscape"
        val items = if (isPortrait()) arrayOf(
            "Scaling  ·  ${scalingLabel()}", "Notch padding  ·  $portraitNotchPadding dp")
        else arrayOf("Scaling  ·  ${scalingLabel()}")
        val dialog = AlertDialog.Builder(this).setTitle("Graphics · $orientation")
            .setItems(items) { _, which ->
                if (which == 0) showScalingChoices(orientation) else showNotchPadding()
            }.setNegativeButton("Close", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun showScalingChoices(orientation: String) {
        val options = arrayOf("Integer  ·  full image (default)",
            "Integer  ·  crop edges", "Fit display  ·  fractional scale")
        val selected = if (!integerScaling) 2 else if (integerCrop) 1 else 0
        val dialog = AlertDialog.Builder(this).setTitle("Scaling · $orientation")
            .setSingleChoiceItems(options, selected) { current, choice ->
                integerScaling = choice != 2
                integerCrop = choice == 1
                preferences.edit()
                    .putBoolean("integer_scaling_$orientation", integerScaling)
                    .putBoolean("integer_crop_$orientation", integerCrop).apply()
                updateSurfaceLayout()
                sessionDrawer?.refreshValues()
                libraryScreen.refreshSettingValues()
                current.dismiss()
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun showNotchPadding() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(portraitNotchPadding.toString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle("Portrait notch padding (dp)")
            .setView(input).setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toIntOrNull()?.coerceIn(0, 240)
                if (value == null) { Ui.message(this, "Enter a number from 0 to 240"); return@setPositiveButton }
                portraitNotchPadding = value
                preferences.edit().putInt("portrait_notch_padding", value).apply()
                updateSurfaceLayout()
                sessionDrawer?.refreshValues()
                libraryScreen.refreshSettingValues()
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun handleGameTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x; lastY = event.y; moved = false
        }
        if (inputModeDecider.resolve(configuredTouchMode()) == InputModeDecider.Mode.KEYBOARD) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                abs(event.x - lastX) + abs(event.y - lastY) > Ui.dp(this, 12)) moved = true
            if (event.actionMasked == MotionEvent.ACTION_UP && !moved)
                keyboard?.open()
            return true
        }
        val direct = effectiveDirectTouch()
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
        val modeNames = listOf("Mouse", "Keyboard (tap opens DOS keyboard)",
            "Auto (follows what the game reads)")
        val dialog = TouchInputSettingsDialog.builder(this, TouchInputSettingsDialog.Options(
            title = "DOS touch input",
            modeLabels = modeNames,
            modeIndex = preferences.getInt("touch_mode", 2).coerceIn(0, 2),
            directTouch = preferences.getBoolean("direct_touch", false),
            directTouchExplanation = "Touchpad moves the DOS mouse by dragging. Direct tap positions it at your finger; some games require relative movement.",
            onSave = { mode, direct, _ ->
                preferences.edit().putInt("touch_mode", mode).putBoolean("direct_touch", direct).apply()
                configureGuest()
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
                configureGuest()
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmRestart() {
        val title = sessionGameTitle ?: "the DOS game"
        val dialog = AlertDialog.Builder(this).setTitle("Restart $title?")
            .setMessage("Progress since your last save state or in-game save is lost.")
            .setPositiveButton("Restart") { _, _ ->
                userPaused = false
                nativeReset()
                closeMenu()
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun touchSettingsLabel(game: DosLibrary.Game): String {
        val mode = when (effectiveTouchMode(game)) {
            0 -> "Mouse"
            1 -> "Keyboard"
            else -> "Auto"
        }
        val pointer = if (effectiveDirectTouch(game)) "direct tap" else "touchpad"
        val source = if (gameSettings.has(game.contentId, "touch_mode") ||
            gameSettings.has(game.contentId, "direct_touch")) "Game" else "Global"
        return "$mode · $pointer · $source"
    }

    private fun cpuSettingsLabel(game: DosLibrary.Game): String {
        val mode = if (effectiveCycles(game) == 0) "Auto" else "Maximum"
        val source = if (gameSettings.has(game.contentId, "cycles_mode")) "Game" else "Global"
        return "$mode · $source"
    }

    private fun showGameTouchSettings(game: DosLibrary.Game) {
        val id = game.contentId ?: return
        val dialog = TouchInputSettingsDialog.builder(this, TouchInputSettingsDialog.Options(
            title = "Touch input · ${catalog.resolve(id, game.displayName).title}",
            modeLabels = listOf("Mouse", "Keyboard (tap opens DOS keyboard)",
                "Auto (follows what the game reads)"),
            modeIndex = effectiveTouchMode(game),
            directTouch = effectiveDirectTouch(game),
            directTouchExplanation = "Touchpad moves the DOS mouse by dragging. Direct tap positions it at your finger; some games require relative movement.",
            onSave = { mode, direct, _ ->
                gameSettings.setInt(id, "touch_mode", mode)
                gameSettings.setBoolean(id, "direct_touch", direct)
                if (currentGame?.contentId == id) {
                    inputModeDecider.reset()
                    configureGuest()
                }
            },
            onReset = {
                gameSettings.clear(id, "touch_mode", "direct_touch")
                if (currentGame?.contentId == id) {
                    inputModeDecider.reset()
                    configureGuest()
                }
            },
            resetLabel = "Use global settings"
        )).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun showGameCpuSettings(game: DosLibrary.Game) {
        val id = game.contentId ?: return
        val selected = if (gameSettings.has(id, "cycles_mode"))
            effectiveCycles(game) + 1 else 0
        val dialog = AlertDialog.Builder(this).setTitle("DOS CPU performance · ${game.displayName}")
            .setSingleChoiceItems(arrayOf("Use global setting", "Auto", "Maximum"), selected) {
                    current, choice ->
                if (choice == 0) gameSettings.clear(id, "cycles_mode")
                else gameSettings.setInt(id, "cycles_mode", choice - 1)
                if (currentGame?.contentId == id) configureGuest()
                current.dismiss()
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun confirmExit() {
        val dialog = AlertDialog.Builder(this).setTitle("Exit KairoDos?")
            .setMessage("The DOS game stops. Progress since your last save state or in-game save is lost.")
            .setPositiveButton("Exit") { _, _ ->
                leaveGame()
                if (installerPromptOpen) finishAfterInstallerPrompt = true else finish()
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun showAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val dialog = AlertDialog.Builder(this).setTitle("KairoDos $version")
            .setMessage("Open the game menu with Back, a controller Mode/Home button when Android delivers it, or a swipe from the left edge. Open the DOS keyboard by tapping a keyboard prompt or using the menu.\n\nKairoDos uses the DOSBox Pure emulator core. KairoDos, the shared Kairo frontend, and DOSBox Pure are GPL-2.0-or-later. Third-party notices and source provenance are in the project source at github.com/MrJackSpade/KairoDos.")
            .setPositiveButton("Done", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun stateFile(game: DosLibrary.Game, slot: Int): File {
        val key = game.contentId!!.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(filesDir, "states/$key/slot$slot.state")
    }

    private fun showStateSlots(saving: Boolean) {
        val game = currentGame?.takeIf { it.contentId != null }
        if (game == null || nativeStatus() != 2) {
            Ui.message(this, "Start a DOS game before using save states")
            return
        }
        if (stateBusy) return
        val format = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        val labels = (1..4).map { slot ->
            val saved = stateFile(game, slot).takeIf { it.isFile }
            "Slot $slot · " + (saved?.let { format.format(java.util.Date(it.lastModified())) }
                ?: "Empty")
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle((if (saving) "Save state" else "Load state") + " · ${sessionGameTitle ?: game.displayName}")
            .setItems(labels) { _, index ->
                val slot = index + 1
                val existing = stateFile(game, slot).isFile
                if (!saving && !existing) {
                    Ui.message(this, "Slot $slot is empty")
                } else if (existing) {
                    val confirm = AlertDialog.Builder(this)
                        .setTitle(if (saving) "Overwrite slot $slot?" else "Load slot $slot?")
                        .setMessage(if (saving) "The current save in this slot is replaced."
                            else "Progress since that save is lost.")
                        .setPositiveButton(if (saving) "Overwrite" else "Load") { _, _ ->
                            if (saving) saveState(game, slot) else loadState(game, slot)
                        }.setNegativeButton("Cancel", null).create()
                    confirm.show()
                    Ui.styleDialog(confirm)
                } else saveState(game, slot)
            }.setNegativeButton("Cancel", null).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun stateError(code: Int) = when (code) {
        1 -> "game is not running"
        2 -> "state is unavailable or incompatible"
        3 -> "storage is unavailable"
        else -> "DOSBox Pure rejected the state"
    }

    private fun saveState(game: DosLibrary.Game, slot: Int) {
        if (stateBusy) return
        stateBusy = true
        statusLabel?.text = "Saving slot $slot…"
        Thread {
            val target = stateFile(game, slot)
            val scratch = File(target.parentFile, "slot$slot.part")
            val previous = File(target.parentFile, "slot$slot.old")
            val result = runCatching {
                require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                scratch.delete()
                val code = nativeSaveState(scratch.absolutePath)
                if (code != 0) return@runCatching "Save failed: ${stateError(code)}"
                previous.delete()
                if (target.exists() && !target.renameTo(previous))
                    error("Could not replace the old save")
                if (!scratch.renameTo(target)) {
                    previous.renameTo(target)
                    error("Could not store the save")
                }
                previous.delete()
                "Saved to slot $slot"
            }.getOrElse { "Save failed: ${it.message ?: "storage error"}" }
            scratch.delete()
            runOnUiThread {
                stateBusy = false
                statusLabel?.text = result
                Ui.message(this, result)
            }
        }.apply { name = "KairoDos-save-state"; start() }
    }

    private fun loadState(game: DosLibrary.Game, slot: Int) {
        if (stateBusy) return
        stateBusy = true
        statusLabel?.text = "Loading slot $slot…"
        Thread {
            val code = nativeLoadState(stateFile(game, slot).absolutePath)
            runOnUiThread {
                stateBusy = false
                if (code == 0) {
                    gamepad.releaseAll()
                    keys.releaseAll()
                    inputModeDecider.reset()
                    userPaused = false
                    closeMenu()
                    Ui.message(this, "Loaded slot $slot")
                } else {
                    statusLabel?.text = "Load failed: ${stateError(code)}"
                    Ui.message(this, "Load failed: ${stateError(code)}")
                    // A rejected state may have partially changed the emulated machine.
                    if (code == 4) { userPaused = false; nativeReset(); closeMenu() }
                }
            }
        }.apply { name = "KairoDos-load-state"; start() }
    }

    private fun startAudio() {
        val generation = ++audioGeneration
        audioThread = Thread {
            if (generation != audioGeneration) return@Thread
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
            if (generation != audioGeneration) {
                track.release()
                return@Thread
            }
            audio = track
            track.setVolume(if (preferences.getBoolean("muted", false)) 0f else 1f)
            track.play()
            val buffer = ShortArray(4096)
            try {
                while (generation == audioGeneration && nativeStatus() == 2) {
                    val frames = nativeReadAudio(buffer, buffer.size / 2)
                    if (frames > 0) track.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    else Thread.sleep(4)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
                if (audio === track) audio = null
            }
        }.apply { name = "KairoDos-audio"; start() }
    }

    private fun showInstallerRemovalPrompt(installer: DosLibrary.Game,
                                           installed: DosLibrary.Game) {
        installerPromptOpen = true
        val dialog = AlertDialog.Builder(this).setTitle("Remove installer ZIP?")
            .setMessage("The installed game archive is in your DOS folder. Remove ${installer.path.substringAfterLast('/')} now?")
            .setPositiveButton("Remove") { _, _ ->
                Thread {
                    runCatching { dosLibrary.deleteInstaller(installer, installed) }
                        .onSuccess { runOnUiThread {
                            installerPromptOpen = false
                            refreshLibrary(false)
                            if (finishAfterInstallerPrompt) {
                                finishAfterInstallerPrompt = false
                                finish()
                            }
                        } }
                        .onFailure { failure -> runOnUiThread {
                            installerPromptOpen = false
                            if (finishAfterInstallerPrompt) {
                                finishAfterInstallerPrompt = false
                                AlertDialog.Builder(this)
                                    .setMessage(failure.message ?: "Could not remove installer")
                                    .setPositiveButton("Close") { _, _ -> finish() }.show()
                            } else Ui.message(this, failure.message ?: "Could not remove installer")
                        } }
                }.apply { name = "KairoDos-remove-installer"; start() }
            }.setNegativeButton("Keep") { _, _ ->
                installerPromptOpen = false
                refreshLibrary(false)
                if (finishAfterInstallerPrompt) {
                    finishAfterInstallerPrompt = false
                    finish()
                }
            }.setCancelable(false).create()
        dialog.show()
        Ui.styleDialog(dialog)
    }

    private fun leaveGame(completed: Boolean = true) {
        val returnToFrontend = completed && sessionFromFrontend
        sessionFromFrontend = false
        launchGeneration++
        prepareCancelled.set(true)
        nativeStop()
        audioGeneration++
        gamepad.releaseAll()
        keys.releaseAll()
        mouse.releasePrefix("touch-")
        onScreenControls?.close()
        keyboard?.close()
        onScreenControls?.refreshVisibility(false)
        sessionFlow?.reset()
        sessionFlow = null
        sessionDrawer = null
        userPaused = false
        nativeSetSurface(null)
        loadingStatus = null
        sessionGameTitle = null
        currentGame = null
        gamepad.bindings = globalControllerBindings()
        if (!returnToFrontend || installerPromptOpen) showLibrary()
        if (returnToFrontend) {
            if (installerPromptOpen) finishAfterInstallerPrompt = true else finish()
        }
    }

    private fun effectiveTouchMode(game: DosLibrary.Game? = currentGame): Int =
        gameSettings.int(game?.contentId, "touch_mode",
            preferences.getInt("touch_mode", 2)).coerceIn(0, 2)

    private fun effectiveDirectTouch(game: DosLibrary.Game? = currentGame): Boolean =
        gameSettings.boolean(game?.contentId, "direct_touch",
            preferences.getBoolean("direct_touch", false))

    private fun effectiveCycles(game: DosLibrary.Game? = currentGame): Int =
        gameSettings.int(game?.contentId, "cycles_mode",
            preferences.getInt("cycles_mode", 0)).coerceIn(0, 1)

    private fun configureGuest() {
        nativeConfigure(if (effectiveDirectTouch()) 1 else 0, effectiveCycles())
    }

    private fun configuredTouchMode(): InputModeDecider.Mode =
        when (effectiveTouchMode()) {
            0 -> InputModeDecider.Mode.MOUSE
            1 -> InputModeDecider.Mode.KEYBOARD
            else -> InputModeDecider.Mode.AUTO
        }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (surface?.holder === holder) nativeSetSurface(holder.surface)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (surface?.holder === holder) nativeSetSurface(holder.surface)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (surface?.holder === holder) nativeSetSurface(null)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::appRoot.isInitialized || !::firstRunScreen.isInitialized || firstRunScreen.isOpen ||
            onScreenControls?.isOpen == true || controllerEditor.isOpen ||
            currentGame == null || libraryScreen.visibility == View.VISIBLE)
            return super.dispatchTouchEvent(event)
        return when (edgeSwipes.handle(event, appRoot.width, sessionFlow?.isOpen == true,
            canOpenMenu = true, canOpenKeyboard = keyboard?.visibility != View.VISIBLE,
            controlsHit = onScreenControls?.hitTest(event.x, event.y) == true)) {
            EdgeSwipeNavigation.Result.PASS -> super.dispatchTouchEvent(event)
            EdgeSwipeNavigation.Result.CONSUME -> true
            EdgeSwipeNavigation.Result.OPEN_MENU -> { openMenu(); true }
            EdgeSwipeNavigation.Result.OPEN_KEYBOARD -> { keyboard?.open(); true }
            EdgeSwipeNavigation.Result.CLOSE_MENU -> {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                closeMenu()
                true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Handle menu navigation before Android moves View focus. The shared drawer
        // owns selection; letting View focus handle D-pad first can select two rows.
        if (event.action == KeyEvent.ACTION_DOWN && ::appRoot.isInitialized && appRoot.isInTouchMode)
            (currentFocus ?: appRoot).requestFocusFromTouch()
        if (firstRunScreen.isOpen) return firstRunScreen.handleKey(event)
        if (controllerEditor.isOpen) {
            if (controllerEditor.handleKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (onScreenControls?.isOpen == true) {
            if (onScreenControls?.handleKey(event) == true) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                backCoordinator.handle()
            return true
        }
        if (libraryScreen.visibility == View.VISIBLE) {
            if (FrontendNavigation.library(libraryScreen,
                    FrontendNavigation.control(event, gamepad),
                    event, backCoordinator::handle)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU ||
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && !gamepad.hasButton(event.keyCode))) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (sessionFlow?.isOpen == true) closeMenu() else openMenu()
            }
            return true
        }
        if (sessionDrawer?.isOpen == true) {
            if (FrontendNavigation.session(sessionDrawer!!,
                    FrontendNavigation.control(event, gamepad), event, ::closeMenu)) return true
            super.dispatchKeyEvent(event)
            return true
        }
        if (gamepad.key(event)) return true
        mapKey(event.keyCode)?.let { key ->
            val owner = "physical:${event.keyCode}"
            when (event.action) {
                KeyEvent.ACTION_DOWN -> keys.hold(owner, listOf(key))
                KeyEvent.ACTION_UP -> keys.release(owner)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (firstRunScreen.isOpen) return true
        if (controllerEditor.isOpen) return controllerEditor.captureMotion(event)
        if (onScreenControls?.isOpen == true) return true
        if (currentGame != null && libraryScreen.visibility != View.VISIBLE &&
            sessionDrawer?.isOpen != true && gamepad.motion(event)) return true
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

    private fun releaseGuestInputs() {
        gamepad.releaseAll()
        keys.releaseAll()
        mouse.releasePrefix("touch-")
    }

    private fun suspendGuest() {
        releaseGuestInputs()
        nativePause(true)
        audio?.pause()
    }

    override fun onPause() {
        suspendGuest()
        super.onPause()
    }

    override fun onStop() {
        suspendGuest()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (currentGame != null) {
            refreshControllerUi()
            audio?.play()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseGuestInputs()
    }

    override fun onDestroy() {
        backCoordinator.unregister()
        controllerDevices.unregister()
        libraryFlow.cancel()
        launchGeneration++
        prepareCancelled.set(true)
        nativeStop()
        audioGeneration++
        nativeSetSurface(null)
        super.onDestroy()
    }
}
