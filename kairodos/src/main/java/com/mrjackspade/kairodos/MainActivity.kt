package com.mrjackspade.kairodos

import android.app.Activity
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
import com.mrjackspade.kairo.frontend.GuestKeyboardPanel
import com.mrjackspade.kairo.frontend.KeyMappingMode
import com.mrjackspade.kairo.frontend.AboutDocuments
import com.mrjackspade.kairo.frontend.InputRouter
import com.mrjackspade.kairo.frontend.InputModeDecider
import com.mrjackspade.kairo.frontend.MouseInputRouter
import com.mrjackspade.kairo.frontend.TouchSettingsCoordinator
import com.mrjackspade.kairo.frontend.TouchSettingsStore
import com.mrjackspade.kairo.frontend.TouchUiCoordinator
import com.mrjackspade.kairo.frontend.ArtworkCoordinator
import com.mrjackspade.kairo.frontend.TouchInputPolicy
import com.mrjackspade.kairo.frontend.TouchInputSelection
import com.mrjackspade.kairo.frontend.ScopedTouchInput
import com.mrjackspade.kairo.frontend.Ui
import com.mrjackspade.kairo.frontend.DirectoryPicker
import com.mrjackspade.kairo.frontend.LibraryScreen
import com.mrjackspade.kairo.frontend.LibraryScanSummary
import com.mrjackspade.kairo.frontend.LibraryFlow
import com.mrjackspade.kairo.frontend.LibraryMenu
import com.mrjackspade.kairo.frontend.ExternalGameIntent
import com.mrjackspade.kairo.frontend.ExternalGameDispatcher
import com.mrjackspade.kairo.frontend.FrontendBackCoordinator
import com.mrjackspade.kairo.frontend.GuestLifecycleCoordinator
import com.mrjackspade.kairo.frontend.ControllerDeviceMonitor
import com.mrjackspade.kairo.frontend.InputDispatchCoordinator
import com.mrjackspade.kairo.frontend.FrontendInputScreens
import com.mrjackspade.kairo.frontend.SecondaryDisplayCoordinator
import com.mrjackspade.kairo.frontend.RgDsDisplayRouter
import com.mrjackspade.kairo.frontend.ImmersiveWindow
import com.mrjackspade.kairo.frontend.ViewScale
import com.mrjackspade.kairo.frontend.EdgeSwipeNavigation
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GameSettingScope
import com.mrjackspade.kairo.frontend.LibraryStrings
import com.mrjackspade.kairo.frontend.SessionFlow
import com.mrjackspade.kairo.frontend.SessionMenus
import com.mrjackspade.kairo.frontend.SessionNavigationCoordinator
import com.mrjackspade.kairo.frontend.GameOrientation
import com.mrjackspade.kairo.frontend.SessionNavigationState
import com.mrjackspade.kairo.frontend.JoystickInputRouter
import com.mrjackspade.kairo.frontend.GamepadMapper
import com.mrjackspade.kairo.frontend.ControllerPages
import com.mrjackspade.kairo.frontend.ControllerProfileStore
import com.mrjackspade.kairo.frontend.ControllerProfileCoordinator
import com.mrjackspade.kairo.frontend.CatalogUpdateController
import com.mrjackspade.kairo.frontend.OnScreenControls
import com.mrjackspade.kairo.frontend.GraphicsOptions
import com.mrjackspade.kairo.frontend.ShaderOptions
import com.mrjackspade.kairo.frontend.GameDeletionFlow
import com.mrjackspade.kairo.frontend.PageStack
import com.mrjackspade.kairo.frontend.GroupsPage
import com.mrjackspade.kairo.frontend.SaveUnit
import com.mrjackspade.kairo.frontend.SaveLocation
import com.mrjackspade.kairo.frontend.SaveFolderSettings
import com.mrjackspade.kairo.frontend.AppSaveLocation
import com.mrjackspade.kairo.frontend.ChoicePage
import com.mrjackspade.kairo.frontend.SetupPage
import com.mrjackspade.kairo.frontend.FooterButton
import com.mrjackspade.kairo.frontend.SettingsRow
import com.mrjackspade.kairo.frontend.RowGroup
import com.mrjackspade.kairo.frontend.ChoiceSpec
import com.mrjackspade.kairo.frontend.ChoiceGroup
import com.mrjackspade.kairo.frontend.ChoiceOption
import com.mrjackspade.kairo.frontend.TrayButton
import com.mrjackspade.kairo.frontend.ButtonTone
import com.mrjackspade.kairo.frontend.ValueTone
import com.mrjackspade.kairo.frontend.GameOverlay
import com.mrjackspade.kairo.frontend.GameNotice
import com.mrjackspade.kairo.frontend.GameValue
import com.mrjackspade.kairo.frontend.GameSource
import com.mrjackspade.kairo.frontend.GameSettingsRows
import com.mrjackspade.kairo.frontend.gameSettingsPage
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** DOS library and session UI. Staging is compiled in :backend-dos and hosted via JNI. */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private external fun nativeRun(config: String, saveDir: String, resources: String): Boolean
    private external fun nativeStop()
    private external fun nativePause(value: Boolean)
    private external fun nativeReset()
    private external fun nativeListDirectory(path: String): String
    private external fun nativeStatus(): Int
    private external fun nativeInputTelemetry(): LongArray
    private external fun nativeCpuTelemetry(): LongArray
    private external fun nativeAudioRate(): Int
    private external fun nativeAspect(): Double
    private external fun nativeVideoWidth(): Int
    private external fun nativeVideoHeight(): Int
    private external fun nativeLastError(): String
    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeSetShaderPreset(path: String?)
    private external fun nativeKey(code: Int, down: Boolean)
    private external fun nativeJoypad(id: Int, down: Boolean)
    private external fun nativeMouseMove(dx: Int, dy: Int)
    private external fun nativeMouseButton(button: Int, down: Boolean)
    private external fun nativePointer(x: Int, y: Int, pressed: Boolean)
    private external fun nativeConfigure(mouseMode: Int, cyclesMode: Int, voodooMode: Int)
    private external fun nativeReadAudio(buffer: ShortArray, maxFrames: Int): Int

    companion object {
        private const val OVERLAY_UNIT = "overlay"
        private const val SAVED_COPY = "saved"
        private const val EDITED_FILE = "edited"
        private const val WORKING_OVERLAY = "staging/overlay-working"
        private const val UNSAVED_OVERLAY = "staging/overlay-working.unsaved"
        private const val PICK_FOLDER = 1001
        private val CPU_OPTIONS = listOf(ChoiceOption("0", "Auto"), ChoiceOption("1", "Maximum"))
        private val VOODOO_OPTIONS = listOf(ChoiceOption("0", "Automatic CPU threads"),
            ChoiceOption("1", "Single CPU thread"))
        init {
            // Load the shared C++ runtime on its own, before any DOS session. Otherwise the
            // DOSBox core loads it as part of its own dlopen group; long-lived threads of the
            // shader runtime then hold that group, and the core can never unload between games.
            System.loadLibrary("c++_shared")
            System.loadLibrary("kairodos_host")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("kairodos", MODE_PRIVATE) }
    private val graphics by lazy {
        GraphicsOptions(this, preferences) {
            updateSurfaceLayout()
            refreshPages()
        }
    }
    private val shaderOptions by lazy {
        ShaderOptions(this, preferences, { if (::pages.isInitialized) pages else null }, { path ->
            nativeSetShaderPreset(path)
            recreateShaderSurface()
            refreshPages()
        }, { message -> Ui.message(this, message) })
    }
    private val gameSettings by lazy { GameSettingScope(preferences) }
    private val dosGameSettings by lazy { DosPerGameSettings(preferences, gameSettings) }
    private val controllerProfiles by lazy {
        ControllerProfileStore(preferences, DosControllerBindings::parse,
            { DosControllerBindings.toJson(it).toString() }, { DosControllerBindings.defaults(it) })
    }
    private val keys = InputRouter(::nativeKey, 341, DosKeyboardLayout.value.lockCodes)
    private val inputModeDecider = InputModeDecider()
    private val mouse = MouseInputRouter(::nativeMouseMove, ::nativeMouseButton)
    private val joystick = JoystickInputRouter(::nativeJoypad,
        DosControllerBindings.joystick)
    private val gamepad = GamepadMapper(keys, joystick, mouse,
        { action -> sessionFlow?.controllerAction(action, sessionState, ::refreshControllerUi) }, {},
        DosControllerBindings.defaults())
    private val controllerFlow by lazy {
        ControllerProfileCoordinator(controllerProfiles, gamepad,
            { game: DosLibrary.Game -> game.key.takeIf { game.contentId != null } },
            { game: DosLibrary.Game ->
                catalog.gameControllerBindings(game, controllerProfiles.configuration.layout,
                    dosGameSettings.controllerBindings(game.key))
                    ?: controllerProfiles.global()
            },
            { id, bindings -> dosGameSettings.setControllerBindings(id,
                DosControllerBindings.toJson(bindings).toString()) },
            dosGameSettings::clearControllerBindings, { currentGame })
    }
    private val keyMapping by lazy {
        KeyMappingMode(controllerFlow::activeBindings, controllerFlow::saveActive, controllerFlow::physical,
            { inputDispatch.guestCapture = it })
    }
    private var onScreenControls: OnScreenControls? = null
    private val dosLibrary by lazy { DosLibrary(this) }
    private val saveFolder by lazy {
        SaveFolderSettings(this, pages, preferences, AppSaveLocation(File(filesDir, "dos-saves")), setOf(OVERLAY_UNIT),
            ::saveTitle, { "Saved files" },
            { if (currentGame != null) "Return to the library to change the save folder." else null },
            ::prepareSaveMove)
    }
    private val externalDispatcher by lazy {
        ExternalGameDispatcher(this, contentResolver, { games },
            { game: DosLibrary.Game -> Uri.parse(game.uri) },
            { file, cancelled ->
                val game = claimSavedData(Uri.EMPTY, listOf(dosLibrary.inspectExternal(file, cancelled))).single()
                listOf(game.also { it.error?.let(::error) })
            },
            { entries, _ -> launch(entries.single(), true) },
            libraryScreen::showStatus, "KairoDos-external-game")
    }
    private val catalog by lazy { DosGameCatalog(this) }
    /** Saves and settings from before games had keys move to their games as the library loads. */
    private val gameKeyMigration by lazy {
        val saves = File(filesDir, "saves")
        GameKeyMigration(preferences, listOf(
            GameKeyMigration.PreferenceStore(preferences, DosPerGameSettings.LEGACY),
            catalog.legacyOverrides(),
            GameKeyMigration.SaveStore({ saveFolder.location }, setOf(OVERLAY_UNIT), cacheDir,
                File(filesDir, "game-key-migration-v1.json")) { conflicts ->
                saveFolder.resolveMigrationConflicts(conflicts) { key ->
                    claiming.firstOrNull { it.key == key }?.let(::titleOf) ?: key.path
                }
            }), listOf(LegacySaveDirectories(saves), LegacyLastPlayed()))
    }

    /** A game's local working files were named by its entry's earlier ID. */
    private class LegacySaveDirectories(private val root: File) : GameKeyMigration.EntryStore<DosLibrary.Game> {
        private var legacy = emptySet<String>()
        override fun refresh() {
            legacy = root.list().orEmpty().filterNot { it.startsWith(GameKey.STORAGE_PREFIX) }.toSet()
        }
        override fun has(claim: GameKeyMigration.Claim<DosLibrary.Game>) = claim.entryId in legacy
        override fun move(claim: GameKeyMigration.Claim<DosLibrary.Game>) {
            val from = File(root, claim.entryId!!)
            val to = File(root, claim.key.storageName)
            if (to.exists()) {
                // Only a drive from before overlays or an unstored session is saved data here.
                require(!File(from, "staging/drive").exists() && !File(from, UNSAVED_OVERLAY).exists()) {
                    "This game's saved files are in two places: ${from.name} and ${to.name}"
                }
                check(from.deleteRecursively()) { "Could not remove the game's earlier working files" }
            } else check(from.renameTo(to)) { "Could not move the game's working files" }
            legacy = legacy - from.name
        }
        override fun isEmpty() = legacy.isEmpty()
    }

    private inner class LegacyLastPlayed : GameKeyMigration.EntryStore<DosLibrary.Game> {
        override fun has(claim: GameKeyMigration.Claim<DosLibrary.Game>) =
            preferences.getString("last_played_entry", null) == claim.entryId
        override fun move(claim: GameKeyMigration.Claim<DosLibrary.Game>) {
            check(preferences.edit().putString("last_played_entry", claim.key.path).commit()) {
                "Could not remember the last played game"
            }
        }
        // A last-played game no longer in the library is not saved data.
        override fun isEmpty() = true
    }

    /** Moves saves and settings from before games had keys to [entries]; a game whose data did not move shows why. */
    /** The games a claim is moving data to; the library list is not published until it ends. */
    @Volatile private var claiming: List<DosLibrary.Game> = emptyList()

    private fun claimSavedData(folder: Uri, entries: List<DosLibrary.Game>): List<DosLibrary.Game> {
        if (!gameKeyMigration.pending) return entries
        claiming = entries
        val errors = gameKeyMigration.claim(entries.map {
            GameKeyMigration.Claim(it, dosLibrary.legacyEntryId(it, folder))
        })
        return if (errors.isEmpty()) entries else entries.map { game ->
            errors[game.key]?.let { game.copy(error = it) } ?: game
        }
    }
    private val artworkDownloads by lazy {
        com.mrjackspade.kairo.frontend.CatalogArtworkDownloadController(
            this, libraryScreen, { games }, catalog::missingArtworkFor, catalog::downloadArtwork) { refreshPages() }
    }
    private val artwork by lazy {
        ArtworkCoordinator(this, pages, catalog.artworkStore, DosLibrary.Game::key,
            { game, kind ->
                val record = catalog.resolve(game)
                ArtworkCoordinator.Record(if (kind == "preview") record.preview else record.boxArt, null,
                    catalog.sourceOf(game, "artwork", kind))
            }, { DosCatalogFields.safeArtPath(it) != null }, catalog::setArtworkOverride,
            { game -> libraryScreen.metadataChanged(game) }, ::titleOf, { Ui.message(this, it) })
    }
    private val catalogUpdates by lazy {
        CatalogUpdateController(this, catalog::downloadUpdate,
            { message -> if (!message.endsWith("\u2026")) Ui.message(this, message) },
            { libraryScreen.refreshCatalog(); controllerFlow.refresh(null) }, {
                if (libraryScreen.visibility == View.VISIBLE) Ui.message(this, "Catalogs updated")
            }, { libraryMenu.showCatalogProgress(it) }, catalog.installedCatalogs::update)
    }
    private lateinit var libraryFlow: LibraryFlow<DosLibrary.Game>
    private lateinit var libraryMenu: LibraryMenu<DosLibrary.Game>
    private lateinit var pages: PageStack
    private lateinit var overlay: GameOverlay
    private var firstRun: SetupPage? = null
    private val inputDispatch: InputDispatchCoordinator by lazy {
        InputDispatchCoordinator(FrontendInputScreens(
            { if (::pages.isInitialized) pages else null },
            { if (::libraryFlow.isInitialized) libraryScreen else null },
            { if (::overlay.isInitialized) overlay else null },
            { currentGame != null }, { false }, backCoordinator::handle),
            gamepad, keys, ::mapKey, backCoordinator::handle,
            { if (sessionFlow?.isOpen == true) closeMenu() else openMenu() },
            {
                if (::appRoot.isInitialized && appRoot.isInTouchMode)
                    (currentFocus ?: appRoot).requestFocusFromTouch()
            }, ::releaseTouchInputs)
    }
    private val controllerDevices by lazy { ControllerDeviceMonitor(this, inputDispatch::releaseDevice) {
        controllerProfiles.configuration.devicesChanged()
        onScreenControls?.devicesChanged()
        if (currentGame == null) gamepad.bindings = controllerFlow.global()
    } }
    private var controllerSetupPending = false
    private var pendingControllerExternalRequest = false
    private val guestLifecycle: GuestLifecycleCoordinator by lazy {
        GuestLifecycleCoordinator(fullscreen::restore, ::releaseGuestInputs, ::refreshControllerUi,
            { audio?.pause() }, { if (currentGame != null) audio?.play() },
            companionActive = { secondaryDisplay.isCompanionActive },
            companionHoldsStop = { secondaryDisplay.holdsStoppedPrimary },
            resetGestures = edgeSwipes::reset)
    }
    private val backCoordinator: FrontendBackCoordinator by lazy {
        FrontendBackCoordinator(this, { if (::pages.isInitialized) pages else null }, libraryScreen,
            { if (!sessionNavigation.resumeGame()) finish() }, { keyboard },
            { if (currentGame != null) openMenu() else finish() }, { false },
            { touchUi.hideKeyboard() })
    }
    private val fullscreen by lazy {
        com.mrjackspade.kairo.frontend.GameFullscreen(this) {
            currentGame != null && ::libraryFlow.isInitialized && libraryScreen.visibility != View.VISIBLE
        }
    }
    private val sessionState = SessionNavigationState()
    private val gameOrientation by lazy { GameOrientation(this, preferences) }
    private val sessionNavigation: SessionNavigationCoordinator by lazy {
        SessionNavigationCoordinator(sessionState, libraryScreen, { sessionFlow },
            { currentGame != null }, { sessionFromFrontend }, { leaveGame() }, edgeSwipes::reset,
            {
                releaseGuestInputs()
                touchUi.hideKeyboard()
                swappedKeyboard?.close()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }, {
                secondaryDisplay.setLibraryInfo(null)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }, libraryFlow::show, ::refreshControllerUi, { surface?.requestFocus() }, ::endSessionForLibrary,
            gameOrientation)
    }
    private val libraryScreen: LibraryScreen<DosLibrary.Game> get() = libraryFlow.screen
    private val touchUi by lazy {
        TouchUiCoordinator(this, libraryScreen,
            { currentGame != null && libraryScreen.visibility != View.VISIBLE },
            { ::secondaryDisplay.isInitialized && secondaryDisplay.isKeyboardVisible },
            ::updateSurfaceLayout, ::refreshControllerUi)
    }
    private val touchSettingsCoordinator by lazy {
        TouchSettingsCoordinator(object : TouchSettingsStore<DosLibrary.Game> {
            override fun global() = TouchInputSelection(
                touchMode(preferences.getInt("touch_mode", 2)),
                preferences.getBoolean("direct_touch", false))
            override fun saveGlobal(value: TouchInputSelection) {
                preferences.edit().putInt("touch_mode", touchModeIndex(value.mode))
                    .putBoolean("direct_touch", value.directTouch).apply()
                configureGuest()
            }
            override fun mode(game: DosLibrary.Game) = gameInt(game, "touch_mode", ::touchMode, ::touchModeIndex) {
                touchMode(preferences.getInt("touch_mode", 2))
            }
            override fun directTouch(game: DosLibrary.Game): GameValue<Boolean> {
                val id = game.key
                return GameValue({ if (gameSettings.has(id, "direct_touch")) gameSettings.boolean(id, "direct_touch", false) else null },
                    { preferences.getBoolean("direct_touch", false) }, { GameSource.ALL_GAMES },
                    { value -> if (value == null) gameSettings.clear(id, "direct_touch")
                        else gameSettings.setBoolean(id, "direct_touch", value) })
            }
        }, listOf(
            InputModeDecider.Mode.AUTO to ChoiceOption("auto", "Auto", "Follows what the game reads"),
            InputModeDecider.Mode.KEYBOARD to ChoiceOption("keyboard", "Keyboard", "Tap opens the DOS keyboard"),
            InputModeDecider.Mode.MOUSE to ChoiceOption("mouse", "Mouse")),
            "Touchpad moves the DOS mouse by dragging. Direct tap positions it at your " +
                "finger; some games require relative movement.") { game ->
            if (game == null || currentGame?.id == game.id) {
                inputModeDecider.reset()
                configureGuest()
            }
        }
    }

    /** A per-game value stored as an int beside the all-games preference. */
    private fun <V> gameInt(game: DosLibrary.Game, key: String, read: (Int) -> V, write: (V) -> Int,
                            global: () -> V): GameValue<V> {
        val id = game.key
        return GameValue({ if (gameSettings.has(id, key)) read(gameSettings.int(id, key, 0)) else null },
            global, { GameSource.ALL_GAMES },
            { value -> if (value == null) gameSettings.clear(id, key) else gameSettings.setInt(id, key, write(value)) })
    }
    private lateinit var appRoot: FrameLayout
    private var gameRoot: FrameLayout? = null
    private lateinit var controllerPages: ControllerPages<DosLibrary.Game>
    private val tree: Uri? get() = libraryFlow.tree
    private var prepareCancelled = AtomicBoolean(false)
    private val games: List<DosLibrary.Game> get() = libraryFlow.entries
    private var currentGame: DosLibrary.Game? = null
    private var sessionFromFrontend = false
    private var installerPromptOpen = false
    private var finishAfterInstallerPrompt = false
    private var gameThread: Thread? = null
    private var programPicker: DirectoryPicker? = null
    private var installerNotice: GameNotice? = null
    private var pendingProgramPicker: (() -> Unit)? = null
    @Volatile private var launchGeneration = 0
    private var audioThread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var audioGeneration = 0
    private var surface: SurfaceView? = null
    private var videoFrame: FrameLayout? = null
    private var keyboard: GuestKeyboardPanel? = null
    private var swappedKeyboard: GuestKeyboardPanel? = null
    private lateinit var secondaryDisplay: SecondaryDisplayCoordinator
    private var sessionGameTitle: String? = null
    private var sessionFlow: SessionFlow? = null
    private var userPaused: Boolean
        get() = sessionState.userPaused
        set(value) { sessionState.userPaused = value }
    private val edgeSwipes by lazy { EdgeSwipeNavigation(resources.displayMetrics.density) }
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFingers = false
    private var directPointerPressed = false
    private var relocating = false
    private lateinit var viewScale: ViewScale

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (RgDsDisplayRouter.routeToUpper(this)) {
            relocating = true
            return
        }
        graphics.load()
        nativeSetShaderPreset(shaderOptions.selectedPath())
        ImmersiveWindow.apply(this)
        viewScale = ViewScale(this)
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
            { nativeAspect().takeIf { it in 0.5..3.0 } ?: 4.0 / 3.0 }, keyMapping)
        pages = PageStack(this, appRoot)
        artwork.restoreInstanceState(savedInstanceState)
        val strings = LibraryStrings("KAIRODOS", "KairoDos", "DOS folder", "No DOS folder selected")
        val libraryPage = LibraryScreen(this, catalog, strings, pages, ::chooseFolder,
            { preferences.getString("last_played_entry", null) }, ::launch,
            ::previewGame, ::showGameDetails, ::showLibrarySelection,
            commandLaunch = { startGame(it, manualConfig(it), target = DosLaunchTarget.Browse) })
        libraryFlow = LibraryFlow(this, preferences, libraryPage, PICK_FOLDER,
            { folder -> claimSavedData(folder, dosLibrary.cached(folder)) },
            { folder, force, cancelled, progress ->
                claimSavedData(folder, dosLibrary.scan(folder, force, cancelled, progress))
            },
            "Choose a DOS folder to find games",
            "DOS folder needs read and write access. Select it again.",
            { found -> LibraryScanSummary.format(found, dosLibrary.hashCount) },
            onFolderSelected = { finishFirstRun() },
            onFolderError = { message -> if (firstRun?.inStack == true) Ui.message(this, message) },
            writable = true, store = dosLibrary::store)
        libraryMenu = LibraryMenu(this, pages, libraryFlow, catalog, strings,
            { catalogUpdates.check(false) }, { artworkDownloads }, ::settingsRows, ::finish)
        libraryPage.openMenuAction = libraryMenu::open
        appRoot.addView(libraryPage, 0, FrameLayout.LayoutParams(-1, -1))
        onScreenControls = OnScreenControls(this, appRoot, gamepad, preferences,
            ::refreshControllerUi, { controllerProfiles.configuration.layout }).also { it.pages = pages }
        overlay = GameOverlay(this)
        appRoot.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        pages.menuKey = { if (libraryScreen.visibility == View.VISIBLE) libraryScreen.menuKey() else closeMenu() }
        pages.base = { libraryScreen.takeIf { it.visibility == View.VISIBLE } }
        sessionFlow = SessionFlow(pages, ::gameFlyout,
            { currentGame != null && libraryScreen.visibility != View.VISIBLE },
            {
                keyboard?.close()
                releaseGuestInputs()
            }, { refreshControllerUi() }, { surface?.requestFocus() })
        controllerPages = ControllerPages(this, pages,
            controllerFlow::load, controllerFlow::save, controllerFlow::reset,
            { controllerProfiles.globalCustom },
            controllerFlow::physical, controllerFlow::savePhysical, controllerFlow::resetPhysical,
            { controllerProfiles.physicalCustom },
            { controllerFlow.deadZone }, { controllerFlow.deadZone = it },
            DosControllerBindings.spec, { DosControllerBindings.toJson(it) }, controllerProfiles.configuration,
            { releaseGuestInputs(); controllerFlow.refresh(null) }, ::controllerMappingStatus, ::titleOf,
            onScreenControls!!, touchSettingsCoordinator) { releaseGuestInputs() }
        controllerFlow.initialize()
        controllerDevices.register(handler)
        backCoordinator.register()
        // Match Kairo98's startup order: attach the library first, then restore
        // its cached entries after Android gets a chance to draw the window.
        controllerSetupPending = controllerProfiles.configuration.needsSetup
        appRoot.post {
            if (isFinishing || isDestroyed) return@post
            if (controllerSetupPending) {
                pages.push(controllerProfiles.configuration.setupPage("KAIRODOS", ::finish) {
                    controllerSetupPending = false
                    controllerFlow.initialize()
                    pages.closeAll()
                    continueStartup(savedInstanceState)
                })
            } else continueStartup(savedInstanceState)
        }
    }

    private fun continueStartup(savedInstanceState: Bundle?) {
        libraryFlow.restore()
        showLibrary()
        val externallyRequested = (savedInstanceState == null || pendingControllerExternalRequest ||
            savedInstanceState.getBoolean("controller_setup_external_v1")) &&
            ExternalGameIntent.hasRequest(intent)
        if (externallyRequested)
            dispatchExternalGame(intent)
        else {
            if (tree != null) refreshLibrary(false)
            if (!preferences.getBoolean("onboarding_complete_v1", tree != null)) showFirstRun()
        }
        catalogUpdates.check(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("controller_setup_external_v1", controllerSetupPending && ExternalGameIntent.hasRequest(intent))
        artwork.saveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (RgDsDisplayRouter.isReturnToUpper(intent)) return
        setIntent(intent)
        if (controllerSetupPending) {
            pendingControllerExternalRequest = true
            return
        }
        dispatchExternalGame(intent)
    }

    /** A frontend launch matches the game against the library, so it waits for the library to load. */
    private fun dispatchExternalGame(intent: Intent) {
        closeFirstRun()
        libraryFlow.whenLoaded { externalDispatcher.dispatch(intent) }
    }

    private fun showLibrary() {
        if (::secondaryDisplay.isInitialized) secondaryDisplay.setLibraryInfo(null)
        currentGame = null
        gameRoot?.let(appRoot::removeView)
        gameRoot = null
        sessionNavigation.showLibrary()
    }

    private fun resumeGameFromLibrary() { sessionNavigation.resumeGame() }

    private fun chooseFolder() = libraryFlow.chooseFolder()

    /** The single setup step. Back and Skip for now both open the library. */
    private fun showFirstRun() {
        val page = SetupPage("KAIRODOS", null, "Choose a DOS folder",
            "Select a writable folder containing your DOS games and ZIP archives.",
            "You can choose or change it later in Menu \u203a Manage library.",
            footerButtons = {
                listOf(FooterButton("skip", { "Skip for now" }, run = ::finishFirstRun),
                    FooterButton("choose", { "Choose DOS folder\u2026" }, ButtonTone.PRIMARY, run = ::chooseFolder))
            }, onBack = ::finishFirstRun, startFocus = "choose")
        firstRun = page
        pages.push(page)
    }

    private fun finishFirstRun() {
        preferences.edit().putBoolean("onboarding_complete_v1", true).apply()
        closeFirstRun()
        if (tree != null) refreshLibrary(false)
        else libraryScreen.showStatus("Choose a DOS folder from the library menu when you\u2019re ready")
    }

    private fun closeFirstRun() {
        if (firstRun?.inStack == true) pages.closeAll()
        firstRun = null
    }

    @Deprecated("Legacy Back path; API 33+ uses OnBackInvokedDispatcher")
    override fun onBackPressed() = backCoordinator.handle()

    @Deprecated("The platform Activity uses onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (shaderOptions.handleActivityResult(requestCode, resultCode, data)) return
        if (libraryMenu.handleActivityResult(requestCode, resultCode, data)) return
        if (artwork.handleActivityResult(requestCode, resultCode, data)) return
        if (saveFolder.handleActivityResult(requestCode, resultCode, data)) return
        libraryFlow.handleActivityResult(requestCode, resultCode, data)
    }

    private fun refreshLibrary(forceHash: Boolean) = libraryFlow.refresh(forceHash)

    private fun showLibrarySelection(entry: DosLibrary.Game?) {
        if (!::secondaryDisplay.isInitialized) return
        secondaryDisplay.showLibrarySelection(entry, entry?.let(libraryScreen::metadata), catalog)
    }

    private fun previewGame(game: DosLibrary.Game) = artwork.view(game)

    private fun titleOf(game: DosLibrary.Game) = catalog.resolve(game).title

    /** Game settings over the game page, or over the game flyout while the game runs. */
    private fun showGameDetails(entry: DosLibrary.Game) {
        pages.push(gameSettingsPage({ titleOf(entry) }) { gameSettings(entry) })
    }

    private fun gameSettings(entry: DosLibrary.Game): List<RowGroup> {
        // Settings belong to the game's key, once its files have been read.
        val id = entry.key.takeIf { entry.contentId != null }
        val hashed = { id != null }
        val noHash = { GameSettingsRows.NO_HASH }
        val scope = { titleOf(entry) }
        val record = catalog.resolve(entry)
        val variants = record.launch?.configs?.keys.orEmpty()
            .sortedWith(compareBy<String> { it != "dosbox.conf" }.thenBy { it })
        fun applyLive() { if (currentGame?.id == entry.id) configureGuest() }
        fun intValue(key: String, global: () -> Int) = if (id == null) GameValue({ null }, { global().toString() },
            { GameSource.ALL_GAMES }, { }) else GameValue(
            { if (gameSettings.has(id, key)) gameSettings.int(id, key, 0).toString() else null },
            { global().toString() }, { GameSource.ALL_GAMES },
            { value ->
                if (value == null) gameSettings.clear(id, key) else gameSettings.setInt(id, key, value.toInt())
                applyLive()
            })
        val controllerSource = {
            when {
                id == null -> GameSource.ALL_GAMES
                dosGameSettings.controllerBindings(id) != null -> GameSource.CUSTOM
                // Controller profiles come from the bundled profile catalog.
                catalog.resolve(entry).controllerProfile != null -> "Shipped catalog"
                else -> GameSource.ALL_GAMES
            }
        }
        val deleteKind = if (entry.external || entry.rootFolder) null else if (entry.folder) "folder" else "file"
        return listOfNotNull(
            RowGroup("CONTROLS", listOf(controllerPages.gameRow(entry, hashed, controllerSource)) +
                (if (id != null) touchSettingsCoordinator.gameRows(entry, scope, hashed) else emptyList())),
            RowGroup("MACHINE", listOfNotNull(
                GameSettingsRows.choice("cpu", "DOS CPU speed", scope, CPU_OPTIONS,
                    intValue("cycles_mode") { preferences.getInt("cycles_mode", 0).coerceIn(0, 1) }, hashed, noHash),
                GameSettingsRows.choice("video", "Emulated video card", scope, videoOptions(),
                    intValue(DosVideoHardware.setting) { dosGameSettings.videoHardware() }, hashed, noHash),
                GameSettingsRows.choice("voodoo", "3dfx rendering", scope, VOODOO_OPTIONS,
                    intValue("staging_voodoo_threads") { preferences.getInt("staging_voodoo_threads", 0).coerceIn(0, 1) },
                    hashed, noHash),
                if (variants.size > 1) SettingsRow.choice("variant", "Startup variant",
                    ChoiceSpec("Startup variant", scope, {
                        listOf(ChoiceGroup(null, variants.map { ChoiceOption(it, variantLabel(it)) }))
                    }, { id?.let(dosGameSettings::startupVariant) },
                        select = { picked -> id?.let { dosGameSettings.setStartupVariant(it, picked) } }, cycles = false),
                    value = { id?.let(dosGameSettings::startupVariant)?.let(::variantLabel) ?: "Choose on first play" },
                    tone = { if (id?.let(dosGameSettings::startupVariant) == null) ValueTone.EMPTY else ValueTone.CURRENT },
                    enabled = hashed, disabledReason = noHash) else null,
                if (DosLaunchConfig.needsPlayer(record.launch)) playerNameRow(entry, hashed) else null),
                "Video changes apply the next time the game starts."),
            RowGroup("LIBRARY", listOf(
                GameSettingsRows.text("title", "Title", "Game title", { titleOf(entry) },
                    { id != null && catalog.sourceOf(entry, "title") == "User override" },
                    { id?.let { GameSource.label(catalog.sourceOf(entry, "title")) } ?: "Filename" },
                    { value -> updateTitle(id, value) }, { updateTitle(id, null) },
                    enabled = hashed, disabledReason = noHash),
                artwork.row(entry, "boxArt", hashed),
                artwork.row(entry, "preview", hashed)),
                "Chosen images are copied into your library."),
            RowGroup("FILE", GameSettingsRows.file(entry.path, null, entry.contentId, entry.error) + listOfNotNull(
                deleteKind?.let { kind ->
                    GameDeletionFlow.row(this, libraryFlow, entry, GameDeletionFlow.Prompt(entry.path, kind,
                        if (entry.folder) "This removes the folder and everything inside it. " else ""),
                        "DOS folder", { currentGame?.id == entry.id },
                        "KairoDos-delete-game") { dosLibrary.deleteSource(entry) }
                })),
            RowGroup(null, listOf(GameSettingsRows.reset(hashed) { resetGameSettings(entry, id!!) })))
    }

    private fun variantLabel(name: String) = if (name == "dosbox.conf") "Default" else name

    private fun videoOptions() = DosVideoHardware.choices.mapIndexed { index, (_, label) ->
        ChoiceOption(index.toString(), label)
    }

    private fun updateTitle(id: GameKey?, value: String?) {
        id ?: return
        runCatching { catalog.setTitle(id, value) }
            .onSuccess { libraryScreen.metadataChanged(id) }
            .onFailure { Ui.message(this, it.message ?: "Could not save title") }
        refreshPages()
    }

    /** The name a game asks for, such as for saves, edited in its tray and checked on Save. */
    private fun playerNameRow(entry: DosLibrary.Game, enabled: () -> Boolean): SettingsRow {
        val id = entry.key.takeIf { entry.contentId != null }
        return GameSettingsRows.text("player", "DOS player name", "1\u20138 letters, digits, or underscores",
            { id?.let(dosGameSettings::playerName) }, { false }, { null },
            { value -> savePlayerName(id, value) }, {}, empty = "Choose on first play",
            enabled = enabled, disabledReason = { GameSettingsRows.NO_HASH })
    }

    private fun savePlayerName(id: GameKey?, value: String): Boolean {
        id ?: return false
        val name = value.trim().uppercase(java.util.Locale.ROOT)
        if (!name.matches(Regex("[A-Z0-9_]{1,8}"))) {
            Ui.message(this, "Use 1\u20138 letters, digits, or underscores")
            return false
        }
        dosGameSettings.setPlayerName(id, name)
        refreshPages()
        return true
    }

    private fun resetGameSettings(entry: DosLibrary.Game, id: GameKey) {
        try {
            catalog.resetOverrides(id)
            dosGameSettings.reset(id)
        } catch (failure: Exception) {
            Ui.message(this, failure.message ?: "Could not reset game settings")
            return
        }
        if (currentGame?.id == entry.id) {
            gamepad.bindings = controllerFlow.load(currentGame)
            inputModeDecider.reset()
            configureGuest()
        }
        libraryScreen.metadataChanged(id)
        Ui.message(this, "Game settings reset. Video changes apply the next time the game starts.")
        refreshPages()
    }

    private fun launch(game: DosLibrary.Game, fromFrontend: Boolean = false) {
        val launch = catalog.resolve(game).launch
        val variants = launch?.configs?.keys.orEmpty()
        if (variants.size > 1) {
            val saved = dosGameSettings.startupVariant(game.key)
            if (saved !in variants) {
                chooseLaunchVariant(game, true, fromFrontend)
                return
            }
            startGame(game, saved!!, fromFrontend)
        } else startGame(game, "dosbox.conf", fromFrontend)
    }

    /** Choose a configuration the first time a game with several is played. Back cancels. */
    private fun chooseLaunchVariant(game: DosLibrary.Game, play: Boolean,
                                    fromFrontend: Boolean = false) {
        val launch = catalog.resolve(game).launch ?: return
        val names = launch.configs.keys.sortedWith(compareBy<String> { it != "dosbox.conf" }
            .thenBy { it })
        pages.push(ChoicePage(ChoiceSpec("Startup variant", { titleOf(game) }, {
            listOf(ChoiceGroup(null, names.map { ChoiceOption(it, variantLabel(it)) }))
        }, { dosGameSettings.startupVariant(game.key) }, select = { selected ->
            dosGameSettings.setStartupVariant(game.key, selected)
            if (play) startGame(game, selected, fromFrontend)
        }, stay = play, cycles = false)))
    }

    private fun manualConfig(game: DosLibrary.Game): String {
        val variants = catalog.resolve(game).launch?.configs?.keys.orEmpty()
        return dosGameSettings.startupVariant(game.key)?.takeIf { it in variants }
            ?: variants.firstOrNull { it == "dosbox.conf" } ?: variants.sorted().firstOrNull() ?: "dosbox.conf"
    }

    private fun closeProgramPicker() {
        pendingProgramPicker = null
        val picker = programPicker
        programPicker = null
        picker?.close()
    }

    private fun showProgramPicker(game: DosLibrary.Game, configName: String, generation: Int) {
        if (generation != launchGeneration || isFinishing || isDestroyed) return
        releaseGuestInputs()
        userPaused = true
        refreshControllerUi()
        val mountedDrives = java.util.concurrent.atomic.AtomicReference<Set<Char>>(emptySet())
        programPicker = DirectoryPicker.show(pages, "DOS prompt or run program", DirectoryPicker.Location("", "Launch"), { path ->
            check(generation == launchGeneration) { "Game session ended" }
            val response = nativeListDirectory(path)
            check(response.startsWith("OK\n")) { response.substringAfter('\n') }
            if (path.isEmpty()) mountedDrives.set(response.lineSequence().drop(1)
                .filter { it.startsWith("D\t") }.map { it.substringAfter('\t').first() }.toSet())
            response.lineSequence().drop(1).filter(String::isNotEmpty).mapNotNull { line ->
                val directory = line.startsWith("D\t")
                val id = line.substringAfter('\t')
                if (!directory && id.substringAfterLast('.').lowercase() !in DosLaunchTarget.executableExtensions) null
                else DirectoryPicker.Entry(id, id.trimEnd('\\').substringAfterLast('\\'), directory)
            }.toList()
        }, { entry ->
            programPicker = null
            if (generation == launchGeneration) {
                if (entry.id.isEmpty()) {
                    // Browse has already mounted the media without executing a program.
                    userPaused = false
                    refreshControllerUi()
                } else startGame(game, configName,
                    target = DosLaunchTarget.Program(entry.id, mountedDrives.get()))
            }
        }, {
            if (generation == launchGeneration && programPicker != null) {
                programPicker = null
                userPaused = false
                refreshControllerUi()
            }
        }, rootEntries = listOf(DirectoryPicker.Entry("", "Prompt", false)))
    }

    private fun startGame(game: DosLibrary.Game, configName: String,
                          fromFrontend: Boolean = false, target: DosLaunchTarget = DosLaunchTarget.Normal) {
        if (saveFolder.busy) {
            Ui.message(this, "Wait for the saves to finish moving.")
            return
        }
        val selected = tree
        if (!game.external && selected == null) {
            libraryScreen.showStatus("Choose a DOS folder for this game")
            return
        }
        if (!game.playable) return
        val launch = catalog.resolve(game).launch
        if (target == DosLaunchTarget.Normal && DosLaunchConfig.needsPlayer(launch) &&
            dosGameSettings.playerName(game.key) == null) {
            choosePlayerName(game, configName, fromFrontend)
            return
        }
        val playerName = dosGameSettings.playerName(game.key)
        val availableGames = games.toList()
        val generation = ++launchGeneration
        closeProgramPicker()
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
            gameRoot?.let(appRoot::removeView)
            gameRoot = null
            nativeSetSurface(null)
            surface = null
            videoFrame = null
        }
        inputModeDecider.reset()
        userPaused = false
        controllerProfiles.configuration.endSession()
        currentGame = game
        controllerProfiles.configuration.beginSession()
        sessionFromFrontend = fromFrontend
        gamepad.bindings = controllerFlow.load(game)
        showGame()
        configureGuest()
        gameThread = Thread {
            oldThread?.join()
            var message: String? = null
            val success = try {
                val playableGame = if (game.installer) {
                    val progress: (String) -> Unit = { status ->
                        runOnUiThread {
                            if (generation == launchGeneration) overlay.showProgress(status)
                        }
                    }
                    if (game.external) dosLibrary.installExternal(game, cancelled, progress)
                    else dosLibrary.install(game, selected!!, cancelled, progress).also { installed ->
                        // The archive is in the folder now, whatever happens to this launch.
                        runOnUiThread {
                            if (tree == selected) libraryFlow.edit { dosLibrary.withGame(it, installed) }
                        }
                    }
                } else game
                if (game.installer) moveGame(game.key, playableGame.key)
                // An external installed copy's working files were named by its earlier ID.
                if (game.installer && game.external)
                    claimSavedData(Uri.EMPTY, listOf(playableGame)).single().error?.let(::error)
                val saveDir = saveDir(playableGame)
                require(saveDir.isDirectory || saveDir.mkdirs()) { "Could not create game save folder" }
                // Each mounted game drive writes to its own overlay. Folder sessions are stored after the run.
                val overlays = LinkedHashMap<File, File>()
                val sessions = ArrayList<File>()
                fun overlayFor(dir: File, owner: DosLibrary.Game) = beginOverlay(dir, owner.key).also {
                    if (saveFolder.folder != null) sessions += dir
                }
                val dependencies = (if (target == DosLaunchTarget.Prompt) emptySet() else
                    DosLaunchConfig.requiredFolders(launch, configName))
                    .associateWith { folder ->
                        val ids = catalog.contentIdsForFolder(folder).toSet()
                        val dependency = availableGames.firstOrNull { catalog.matchId(it) in ids }
                            ?: error("This game also needs the $folder archive in your DOS library")
                        val dependencyFile = dosLibrary.prepare(dependency, selected, cancelled)
                        val dependencyDir = saveDir(dependency)
                        val stored = DosStagingStorage.prepare(dependencyFile, dependencyDir, dependency.contentId!!,
                            overlayFor(dependencyDir, dependency), cancelled, folder,
                            { conflicts -> chooseEditedFiles(dependency, conflicts) })
                        overlays[stored.directory] = stored.overlay
                        stored.directory.listFiles().orEmpty().firstOrNull {
                            it.isDirectory && it.name.equals(folder, true)
                        } ?: stored.directory
                    }
                val file = dosLibrary.prepare(playableGame, selected, cancelled)
                if (generation != launchGeneration) return@Thread
                runOnUiThread {
                    if (generation == launchGeneration) overlay.showProgress("Starting DOSBox Staging\u2026")
                }
                val drive = DosStagingStorage.prepare(file, saveDir, playableGame.contentId!!,
                    overlayFor(saveDir, playableGame), cancelled, launch?.folder,
                    { conflicts -> chooseEditedFiles(playableGame, conflicts) }) { progress -> runOnUiThread {
                        if (generation == launchGeneration) overlay.showProgress(progress)
                    } }
                overlays[drive.directory] = drive.overlay
                val resources = DosStagingResources.prepare(this, cancelled)
                val configuration = DosStagingLaunchConfig.write(File(saveDir, "staging/launch.conf"),
                    drive, launch, configName, dependencies, overlays, playerName,
                    effectiveVoodoo() == 1, effectiveDirectTouch(),
                    dosGameSettings.videoHardware(playableGame.key), target)
                if (target == DosLaunchTarget.Browse) runOnUiThread {
                    if (generation == launchGeneration) pendingProgramPicker = {
                        showProgramPicker(playableGame, configName, generation)
                    }
                }
                if (game.installer && !game.external) runOnUiThread {
                    if (generation == launchGeneration) {
                        sessionGameTitle = catalog.resolve(playableGame).title
                        preferences.edit().putString("last_played_entry", playableGame.id).apply()
                        showInstallerRemovalPrompt(game, playableGame)
                    }
                }
                if (cancelled.get() || generation != launchGeneration) return@Thread
                // Each DOS machine boots with Caps Lock off.
                keys.resetLocks()
                val started = nativeRun(configuration.absolutePath, saveDir.absolutePath,
                    resources.absolutePath)
                sessions.forEach(::publishOverlay)
                if (!started) message = nativeLastError().ifBlank {
                    "DOSBox Staging could not start this game."
                }
                started
            } catch (failure: Exception) {
                message = failure.message ?: "Could not prepare this game"
                false
            }
            runOnUiThread {
                if (generation == launchGeneration && currentGame?.id == game.id) {
                    leaveGame(success)
                    if (!success) {
                        Ui.message(this, message ?: "Could not prepare this game", long = true)
                        // Let the message finish before rebuilding views for new display settings.
                        handler.postDelayed({ rebuildIfRescaled() }, 3800)
                    } else rebuildIfRescaled()
                }
            }
        }.apply { name = "KairoDos-emulation"; start() }
        pollSession(game.id)
    }

    /** A game's local working files: its base drive, launch configuration and unsaved session. */
    private fun saveDir(game: DosLibrary.Game) = File(filesDir, "saves/${game.key.storageName}")

    /**
     * Asks, from the launch thread, which saved files give way to the game's edited source files.
     * Waits for the choice; leaving the page cancels the launch.
     */
    private fun chooseEditedFiles(game: DosLibrary.Game, conflicts: List<String>): Set<String> {
        val chosen = java.util.concurrent.LinkedBlockingQueue<Result<Set<String>>>()
        runOnUiThread {
            val useEdited = HashSet<String>()
            var confirmed = false
            val rows = conflicts.mapIndexed { index, path ->
                SettingsRow.choice("edited-$index", path, ChoiceSpec(path, { titleOf(game) }, {
                    listOf(ChoiceGroup(null, listOf(ChoiceOption(SAVED_COPY, "Saved copy"),
                        ChoiceOption(EDITED_FILE, "Edited file"))))
                }, { if (path in useEdited) EDITED_FILE else SAVED_COPY }, select = {
                    if (it == EDITED_FILE) useEdited += path else useEdited -= path
                }, cycles = false), value = { if (path in useEdited) "Edited file" else "Saved copy" })
            }
            pages.push(object : GroupsPage("Edited game files", { titleOf(game) }, {
                listOf(RowGroup("KEEP", rows))
            }, {
                listOf(FooterButton("cancel", { "Cancel" }) { pages.pop() },
                    FooterButton("continue", { "Continue" }, ButtonTone.PRIMARY) {
                        confirmed = true
                        chosen.put(Result.success(useEdited.toSet()))
                        pages.pop()
                    })
            }) {
                override fun onRemoved() {
                    super.onRemoved()
                    if (!confirmed) chosen.put(Result.failure(IllegalStateException("Cancelled")))
                }
            })
        }
        return chosen.take().getOrThrow()
    }

    /** An installer's saves and settings belong to the game it installs. */
    private fun moveGame(from: GameKey, to: GameKey) {
        if (from == to) return
        val oldDir = File(filesDir, "saves/${from.storageName}")
        val newDir = File(filesDir, "saves/${to.storageName}")
        if (oldDir.isDirectory && !newDir.exists())
            require(oldDir.renameTo(newDir)) { "Could not move existing game saves to the installed archive" }
        val saves = saveFolder.location
        val unit = SaveUnit(from.storageName, OVERLAY_UNIT)
        if (saves.exists(unit) && !saves.exists(SaveUnit(to.storageName, OVERLAY_UNIT))) {
            val copy = File(cacheDir, "installer-saves")
            check(!copy.exists() || copy.deleteRecursively()) { "Could not clear an earlier move" }
            try {
                saves.export(unit, copy)
                saves.import(SaveUnit(to.storageName, OVERLAY_UNIT), copy)
            } finally { copy.deleteRecursively() }
            saves.delete(unit)
        }
        dosGameSettings.move(from, to)
        catalog.moveOverrides(from, to)
    }

    /** Games that ask for a DOS player name get one before their first launch. */
    private fun choosePlayerName(game: DosLibrary.Game, playConfig: String,
                                 fromFrontend: Boolean = false) {
        val id = game.key.takeIf { game.contentId != null } ?: return
        var name = dosGameSettings.playerName(id) ?: "PLAYER"
        lateinit var page: GroupsPage
        page = GroupsPage("DOS player name", { titleOf(game) }, {
            listOf(RowGroup(null, listOf(GameSettingsRows.text("player", "Player name",
                "1\u20138 letters, digits, or underscores", { name }, { false }, { null },
                { value -> if (savePlayerName(id, value)) name = dosGameSettings.playerName(id) ?: name },
                {})), "The game uses this name for its saves."))
        }, {
            listOf(FooterButton("play", { "Play" }, ButtonTone.PRIMARY) {
                if (savePlayerName(id, name)) startGame(game, playConfig, fromFrontend)
            })
        })
        pages.push(page)
    }

    /** One settings list, shown the same way in the game flyout and the library flyout. */
    private fun settingsRows() = SessionMenus.settingsRows(SessionMenus.Settings(
        graphics = { pages.push(graphics.page(shaderOptions) { listOf(videoGroup()) }) },
        controls = {
            pages.push(controllerPages.controlsPage(currentGame?.takeIf {
                it.contentId != null && libraryScreen.visibility != View.VISIBLE }))
        },
        machine = { pages.push(machinePage()) },
        configurations = {
            pages.push(SessionMenus.configurationsPage(gameOrientation, saveFolder, "SAVED GAMES"))
        },
        soundOn = { !preferences.getBoolean("muted", false) },
        setSound = { on ->
            preferences.edit().putBoolean("muted", !on).apply()
            audio?.setVolume(if (on) 1f else 0f)
        },
        about = { pages.push(aboutPage()) }))

    /** Graphics › VIDEO, for all games. */
    private fun videoGroup() = RowGroup("VIDEO", listOf(
        SettingsRow.choice("video", "Emulated video card", ChoiceSpec("Emulated video card", { GameSource.ALL_GAMES },
            { listOf(ChoiceGroup(null, videoOptions())) }, { dosGameSettings.videoHardware().toString() },
            select = { preferences.edit().putInt(DosVideoHardware.setting, it.toInt()).apply() })),
        SettingsRow.choice("voodoo", "3dfx rendering", ChoiceSpec("3dfx rendering", { GameSource.ALL_GAMES },
            { listOf(ChoiceGroup(null, VOODOO_OPTIONS)) },
            { preferences.getInt("staging_voodoo_threads", 0).coerceIn(0, 1).toString() },
            select = { preferences.edit().putInt("staging_voodoo_threads", it.toInt()).apply(); configureGuest() }))),
        "Changes apply the next time a game starts. Games with their own setting keep it.")

    /** Configurations: settings for all games, such as screen orientation and where saves go. */

    /** A save folder's game name, as a library title when the game is still in the library. */
    private fun saveTitle(game: String): String =
        games.firstOrNull { it.key.storageName == game }?.let(::titleOf) ?: game

    /**
     * Where a game's overlay lives while it runs: in app storage, or a working copy of the
     * save folder's. [publishOverlay] stores the working copy back when the session ends.
     */
    private fun beginOverlay(saveDir: File, game: GameKey): File {
        val unit = SaveUnit(game.storageName, OVERLAY_UNIT)
        val folder = saveFolder.folder
        if (folder == null) {
            require(!File(saveDir, UNSAVED_OVERLAY).isFile) {
                "This game has saved files that were not stored in the save folder. Choose the folder again."
            }
            return saveFolder.app.directory(unit)
        }
        require(!DosStagingStorage.hasLegacyDrive(saveDir)) {
            "This game's saved files use an older format. Choose Private app storage in " +
                "Configurations › Save folder, then choose the folder again to move them."
        }
        publishOverlay(saveDir)
        val working = File(saveDir, WORKING_OVERLAY)
        require(!working.exists() || working.deleteRecursively()) { "Could not clear the game's working save folder" }
        if (folder.exists(unit)) folder.export(unit, working)
        else require(working.mkdirs()) { "Could not create the game's working save folder" }
        File(saveDir, UNSAVED_OVERLAY).writeText("${folder.tree}\n${game.storageName}")
        return working
    }

    /** Stores a session's working overlay in the save folder. */
    private fun publishOverlay(saveDir: File) {
        val unsaved = File(saveDir, UNSAVED_OVERLAY)
        if (!unsaved.isFile) return
        val (tree, owner) = unsaved.readLines()
        val folder = checkNotNull(saveFolder.folder) { "The save folder for these saved files is no longer selected" }
        require(folder.tree.toString() == tree) { "These saved files belong to a different save folder" }
        // A session from before games had keys named its game by content ID. Its working files
        // moved to its game's key, which now names the session's saves too.
        val game = when {
            owner.startsWith(GameKey.STORAGE_PREFIX) -> owner
            saveDir.name.startsWith(GameKey.STORAGE_PREFIX) -> saveDir.name
            else -> SaveLocation.contentIdName(owner)
        }
        folder.import(SaveUnit(game, OVERLAY_UNIT), File(saveDir, WORKING_OVERLAY))
        check(unsaved.delete()) { "Could not finish storing the game's saved files" }
    }

    /** Before saves move: store unfinished sessions and convert drives from before overlays. */
    private fun prepareSaveMove(progress: (String) -> Unit) {
        val saveDirs = File(filesDir, "saves").listFiles().orEmpty().filter { it.isDirectory }
        saveDirs.forEach(::publishOverlay)
        val library = games.toList()
        val legacy = saveDirs.filter(DosStagingStorage::hasLegacyDrive)
        val missing = legacy.filter { dir -> library.none { saveDir(it) == dir } }
        require(missing.isEmpty()) {
            "These games' saved files use an older format, and moving them needs their game files in the " +
                "library: " + missing.joinToString { DosStagingStorage.legacyContentId(it).let { id -> catalog.resolve(id, id).title } }
        }
        for ((index, saveDir) in legacy.withIndex()) {
            val game = library.first { saveDir(it) == saveDir }
            progress("Converting ${index + 1} of ${legacy.size}…")
            val archive = dosLibrary.prepare(game, tree, AtomicBoolean(false))
            DosStagingStorage.prepare(archive, saveDir, DosStagingStorage.legacyContentId(saveDir),
                saveFolder.app.directory(SaveUnit(game.key.storageName, OVERLAY_UNIT)), AtomicBoolean(false),
                catalog.resolve(game).launch?.folder)
        }
    }

    /** Machine: the DOS CPU speed for all games. */
    private fun machinePage() = GroupsPage("Machine", { GameSource.ALL_GAMES }, {
        listOf(RowGroup("CPU", listOf(SettingsRow.choice("cpu", "DOS CPU speed", ChoiceSpec("DOS CPU speed",
            { GameSource.ALL_GAMES }, { listOf(ChoiceGroup(null, CPU_OPTIONS)) },
            { preferences.getInt("cycles_mode", 0).coerceIn(0, 1).toString() },
            select = { preferences.edit().putInt("cycles_mode", it.toInt()).apply(); configureGuest() }))),
            "Games with their own speed keep it."))
    })

    /** The game flyout: Game settings, settings, then leaving. */
    private fun gameFlyout() = SessionMenus.gameFlyout("KAIRODOS", "KairoDos",
        { sessionGameTitle ?: currentGame?.displayName ?: "" }, ::sessionStatusText, sessionNavigation,
        { emptyList() }, { currentGame != null }, { currentGame?.let(::showGameDetails) }, ::settingsRows,
        restart = {
            userPaused = false
            keys.resetLocks()
            nativeReset()
            closeMenu()
        }, exit = {
            leaveGame()
            if (installerPromptOpen) finishAfterInstallerPrompt = true else finish()
        })

    private fun sessionStatusText(): String {
        val state = when (nativeStatus()) {
            1 -> "Loading"
            2 -> if (userPaused) "Paused" else "Running"
            3 -> "Failed"
            else -> "Stopped"
        }
        return listOfNotNull(state, if (preferences.getBoolean("muted", false)) "Muted" else null)
            .joinToString(" \u00b7 ")
    }

    private fun refreshPages() { if (::pages.isInitialized) pages.refresh() }

    private fun showGame() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val game = currentGame ?: return
        sessionGameTitle = catalog.resolve(game).title
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        gameRoot = root
        gameOrientation.launching()
        sessionNavigation.enterGame(notify = false)
        fullscreen.restore()
        appRoot.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame = frame
        frame.setOnTouchListener { _, event ->
            sessionFlow?.isOpen != true && touchUi.handleKeyboardTouch(event,
                inputModeDecider.resolve(configuredTouchMode()) == InputModeDecider.Mode.KEYBOARD)
        }
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        overlay.showProgress(if (game.installer) "Preparing installer archive\u2026"
            else "Preparing ${sessionGameTitle ?: game.displayName}\u2026")
        val display = SurfaceView(this)
        surface = display
        frame.addView(display, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
        display.holder.addCallback(this)
        display.setOnTouchListener(::handleGameTouch)
        lateinit var panel: GuestKeyboardPanel
        panel = GuestKeyboardPanel(this, keys, DosKeyboardLayout.value,
            { touchUi.hideKeyboard() }, mouse = mouse,
            mouseReferenceSize = { 640 to 400 }, mapping = keyMapping)
        keyboard = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, Ui.dp(this, 280), Gravity.BOTTOM))
        touchUi.bind(panel)
        onScreenControls?.bindGuestKeyboard(panel)
        swappedKeyboard = GuestKeyboardPanel(this, keys, DosKeyboardLayout.value, {},
            showClose = false, onSwap = { secondaryDisplay.toggleSwap() }, mouse = mouse, mapping = keyMapping)
            .also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        onScreenControls?.refreshVisibility(true)
        refreshControllerUi()
    }

    private fun openMenu(start: String? = null, confirm: Boolean = false) { sessionFlow?.open(start, confirm) }

    private fun closeMenu() { sessionFlow?.close() }

    /** About › Diagnostics: the running session, updated four times a second. */
    private fun diagnosticsPage() = object : GroupsPage("Diagnostics", { sessionGameTitle }, {
        val running = currentGame != null
        val rows = mutableListOf(
            SettingsRow.info("core", "Core") { "DOSBox Staging" },
            SettingsRow.info("state", "State") { if (running) sessionStatusText().substringBefore(" \u00b7") else "Stopped" })
        if (running) {
            rows += SettingsRow.info("cpu", "DOS CPU speed") { if (effectiveCycles() == 0) "Auto" else "Maximum" }
            rows += SettingsRow.info("touch", "Touch input") {
                configuredTouchMode().name.lowercase().replaceFirstChar(Char::uppercase)
            }
            if (nativeStatus() == 2) {
                rows += SettingsRow.info("video", "Video") { "${nativeVideoWidth()} \u00d7 ${nativeVideoHeight()}" }
                rows += SettingsRow.info("audio", "Audio") { "${nativeAudioRate()} Hz" }
            }
        }
        listOf(RowGroup("SESSION", rows,
            if (running) "Live values from the emulator, updated four times a second." else "Start a game to see live values."))
    }) {
        override fun tick() = refresh()
    }

    private fun controllerMappingStatus(game: DosLibrary.Game?): String? {
        if (controllerProfiles.configuration.layout != com.mrjackspade.kairo.frontend.ControllerLayout.WITH_STICKS)
            return null
        if (game?.contentId != null) {
            if (dosGameSettings.controllerBindings(game.key) != null) return null
            val profile = catalog.resolve(game).controllerProfile
            if (profile != null && !catalog.controllerFallback(profile)) return null
            if (profile != null) return "Using Without Sticks fallback"
        }
        return null
    }

    private fun refreshControllerUi() {
        if (relocating || !::secondaryDisplay.isInitialized ||
            !::libraryFlow.isInitialized || !::controllerPages.isInitialized) return
        val menuOpen = sessionFlow?.isOpen == true
        val presentation = sessionState.presentation(guestLifecycle.isVisible, menuOpen, false,
            preparing = false)
        overlay.setPaused(userPaused && currentGame != null && !menuOpen &&
            libraryScreen.visibility != View.VISIBLE)
        val showingGuest = currentGame != null && presentation.showGuest
        if (libraryScreen.visibility != View.VISIBLE) {
            secondaryDisplay.setLibraryInfo(null)
        }
        swappedKeyboard?.setShown(secondaryDisplay.swapped && showingGuest)
        secondaryDisplay.setAppearance(showingGuest, Color.BLACK)
        if (showingGuest) ImmersiveWindow.hideBars(this)
        if (currentGame != null) nativePause(presentation.pauseGuest)
        touchUi.refreshControls(onScreenControls, showingGuest, secondaryDisplay.swapped)
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

    private fun pollSession(id: String) {
        handler.postDelayed({
            if (currentGame?.id != id || endingSessionForLibrary) return@postDelayed
            if (nativeStatus() == 2) {
                InputModeDecider.GuestInput.fromNative(nativeInputTelemetry())?.let {
                    inputModeDecider.observe(it, android.os.SystemClock.elapsedRealtime())
                }
            }
            if (sessionFlow?.isOpen == true) pages.tick()
            when (nativeStatus()) {
                2 -> {
                    overlay.hideProgress()
                    gameOrientation.launched()
                    if (audioThread == null || audioThread?.isAlive != true) startAudio()
                    updateSurfaceLayout()
                    // Wait until autoexec mounts have completed and the shell is
                    // reading input, rather than opening on the initial VGA frame.
                    if (pendingProgramPicker != null && nativeInputTelemetry().getOrNull(3) == 1L && !installerPromptOpen) {
                        val open = pendingProgramPicker
                        pendingProgramPicker = null
                        open?.invoke()
                    }
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

    private fun recreateShaderSurface() {
        val frame = videoFrame ?: return
        val old = surface ?: return
        val params = old.layoutParams as FrameLayout.LayoutParams
        nativeSetSurface(null)
        old.holder.removeCallback(this)
        frame.removeView(old)
        val display = SurfaceView(this)
        surface = display
        frame.addView(display, 0, params)
        display.holder.addCallback(this)
        display.setOnTouchListener(::handleGameTouch)
        updateSurfaceLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (relocating || rebuildIfRescaled()) return
        fullscreen.restore()
        graphics.load()
        videoFrame?.post { updateSurfaceLayout() }
        gameOrientation.configurationChanged()
    }

    /** Rebuilds views sized for earlier density or font settings once no machine is running or starting. */
    private fun rebuildIfRescaled(): Boolean {
        if (isFinishing || currentGame != null || endingSessionForLibrary || installerPromptOpen ||
            !viewScale.changed) return false
        recreate()
        return true
    }

    private fun handleGameTouch(view: View, event: MotionEvent): Boolean =
        handleGameTouchAt(event, view.width, view.height)

    private fun handleGameTouchAt(event: MotionEvent, width: Int, height: Int): Boolean {
        if (touchUi.handleKeyboardTouch(event,
                inputModeDecider.resolve(configuredTouchMode()) == InputModeDecider.Mode.KEYBOARD))
            return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x; lastY = event.y; moved = false
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

    private fun aboutPage() = AboutDocuments.page(this, pages, listOf(
        "Open the menu" to "Back, Mode or Home button, or a swipe from the left edge",
        "Open the keyboard" to "Tap a keyboard prompt or swipe in from the right edge",
        "Second screen" to "On dual-screen devices, the keyboard appears on the other screen"),
        "Android reserves the system Home key.",
        "KairoDos uses the DOSBox Staging emulator core. Source and provenance: github.com/MrJackSpade/KairoDos.",
        "PRIVACY_POLICY.txt", "THIRD_PARTY_NOTICES.txt", ::diagnosticsPage)

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
            val bufferPolicy = com.mrjackspade.kairo.frontend.AudioTrackBufferPolicy(track)
            track.setVolume(if (preferences.getBoolean("muted", false)) 0f else 1f)
            track.play()
            val buffer = ShortArray(4096)
            try {
                while (generation == audioGeneration && nativeStatus() == 2) {
                    val frames = nativeReadAudio(buffer, buffer.size / 2)
                    if (frames > 0) track.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    else Thread.sleep(4)
                    bufferPolicy.checkAfterWrite()
                }
            } finally {
                runCatching { track.stop() }
                track.release()
                if (audio === track) audio = null
            }
        }.apply { name = "KairoDos-audio"; start() }
    }

    /** Offered while the installed game starts; Keep is the same as dismissing it. */
    private fun showInstallerRemovalPrompt(installer: DosLibrary.Game,
                                           installed: DosLibrary.Game) {
        installerPromptOpen = true
        fun keep() {
            installerPromptOpen = false
            installerNotice = null
            if (finishAfterInstallerPrompt) {
                finishAfterInstallerPrompt = false
                finish()
            }
        }
        val notice = GameNotice("Remove installer ZIP?",
            "The installed game archive is in your DOS folder. Remove ${installer.path.substringAfterLast('/')} now?",
            listOf(TrayButton("Keep") { keep() }, TrayButton("Remove", ButtonTone.DANGER) {
                installerNotice = null
                Thread {
                    runCatching { dosLibrary.deleteInstaller(installer, installed) }
                        .onSuccess { runOnUiThread {
                            installerPromptOpen = false
                            libraryFlow.removeSource(installer)
                            if (finishAfterInstallerPrompt) {
                                finishAfterInstallerPrompt = false
                                finish()
                            }
                        } }
                        .onFailure { failure -> runOnUiThread {
                            installerPromptOpen = false
                            Ui.message(this, failure.message ?: "Could not remove installer", long = true)
                            if (finishAfterInstallerPrompt) {
                                finishAfterInstallerPrompt = false
                                handler.postDelayed({ finish() }, 3500)
                            }
                        } }
                }.apply { name = "KairoDos-remove-installer"; start() }
            }), ::keep)
        installerNotice = notice
        overlay.showNotice(notice)
    }

    private var endingSessionForLibrary = false

    private fun endSessionForLibrary(done: () -> Unit) {
        endingSessionForLibrary = true
        launchGeneration++
        closeProgramPicker()
        prepareCancelled.set(true)
        releaseGuestInputs()
        audioGeneration++
        val previousAudio = audio
        val previousThread = audioThread
        val previousGame = gameThread
        Thread {
            runCatching { previousAudio?.stop() }
            // nativeRun initializes its stop flag. If Library is requested during
            // launch, cancellation can race that initialization; keep requesting
            // stop until the canceled launch has actually returned.
            do {
                nativeStop()
                previousGame?.join(100)
            } while (previousGame?.isAlive == true)
            previousThread?.join()
            runOnUiThread {
                leaveGame(completed = false, nativeStopped = true, showAfter = false)
                gameRoot?.let(appRoot::removeView)
                gameRoot = null
                gameThread = null
                audioThread = null
                surface = null
                videoFrame = null
                keyboard = null
                // Controls are attached to appRoot and live for the activity, across games.
                endingSessionForLibrary = false
                done()
                rebuildIfRescaled()
            }
        }.apply { name = "KairoDos-end-session"; start() }
    }

    private fun leaveGame(completed: Boolean = true, nativeStopped: Boolean = false, showAfter: Boolean = true) {
        val returnToFrontend = completed && sessionFromFrontend
        sessionFromFrontend = false
        launchGeneration++
        closeProgramPicker()
        prepareCancelled.set(true)
        if (!nativeStopped) nativeStop()
        audioGeneration++
        gamepad.releaseAll()
        keys.releaseAll()
        mouse.releasePrefix("touch-")
        keyboard?.close()
        onScreenControls?.bindGuestKeyboard(null)
        swappedKeyboard?.close()
        onScreenControls?.refreshVisibility(false)
        sessionFlow?.reset()
        userPaused = false
        nativeSetSurface(null)
        overlay.hideProgress()
        // The installer prompt outlives the game; it still decides whether the app closes.
        if (installerNotice?.let(overlay::showing) != true) overlay.hideNotice()
        sessionGameTitle = null
        swappedKeyboard = null
        currentGame = null
        controllerProfiles.configuration.endSession()
        gamepad.bindings = controllerFlow.global()
        if (showAfter && (!returnToFrontend || installerPromptOpen)) showLibrary()
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
        val id = game?.key?.takeIf { game.contentId != null }
        return TouchInputPolicy.resolve(
            TouchInputSelection(touchMode(preferences.getInt("touch_mode", 2)),
                preferences.getBoolean("direct_touch", false)),
            if (gameSettings.has(id, "touch_mode"))
                touchMode(gameSettings.int(id, "touch_mode", 2)) else null,
            if (gameSettings.has(id, "direct_touch"))
                gameSettings.boolean(id, "direct_touch", false) else null)
    }

    private fun effectiveDirectTouch(game: DosLibrary.Game? = currentGame) =
        touchSelection(game).selection.directTouch

    private fun effectiveCycles(game: DosLibrary.Game? = currentGame): Int =
        gameSettings.int(game?.key?.takeIf { game.contentId != null }, "cycles_mode",
            preferences.getInt("cycles_mode", 0)).coerceIn(0, 1)

    private fun effectiveVoodoo(game: DosLibrary.Game? = currentGame): Int =
        gameSettings.int(game?.key?.takeIf { game.contentId != null }, "staging_voodoo_threads",
            preferences.getInt("staging_voodoo_threads", 0)).coerceIn(0, 1)

    private fun configureGuest() {
        nativeConfigure(if (effectiveDirectTouch()) 1 else 0, effectiveCycles(), effectiveVoodoo())
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
        if (!::appRoot.isInitialized || !::pages.isInitialized) return super.dispatchTouchEvent(event)
        return edgeSwipes.dispatch(event, appRoot.width, pages,
            currentGame != null && libraryScreen.visibility != View.VISIBLE, sessionFlow?.isOpen == true,
            { keyboard?.visibility != View.VISIBLE && !secondaryDisplay.isKeyboardVisible },
            { onScreenControls?.hitTest(event.x, event.y) == true },
            { super.dispatchTouchEvent(it) }, ::dispatchGuestTouch, { openMenu() }, touchUi::showKeyboard,
            ::closeMenu)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputDispatch.dispatchKey(event) { super.dispatchKeyEvent(it) }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        inputDispatch.dispatchMotion(event) { super.dispatchGenericMotionEvent(it) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        inputDispatch.physicalKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        inputDispatch.physicalKey(event) || super.onKeyUp(keyCode, event)

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
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 256 + code - KeyEvent.KEYCODE_NUMPAD_0
        KeyEvent.KEYCODE_NUMPAD_DOT -> 266
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 267
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 268
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 269
        KeyEvent.KEYCODE_NUMPAD_ADD -> 270
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 271
        KeyEvent.KEYCODE_NUM_LOCK -> 300
        KeyEvent.KEYCODE_CAPS_LOCK -> 301
        KeyEvent.KEYCODE_SCROLL_LOCK -> 302
        KeyEvent.KEYCODE_SYSRQ -> 316
        KeyEvent.KEYCODE_BREAK -> 19
        else -> null
    }

    private fun releaseGuestInputs() = inputDispatch.releaseInputs()

    private fun releaseTouchInputs() {
        mouse.releasePrefix("touch-")
        if (directPointerPressed) {
            directPointerPressed = false
            nativePointer(0, 0, false)
        }
    }

    override fun onPause() {
        if (!relocating && guestLifecycle.onPause()) secondaryDisplay.stop()
        super.onPause()
    }

    override fun onStop() {
        if (!relocating && guestLifecycle.onStop()) secondaryDisplay.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (relocating) return
        secondaryDisplay.start(handler)
        guestLifecycle.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (relocating) return
        guestLifecycle.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        if (relocating) {
            super.onDestroy()
            return
        }
        catalogUpdates.cancel()
        catalog.installedCatalogs.close()
        artworkDownloads.cancel()
        backCoordinator.unregister()
        releaseGuestInputs()
        controllerDevices.unregister()
        libraryFlow.cancel()
        externalDispatcher.cancel()
        secondaryDisplay.stop()
        launchGeneration++
        closeProgramPicker()
        prepareCancelled.set(true)
        nativeStop()
        audioGeneration++
        nativeSetSurface(null)
        super.onDestroy()
    }
}
