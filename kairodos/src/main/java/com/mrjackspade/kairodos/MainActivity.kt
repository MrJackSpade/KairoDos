package com.mrjackspade.kairodos

import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import com.mrjackspade.kairo.frontend.ButtonTone
import com.mrjackspade.kairo.frontend.CatalogGame
import com.mrjackspade.kairo.frontend.CatalogSetting
import com.mrjackspade.kairo.frontend.ChoiceGroup
import com.mrjackspade.kairo.frontend.ChoiceOption
import com.mrjackspade.kairo.frontend.ChoicePage
import com.mrjackspade.kairo.frontend.ChoiceSpec
import com.mrjackspade.kairo.frontend.DirectoryPicker
import com.mrjackspade.kairo.frontend.ExternalGameFile
import com.mrjackspade.kairo.frontend.FooterButton
import com.mrjackspade.kairo.frontend.GameDeletionFlow
import com.mrjackspade.kairo.frontend.GameKey
import com.mrjackspade.kairo.frontend.GameKeyMigration
import com.mrjackspade.kairo.frontend.GameNotice
import com.mrjackspade.kairo.frontend.GamePathMigration
import com.mrjackspade.kairo.frontend.GameSettingsRows
import com.mrjackspade.kairo.frontend.GameSource
import com.mrjackspade.kairo.frontend.GameValue
import com.mrjackspade.kairo.frontend.GroupsPage
import com.mrjackspade.kairo.frontend.LayeredGameCatalog
import com.mrjackspade.kairo.frontend.LibraryStrings
import com.mrjackspade.kairo.frontend.MachineActivity
import com.mrjackspade.kairo.frontend.MachineFirstRun
import com.mrjackspade.kairo.frontend.MachineLibrary
import com.mrjackspade.kairo.frontend.MachineOption
import com.mrjackspade.kairo.frontend.MachineStatus
import com.mrjackspade.kairo.frontend.NativeMachine
import com.mrjackspade.kairo.frontend.Page
import com.mrjackspade.kairo.frontend.RowGroup
import com.mrjackspade.kairo.frontend.SaveLocation
import com.mrjackspade.kairo.frontend.SaveUnit
import com.mrjackspade.kairo.frontend.ScanDepth
import com.mrjackspade.kairo.frontend.SettingsRow
import com.mrjackspade.kairo.frontend.TrayButton
import com.mrjackspade.kairo.frontend.UiText
import com.mrjackspade.kairo.frontend.text
import com.mrjackspade.kairo.frontend.R as FrontendR
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KairoDos: DOS games on DOSBox Staging (:backend-dos), run as the shared machine host's core
 * (cpp/dos_core.cpp), in the shared Kairo frontend.
 */
class MainActivity : MachineActivity<DosLibrary.Game>() {
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
        /** The lock keys' codes (mapKey), in the order dos_core.cpp reports them. */
        private val LOCK_KEYS = listOf(301, 300, 302)
        /**
         * How often a session checks its picture's size (followVideoSize) and a Browse launch whether
         * DOS reads its prompt, to open the program picker.
         */
        private const val SESSION_POLL_MILLIS = 250L
        init {
            // Load the shared C++ runtime on its own, before any DOS session. Otherwise the
            // DOSBox core loads it as part of its own dlopen group; long-lived threads of the
            // shader runtime then hold that group, and the core can never unload between games.
            System.loadLibrary("c++_shared")
        }
    }

    override val identity = Identity("KAIRODOS", 0xff03C5FB.toInt(), "KairoDos", "DOS", "kairodos",
        LibraryStrings("KAIRODOS", "KairoDos", UiText.res(R.string.dos_folder), UiText.res(R.string.no_dos_folder)),
        UiText.res(R.string.credits, "KairoDos", "DOSBox Staging", "MrJackSpade/KairoDos"),
        UiText.res(R.string.library_empty), UiText.res(R.string.library_expired),
        MachineFirstRun.Folder(UiText.res(R.string.folder_title), UiText.res(R.string.folder_description),
            UiText.res(R.string.folder_button)),
        UiText.res(R.string.saves_group), "MrJackSpade/KairoDos")

    // Staging has no save states: a game's saves are its overlay ([saveKinds]). Before a game
    // draws, the screen is 640x400 at 4:3 ([videoSize]).
    override val machine = Machine(640, 400, 4.0 / 3.0, "")
    override val saveStates = false
    override val bridge by lazy { NativeMachine("kairodos", LOCK_KEYS) }
    override val artworkDownloadsEnabled = true

    // Input ------------------------------------------------------------------------------------------

    override val keyboard = DosKeyboardLayout.value
    override val maxKeyCode = 341
    override fun guestKey(androidKey: Int) = mapKey(androidKey)
    override val controllerSpec = DosControllerBindings.spec
    override val bindingsCodec = DosControllerBindings.codec
    override val joystickControls: List<String> get() = DosControllerBindings.joystick
    override fun touchpadReferenceSize() = 640 to 400
    override val directTouchExplanation = UiText.res(R.string.direct_touch_explanation)

    // The library and its catalog --------------------------------------------------------------------

    private val dosLibrary by lazy { DosLibrary(this) }

    override fun createCatalog(): LayeredGameCatalog {
        // Before KairoDos read the shared catalog its overrides and catalog file had their own names.
        DosCatalogMigration.moveLocalFiles(filesDir)
        return LayeredGameCatalog(this, DosCatalog).also { DosSettingsMigration.run(preferences, it) }
    }

    override fun createLibrary(catalog: LayeredGameCatalog): MachineLibrary<DosLibrary.Game> = Library()

    /**
     * KairoDos's games as the machine activity reads them: archives and folders, which a game plays
     * from its base drive and overlay ([startGame]) rather than a working disk image.
     */
    private inner class Library : MachineLibrary<DosLibrary.Game> {
        override val hashCount: Int get() = dosLibrary.hashCount
        override fun cached(tree: Uri) = dosLibrary.cached(tree)
        override fun scan(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean, progress: (String) -> Unit) =
            dosLibrary.scan(tree, depth, cancelled, progress)
        override fun store(tree: Uri, entries: List<DosLibrary.Game>) = dosLibrary.store(tree, entries)
        override fun inspectExternal(file: ExternalGameFile, cancelled: AtomicBoolean) =
            listOf(dosLibrary.inspectExternal(file, cancelled))
        override fun deleteSource(entry: DosLibrary.Game, entries: List<DosLibrary.Game>) = dosLibrary.deleteSource(entry)
        override fun hasWorkingImage(entry: DosLibrary.Game) = false
        override fun withMember(entry: DosLibrary.Game, member: String) = entry.copy(member = member)
        override fun resetWorkingImage(entry: DosLibrary.Game) = error("A DOS game has no working disk image")
        override fun prepare(entry: DosLibrary.Game, cancelled: AtomicBoolean, progress: (String) -> Unit) =
            dosLibrary.prepare(entry, tree, cancelled)
        override fun entryForWorkingImage(path: String, entries: List<DosLibrary.Game>): DosLibrary.Game? = null
        override fun statePaths(entries: List<DosLibrary.Game>): Map<String, String> = emptyMap()
        override fun withError(entry: DosLibrary.Game, message: String) = entry.copy(error = message)
        override fun hashNamedWorkingCopies(): GamePathMigration.Store<DosLibrary.Game> = EarlierSaveDirectories()
    }

    override fun cachedLibrary(tree: Uri) = claimSavedData(tree, dosLibrary.cached(tree))
    override fun scanLibrary(tree: Uri, depth: ScanDepth, cancelled: AtomicBoolean, progress: (String) -> Unit) =
        claimSavedData(tree, dosLibrary.scan(tree, depth, cancelled, progress))
    override val writableFolder = true
    override fun inspectExternal(file: ExternalGameFile, cancelled: AtomicBoolean) =
        listOf(claimSavedData(Uri.EMPTY, listOf(dosLibrary.inspectExternal(file, cancelled))).single()
            .also { it.error?.let(::error) })

    /** Settings the library's older migrations brought to games' keys and paths become their overrides. */
    override fun libraryLoaded(entries: List<DosLibrary.Game>) {
        super.libraryLoaded(entries)
        DosSettingsMigration.run(preferences, catalog)
    }

    override fun validArtworkPath(path: String) = DosCatalog.validArtPath(path)

    /** Saves and settings from before games had keys move to their games as the library loads. */
    private val gameKeyMigration by lazy {
        val saves = File(filesDir, "saves")
        GameKeyMigration(preferences, listOf(
            GameKeyMigration.PreferenceStore(preferences, DosSettingsMigration.LEGACY),
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

    // Settings a game has as its own: catalog overrides (DosCatalog.settings) ---------------------------

    /**
     * CPU speed is Machine's; the video card and 3dfx rendering are Graphics' (AGENTS.md), each the
     * all-games default of its option ([DosCatalog.options]).
     */
    override val machineOptions = DosCatalog.options

    /** Machine: the DOS CPU speed for all games. */
    override fun machinePage(): Page = GroupsPage(getString(FrontendR.string.kairo_machine), { GameSource.ALL_GAMES }, {
        listOf(RowGroup(getString(R.string.machine_cpu_group), listOf(defaultRow(DosCatalog.cpu)), getString(R.string.cpu_foot)))
    })

    /** Graphics › VIDEO, for all games. */
    override fun graphicsGroups() = listOf(RowGroup(getString(R.string.video_group),
        listOf(defaultRow(DosCatalog.video), defaultRow(DosCatalog.voodoo)), getString(R.string.video_foot)))

    /** An option's all-games default, kept where the machine activity reads it ([MachineOption.id]). */
    private fun defaultRow(option: MachineOption): SettingsRow {
        val label = text(option.label)
        return SettingsRow.choice(option.id, label, ChoiceSpec(label, { GameSource.ALL_GAMES },
            { listOf(ChoiceGroup(null, option.choices)) }, { optionDefault(option) },
            select = { value ->
                preferences.edit().putString(option.id, value).apply()
                refreshSettingValues()
            }))
    }

    /** The game's launch section: its catalog record's with the player's own values over it. */
    private fun launchOf(entry: DosLibrary.Game): JSONObject? = catalog.resolve(entry).section(DosCatalog.LAUNCH)
    private fun custom(entry: DosLibrary.Game) = launchOf(entry)?.optBoolean(DosCatalog.CUSTOM) == true
    private fun pickedVersion(entry: DosLibrary.Game) = launchOf(entry)?.optString(DosCatalog.VERSION)?.takeIf(String::isNotEmpty)
    private fun playerName(entry: DosLibrary.Game) = launchOf(entry)?.optString(DosCatalog.PLAYER)?.takeIf(String::isNotEmpty)
    private fun ownSettings(entry: DosLibrary.Game): Map<String, String> =
        launchOf(entry)?.optJSONObject(DosCatalog.SETTINGS)?.let { own -> own.keys().asSequence().associateWith(own::getString) }
            ?: emptyMap()
    private fun ownAutoexec(entry: DosLibrary.Game): String? =
        launchOf(entry)?.takeIf { it.has(DosCatalog.AUTOEXEC) }?.getString(DosCatalog.AUTOEXEC)
    private fun gameKey(entry: DosLibrary.Game) = entry.key.takeIf { entry.contentId != null }

    override fun customSettingRow(entry: DosLibrary.Game, setting: CatalogSetting.Custom, enabled: () -> Boolean): SettingsRow? =
        when (setting.id) {
            DosCatalog.version.id -> versionRow(entry, enabled)
            DosCatalog.dosboxSettings.id -> if (custom(entry)) configRows(entry, enabled).settingsRow { pages.push(it) } else null
            DosCatalog.startupCommands.id -> if (custom(entry)) configRows(entry, enabled).commandsRow() else null
            DosCatalog.player.id -> if (DosLaunchConfig.needsPlayer(catalog.resolve(entry).launch)) playerNameRow(entry, enabled) else null
            else -> error("KairoDos has no row for its catalog setting ${setting.id}")
        }

    /** One of the game's versions (its catalog configs), or Custom: that version with the player's own settings. */
    private fun versionRow(entry: DosLibrary.Game, enabled: () -> Boolean): SettingsRow {
        val id = gameKey(entry)
        val versions = versions(catalog.resolve(entry))
        val value = GameValue({ if (id == null) null else if (custom(entry)) CUSTOM_VERSION else pickedVersion(entry)?.takeIf { it in versions } },
            { versions.firstOrNull().orEmpty() },
            { if (versions.isEmpty()) GameSource.BUILT_IN else catalogSource(entry) },
            { picked -> saveGameSetting(entry) { setVersion(id!!, picked) } })
        return GameSettingsRows.choice(this, "version", getString(R.string.version), { titleOf(entry) },
            versions.map { ChoiceOption(it, DosConfig.versionLabel(this, it)) } +
                ChoiceOption(CUSTOM_VERSION, UiText.res(R.string.version_custom)),
            value, enabled, { GameSettingsRows.noHash(this) }, defaultLabel = {
                versions.firstOrNull()?.let { DosConfig.versionLabel(this, it) } ?: getString(FrontendR.string.kairo_none)
            })
    }

    /**
     * Picks a game's version: one of its presets, or Custom, which runs the preset it had with the
     * player's own DOSBox settings and startup commands. Null goes back to the catalog's preset.
     */
    private fun setVersion(game: GameKey, picked: String?) = when (picked) {
        CUSTOM_VERSION -> catalog.setOverrideSubfield(game, DosCatalog.LAUNCH, DosCatalog.CUSTOM, true)
        null -> catalog.updateOverrideSubfields(game, DosCatalog.LAUNCH, mapOf(DosCatalog.VERSION to null, DosCatalog.CUSTOM to null))
        else -> catalog.updateOverrideSubfields(game, DosCatalog.LAUNCH, mapOf(DosCatalog.VERSION to picked, DosCatalog.CUSTOM to null))
    }

    private fun configRows(entry: DosLibrary.Game, enabled: () -> Boolean): DosConfigRows {
        val id = gameKey(entry)!!
        return DosConfigRows(this, { ownSettings(entry) }, { section, key, value ->
            val own = ownSettings(entry).toMutableMap()
            if (value == null) own.remove("$section.$key") else own["$section.$key"] = value
            saveGameSetting(entry) {
                if (own.isEmpty()) catalog.resetOverrideSubfield(id, DosCatalog.LAUNCH, DosCatalog.SETTINGS)
                else catalog.setOverrideSubfield(id, DosCatalog.LAUNCH, DosCatalog.SETTINGS, JSONObject(own as Map<*, *>))
            }
        }, { ownAutoexec(entry) }, { lines ->
            saveGameSetting(entry) {
                if (lines == null) catalog.resetOverrideSubfield(id, DosCatalog.LAUNCH, DosCatalog.AUTOEXEC)
                else catalog.setOverrideSubfield(id, DosCatalog.LAUNCH, DosCatalog.AUTOEXEC, lines)
            }
        }, { catalogConfig(entry) }, {
            val game = catalog.resolve(entry)
            DosStagingLaunchConfig.hostSettings(game.launch?.configs?.get(startConfig(entry)), machineSettings(entry, game))
        }, { catalogSource(entry) }, enabled, { titleOf(entry) }) { toast(it) }
    }

    /** A game's versions, its catalog configs (eXoDOS presets), the standard one first. */
    private fun versions(record: CatalogGame) = record.launch?.configs?.keys.orEmpty()
        .sortedWith(compareBy<String> { it != "dosbox.conf" }.thenBy { it })

    /** The catalog config the game starts with: the version the player picked, or its standard one. */
    private fun startConfig(game: DosLibrary.Game): String {
        val versions = versions(catalog.resolve(game))
        return pickedVersion(game)?.takeIf { it in versions } ?: versions.firstOrNull() ?: "dosbox.conf"
    }

    private fun catalogConfig(game: DosLibrary.Game): DosConfig? =
        catalog.resolve(game).launch?.configs?.get(startConfig(game))?.let(DosConfig::parse)

    private fun catalogSource(game: DosLibrary.Game) = GameSource.label(catalog.sourceOf(game, "launch", "configs"))

    /** What the game's machine options and touch give the launch configuration. */
    private fun machineSettings(entry: DosLibrary.Game, game: CatalogGame): DosStagingLaunchConfig.Machine {
        val options = optionValues(game)
        return DosStagingLaunchConfig.Machine(options.getValue(DosCatalog.cpu.id) == DosCatalog.CPU_MAX,
            options.getValue(DosCatalog.voodoo.id) == DosCatalog.VOODOO_SINGLE, touchSelection(entry).directTouch,
            DosVideoHardware.machine(options.getValue(DosCatalog.video.id)),
            options.getValue(DosCatalog.joystick.id) == DosCatalog.ON)
    }

    /** The name a game asks for, such as for saves, edited in its tray and checked on Save. */
    private fun playerNameRow(entry: DosLibrary.Game, enabled: () -> Boolean): SettingsRow {
        val id = gameKey(entry)
        return GameSettingsRows.text(this, "player", getString(R.string.player_name), getString(R.string.player_name_hint),
            { playerName(entry) }, { false }, { null },
            { value -> savePlayerName(entry, id, value) }, {}, empty = getString(R.string.player_name_empty),
            enabled = enabled, disabledReason = { GameSettingsRows.noHash(this) })
    }

    private fun savePlayerName(entry: DosLibrary.Game, id: GameKey?, value: String): Boolean {
        id ?: return false
        val name = value.trim().uppercase(java.util.Locale.ROOT)
        if (!name.matches(DosCatalog.PLAYER_NAME)) {
            toast(getString(R.string.player_name_invalid))
            return false
        }
        saveGameSetting(entry) { catalog.setOverrideSubfield(id, DosCatalog.LAUNCH, DosCatalog.PLAYER, name) }
        refreshSettingValues()
        return true
    }

    /** The game's own folder or archive; a frontend's file or the DOS folder itself is not the library's to delete. */
    override fun deletePrompt(entry: DosLibrary.Game) = if (entry.external || entry.rootFolder) null
        else GameDeletionFlow.Prompt(entry.path, entry.folder)
    override fun deleteSource(entry: DosLibrary.Game) = dosLibrary.deleteSource(entry)
    override val resetMessage = UiText.res(R.string.reset_message)

    // Diagnostics ------------------------------------------------------------------------------------

    override fun diagnosticsRows(running: Boolean): List<SettingsRow> =
        listOf(SettingsRow.info("core", getString(R.string.diagnostics_core)) { "DOSBox Staging" }) +
            super.diagnosticsRows(running) + if (!running) emptyList() else listOf(
                SettingsRow.info("video", getString(R.string.diagnostics_video)) {
                    videoSize().let { (width, height) -> getString(R.string.diagnostics_video_size, width, height) }
                })

    /** DOS video changes size as games change modes (dos_core.cpp details); before a game draws, [machine]'s. */
    override fun videoSize(): Triple<Int, Int, Double> {
        val core = MachineStatus(bridge.status()).core
        val size = core["video"]?.split('x')?.map(String::toInt)
        val aspect = core["aspect"]?.toDouble()
        if (size == null || size[0] <= 0 || size[1] <= 0 || aspect == null || aspect !in 0.5..3.0)
            return Triple(machine.screenWidth, machine.screenHeight, machine.aspect)
        return Triple(size[0], size[1], aspect)
    }

    // First run: one step, the DOS folder ------------------------------------------------------------

    override var firstRunStep: Int
        get() = if (preferences.getBoolean("onboarding_complete_v1", tree != null)) 2 else 0
        set(value) { preferences.edit().putBoolean("onboarding_complete_v1", value >= 2).apply() }

    // Saved games ------------------------------------------------------------------------------------

    override val saveKinds = mapOf(SAVE_KIND to OVERLAY_UNIT)
    override val appSaves: File get() = File(filesDir, "dos-saves")
    override fun saveUnitLabel(kind: String) = getString(R.string.saved_files)
    override fun v1DataStores(): List<GamePathMigration.Store<DosLibrary.Game>> =
        listOf(DosSettingsMigration.v1Settings(preferences), catalog.v1Overrides(), EarlierSaveDirectories())

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
    @Synchronized private fun publishOverlay(saveDir: File, game: GameKey?) {
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

    /** The working folders of the session that last started, whose overlays go back once it ends. */
    @Volatile private var sessionDirs: List<File> = emptyList()

    /**
     * Stores the ended session's overlays where its saves are ([publishOverlay]). One that cannot
     * go yet (the save folder changed) keeps its unsaved mark, so the game's next launch stores it
     * or fails with why; the player is told now.
     */
    private fun publishSession() {
        val dirs = sessionDirs
        sessionDirs = emptyList()
        try {
            dirs.forEach { publishOverlay(it, null) }
        } catch (failure: Exception) {
            runOnUiThread { toast(failure.message ?: getString(R.string.prepare_failed), long = true) }
        }
    }

    /** The session's overlays go back once DOSBox has ended. */
    override fun stopGuest() {
        super.stopGuest()
        publishSession()
    }

    /** A game that ended DOSBox itself (EXIT) has its overlays stored too. */
    override fun sessionEnded() {
        super.sessionEnded()
        closeProgramPicker()
        Thread { publishSession() }.apply { name = "KairoDos-saves"; start() }
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
        catalog.moveOverrides(from, to)
    }

    // Launching --------------------------------------------------------------------------------------

    /** What the next session runs: the game, its prompt to browse, or a program the player picked. */
    @Volatile private var launchTarget: DosLaunchTarget = DosLaunchTarget.Normal
    /** The game an installer installed in the session starting, which becomes the session's game. */
    @Volatile private var installed: Pair<DosLibrary.Game, DosLibrary.Game>? = null
    private var installerPromptOpen = false
    private var finishAfterInstallerPrompt = false
    private var installerNotice: GameNotice? = null
    private var programPicker: DirectoryPicker? = null

    /** A game with several versions asks which the first time; one that asks for a DOS player name gets one. */
    override fun launch(entry: DosLibrary.Game, fromFrontend: Boolean) {
        val versions = versions(catalog.resolve(entry))
        val chosen = custom(entry) || pickedVersion(entry) in versions
        if (versions.size > 1 && !chosen && gameKey(entry) != null) chooseVersion(entry, versions, fromFrontend)
        else launchPlayer(entry, fromFrontend)
    }

    private fun launchPlayer(entry: DosLibrary.Game, fromFrontend: Boolean) {
        if (DosLaunchConfig.needsPlayer(catalog.resolve(entry).launch) && playerName(entry) == null) {
            choosePlayerName(entry, fromFrontend)
            return
        }
        launchTarget = DosLaunchTarget.Normal
        super.launch(entry, fromFrontend)
    }

    /** Choose a version the first time a game with several presets is played. Back cancels. */
    private fun chooseVersion(game: DosLibrary.Game, versions: List<String>, fromFrontend: Boolean) {
        pages.push(ChoicePage(ChoiceSpec(getString(R.string.version), { titleOf(game) }, {
            listOf(ChoiceGroup(null, versions.map { ChoiceOption(it, DosConfig.versionLabel(this, it)) }))
        }, { pickedVersion(game) }, select = { selected ->
            catalog.setOverrideSubfield(game.key, DosCatalog.LAUNCH, DosCatalog.VERSION, selected)
            launchPlayer(game, fromFrontend)
        }, stay = true, cycles = false)))
    }

    /** Games that ask for a DOS player name get one before their first launch. */
    private fun choosePlayerName(game: DosLibrary.Game, fromFrontend: Boolean) {
        val id = gameKey(game) ?: return
        var name = playerName(game) ?: "PLAYER"
        pages.push(GroupsPage(getString(R.string.player_name), { titleOf(game) }, {
            listOf(RowGroup(null, listOf(GameSettingsRows.text(this, "player", getString(R.string.player_name_short),
                getString(R.string.player_name_hint), { name }, { false }, { null },
                { value -> if (savePlayerName(game, id, value)) name = playerName(game) ?: name },
                {})), getString(R.string.player_name_foot)))
        }, {
            listOf(FooterButton("play", { getString(R.string.play) }, ButtonTone.PRIMARY) {
                if (savePlayerName(game, id, name)) launchPlayer(game, fromFrontend)
            })
        }))
    }

    /** Open at prompt: the game's drives mounted and nothing run, then the program picker. */
    override val commandLine = true

    override fun startAtPrompt(entry: DosLibrary.Game, game: CatalogGame, cancelled: AtomicBoolean, progress: (String) -> Unit) {
        launchTarget = DosLaunchTarget.Browse
        startGame(entry, game, cancelled, progress)
    }

    /**
     * Prepares [entry]'s drives (an installer installs its game first) and starts DOSBox on them
     * with its launch configuration, on the launch thread.
     */
    override fun startGame(entry: DosLibrary.Game, game: CatalogGame, cancelled: AtomicBoolean, progress: (String) -> Unit) {
        val target = launchTarget
        launchTarget = DosLaunchTarget.Normal
        installed = null
        check(!saveFolder.busy) { getString(FrontendR.string.kairo_saves_moving_wait) }
        val selected = tree
        check(entry.external || selected != null) { getString(R.string.choose_dos_folder_for_game) }
        val availableGames = libraryEntries.toList()
        val playable = if (entry.installer) {
            if (entry.external) dosLibrary.installExternal(entry, cancelled, progress)
            else dosLibrary.install(entry, selected!!, cancelled, progress).also { result ->
                // The archive is in the folder now, whatever happens to this launch.
                runOnUiThread { if (tree == selected) editLibrary { dosLibrary.withGame(it, result) } }
            }.let { result -> entry.member?.let { result.copy(member = it) } ?: result }
        } else entry
        if (entry.installer) {
            moveGame(entry.key, playable.key)
            // An external installed copy's working files were named by its earlier ID.
            if (entry.external) claimSavedData(Uri.EMPTY, listOf(playable)).single().error?.let(::error)
            installed = entry to playable
        }
        val record = catalog.resolve(playable)
        val launch = record.launch
        val configName = startConfig(playable)
        val player = playerName(playable)
        check(target != DosLaunchTarget.Normal || !DosLaunchConfig.needsPlayer(launch) || player != null) {
            getString(R.string.player_name_empty)
        }
        val saveDir = saveDir(playable)
        require(saveDir.isDirectory || saveDir.mkdirs()) { "Could not create game save folder" }
        // Each mounted game drive writes to its own overlay. Folder sessions are stored after the run.
        val overlays = LinkedHashMap<File, File>()
        val sessions = ArrayList<File>()
        fun overlayFor(dir: File, owner: DosLibrary.Game) = beginOverlay(dir, owner.key).also { sessions += dir }
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
        val file = dosLibrary.prepare(playable, selected, cancelled)
        progress(getString(FrontendR.string.kairo_launch_starting, "DOSBox Staging"))
        val drive = DosStagingStorage.prepare({ text(it) }, file, saveDir, playable.contentId!!,
            overlayFor(saveDir, playable), cancelled, launch?.folder,
            { conflicts -> chooseEditedFiles(playable, conflicts) }, progress)
        overlays[drive.directory] = drive.overlay
        sessionDirs = sessions
        val resources = DosStagingResources.prepare(this, cancelled)
        val customized = custom(playable)
        val configuration = DosStagingLaunchConfig.write(this, File(saveDir, "staging/launch.conf"),
            drive, launch, configName, dependencies, overlays, player, machineSettings(playable, record), target,
            if (customized) ownSettings(playable) else emptyMap(), if (customized) ownAutoexec(playable) else null)
        check(!cancelled.get()) { getString(R.string.prepare_failed) }
        // dos_core.cpp's launch: the game's working folder, the host paths its configuration names, and Staging's files.
        check(bridge.start("", saveDir.absolutePath, configuration.hostPaths,
            mapOf("config" to configuration.config.absolutePath, "resources" to resources.absolutePath))) {
            "Another machine is still running"
        }
        awaitRunning()
        if (target == DosLaunchTarget.Browse) browsing = true
    }

    /** A Browse launch opens the program picker once DOS reads its prompt. */
    @Volatile private var browsing = false

    override fun gameStarted(entry: DosLibrary.Game, game: CatalogGame) {
        installed?.let { (installer, game) ->
            installed = null
            // From here the installed archive is the session's game; its key holds the moved settings.
            replaceSessionEntry(game)
            if (!installer.external) {
                preferences.edit().putString("last_played_entry", game.id).apply()
                showInstallerRemovalPrompt(installer, game)
            }
        }
        if (browsing) {
            browsing = false
            awaitPrompt(sessionGeneration)
        }
        followVideoSize(sessionGeneration, null)
    }

    /** DOS changes its picture's size with the video mode; the game's view follows it while the session lasts. */
    private fun followVideoSize(generation: Int, shown: Triple<Int, Int, Double>?) {
        handler.postDelayed({
            if (!current(generation) || currentEntry == null) return@postDelayed
            val size = videoSize()
            if (size != shown) updateViewport()
            followVideoSize(generation, size)
        }, SESSION_POLL_MILLIS)
    }

    /** Waits until autoexec mounts have completed and the shell reads input, rather than opening on the first frame. */
    private fun awaitPrompt(generation: Int) {
        handler.postDelayed({
            if (!current(generation) || currentEntry == null) return@postDelayed
            if (bridge.inputTelemetry()?.getOrNull(3) == 1L && !installerPromptOpen) showProgramPicker(generation)
            else awaitPrompt(generation)
        }, SESSION_POLL_MILLIS)
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
        val picker = programPicker
        programPicker = null
        picker?.close()
    }

    /** The guest's directory through the core (dos_core.cpp command "list"). */
    private fun listDirectory(path: String): String =
        checkNotNull(bridge.command("list $path")) { getString(FrontendR.string.kairo_state_error_not_running) }

    private fun showProgramPicker(generation: Int) {
        if (!current(generation) || isFinishing || isDestroyed) return
        val game = currentEntry ?: return
        releaseInputs()
        userPaused = true
        applyPauseState()
        val mountedDrives = java.util.concurrent.atomic.AtomicReference<Set<Char>>(emptySet())
        programPicker = DirectoryPicker.show(pages, getString(R.string.program_picker_title),
            DirectoryPicker.Location("", getString(R.string.program_picker_launch)), { path ->
            check(current(generation)) { "Game session ended" }
            val response = listDirectory(path)
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
                } else {
                    launchTarget = DosLaunchTarget.Program(entry.id, mountedDrives.get())
                    startSession(game, catalog.resolve(game), sessionFromFrontend)
                }
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

    // Touch: a touchpad with two-finger right click; direct touch is the shared pointer -------------

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFingers = false

    override fun handleMouseTouch(event: MotionEvent, width: Int, height: Int) {
        if (touchSelection().directTouch) {
            super.handleMouseTouch(event, width, height)
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
