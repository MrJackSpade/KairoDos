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
import com.mrjackspade.kairo.frontend.AboutDocuments
import com.mrjackspade.kairo.frontend.InputRouter
import com.mrjackspade.kairo.frontend.InputModeDecider
import com.mrjackspade.kairo.frontend.MouseInputRouter
import com.mrjackspade.kairo.frontend.TouchInputSettingsDialog
import com.mrjackspade.kairo.frontend.ArtworkOverrideEditor
import com.mrjackspade.kairo.frontend.TouchInputPolicy
import com.mrjackspade.kairo.frontend.TouchInputSelection
import com.mrjackspade.kairo.frontend.ScopedTouchInput
import com.mrjackspade.kairo.frontend.Ui
import com.mrjackspade.kairo.frontend.LibraryScreen
import com.mrjackspade.kairo.frontend.LibraryScanSummary
import com.mrjackspade.kairo.frontend.LibraryFlow
import com.mrjackspade.kairo.frontend.ExternalGameIntent
import com.mrjackspade.kairo.frontend.ExternalGameDispatcher
import com.mrjackspade.kairo.frontend.FirstRunScreen
import com.mrjackspade.kairo.frontend.FrontendNavigation
import com.mrjackspade.kairo.frontend.FrontendBackCoordinator
import com.mrjackspade.kairo.frontend.GuestLifecycleCoordinator
import com.mrjackspade.kairo.frontend.ControllerDeviceMonitor
import com.mrjackspade.kairo.frontend.SecondaryDisplayCoordinator
import com.mrjackspade.kairo.frontend.RgDsDisplayRouter
import com.mrjackspade.kairo.frontend.ImmersiveWindow
import com.mrjackspade.kairo.frontend.EdgeSwipeNavigation
import com.mrjackspade.kairo.frontend.GameSettingScope
import com.mrjackspade.kairo.frontend.LibraryStrings
import com.mrjackspade.kairo.frontend.SettingsEntry
import com.mrjackspade.kairo.frontend.SessionAction
import com.mrjackspade.kairo.frontend.SessionDrawer
import com.mrjackspade.kairo.frontend.SessionFlow
import com.mrjackspade.kairo.frontend.SessionStatusDialog
import com.mrjackspade.kairo.frontend.StateSlotDialog
import com.mrjackspade.kairo.frontend.SurfaceThumbnail
import com.mrjackspade.kairo.frontend.GameSettingsRow
import com.mrjackspade.kairo.frontend.GameSettingsCoordinator
import com.mrjackspade.kairo.frontend.CommonGameSettings
import com.mrjackspade.kairo.frontend.CommonGameSettingsActions
import com.mrjackspade.kairo.frontend.GameSettingsValue
import com.mrjackspade.kairo.frontend.GameTitleEditor
import com.mrjackspade.kairo.frontend.JoystickInputRouter
import com.mrjackspade.kairo.frontend.GamepadMapper
import com.mrjackspade.kairo.frontend.ControllerEditor
import com.mrjackspade.kairo.frontend.ControllerProfileStore
import com.mrjackspade.kairo.frontend.ControllerProfileCoordinator
import com.mrjackspade.kairo.frontend.ControllerEditorFlow
import com.mrjackspade.kairo.frontend.CatalogUpdateController
import com.mrjackspade.kairo.frontend.OnScreenControls
import com.mrjackspade.kairo.frontend.GraphicsOptions
import com.mrjackspade.kairo.frontend.GameDeletionFlow
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.EditText
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.math.abs
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
    private val graphics by lazy {
        GraphicsOptions(this, preferences, { builder ->
            builder.create().also { dialog -> dialog.show(); Ui.styleDialog(dialog) }
        }, {
            updateSurfaceLayout()
            sessionDrawer?.refreshValues()
            libraryScreen.refreshSettingValues()
        }, { message -> Ui.message(this, message) })
    }
    private val gameSettings by lazy { GameSettingScope(preferences) }
    private val dosGameSettings by lazy { DosPerGameSettings(preferences, gameSettings) }
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
    private val controllerFlow by lazy {
        ControllerProfileCoordinator(controllerProfiles, gamepad,
            DosLibrary.Game::contentId,
            { game: DosLibrary.Game ->
                dosGameSettings.controllerBindings(game.contentId!!)?.let(DosControllerBindings::parse)
                    ?: when (catalog.resolve(game.contentId, game.displayName).controllerProfile) {
                        "doom-v1" -> DosControllerBindings.doom()
                        else -> controllerProfiles.global()
                    }
            },
            { id, bindings -> dosGameSettings.setControllerBindings(id,
                DosControllerBindings.toJson(bindings).toString()) },
            dosGameSettings::clearControllerBindings, { currentGame })
    }
    private var onScreenControls: OnScreenControls? = null
    private val dosLibrary by lazy { DosLibrary(this) }
    private val externalDispatcher by lazy {
        ExternalGameDispatcher(this, contentResolver, { games },
            { game: DosLibrary.Game -> Uri.parse(game.uri) },
            { file, cancelled -> listOf(dosLibrary.inspectExternal(file, cancelled)) },
            { entries, _ -> launch(entries.single(), true) },
            libraryScreen::showStatus, "KairoDos-external-game")
    }
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
    private val guestLifecycle by lazy {
        GuestLifecycleCoordinator(::releaseGuestInputs, ::refreshControllerUi,
            { audio?.pause() }, { if (currentGame != null) audio?.play() })
    }
    private val backCoordinator by lazy {
        FrontendBackCoordinator(this, firstRunScreen, { onScreenControls },
            controllerEditor, libraryScreen,
            { if (currentGame != null) resumeGameFromLibrary() else finish() },
            { sessionFlow }, ::closeMenu, { keyboard },
            { if (currentGame != null) openMenu() else finish() })
    }
    private val libraryScreen: LibraryScreen<DosLibrary.Game> get() = libraryFlow.screen
    private lateinit var appRoot: FrameLayout
    private var gameRoot: FrameLayout? = null
    private lateinit var controllerEditor: ControllerEditor<DosLibrary.Game>
    private val controllerEditorFlow by lazy {
        ControllerEditorFlow(controllerEditor, DosLibrary.Game::contentId, ::closeMenu,
            ::releaseGuestInputs, { keyboard?.close() },
            { onScreenControls?.show() }, { Ui.message(this, it) })
    }
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
    private var audioThread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var audioGeneration = 0
    private var surface: SurfaceView? = null
    private var videoFrame: FrameLayout? = null
    private var keyboard: GuestKeyboardPanel? = null
    private var swappedKeyboard: GuestKeyboardPanel? = null
    private lateinit var secondaryDisplay: SecondaryDisplayCoordinator
    private val libraryArtExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var librarySelectionGeneration = 0
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
    private var directPointerPressed = false
    private var relocating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (RgDsDisplayRouter.routeToUpper(this)) {
            relocating = true
            return
        }
        graphics.load()
        ImmersiveWindow.apply(this)
        appRoot = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(appRoot)
        secondaryDisplay = SecondaryDisplayCoordinator(this, keys, mouse,
            DosKeyboardLayout.value, "KairoDos",
            { available ->
                if (available && secondaryDisplay.isKeyboardVisible &&
                    keyboard?.visibility == View.VISIBLE) keyboard?.close()
                refreshControllerUi()
            },
            { remoteSurface, _, _ -> nativeSetSurface(remoteSurface) },
            { event, width, height -> handleGameTouchAt(event, width, height) },
            ::onSecondarySwapChanged,
            { nativeAspect().takeIf { it in 0.5..3.0 } ?: 4.0 / 3.0 })
        val libraryPage = LibraryScreen(this, catalog, LibraryStrings("KAIRODOS",
            "Select DOS folder", "Choose a writable folder for DOS games and ZIP archives",
            "No DOS folder selected"), ::chooseFolder, { refreshLibrary(false) },
            { refreshLibrary(true) },
            { catalogUpdates.check(false) },
            null, {}, settingsEntries(),
            { preferences.getString("last_played_entry", null) }, ::launch,
            ::previewGame, ::showGameDetails, ::showLibrarySelection)
        libraryFlow = LibraryFlow(this, preferences, libraryPage, PICK_FOLDER,
            dosLibrary::cached, dosLibrary::scan,
            { uri -> uri.lastPathSegment ?: "DOS folder" },
            "Choose a DOS folder to find games",
            "DOS folder needs read and write access. Select it again.",
            { found -> LibraryScanSummary.format(found, dosLibrary.hashCount) },
            onFolderSelected = { finishFirstRun() },
            onFolderError = { message -> if (firstRunScreen.isOpen) Ui.message(this, message) },
            writable = true)
        appRoot.addView(libraryPage, FrameLayout.LayoutParams(-1, -1))
        firstRunScreen = FirstRunScreen(this)
        appRoot.addView(firstRunScreen, FrameLayout.LayoutParams(-1, -1))
        onScreenControls = OnScreenControls(this, appRoot, gamepad, preferences,
            ::refreshControllerUi)
        controllerEditor = ControllerEditor(this, appRoot,
            controllerFlow::load, controllerFlow::save, controllerFlow::reset,
            controllerFlow::physical, controllerFlow::savePhysical,
            controllerFlow::resetPhysical,
            { controllerFlow.deadZone }, { controllerFlow.deadZone = it },
            ::refreshControllerUi, ::showOnScreenControls,
            { onScreenControls?.eightWayDpad ?: false },
            { onScreenControls?.eightWayDpad = it },
            DosLibrary.Game::id, DosControllerBindings.spec,
            { DosControllerBindings.toJson(it) })
        controllerFlow.initialize()
        controllerDevices.register(handler)
        backCoordinator.register()
        // Match Kairo98's startup order: attach the library first, then restore
        // its cached entries after Android gets a chance to draw the window.
        appRoot.post {
            if (isFinishing || isDestroyed) return@post
            libraryFlow.restore()
            showLibrary()
            val externallyRequested = savedInstanceState == null &&
                ExternalGameIntent.hasRequest(intent)
            if (externallyRequested)
                dispatchExternalGame(intent)
            else {
                if (tree != null) refreshLibrary(false)
                if (!preferences.getBoolean("onboarding_complete_v1", tree != null)) showFirstRun()
            }
            catalogUpdates.check(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchExternalGame(intent)
    }

    private fun dispatchExternalGame(intent: Intent) {
        if (::firstRunScreen.isInitialized) closeFirstRun()
        externalDispatcher.dispatch(intent)
    }

    private fun showLibrary() {
        librarySelectionGeneration++
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentGame = null
        gameRoot?.let(appRoot::removeView)
        gameRoot = null
        libraryScreen.visibility = View.VISIBLE
        libraryFlow.show()
        refreshControllerUi()
    }

    private fun openLibraryOverGame() {
        if (currentGame == null) return
        if (sessionFromFrontend) { leaveGame(); return }
        edgeSwipes.reset()
        libraryScreen.visibility = View.VISIBLE
        refreshControllerUi()
        sessionFlow?.reset()
        keyboard?.close()
        swappedKeyboard?.close()
        releaseGuestInputs()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        libraryFlow.show()
        refreshControllerUi()
    }

    private fun resumeGameFromLibrary() {
        librarySelectionGeneration++
        secondaryDisplay.setLibraryInfo(null)
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

    private fun showLibrarySelection(entry: DosLibrary.Game?) {
        if (!::secondaryDisplay.isInitialized) return
        val generation = ++librarySelectionGeneration
        if (entry == null) {
            secondaryDisplay.setLibraryInfo(null)
            return
        }
        val game = catalog.resolve(entry.contentId ?: "", entry.displayName)
        val info = SecondaryDisplayCoordinator.LibraryInfo(game.title,
            listOf(entry.displayName) + game.tags,
            game.description ?: "No description available yet.", null)
        secondaryDisplay.setLibraryInfo(info)
        val art = game.preview ?: return
        libraryArtExecutor.execute {
            val bitmap = runCatching {
                catalog.openArtwork(art).use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                        inSampleSize = 2
                    })
                }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (generation == librarySelectionGeneration &&
                    libraryScreen.visibility == View.VISIBLE && !isDestroyed)
                    secondaryDisplay.setLibraryInfo(info.copy(art = bitmap))
                else bitmap.recycle()
            }
        }
    }

    private fun previewGame(game: DosLibrary.Game) {
        val art = catalog.resolve(game.contentId ?: "", game.displayName).preview ?: return
        val bitmap = runCatching { catalog.openArtwork(art).use(BitmapFactory::decodeStream) }.getOrNull()
            ?: return
        AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(bitmap) })
            .setPositiveButton("Close", null).show()
    }

    private fun editGameArtwork(game: DosLibrary.Game, kind: String) {
        val id = game.contentId ?: return
        val current = catalog.resolve(id, game.displayName)
        val label = if (kind == "boxArt") "Box art" else "Screenshot"
        ArtworkOverrideEditor.show(this, ArtworkOverrideEditor.Options(
            title = label,
            currentPath = if (kind == "boxArt") current.boxArt ?: "" else current.preview ?: "",
            hint = "art/catalog/dos/example.webp",
            explanation = "Use a packaged DOS artwork path. Missing art falls back to the game title.",
            resetLabel = "Reset $label",
            onSave = { path ->
                runCatching { catalog.setArtworkOverride(id, kind, path) }
                    .onSuccess { libraryScreen.showEntries(games) }
                    .onFailure { Ui.message(this, it.message ?: "Could not save artwork") }
            },
            onReset = {
                runCatching { catalog.setArtworkOverride(id, kind, null) }
                    .onSuccess { libraryScreen.showEntries(games) }
                    .onFailure { Ui.message(this, it.message ?: "Could not reset artwork") }
            }))
    }

    private fun showGameDetails(entry: DosLibrary.Game) {
        val id = entry.contentId
        val record = catalog.resolve(id ?: "", entry.displayName)
        val variants = record.launch?.configs?.keys.orEmpty()
        val controllerSource = when {
            id == null -> "Global"
            dosGameSettings.controllerBindings(id) != null -> "User override"
            record.controllerProfile != null -> "Catalog"
            else -> "Global"
        }
        val common = CommonGameSettings(
            title = GameSettingsValue(record.title,
                id?.let { catalog.sourceOf(it, entry.displayName, "title") } ?: "Filename"),
            touch = GameSettingsValue(touchSettingsLabel(entry)),
            controller = GameSettingsValue("${controllerFlow.load(entry).size} bindings",
                controllerSource),
            boxArt = GameSettingsValue(if (record.boxArt == null) "None" else "Available",
                catalog.sourceOf(id ?: "", entry.displayName, "artwork", "boxArt")),
            screenshot = GameSettingsValue(if (record.preview == null) "None" else "Available",
                catalog.sourceOf(id ?: "", entry.displayName, "artwork", "preview")),
            filePath = entry.path,
            zipEntry = null,
            contentId = id,
            error = entry.error,
            deleteKind = if (!entry.external && !entry.rootFolder)
                if (entry.folder) "game folder" else "game file" else null)
        val machineRows = listOf(GameSettingsRow("DOS CPU speed", cpuSettingsLabel(entry),
            true) { showGameCpuSettings(entry) }) +
            (if (variants.size > 1) listOf(GameSettingsRow("Startup variant",
                id?.let(dosGameSettings::startupVariant) ?: "Choose on first play", false) {
                chooseLaunchVariant(entry, false)
            }) else emptyList()) +
            (if (DosLaunchConfig.needsPlayer(record.launch)) listOf(GameSettingsRow(
                "DOS player name", id?.let(dosGameSettings::playerName)
                    ?: "Choose on first play", false) {
                choosePlayerName(entry, null)
            }) else emptyList())
        GameSettingsCoordinator.show(this, common, entry.playable, machineRows,
            CommonGameSettingsActions(
                play = { launch(entry) },
                editTouch = { showGameTouchSettings(entry) },
                editController = { showControllerScope(entry) },
                editTitle = { editGameTitle(entry, record.title) },
                editBoxArt = { editGameArtwork(entry, "boxArt") },
                editScreenshot = { editGameArtwork(entry, "preview") },
                viewScreenshot = { previewGame(entry) },
                delete = if (common.deleteKind == null) null else {{ confirmDeleteGame(entry) }},
                reset = id?.let { { resetGameSettings(entry, it) } },
                resetFailed = { failure -> Ui.message(this,
                    failure.message ?: "Could not reset game settings") },
                resetCancelled = { showGameDetails(entry) },
                noHash = { Ui.message(this, "Hash this game before saving settings") }))
    }

    private fun editGameTitle(entry: DosLibrary.Game, current: String) {
        val id = entry.contentId ?: return
        fun update(value: String?) {
            runCatching { catalog.setTitle(id, value) }
                .onSuccess {
                    libraryScreen.showEntries(games)
                    showGameDetails(entry)
                }
                .onFailure { Ui.message(this, it.message ?: "Could not save title") }
        }
        GameTitleEditor.show(this, current, { update(it) }, { update(null) },
            { showGameDetails(entry) })
    }

    private fun resetGameSettings(entry: DosLibrary.Game, id: String) {
        catalog.resetOverrides(id)
        dosGameSettings.reset(id)
        if (currentGame?.contentId == id) {
            gamepad.bindings = controllerFlow.load(currentGame)
            inputModeDecider.reset()
            configureGuest()
        }
        libraryScreen.showEntries(games)
        showGameDetails(entry)
    }

    private fun launch(game: DosLibrary.Game, fromFrontend: Boolean = false) {
        val launch = catalog.resolve(game.contentId ?: "", game.displayName).launch
        val variants = launch?.configs?.keys.orEmpty()
        if (variants.size > 1) {
            val saved = game.contentId?.let(dosGameSettings::startupVariant)
            if (saved !in variants) {
                chooseLaunchVariant(game, true, fromFrontend)
                return
            }
            startGame(game, saved!!, fromFrontend)
        } else startGame(game, "dosbox.conf", fromFrontend)
    }

    private fun confirmDeleteGame(entry: DosLibrary.Game) {
        if (currentGame?.id == entry.id) {
            Ui.message(this, "Exit this game before deleting its source")
            return
        }
        val kind = if (entry.folder) "folder and everything inside it" else "file"
        GameDeletionFlow.show(this, libraryScreen,
            GameDeletionFlow.Prompt(entry.path, kind,
                "This permanently removes the $kind. "),
            "KairoDos-delete-game", { dosLibrary.deleteSource(entry) },
            { refreshLibrary(false) }, { Ui.message(this, it) })
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
                game.contentId?.let { dosGameSettings.setStartupVariant(it, selected) }
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
        if (DosLaunchConfig.needsPlayer(launch) &&
            game.contentId?.let(dosGameSettings::playerName) == null) {
            choosePlayerName(game, configName, fromFrontend)
            return
        }
        val playerName = game.contentId?.let(dosGameSettings::playerName)
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
        gamepad.bindings = controllerFlow.load(game)
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
        val id = game.contentId ?: return
        val input = EditText(this).apply {
            hint = "1–8 letters, digits, or underscores"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(dosGameSettings.playerName(id) ?: "PLAYER")
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("DOS player name").setView(input)
            .setPositiveButton(if (playConfig == null) "Save" else "Play") { _, _ ->
                val name = input.text.toString().trim().uppercase(java.util.Locale.ROOT)
                if (name.matches(Regex("[A-Z0-9_]{1,8}"))) {
                    dosGameSettings.setPlayerName(id, name)
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
        SettingsEntry("Graphics", graphics::settingsLabel, graphics::show),
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
        restoreGameFullscreen()
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
        onScreenControls?.bindGuestKeyboard(panel)
        swappedKeyboard = GuestKeyboardPanel(this, keys, DosKeyboardLayout.value, {},
            showClose = false, onSwap = { secondaryDisplay.toggleSwap() }, mouse = mouse)
            .also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
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
                if (!secondaryDisplay.isShowing) panel.open()
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
        refreshControllerUi()
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

    private fun showControllerScope(game: DosLibrary.Game? = null) {
        controllerEditorFlow.showScope(game)
    }

    private fun showOnScreenControls() {
        controllerEditorFlow.showOnScreenControls()
    }

    private fun refreshControllerUi() {
        if (relocating || !::secondaryDisplay.isInitialized ||
            !::libraryFlow.isInitialized || !::controllerEditor.isInitialized) return
        val blocked = controllerEditor.isOpen || onScreenControls?.isOpen == true ||
            sessionFlow?.isOpen == true || libraryScreen.visibility == View.VISIBLE ||
            !guestLifecycle.isVisible
        val showingGuest = currentGame != null && !blocked && !userPaused
        if (libraryScreen.visibility != View.VISIBLE) {
            librarySelectionGeneration++
            secondaryDisplay.setLibraryInfo(null)
        }
        if (secondaryDisplay.swapped && showingGuest) {
            if (swappedKeyboard?.visibility != View.VISIBLE) swappedKeyboard?.open()
        } else if (swappedKeyboard?.visibility == View.VISIBLE) swappedKeyboard?.close()
        secondaryDisplay.setAppearance(showingGuest, Color.BLACK)
        if (showingGuest) ImmersiveWindow.hideBars(this)
        if (currentGame != null) nativePause(blocked || userPaused)
        onScreenControls?.refreshVisibility(currentGame != null && !blocked && !userPaused &&
            !secondaryDisplay.swapped)
    }

    private fun onSecondarySwapChanged(swapped: Boolean) {
        keyboard?.close()
        updateSurfaceLayout()
        if (!swapped) {
            val holder = surface?.holder
            nativeSetSurface(holder?.surface?.takeIf { it.isValid })
        }
        refreshControllerUi()
    }

    private fun restoreGameFullscreen() {
        if (currentGame == null || !::libraryFlow.isInitialized ||
            libraryScreen.visibility == View.VISIBLE) return
        ImmersiveWindow.apply(this)
        window.decorView.post {
            if (!isDestroyed && window.decorView.hasWindowFocus() && currentGame != null &&
                libraryScreen.visibility != View.VISIBLE) ImmersiveWindow.hideBars(this)
        }
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
        val viewport = graphics.viewport(frame.width, frame.height, keyboardHeight,
            nativeVideoWidth(), nativeVideoHeight(), nativeAspect()) ?: return
        val params = display.layoutParams as FrameLayout.LayoutParams
        if (params.width != viewport.width || params.height != viewport.height ||
            params.topMargin != viewport.topMargin ||
            params.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
            display.layoutParams = FrameLayout.LayoutParams(viewport.width, viewport.height,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = viewport.topMargin
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        graphics.load()
        videoFrame?.post { updateSurfaceLayout() }
    }

    private fun handleGameTouch(view: View, event: MotionEvent): Boolean =
        handleGameTouchAt(event, view.width, view.height)

    private fun handleGameTouchAt(event: MotionEvent, width: Int, height: Int): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x; lastY = event.y; moved = false
        }
        if (inputModeDecider.resolve(configuredTouchMode()) == InputModeDecider.Mode.KEYBOARD) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                abs(event.x - lastX) + abs(event.y - lastY) > Ui.dp(this, 12)) moved = true
            if (event.actionMasked == MotionEvent.ACTION_UP && !moved &&
                !secondaryDisplay.isKeyboardVisible)
                keyboard?.open()
            return true
        }
        val direct = effectiveDirectTouch()
        if (direct) {
            val x = ((event.x / width.coerceAtLeast(1)) * 65534 - 32767).roundToInt()
            val y = ((event.y / height.coerceAtLeast(1)) * 65534 - 32767).roundToInt()
            directPointerPressed = event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL
            nativePointer(x, y, directPointerPressed)
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

    private fun touchSettingsLabel(game: DosLibrary.Game) = touchSelection(game).label()

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
        AboutDocuments.show(this, "KairoDos",
            "Open the game menu with Back, a controller Mode/Home button when Android delivers it, or a swipe from the left edge. Open the DOS keyboard by tapping a keyboard prompt or using the menu.\n\nKairoDos uses the DOSBox Pure emulator core. Source and provenance: github.com/MrJackSpade/KairoDos.",
            "PRIVACY_POLICY.txt", "THIRD_PARTY_NOTICES.txt")
    }

    private fun stateFile(game: DosLibrary.Game, slot: Int): File {
        val key = game.contentId!!.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(filesDir, "states/$key/slot$slot.state")
    }

    private fun stateThumbnailFile(game: DosLibrary.Game, slot: Int): File =
        File(stateFile(game, slot).parentFile, "slot$slot.png")

    private fun showStateSlots(saving: Boolean) {
        val game = currentGame?.takeIf { it.contentId != null }
        if (game == null || nativeStatus() != 2) {
            Ui.message(this, "Start a DOS game before using save states")
            return
        }
        if (stateBusy) return
        val slots = (1..4).map { slot ->
            val saved = stateFile(game, slot).takeIf { it.isFile }
            StateSlotDialog.Slot(slot, saved?.lastModified(), saved?.let {
                BitmapFactory.decodeFile(stateThumbnailFile(game, slot).absolutePath)
            })
        }
        StateSlotDialog.show(this, sessionGameTitle ?: game.displayName, saving, slots,
            { saveState(game, it) }, { loadState(game, it) })
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
        SurfaceThumbnail.capture(secondaryDisplay.activeGameSurface ?: surface, handler) { thumbnail -> Thread {
            val target = stateFile(game, slot)
            val scratch = File(target.parentFile, "slot$slot.part")
            val previous = File(target.parentFile, "slot$slot.old")
            var saved = false
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
                saved = true
                "Saved to slot $slot"
            }.getOrElse { "Save failed: ${it.message ?: "storage error"}" }
            scratch.delete()
            if (saved) {
                val previewFile = stateThumbnailFile(game, slot)
                if (thumbnail == null) previewFile.delete()
                else runCatching {
                    val atomic = android.util.AtomicFile(previewFile)
                    val stream = atomic.startWrite()
                    try {
                        check(thumbnail.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                        atomic.finishWrite(stream)
                    } catch (error: Exception) {
                        atomic.failWrite(stream)
                        throw error
                    }
                }
            }
            thumbnail?.recycle()
            runOnUiThread {
                stateBusy = false
                statusLabel?.text = result
                Ui.message(this, result)
            }
        }.apply { name = "KairoDos-save-state"; start() } }
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
        onScreenControls?.bindGuestKeyboard(null)
        swappedKeyboard?.close()
        onScreenControls?.refreshVisibility(false)
        sessionFlow?.reset()
        sessionFlow = null
        sessionDrawer = null
        userPaused = false
        nativeSetSurface(null)
        loadingStatus = null
        sessionGameTitle = null
        swappedKeyboard = null
        currentGame = null
        gamepad.bindings = controllerFlow.global()
        if (!returnToFrontend || installerPromptOpen) showLibrary()
        if (returnToFrontend) {
            if (installerPromptOpen) finishAfterInstallerPrompt = true else finish()
        }
    }

    private fun touchMode(value: Int): InputModeDecider.Mode = when (value.coerceIn(0, 2)) {
        0 -> InputModeDecider.Mode.MOUSE
        1 -> InputModeDecider.Mode.KEYBOARD
        else -> InputModeDecider.Mode.AUTO
    }

    private fun touchModeIndex(mode: InputModeDecider.Mode): Int = when (mode) {
        InputModeDecider.Mode.MOUSE -> 0
        InputModeDecider.Mode.KEYBOARD -> 1
        InputModeDecider.Mode.AUTO -> 2
    }

    private fun touchSelection(game: DosLibrary.Game? = currentGame): ScopedTouchInput {
        val id = game?.contentId
        return TouchInputPolicy.resolve(
            TouchInputSelection(touchMode(preferences.getInt("touch_mode", 2)),
                preferences.getBoolean("direct_touch", false)),
            if (gameSettings.has(id, "touch_mode"))
                touchMode(gameSettings.int(id, "touch_mode", 2)) else null,
            if (gameSettings.has(id, "direct_touch"))
                gameSettings.boolean(id, "direct_touch", false) else null)
    }

    private fun effectiveTouchMode(game: DosLibrary.Game? = currentGame) =
        touchModeIndex(touchSelection(game).selection.mode)

    private fun effectiveDirectTouch(game: DosLibrary.Game? = currentGame) =
        touchSelection(game).selection.directTouch

    private fun effectiveCycles(game: DosLibrary.Game? = currentGame): Int =
        gameSettings.int(game?.contentId, "cycles_mode",
            preferences.getInt("cycles_mode", 0)).coerceIn(0, 1)

    private fun configureGuest() {
        nativeConfigure(if (effectiveDirectTouch()) 1 else 0, effectiveCycles())
    }

    private fun configuredTouchMode() = touchSelection().selection.mode

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (surface?.holder === holder && !secondaryDisplay.swapped) nativeSetSurface(holder.surface)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (surface?.holder === holder && !secondaryDisplay.swapped) nativeSetSurface(holder.surface)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (surface?.holder === holder && !secondaryDisplay.swapped) nativeSetSurface(null)
    }

    private fun dispatchGuestTouch(event: MotionEvent) = super.dispatchTouchEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::appRoot.isInitialized || !::firstRunScreen.isInitialized || firstRunScreen.isOpen ||
            onScreenControls?.isOpen == true || controllerEditor.isOpen ||
            currentGame == null || libraryScreen.visibility == View.VISIBLE)
            return super.dispatchTouchEvent(event)
        return when (edgeSwipes.handle(event, appRoot.width, sessionFlow?.isOpen == true,
            canOpenMenu = true, canOpenKeyboard = keyboard?.visibility != View.VISIBLE &&
                !secondaryDisplay.isKeyboardVisible,
            controlsHit = onScreenControls?.hitTest(event.x, event.y) == true)) {
            EdgeSwipeNavigation.Result.PASS -> super.dispatchTouchEvent(event)
            EdgeSwipeNavigation.Result.CONSUME -> true
            EdgeSwipeNavigation.Result.REPLAY_GUEST ->
                edgeSwipes.replay(event, ::dispatchGuestTouch)
            EdgeSwipeNavigation.Result.OPEN_MENU -> { openMenu(); true }
            EdgeSwipeNavigation.Result.OPEN_KEYBOARD -> {
                if (!secondaryDisplay.isKeyboardVisible) keyboard?.open()
                true
            }
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
        if (directPointerPressed) {
            directPointerPressed = false
            nativePointer(0, 0, false)
        }
    }

    override fun onPause() {
        if (relocating || secondaryDisplay.isCompanionActive) {
            super.onPause()
            return
        }
        guestLifecycle.onPause()
        secondaryDisplay.stop()
        super.onPause()
    }

    override fun onStop() {
        if (!relocating && !secondaryDisplay.isCompanionActive) {
            guestLifecycle.onStop()
            secondaryDisplay.stop()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (relocating) return
        secondaryDisplay.start(handler)
        guestLifecycle.onResume()
        restoreGameFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (relocating || (!hasFocus && secondaryDisplay.isCompanionActive)) return
        guestLifecycle.onWindowFocusChanged(hasFocus)
        if (hasFocus) restoreGameFullscreen()
    }

    override fun onDestroy() {
        if (relocating) {
            super.onDestroy()
            return
        }
        backCoordinator.unregister()
        controllerDevices.unregister()
        libraryFlow.cancel()
        externalDispatcher.cancel()
        libraryArtExecutor.shutdownNow()
        secondaryDisplay.stop()
        launchGeneration++
        prepareCancelled.set(true)
        nativeStop()
        audioGeneration++
        nativeSetSurface(null)
        super.onDestroy()
    }
}
