package com.mrjackspade.kairodos

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.mrjackspade.kairo.frontend.Anchor
import com.mrjackspade.kairo.frontend.AppSaveLocation
import com.mrjackspade.kairo.frontend.ArtworkCoordinator
import com.mrjackspade.kairo.frontend.ButtonTone
import com.mrjackspade.kairo.frontend.CatalogArtworkDownloadController
import com.mrjackspade.kairo.frontend.CatalogUpdateTask
import com.mrjackspade.kairo.frontend.CatalogGame
import com.mrjackspade.kairo.frontend.LayeredGameCatalog
import com.mrjackspade.kairo.frontend.ChoiceGroup
import com.mrjackspade.kairo.frontend.ChoiceOption
import com.mrjackspade.kairo.frontend.ChoicePage
import com.mrjackspade.kairo.frontend.ChoiceSpec
import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerDefaults
import com.mrjackspade.kairo.frontend.ControllerLayout
import com.mrjackspade.kairo.frontend.DirectoryPicker
import com.mrjackspade.kairo.frontend.ExternalGameFile
import com.mrjackspade.kairo.frontend.FooterButton
import com.mrjackspade.kairo.frontend.GameDeletionFlow
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GameNotice
import com.mrjackspade.kairo.frontend.GamePathMigration
import com.mrjackspade.kairo.frontend.GameSettingScope
import com.mrjackspade.kairo.frontend.GameSettingsRows
import com.mrjackspade.kairo.frontend.GameSource
import com.mrjackspade.kairo.frontend.GameValue
import com.mrjackspade.kairo.frontend.GroupsPage
import com.mrjackspade.kairo.frontend.InputModeDecider
import com.mrjackspade.kairo.frontend.KairoActivity
import com.mrjackspade.kairo.frontend.LibraryScreen
import com.mrjackspade.kairo.frontend.LibraryStrings
import com.mrjackspade.kairo.frontend.MachineFirstRun
import com.mrjackspade.kairo.frontend.RowGroup
import com.mrjackspade.kairo.frontend.SaveLocation
import com.mrjackspade.kairo.frontend.SaveUnit
import com.mrjackspade.kairo.frontend.SettingsRow
import com.mrjackspade.kairo.frontend.TouchInputSelection
import com.mrjackspade.kairo.frontend.TouchSettingsStore
import com.mrjackspade.kairo.frontend.TrayButton
import com.mrjackspade.kairo.frontend.UiText
import com.mrjackspade.kairo.frontend.ValueTone
import com.mrjackspade.kairo.frontend.ScanDepth
import com.mrjackspade.kairo.frontend.text
import com.mrjackspade.kairo.frontend.R as FrontendR
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** KairoDos: DOS games on DOSBox Staging, compiled in :backend-dos and hosted via JNI, in the shared Kairo frontend. */
class MainActivity : KairoActivity<DosLibrary.Game>() {
    private external fun nativeRun(config: String, saveDir: String, resources: String, hostPaths: Array<String>): Boolean
    private external fun nativeStop()
    private external fun nativePause(value: Boolean)
    private external fun nativePaused(): Boolean
    private external fun nativeReset()
    private external fun nativeListDirectory(path: String): String
    private external fun nativeStatus(): Int
    private external fun nativeInputTelemetry(): LongArray
    private external fun nativeCpuTelemetry(): LongArray
    private external fun nativeAudioRate(): Int
    private external fun nativeFrames(): Long
    private external fun nativeAspect(): Double
    private external fun nativeVideoWidth(): Int
    private external fun nativeVideoHeight(): Int
    private external fun nativeLastError(): String
    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeSetShaderPreset(path: String?)
    private external fun nativeKey(code: Int, down: Boolean)
    private external fun nativeFastForward(enabled: Boolean)
    private external fun nativeJoypad(id: Int, down: Boolean)
    private external fun nativeMouseMove(dx: Int, dy: Int)
    private external fun nativeMouseButton(button: Int, down: Boolean)
    private external fun nativePointer(x: Int, y: Int, pressed: Boolean)
    private external fun nativeConfigure(mouseMode: Int, cyclesMode: Int, voodooMode: Int)
    private external fun nativeReadAudio(buffer: ShortArray, maxFrames: Int): Int

    companion object {
        /** A game's saved files: `<savePath>.sav`; in v1.4.0 and earlier, `<hash name>/overlay`. */
        private const val SAVE_KIND = "sav"
        private const val OVERLAY_UNIT = "overlay"
        private const val SAVED_COPY = "saved"
        private const val EDITED_FILE = "edited"
        private const val WORKING_OVERLAY = "staging/overlay-working"
        private const val UNSAVED_OVERLAY = "staging/overlay-working.unsaved"
        /** The Version choice that runs the preset with the player's own DOSBox settings and startup commands. */
        private const val CUSTOM_VERSION = "custom"
        private val CPU_OPTIONS = listOf(ChoiceOption("0", UiText.res(FrontendR.string.kairo_auto)),
            ChoiceOption("1", UiText.res(R.string.cpu_maximum)))
        private val VOODOO_OPTIONS = listOf(ChoiceOption("0", UiText.res(R.string.voodoo_automatic)),
            ChoiceOption("1", UiText.res(R.string.voodoo_single)))
        init {
            // Load the shared C++ runtime on its own, before any DOS session. Otherwise the
            // DOSBox core loads it as part of its own dlopen group; long-lived threads of the
            // shader runtime then hold that group, and the core can never unload between games.
            System.loadLibrary("c++_shared")
            System.loadLibrary("kairodos")
        }
    }

    override val identity = Identity("KAIRODOS", 0xff03C5FB.toInt(), "KairoDos", "DOS", "kairodos",
        LibraryStrings("KAIRODOS", "KairoDos", UiText.res(R.string.dos_folder), UiText.res(R.string.no_dos_folder)),
        UiText.res(R.string.credits, "KairoDos", "DOSBox Staging", "MrJackSpade/KairoDos"),
        UiText.res(R.string.library_empty), UiText.res(R.string.library_expired),
        MachineFirstRun.Folder(UiText.res(R.string.folder_title), UiText.res(R.string.folder_description),
            UiText.res(R.string.folder_button)),
        UiText.res(R.string.saves_group), "MrJackSpade/KairoDos")

    // Input ------------------------------------------------------------------------------------------

    override val keyboard = DosKeyboardLayout.value
    override val maxKeyCode = 341
    override fun guestKey(androidKey: Int) = mapKey(androidKey)
    override val controllerSpec = DosControllerBindings.spec
    override val bindingsCodec = DosControllerBindings.codec
    // Read each time: a catalog update can bring a new system mapping while the app runs.
    override fun systemControllerBindings() = DosControllerBindings.parse(catalog.systemControllerBindings().toString())
    override val joystickControls: List<String> get() = DosControllerBindings.joystick
    override fun sendKey(code: Int, down: Boolean) = nativeKey(code, down)
    override fun sendJoystick(control: Int, down: Boolean) = nativeJoypad(control, down)
    override fun sendMouseMove(dx: Int, dy: Int) = nativeMouseMove(dx, dy)
    override fun sendMouseButton(button: Int, down: Boolean) = nativeMouseButton(button, down)
    override fun inputTelemetry(): LongArray? = if (nativeStatus() == 2) nativeInputTelemetry() else null
    override fun touchpadReferenceSize() = 640 to 400

    // The library and its catalog --------------------------------------------------------------------

    private val dosLibrary by lazy { DosLibrary(this) }
    override val catalog by lazy {
        // Before KairoDos read the shared catalog its overrides and catalog file had their own names.
        DosCatalogMigration.moveLocalFiles(filesDir)
        LayeredGameCatalog(this, DosCatalog)
    }
    override fun cachedLibrary(tree: Uri) = claimSavedData(tree, dosLibrary.cached(tree))
    override fun scanLibrary(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean, progress: (String) -> Unit) =
        claimSavedData(tree, dosLibrary.scan(tree, depth, cancelled, progress))
    override fun storeLibrary(tree: Uri, entries: List<DosLibrary.Game>) = dosLibrary.store(tree, entries)
    override val hashCount: Int get() = dosLibrary.hashCount
    override val writableFolder = true
    override fun inspectExternal(file: ExternalGameFile, cancelled: AtomicBoolean) =
        listOf(claimSavedData(Uri.EMPTY, listOf(dosLibrary.inspectExternal(file, cancelled))).single()
            .also { it.error?.let(::error) })
    override val commandLaunch: (DosLibrary.Game) -> Unit = {
        startGame(it, startConfig(it), target = DosLaunchTarget.Browse)
    }

    override val artworkStore get() = catalog.artworkStore
    override fun artworkRecord(entry: DosLibrary.Game, kind: String): ArtworkCoordinator.Record {
        val record = catalog.resolve(entry)
        return ArtworkCoordinator.Record(if (kind == "preview") record.preview else record.boxArt, null,
            catalog.sourceOf(entry, "artwork", kind))
    }
    override fun validArtworkPath(path: String) = DosCatalog.validArtPath(path)
    override fun saveArtworkOverride(game: GameKey, kind: String, path: String?) =
        if (path == null) catalog.resetArtworkOverride(game, kind) else catalog.setArtworkOverride(game, kind, path)
    override fun createArtworkDownloads(screen: LibraryScreen<DosLibrary.Game>, entries: () -> List<DosLibrary.Game>) =
        CatalogArtworkDownloadController(this, screen, entries, catalog::missingArtworkFor, catalog::downloadArtwork) {
            pages.refresh()
        }
    override fun downloadCatalogUpdate(task: CatalogUpdateTask) = catalog.downloadUpdate(task)
    override val installedCatalogs get() = catalog.installedCatalogs
    override fun catalogUpdated() = controllerFlow.refresh(null)
    override fun titleOf(entry: DosLibrary.Game) = catalog.resolve(entry).title

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

    override val keyMigrationPending: Boolean get() = gameKeyMigration.pending

    /** A game's local working files were named by its entry's earlier ID. */
    private inner class LegacySaveDirectories(private val root: File) : GameKeyMigration.EntryStore<DosLibrary.Game> {
        private var legacy = emptySet<String>()
        override fun refresh() {
            legacy = root.list().orEmpty().filterNot { GameKey.HASH_NAME.matches(it) }.toSet()
        }
        override fun has(claim: GameKeyMigration.Claim<DosLibrary.Game>) = claim.entryId in legacy
        override fun move(claim: GameKeyMigration.Claim<DosLibrary.Game>) {
            val from = File(root, claim.entryId!!)
            val to = File(root, claim.key.hashName)
            if (to.exists()) {
                // Only a drive from before overlays or an unstored session is saved data here.
                require(!File(from, "staging/drive").exists() && !File(from, UNSAVED_OVERLAY).exists()) {
                    getString(R.string.saves_in_two_places, from.name, to.name)
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

    /** The games a claim is moving data to; the library list is not published until it ends. */
    @Volatile private var claiming: List<DosLibrary.Game> = emptyList()

    /** Moves saves and settings from before games had keys to [entries]; a game whose data did not move shows why. */
    private fun claimSavedData(folder: Uri, entries: List<DosLibrary.Game>): List<DosLibrary.Game> {
        if (!gameKeyMigration.pending) return entries
        claiming = entries
        val errors = gameKeyMigration.claim(entries.map {
            GameKeyMigration.Claim(it, dosLibrary.legacyEntryId(it, folder))
        })
        return if (errors.isEmpty()) entries else entries.map { game ->
            errors[game.key]?.let { game.copy(error = text(it)) } ?: game
        }
    }

    // Settings a game has as its own -----------------------------------------------------------------

    private val settingScope by lazy { GameSettingScope(preferences) }
    private val dosGameSettings by lazy { DosPerGameSettings(preferences, settingScope) }

    override fun catalogControllerLayer(entry: DosLibrary.Game) =
        catalog.resolve(entry).controllerLayer(controllerProfiles.configuration.layout)
    override fun playerControllerLayer(entry: DosLibrary.Game): List<ControllerBinding> {
        dosGameSettings.controllerLayer(entry.key)?.let { return DosControllerBindings.parse(it) }
        // Saved before mappings were layers: the whole mapping as it played.
        return dosGameSettings.legacyControllerBindings(entry.key)
            ?.let { ControllerDefaults.legacyLayer(DosControllerBindings.parse(it)) } ?: emptyList()
    }
    override fun savePlayerControllerLayer(game: GameKey, layer: List<ControllerBinding>) =
        dosGameSettings.setControllerLayer(game, DosControllerBindings.toJson(layer).toString())
    override fun clearGameBindings(game: GameKey) = dosGameSettings.clearControllerBindings(game)
    override fun controllerSource(entry: DosLibrary.Game): String {
        val id = entry.key.takeIf { entry.contentId != null }
        return when {
            id == null -> GameSource.ALL_GAMES
            dosGameSettings.hasOwnController(id) -> GameSource.CUSTOM
            // Controller profiles come from the bundled profile catalog.
            catalog.resolve(entry).controllerProfile != null -> "Shipped catalog"
            else -> GameSource.ALL_GAMES
        }
    }
    override fun controllerMappingStatus(entry: DosLibrary.Game?): String? {
        if (controllerProfiles.configuration.layout != ControllerLayout.WITH_STICKS) return null
        if (entry?.contentId == null || dosGameSettings.hasOwnController(entry.key)) return null
        val game = catalog.resolve(entry).takeIf { it.controllerProfile != null } ?: return null
        return if (game.controllerFallsBack) getString(FrontendR.string.kairo_mapping_without_sticks_fallback) else null
    }

    override val touchModes = listOf(
        InputModeDecider.Mode.AUTO to ChoiceOption("auto", UiText.res(FrontendR.string.kairo_auto),
            UiText.res(FrontendR.string.kairo_touch_auto_help)),
        InputModeDecider.Mode.KEYBOARD to ChoiceOption("keyboard", UiText.res(FrontendR.string.kairo_keyboard),
            UiText.res(FrontendR.string.kairo_touch_tap_keyboard_help, "DOS")),
        InputModeDecider.Mode.MOUSE to ChoiceOption("mouse", UiText.res(FrontendR.string.kairo_mouse)))
    override val directTouchExplanation = UiText.res(R.string.direct_touch_explanation)
    override val directTouchAvailable = true
    override val touchStore: TouchSettingsStore<DosLibrary.Game> by lazy {
        object : TouchSettingsStore<DosLibrary.Game> {
            override fun global() = TouchInputSelection(touchMode(preferences.getInt("touch_mode", 2)),
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
                return GameValue({ if (settingScope.has(id, "direct_touch")) settingScope.boolean(id, "direct_touch", false) else null },
                    { preferences.getBoolean("direct_touch", false) }, { GameSource.ALL_GAMES },
                    { value -> if (value == null) settingScope.clear(id, "direct_touch")
                        else settingScope.setBoolean(id, "direct_touch", value) })
            }
        }
    }
    override fun touchChanged() = configureGuest()

    /** A per-game value stored as an int beside the all-games preference. */
    override fun gameAnchor(game: DosLibrary.Game, orientation: String, allGames: () -> Anchor): GameValue<String> {
        val id = game.key
        val setting = DosPerGameSettings.anchorSetting(orientation)
        return GameValue({ settingScope.string(id, setting) }, { allGames().id }, { GameSource.ALL_GAMES },
            { value -> if (value == null) settingScope.clear(id, setting) else settingScope.setString(id, setting, value) })
    }

    private fun <V> gameInt(game: DosLibrary.Game, key: String, read: (Int) -> V, write: (V) -> Int,
                            global: () -> V): GameValue<V> {
        val id = game.key
        return GameValue({ if (settingScope.has(id, key)) read(settingScope.int(id, key, 0)) else null },
            global, { GameSource.ALL_GAMES },
            { value -> if (value == null) settingScope.clear(id, key) else settingScope.setInt(id, key, write(value)) })
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

    private fun effectiveCycles(game: DosLibrary.Game? = currentEntry): Int =
        settingScope.int(game?.key?.takeIf { game.contentId != null }, "cycles_mode",
            preferences.getInt("cycles_mode", 0)).coerceIn(0, 1)

    private fun effectiveVoodoo(game: DosLibrary.Game? = currentEntry): Int =
        settingScope.int(game?.key?.takeIf { game.contentId != null }, "staging_voodoo_threads",
            preferences.getInt("staging_voodoo_threads", 0)).coerceIn(0, 1)

    private fun configureGuest() {
        nativeConfigure(if (touchSelection().directTouch) 1 else 0, effectiveCycles(), effectiveVoodoo())
    }

    override fun machineGroup(entry: DosLibrary.Game, enabled: () -> Boolean): RowGroup {
        val id = entry.key.takeIf { entry.contentId != null }
        val noHash = { GameSettingsRows.noHash(this) }
        val scope = { titleOf(entry) }
        val record = catalog.resolve(entry)
        val versions = versions(record)
        val configRows = id?.let { game ->
            DosConfigRows(this, game, dosGameSettings, { catalogConfig(entry) }, {
                DosStagingLaunchConfig.hostSettings(record.launch?.configs?.get(startConfig(entry)),
                    effectiveVoodoo(entry) == 1, touchSelection(entry).directTouch, dosGameSettings.videoHardware(game))
            }, { catalogSource(entry) }, enabled, scope) { toast(it) }
        }
        fun intValue(key: String, global: () -> Int) = if (id == null) GameValue({ null }, { global().toString() },
            { GameSource.ALL_GAMES }, { }) else GameValue(
            { if (settingScope.has(id, key)) settingScope.int(id, key, 0).toString() else null },
            { global().toString() }, { GameSource.ALL_GAMES },
            { value ->
                if (value == null) settingScope.clear(id, key) else settingScope.setInt(id, key, value.toInt())
                if (currentEntry?.id == entry.id) configureGuest()
            })
        return RowGroup(getString(FrontendR.string.kairo_machine_group), listOfNotNull(
            GameSettingsRows.choice(this, "cpu", getString(R.string.cpu_speed), scope, CPU_OPTIONS,
                intValue("cycles_mode") { preferences.getInt("cycles_mode", 0).coerceIn(0, 1) }, enabled, noHash),
            GameSettingsRows.choice(this, "video", getString(R.string.video_card), scope, videoOptions(),
                intValue(DosVideoHardware.setting) { dosGameSettings.videoHardware() }, enabled, noHash),
            GameSettingsRows.choice(this, "voodoo", getString(R.string.voodoo), scope, VOODOO_OPTIONS,
                intValue("staging_voodoo_threads") { preferences.getInt("staging_voodoo_threads", 0).coerceIn(0, 1) },
                enabled, noHash),
            GameSettingsRows.choice(this, "version", getString(R.string.version), scope,
                versions.map { ChoiceOption(it, DosConfig.versionLabel(this, it)) } +
                    ChoiceOption(CUSTOM_VERSION, UiText.res(R.string.version_custom)),
                GameValue({ id?.let { if (dosGameSettings.custom(it)) CUSTOM_VERSION else dosGameSettings.version(it)?.takeIf { v -> v in versions } } },
                    { versions.firstOrNull().orEmpty() },
                    { if (versions.isEmpty()) GameSource.BUILT_IN else catalogSource(entry) },
                    { picked -> id?.let { setVersion(it, picked) } }),
                enabled, noHash, defaultLabel = {
                    versions.firstOrNull()?.let { DosConfig.versionLabel(this, it) } ?: getString(FrontendR.string.kairo_none)
                }),
            configRows?.takeIf { id != null && dosGameSettings.custom(id) }?.settingsRow { pages.push(it) },
            configRows?.takeIf { id != null && dosGameSettings.custom(id) }?.commandsRow(),
            if (DosLaunchConfig.needsPlayer(record.launch)) playerNameRow(entry, enabled) else null),
            getString(R.string.game_machine_foot))
    }

    /**
     * Picks a game's version: one of its presets, or Custom, which runs the preset it had with the
     * player's own DOSBox settings and startup commands. Null goes back to the catalog's preset.
     */
    private fun setVersion(game: GameKey, picked: String?) = when (picked) {
        CUSTOM_VERSION -> dosGameSettings.setCustom(game, true)
        else -> {
            dosGameSettings.setVersion(game, picked)
            dosGameSettings.setCustom(game, false)
        }
    }

    /** A game's versions, its catalog configs (eXoDOS presets), the standard one first. */
    private fun versions(record: CatalogGame) = record.launch?.configs?.keys.orEmpty()
        .sortedWith(compareBy<String> { it != "dosbox.conf" }.thenBy { it })

    /** The catalog config the game starts with: the version the player picked, or its standard one. */
    private fun startConfig(game: DosLibrary.Game): String {
        val versions = versions(catalog.resolve(game))
        return dosGameSettings.version(game.key)?.takeIf { it in versions } ?: versions.firstOrNull() ?: "dosbox.conf"
    }

    private fun catalogConfig(game: DosLibrary.Game): DosConfig? =
        catalog.resolve(game).launch?.configs?.get(startConfig(game))?.let(DosConfig::parse)

    private fun catalogSource(game: DosLibrary.Game) = GameSource.label(catalog.sourceOf(game, "launch", "configs"))

    private fun videoOptions() = DosVideoHardware.choices.mapIndexed { index, (_, label) ->
        ChoiceOption(index.toString(), label)
    }

    override fun titleCustom(entry: DosLibrary.Game) =
        entry.contentId != null && catalog.sourceOf(entry, "title") == "User override"
    override fun titleSource(entry: DosLibrary.Game) =
        entry.key.takeIf { entry.contentId != null }?.let { GameSource.label(catalog.sourceOf(entry, "title")) } ?: "Filename"
    override fun setTitle(game: GameKey, title: String?) =
        if (title == null) catalog.resetOverride(game, "title") else catalog.setOverride(game, "title", title)

    /** The game's own folder or archive; a frontend's file or the DOS folder itself is not the library's to delete. */
    override fun deletePrompt(entry: DosLibrary.Game) = if (entry.external || entry.rootFolder) null
        else GameDeletionFlow.Prompt(entry.path, entry.folder)
    override fun deleteSource(entry: DosLibrary.Game) = dosLibrary.deleteSource(entry)
    override fun resetGameSettings(game: GameKey) {
        catalog.resetOverride(game)
        dosGameSettings.reset(game)
    }
    override val resetMessage = UiText.res(R.string.reset_message)

    /** The name a game asks for, such as for saves, edited in its tray and checked on Save. */
    private fun playerNameRow(entry: DosLibrary.Game, enabled: () -> Boolean): SettingsRow {
        val id = entry.key.takeIf { entry.contentId != null }
        return GameSettingsRows.text(this, "player", getString(R.string.player_name), getString(R.string.player_name_hint),
            { id?.let(dosGameSettings::playerName) }, { false }, { null },
            { value -> savePlayerName(id, value) }, {}, empty = getString(R.string.player_name_empty),
            enabled = enabled, disabledReason = { GameSettingsRows.noHash(this) })
    }

    private fun savePlayerName(id: GameKey?, value: String): Boolean {
        id ?: return false
        val name = value.trim().uppercase(java.util.Locale.ROOT)
        if (!name.matches(Regex("[A-Z0-9_]{1,8}"))) {
            toast(getString(R.string.player_name_invalid))
            return false
        }
        dosGameSettings.setPlayerName(id, name)
        refreshSettingValues()
        return true
    }

    // Pages ------------------------------------------------------------------------------------------

    /** Machine: the DOS CPU speed for all games. */
    override fun machinePage() = GroupsPage(getString(FrontendR.string.kairo_machine), { GameSource.ALL_GAMES }, {
        val speed = getString(R.string.cpu_speed)
        listOf(RowGroup(getString(R.string.machine_cpu_group), listOf(SettingsRow.choice("cpu", speed, ChoiceSpec(speed,
            { GameSource.ALL_GAMES }, { listOf(ChoiceGroup(null, CPU_OPTIONS)) },
            { preferences.getInt("cycles_mode", 0).coerceIn(0, 1).toString() },
            select = { preferences.edit().putInt("cycles_mode", it.toInt()).apply(); configureGuest() }))),
            getString(R.string.cpu_foot)))
    })

    /** Graphics › VIDEO, for all games. */
    override fun graphicsGroups() = listOf(RowGroup(getString(R.string.video_group), listOf(
        SettingsRow.choice("video", getString(R.string.video_card), ChoiceSpec(getString(R.string.video_card), { GameSource.ALL_GAMES },
            { listOf(ChoiceGroup(null, videoOptions())) }, { dosGameSettings.videoHardware().toString() },
            select = { preferences.edit().putInt(DosVideoHardware.setting, it.toInt()).apply() })),
        SettingsRow.choice("voodoo", getString(R.string.voodoo), ChoiceSpec(getString(R.string.voodoo), { GameSource.ALL_GAMES },
            { listOf(ChoiceGroup(null, VOODOO_OPTIONS)) },
            { preferences.getInt("staging_voodoo_threads", 0).coerceIn(0, 1).toString() },
            select = { preferences.edit().putInt("staging_voodoo_threads", it.toInt()).apply(); configureGuest() }))),
        getString(R.string.video_foot)))

    /**
     * Frames, error and video size, and the CPU core's telemetry (dynrec available, dynamic core
     * active, blocks translated and executed), as the UI tests read them.
     */
    override fun probeDetails(): Map<String, String> {
        val cpu = nativeCpuTelemetry()
        return mapOf("frames" to nativeFrames().toString(), "native.paused" to nativePaused().toString(), "error" to nativeLastError(),
            "video" to "${nativeVideoWidth()}x${nativeVideoHeight()}",
            "cpu.dynrec" to cpu[0].toString(), "cpu.dynamic" to cpu[1].toString(),
            "cpu.translated" to cpu[2].toString(), "cpu.executed" to cpu[3].toString())
    }

    override fun sessionStatus() = when (nativeStatus()) {
        1 -> "Loading"
        // Held paused by the menu or the player, as the machine apps report it.
        2 -> if (nativePaused()) "Paused" else "Running"
        3 -> "Failed"
        else -> "Stopped"
    }

    /** [sessionStatus] as the player reads it. */
    private fun sessionStatusText() = getString(when (sessionStatus()) {
        "Loading" -> R.string.session_loading
        "Paused" -> FrontendR.string.kairo_state_paused
        "Running" -> FrontendR.string.kairo_state_running
        "Failed" -> R.string.session_failed
        else -> FrontendR.string.kairo_state_stopped
    })

    override fun diagnosticsRows(running: Boolean): List<SettingsRow> {
        val rows = mutableListOf(
            SettingsRow.info("core", getString(R.string.diagnostics_core)) { "DOSBox Staging" },
            SettingsRow.info("state", getString(FrontendR.string.kairo_diagnostics_state)) {
                if (running) sessionStatusText() else getString(FrontendR.string.kairo_diagnostics_stopped)
            })
        if (running) {
            rows += SettingsRow.info("cpu", getString(R.string.cpu_speed)) {
                getString(if (effectiveCycles() == 0) FrontendR.string.kairo_auto else R.string.cpu_maximum)
            }
            rows += SettingsRow.info("touch", getString(R.string.diagnostics_touch)) {
                text(touchModes.first { it.first == touchSelection().mode }.second.label)
            }
            if (nativeStatus() == 2) {
                rows += SettingsRow.info("video", getString(R.string.diagnostics_video)) {
                    getString(R.string.diagnostics_video_size, nativeVideoWidth(), nativeVideoHeight())
                }
                rows += SettingsRow.info("audio", getString(FrontendR.string.kairo_diagnostics_audio)) {
                    getString(R.string.diagnostics_audio_rate, nativeAudioRate())
                }
            }
        }
        return rows
    }

    // First run: one step, the DOS folder ------------------------------------------------------------

    override val firmware: MachineFirstRun.Firmware? = null
    override var firstRunStep: Int
        get() = if (preferences.getBoolean("onboarding_complete_v1", tree != null)) 2 else 0
        set(value) { preferences.edit().putBoolean("onboarding_complete_v1", value >= 2).apply() }

    // Saved games ------------------------------------------------------------------------------------

    override val saveKinds = mapOf(SAVE_KIND to OVERLAY_UNIT)
    override val appSaves: File get() = File(filesDir, "dos-saves")
    override fun saveUnitLabel(kind: String) = getString(R.string.saved_files)
    override fun withError(entry: DosLibrary.Game, message: String) = entry.copy(error = message)
    override fun v1DataStores(): List<GamePathMigration.Store<DosLibrary.Game>> =
        listOf(DosPerGameSettings.v1Settings(preferences), catalog.v1Overrides(), EarlierSaveDirectories())

    /**
     * A game's local working files, named by its hash name before v1.4.1 (`saves/<hash name>`),
     * move to its path ([saveDir], Kairo#113): a library game's as the library loads, a frontend's
     * file's from the hash name of the URI v1.4.0 keyed it by.
     */
    private inner class EarlierSaveDirectories : GamePathMigration.Store<DosLibrary.Game> {
        private val root = File(filesDir, "saves")
        private var names = emptySet<String>()

        override fun refresh(claims: List<GamePathMigration.Claim<DosLibrary.Game>>, every: Boolean) {
            names = if (every) root.list().orEmpty().filter { GameKey.HASH_NAME.matches(it) }.toSet()
                else claims.map { it.from }.filter { File(root, it).isDirectory }.toSet()
        }
        override fun has(hashName: String) = hashName in names
        override fun move(claim: GamePathMigration.Claim<DosLibrary.Game>, keep: Set<GamePathMigration.Conflict>) {
            val from = File(root, claim.from)
            val to = saveDir(claim.to)
            if (to.exists()) {
                // Only an unstored session or a drive from before overlays is saved data here.
                require(!File(from, UNSAVED_OVERLAY).exists() && !DosStagingStorage.hasLegacyDrive(from)) {
                    getString(R.string.saves_in_two_places, from.name, to.path)
                }
                check(from.deleteRecursively()) { "Could not remove the game's earlier working files" }
            } else {
                check(to.parentFile!!.isDirectory || to.parentFile!!.mkdirs()) { "Could not create the game's working folder" }
                check(from.renameTo(to)) { "Could not move the game's working files" }
            }
            names = names - claim.from
        }
        override fun isEmpty() = names.isEmpty()
    }

    /**
     * A game's local working files: its base drive, launch configuration and, for a save folder,
     * the working copy of its saved files, at `saves/<savePath>.work`. DOSBox is given their host
     * paths by reference (DosStagingLaunchConfig.Written), so any name works.
     */
    private fun saveDir(game: GameKey) =
        File(File(filesDir, "saves"), SaveLocation.segments("${game.savePath}.work").joinToString(File.separator))

    private fun saveDir(game: DosLibrary.Game) = saveDir(game.key)

    /**
     * Every game's working folder: `<savePath>.work` anywhere under `saves`, and one still named
     * by its hash name which a later library load moves.
     */
    private fun saveDirs(): List<File> {
        val root = File(filesDir, "saves")
        val found = ArrayList<File>()
        fun walk(directory: File, top: Boolean) {
            for (child in directory.listFiles().orEmpty().filter { it.isDirectory }) {
                if (child.name.endsWith(".work") || (top && GameKey.HASH_NAME.matches(child.name))) found += child
                else walk(child, false)
            }
        }
        walk(root, true)
        return found
    }

    /**
     * The overlay a game's session writes: in app storage its saved files themselves
     * (`<savePath>.sav`), which DOSBox mounts directly; for a save folder, which DOSBox cannot
     * open by path, a working copy in [saveDir] that [publishOverlay] stores back when the session
     * ends, or before the next one if it never did.
     */
    private fun beginOverlay(saveDir: File, game: GameKey): File {
        val unit = SaveUnit.of(game, SAVE_KIND)
        val folder = saveFolder.folder
        if (folder != null) require(!DosStagingStorage.hasLegacyDrive(saveDir)) {
            getString(R.string.saves_older_format)
        }
        publishOverlay(saveDir, game)
        if (folder == null) return saveFolder.app.directory(unit).apply {
            require(isDirectory || mkdirs()) { "Could not create the game's save folder" }
        }
        val saves = saveFolder.location
        val working = File(saveDir, WORKING_OVERLAY)
        require(!working.exists() || working.deleteRecursively()) { "Could not clear the game's working save folder" }
        if (saves.exists(unit)) saves.export(unit, working)
        else require(working.mkdirs()) { "Could not create the game's working save folder" }
        File(saveDir, UNSAVED_OVERLAY).writeText("${folder.tree}\n${unit.path}")
        return working
    }

    /**
     * Stores a session's working overlay where its saves are. v1.4.0 left one only for a save
     * folder, naming its game by hash name, or before games had keys by content ID: such a
     * session is [game]'s, the game this folder belongs to.
     */
    private fun publishOverlay(saveDir: File, game: GameKey?) {
        val unsaved = File(saveDir, UNSAVED_OVERLAY)
        if (!unsaved.isFile) return
        val (location, owner) = unsaved.readLines()
        val folder = saveFolder.folder
        require(location == folder?.tree?.toString()) {
            getString(if (folder == null) R.string.saves_folder_not_selected else R.string.saves_other_folder)
        }
        val unit = if (owner.endsWith(".$SAVE_KIND") && !SaveLocation.isV1Game(owner))
            SaveUnit(owner.removeSuffix(".$SAVE_KIND"), SAVE_KIND)
        else SaveUnit.of(checkNotNull(game) {
            getString(R.string.saves_waiting_for_game)
        }, SAVE_KIND)
        saveFolder.location.import(unit, File(saveDir, WORKING_OVERLAY))
        check(unsaved.delete()) { "Could not finish storing the game's saved files" }
    }

    /** Before saves move: store unfinished sessions and convert drives from before overlays. */
    override fun prepareSaveMove(progress: (String) -> Unit) {
        val saveDirs = saveDirs()
        val library = libraryEntries.toList()
        saveDirs.forEach { dir -> publishOverlay(dir, library.firstOrNull { saveDir(it) == dir }?.key) }
        val legacy = saveDirs.filter(DosStagingStorage::hasLegacyDrive)
        val missing = legacy.filter { dir -> library.none { saveDir(it) == dir } }
        require(missing.isEmpty()) {
            getString(R.string.saves_need_games, missing.joinToString(getString(FrontendR.string.kairo_list_separator)) {
                DosStagingStorage.legacyContentId(it).let { id -> catalog.resolve(id, id).title }
            })
        }
        for ((index, saveDir) in legacy.withIndex()) {
            val game = library.first { saveDir(it) == saveDir }
            progress(getString(R.string.saves_converting, index + 1, legacy.size))
            val archive = dosLibrary.prepare(game, tree, AtomicBoolean(false))
            DosStagingStorage.prepare({ text(it) }, archive, saveDir, DosStagingStorage.legacyContentId(saveDir),
                saveFolder.app.directory(SaveUnit.of(game.key, SAVE_KIND)), AtomicBoolean(false),
                catalog.resolve(game).launch?.folder)
        }
    }

    /** An installer's saves and settings belong to the game it installs. */
    private fun moveGame(from: GameKey, to: GameKey) {
        if (from == to) return
        val oldDir = saveDir(from)
        val newDir = saveDir(to)
        // An unfinished session is the installer's; it is stored before its saves move.
        publishOverlay(oldDir, from)
        if (oldDir.isDirectory && !newDir.exists())
            require((newDir.parentFile!!.isDirectory || newDir.parentFile!!.mkdirs()) && oldDir.renameTo(newDir)) { "Could not move existing game saves to the installed archive" }
        val saves = saveFolder.location
        val unit = SaveUnit.of(from, SAVE_KIND)
        if (saves.exists(unit) && !saves.exists(SaveUnit.of(to, SAVE_KIND))) {
            val copy = File(cacheDir, "installer-saves")
            check(!copy.exists() || copy.deleteRecursively()) { "Could not clear an earlier move" }
            try {
                saves.export(unit, copy)
                saves.import(SaveUnit.of(to, SAVE_KIND), copy)
            } finally { copy.deleteRecursively() }
            saves.delete(unit)
        }
        dosGameSettings.move(from, to)
        catalog.moveOverrides(from, to)
    }

    // Launching --------------------------------------------------------------------------------------

    @Volatile private var prepareCancelled = AtomicBoolean(false)
    @Volatile private var gameThread: Thread? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var audioGeneration = 0
    private var installerPromptOpen = false
    private var finishAfterInstallerPrompt = false
    private var installerNotice: GameNotice? = null
    private var programPicker: DirectoryPicker? = null
    private var pendingProgramPicker: (() -> Unit)? = null

    override fun launch(entry: DosLibrary.Game, fromFrontend: Boolean) {
        val versions = versions(catalog.resolve(entry))
        val chosen = dosGameSettings.custom(entry.key) || dosGameSettings.version(entry.key) in versions
        if (versions.size > 1 && !chosen) chooseVersion(entry, versions, fromFrontend)
        else startGame(entry, startConfig(entry), fromFrontend)
    }

    /** Choose a version the first time a game with several presets is played. Back cancels. */
    private fun chooseVersion(game: DosLibrary.Game, versions: List<String>, fromFrontend: Boolean) {
        pages.push(ChoicePage(ChoiceSpec(getString(R.string.version), { titleOf(game) }, {
            listOf(ChoiceGroup(null, versions.map { ChoiceOption(it, DosConfig.versionLabel(this, it)) }))
        }, { dosGameSettings.version(game.key) }, select = { selected ->
            dosGameSettings.setVersion(game.key, selected)
            startGame(game, selected, fromFrontend)
        }, stay = true, cycles = false)))
    }

    /** Games that ask for a DOS player name get one before their first launch. */
    private fun choosePlayerName(game: DosLibrary.Game, playConfig: String, fromFrontend: Boolean) {
        val id = game.key.takeIf { game.contentId != null } ?: return
        var name = dosGameSettings.playerName(id) ?: "PLAYER"
        pages.push(GroupsPage(getString(R.string.player_name), { titleOf(game) }, {
            listOf(RowGroup(null, listOf(GameSettingsRows.text(this, "player", getString(R.string.player_name_short),
                getString(R.string.player_name_hint), { name }, { false }, { null },
                { value -> if (savePlayerName(id, value)) name = dosGameSettings.playerName(id) ?: name },
                {})), getString(R.string.player_name_foot)))
        }, {
            listOf(FooterButton("play", { getString(R.string.play) }, ButtonTone.PRIMARY) {
                if (savePlayerName(id, name)) startGame(game, playConfig, fromFrontend)
            })
        }))
    }

    /**
     * Prepares [game]'s drives and runs it on the emulation thread, which returns when the game
     * ends. A session already running ends first; its thread finishes before the new one starts.
     */
    private fun startGame(game: DosLibrary.Game, configName: String, fromFrontend: Boolean = false,
                          target: DosLaunchTarget = DosLaunchTarget.Normal) {
        if (saveFolder.busy) {
            toast(getString(FrontendR.string.kairo_saves_moving_wait))
            return
        }
        val selected = tree
        if (!game.external && selected == null) {
            libraryScreen.showStatus(getString(R.string.choose_dos_folder_for_game))
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
        val availableGames = libraryEntries.toList()
        val loading = if (game.installer) getString(R.string.preparing_installer)
            else getString(FrontendR.string.kairo_launch_preparing, titleOf(game))
        releaseInputs()
        hideKeyboard()
        val generation = beginLaunch(loading)
        closeProgramPicker()
        prepareCancelled.set(true)
        val cancelled = AtomicBoolean(false)
        prepareCancelled = cancelled
        val oldThread = gameThread
        nativeStop()
        stopAudio()
        enterSession(game, fromFrontend, loading)
        configureGuest()
        gameThread = Thread {
            oldThread?.join()
            var message: String? = null
            val success = try {
                val progress: (String) -> Unit = { status ->
                    runOnUiThread { if (current(generation)) showSessionProgress(status) }
                }
                val playableGame = if (game.installer) {
                    if (game.external) dosLibrary.installExternal(game, cancelled, progress)
                    else dosLibrary.install(game, selected!!, cancelled, progress).also { installed ->
                        // The archive is in the folder now, whatever happens to this launch.
                        runOnUiThread {
                            if (tree == selected) editLibrary { dosLibrary.withGame(it, installed) }
                        }
                    }
                } else game
                if (game.installer) {
                    moveGame(game.key, playableGame.key)
                    // An external installed copy's working files were named by its earlier ID.
                    if (game.external) claimSavedData(Uri.EMPTY, listOf(playableGame)).single().error?.let(::error)
                    // From here the installed archive is the session's game; its key holds the moved settings.
                    runOnUiThread {
                        if (current(generation)) {
                            replaceSessionEntry(playableGame)
                            configureGuest()
                        }
                    }
                }
                val saveDir = saveDir(playableGame)
                require(saveDir.isDirectory || saveDir.mkdirs()) { "Could not create game save folder" }
                // Each mounted game drive writes to its own overlay. Folder sessions are stored after the run.
                val overlays = LinkedHashMap<File, File>()
                val sessions = ArrayList<File>()
                fun overlayFor(dir: File, owner: DosLibrary.Game) = beginOverlay(dir, owner.key).also {
                    sessions += dir
                }
                val dependencies = (if (target == DosLaunchTarget.Prompt) emptySet() else
                    DosLaunchConfig.requiredFolders(launch, configName))
                    .associateWith { folder ->
                        val ids = catalog.contentIdsForFolder(folder).toSet()
                        val dependency = availableGames.firstOrNull { catalog.matchId(it) in ids }
                            ?: error(getString(R.string.dependency_missing, folder))
                        val dependencyFile = dosLibrary.prepare(dependency, selected, cancelled)
                        val dependencyDir = saveDir(dependency)
                        val stored = DosStagingStorage.prepare({ text(it) }, dependencyFile, dependencyDir, dependency.contentId!!,
                            overlayFor(dependencyDir, dependency), cancelled, folder,
                            { conflicts -> chooseEditedFiles(dependency, conflicts) })
                        overlays[stored.directory] = stored.overlay
                        stored.directory.listFiles().orEmpty().firstOrNull {
                            it.isDirectory && it.name.equals(folder, true)
                        } ?: stored.directory
                    }
                val file = dosLibrary.prepare(playableGame, selected, cancelled)
                if (!current(generation)) return@Thread
                progress(getString(FrontendR.string.kairo_launch_starting, "DOSBox Staging"))
                val drive = DosStagingStorage.prepare({ text(it) }, file, saveDir, playableGame.contentId!!,
                    overlayFor(saveDir, playableGame), cancelled, launch?.folder,
                    { conflicts -> chooseEditedFiles(playableGame, conflicts) }, progress)
                overlays[drive.directory] = drive.overlay
                val resources = DosStagingResources.prepare(this, cancelled)
                val configuration = DosStagingLaunchConfig.write(this, File(saveDir, "staging/launch.conf"),
                    drive, launch, configName, dependencies, overlays, playerName,
                    effectiveVoodoo(playableGame) == 1, touchSelection(playableGame).directTouch,
                    dosGameSettings.videoHardware(playableGame.key), target,
                    if (dosGameSettings.custom(playableGame.key)) dosGameSettings.configSettings(playableGame.key) else emptyMap(),
                    if (dosGameSettings.custom(playableGame.key)) dosGameSettings.autoexec(playableGame.key) else null)
                if (target == DosLaunchTarget.Browse) runOnUiThread {
                    if (current(generation)) pendingProgramPicker = {
                        showProgramPicker(playableGame, configName, generation)
                    }
                }
                if (game.installer && !game.external) runOnUiThread {
                    if (current(generation)) {
                        preferences.edit().putString("last_played_entry", playableGame.id).apply()
                        showInstallerRemovalPrompt(game, playableGame)
                    }
                }
                if (cancelled.get() || !current(generation)) return@Thread
                // Each DOS machine boots with Caps Lock off.
                inputRouter.resetLocks()
                val started = nativeRun(configuration.config.absolutePath, saveDir.absolutePath, resources.absolutePath,
                    configuration.hostPaths.toTypedArray())
                sessions.forEach { publishOverlay(it, null) }
                if (!started) message = nativeLastError().ifBlank { getString(R.string.emulator_start_failed, "DOSBox Staging") }
                started
            } catch (failure: Exception) {
                message = failure.message ?: getString(R.string.prepare_failed)
                false
            }
            runOnUiThread {
                if (current(generation) && currentEntry != null) {
                    stopAudio()
                    guestFinished(success)
                    if (!success) {
                        toast(message ?: getString(R.string.prepare_failed), long = true)
                        // Let the message finish before rebuilding views for new display settings.
                        handler.postDelayed({ rebuildIfRescaled() }, 3800)
                    } else rebuildIfRescaled()
                }
            }
        }.apply { name = "KairoDos-emulation"; start() }
        pollSession(generation)
    }

    /** The emulation thread ends with the game; a loading session runs once DOSBox reports it. */
    private fun pollSession(generation: Int) {
        handler.postDelayed({
            if (!current(generation) || currentEntry == null) return@postDelayed
            if (nativeStatus() == 2) {
                sessionRunning()
                if (audioThread?.isAlive != true) startAudio()
                updateViewport()
                // Wait until autoexec mounts have completed and the shell is
                // reading input, rather than opening on the initial VGA frame.
                if (pendingProgramPicker != null && nativeInputTelemetry().getOrNull(3) == 1L && !installerPromptOpen) {
                    val open = pendingProgramPicker
                    pendingProgramPicker = null
                    open?.invoke()
                }
            }
            pollSession(generation)
        }, 250)
    }

    /** Ends the emulation thread, including a launch still preparing, and the audio thread after it. */
    override fun stopGuest() {
        prepareCancelled.set(true)
        audioGeneration++
        runCatching { audio?.stop() }
        val game = gameThread
        val sound = audioThread
        // nativeRun initializes its stop flag. If a stop is requested during launch, cancellation
        // can race that initialization; keep requesting stop until the launch has actually returned.
        do {
            nativeStop()
            game?.join(100)
        } while (game?.isAlive == true)
        sound?.join()
    }

    override fun sessionEnded() {
        closeProgramPicker()
        gameThread = null
        audioThread = null
    }

    override fun pauseGuest(paused: Boolean) = nativePause(paused)
    override fun restartGame() {
        inputRouter.resetLocks()
        nativeReset()
    }

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
                    listOf(ChoiceGroup(null, listOf(ChoiceOption(SAVED_COPY, UiText.res(R.string.edited_saved_copy)),
                        ChoiceOption(EDITED_FILE, UiText.res(R.string.edited_file)))))
                }, { if (path in useEdited) EDITED_FILE else SAVED_COPY }, select = {
                    if (it == EDITED_FILE) useEdited += path else useEdited -= path
                }, cycles = false), value = {
                    getString(if (path in useEdited) R.string.edited_file else R.string.edited_saved_copy)
                })
            }
            pages.push(object : GroupsPage(getString(R.string.edited_files), { titleOf(game) }, {
                listOf(RowGroup(getString(R.string.edited_keep_group), rows))
            }, {
                listOf(FooterButton("cancel", { getString(FrontendR.string.kairo_cancel) }) { pages.pop() },
                    FooterButton("continue", { getString(FrontendR.string.kairo_continue) }, ButtonTone.PRIMARY) {
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

    private fun closeProgramPicker() {
        pendingProgramPicker = null
        val picker = programPicker
        programPicker = null
        picker?.close()
    }

    private fun showProgramPicker(game: DosLibrary.Game, configName: String, generation: Int) {
        if (!current(generation) || isFinishing || isDestroyed) return
        releaseInputs()
        userPaused = true
        applyPauseState()
        val mountedDrives = java.util.concurrent.atomic.AtomicReference<Set<Char>>(emptySet())
        programPicker = DirectoryPicker.show(pages, getString(R.string.program_picker_title),
            DirectoryPicker.Location("", getString(R.string.program_picker_launch)), { path ->
            check(current(generation)) { "Game session ended" }
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
            if (current(generation)) {
                if (entry.id.isEmpty()) {
                    // Browse has already mounted the media without executing a program.
                    userPaused = false
                    applyPauseState()
                } else startGame(game, configName, sessionFromFrontend,
                    target = DosLaunchTarget.Program(entry.id, mountedDrives.get()))
            }
        }, {
            if (current(generation) && programPicker != null) {
                programPicker = null
                userPaused = false
                applyPauseState()
            }
        }, rootEntries = listOf(DirectoryPicker.Entry("", getString(R.string.program_picker_prompt), false)))
    }

    /** Offered while the installed game starts; Keep is the same as dismissing it. */
    private fun showInstallerRemovalPrompt(installer: DosLibrary.Game, installed: DosLibrary.Game) {
        installerPromptOpen = true
        fun keep() {
            installerPromptOpen = false
            installerNotice = null
            if (finishAfterInstallerPrompt) {
                finishAfterInstallerPrompt = false
                finish()
            }
        }
        val notice = GameNotice(getString(R.string.installer_remove_title),
            getString(R.string.installer_remove_message, installer.path.substringAfterLast('/')),
            listOf(TrayButton(getString(R.string.installer_keep)) { keep() },
                TrayButton(getString(FrontendR.string.kairo_remove), ButtonTone.DANGER) {
                installerNotice = null
                Thread {
                    runCatching { dosLibrary.deleteInstaller(installer, installed) }
                        .onSuccess { runOnUiThread {
                            installerPromptOpen = false
                            removeLibrarySource(installer)
                            if (finishAfterInstallerPrompt) {
                                finishAfterInstallerPrompt = false
                                finish()
                            }
                        } }
                        .onFailure { failure -> runOnUiThread {
                            installerPromptOpen = false
                            toast(failure.message ?: getString(R.string.installer_remove_failed), long = true)
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

    /** The installer prompt outlives the game; it still decides whether the app closes. */
    override fun finishApp(): Boolean {
        if (installerPromptOpen) {
            finishAfterInstallerPrompt = true
            return false
        }
        finish()
        return true
    }

    override fun keepsNotice() = installerNotice?.let(overlay::showing) == true
    override fun holdsRebuild() = !installerPromptOpen

    // Audio and video --------------------------------------------------------------------------------

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
            track.setVolume(if (muted) 0f else 1f)
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

    private fun stopAudio() {
        audioGeneration++
        runCatching { audio?.pause() }
        audioThread = null
    }

    override fun pauseAudio() { audio?.pause() }
    override fun resumeAudio() { audio?.play() }
    override fun setMuted(muted: Boolean) { audio?.setVolume(if (muted) 0f else 1f) }
    override fun setFastForward(enabled: Boolean) = nativeFastForward(enabled)
    override fun setGuestSurface(surface: Surface?) = nativeSetSurface(surface)
    override fun setShaderPreset(path: String?) = nativeSetShaderPreset(path)
    override val shaderNeedsNewSurface = true

    /** DOS video changes size as games change modes; before a game draws, the screen is 640x400 at 4:3. */
    override fun videoSize(): Triple<Int, Int, Double> {
        val width = nativeVideoWidth()
        val height = nativeVideoHeight()
        if (width <= 0 || height <= 0) return Triple(640, 400, 4.0 / 3.0)
        return Triple(width, height, nativeAspect().takeIf { it in 0.5..3.0 } ?: (4.0 / 3.0))
    }

    // Touch: a touchpad with two-finger right click, or an absolute pointer --------------------------

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFingers = false
    private var directPointerPressed = false

    override fun handleMouseTouch(event: MotionEvent, width: Int, height: Int) {
        if (touchSelection().directTouch) {
            val x = ((event.x / width.coerceAtLeast(1)) * 65534 - 32767).roundToInt()
            val y = ((event.y / height.coerceAtLeast(1)) * 65534 - 32767).roundToInt()
            directPointerPressed = event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL
            nativePointer(x, y, directPointerPressed)
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; moved = false; twoFingers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingers = true
                mouseRouter.hold("touch-right", "rightButton")
            }
            MotionEvent.ACTION_MOVE -> if (!twoFingers) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) + abs(dy) > dp(3)) moved = true
                mouseRouter.moveBy(dx.roundToInt(), dy.roundToInt())
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> mouseRouter.release("touch-right")
            MotionEvent.ACTION_UP -> {
                mouseRouter.release("touch-right")
                if (!moved && !twoFingers) {
                    mouseRouter.hold("touch-tap", "leftButton")
                    handler.postDelayed({ mouseRouter.release("touch-tap") }, 90)
                }
            }
            MotionEvent.ACTION_CANCEL -> mouseRouter.releasePrefix("touch-")
        }
    }

    override fun releaseMouseTouch() {
        if (directPointerPressed) {
            directPointerPressed = false
            nativePointer(0, 0, false)
        }
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
}
