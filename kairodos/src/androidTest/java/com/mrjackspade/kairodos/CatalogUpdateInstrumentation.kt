package com.mrjackspade.kairodos

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import com.mrjackspade.kairo.frontend.LibraryScrollFixture
import com.mrjackspade.kairo.frontend.LibraryArtworkFixture
import com.mrjackspade.kairo.frontend.LibraryMenuFixture
import com.mrjackspade.kairo.frontend.DeadZoneFixture
import com.mrjackspade.kairo.frontend.LibrarySearchFixture
import com.mrjackspade.kairo.frontend.LibraryKeyboardFixture
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Device fixture for partial catalog layers and rejection before snapshot activation. */
class CatalogUpdateInstrumentation : Instrumentation() {
    private var audioOutputPolicy = false
    private var hardwareRender = false
    private var hardwareGame: String? = null
    private var presentationArchive: String? = null
    private var mouseAnalogHardware = false
    private var mouseAnalogUri: String? = null
    private var presentationUri: String? = null
    private var presentationGame = "doom"
    private var presentationSeconds = 30
    private var presentationCopies = false
    private var presentationSurface = "normal"
    private var presentationTiming = true
    private var menuNavigation = false
    private var controllerMouse = false
    private var catalogControllerSpeed: Float? = null
    private var catalogFetch = false
    private var libraryScroll = false
    private var libraryArtwork = false
    private var libraryMenu = false
    private var deadZone = false
    private var librarySearch = false
    private var endSessionUri: String? = null
    private var touchSticks = false
    private var nextSessionUri: String? = null
    private var catalogProgress = false
    private var catalogInstall = false
    private var catalogPackageFile: String? = null
    private var snapshotActivation = false
    private var keyCycle = false
    private var directoryPicker = false
    private var startup = false
    private var traceStartup = false
    private var exitDialog = false
    private var traceSearch = false
    private var libraryKeyboard = false
    private var stateSlots = false
    private var inputDispatch = false
    private var catalogOverrides = false
    private var stagingStorage = false
    private var dosPromptUri: String? = null
    private var dosProgramUri: String? = null
    private var dosMedia = false
    private var stagingArchive: String? = null
    private var stagingOplMode: String? = null
    private var stagingOplDcBias = false
    private var resolutionSource: String? = null
    private var resolutionWidth = 320
    private var resolutionNoDouble = false
    private var touchKeyboardUri: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        touchKeyboardUri = arguments?.getString("touchKeyboardUri")
        audioOutputPolicy = arguments?.getString("audioOutputPolicy") == "true"
        hardwareRender = arguments?.getString("hardwareRender") == "true"
        hardwareGame = arguments?.getString("hardwareGame")
        presentationArchive = arguments?.getString("presentationArchive")
        mouseAnalogHardware = arguments?.getString("mouseAnalogHardware") == "true"
        mouseAnalogUri = arguments?.getString("mouseAnalogUri")
        presentationUri = arguments?.getString("presentationUri")
        presentationGame = arguments?.getString("presentationGame") ?: "doom"
        presentationSeconds = arguments?.getString("presentationSeconds")?.toInt() ?: 30
        presentationCopies = arguments?.getString("presentationCopies") == "true"
        presentationSurface = arguments?.getString("presentationSurface") ?: "normal"
        presentationTiming = arguments?.getString("presentationTiming") != "false"
        menuNavigation = arguments?.getString("menuNavigation") == "true"
        controllerMouse = arguments?.getString("controllerMouse") == "true"
        catalogControllerSpeed = arguments?.getString("catalogControllerSpeed")?.toFloat()
        catalogFetch = arguments?.getString("catalogFetch") == "true"
        libraryScroll = arguments?.getString("libraryScroll") == "true"
        libraryArtwork = arguments?.getString("libraryArtwork") == "true"
        libraryMenu = arguments?.getString("libraryMenu") == "true"
        deadZone = arguments?.getString("deadZone") == "true"
        librarySearch = arguments?.getString("librarySearch") == "true"
        traceSearch = arguments?.getString("traceSearch") == "true"
        libraryKeyboard = arguments?.getString("libraryKeyboard") == "true"
        stateSlots = arguments?.getString("stateSlots") == "true"
        inputDispatch = arguments?.getString("inputDispatch") == "true"
        catalogOverrides = arguments?.getString("catalogOverrides") == "true"
        stagingStorage = arguments?.getString("stagingStorage") == "true"
        dosPromptUri = arguments?.getString("dosPromptUri")
        dosProgramUri = arguments?.getString("dosProgramUri")
        dosMedia = arguments?.getString("dosMedia") == "true"
        stagingArchive = arguments?.getString("stagingArchive")
        stagingOplMode = arguments?.getString("stagingOplMode")
        stagingOplDcBias = arguments?.getString("stagingOplDcBias") == "true"
        resolutionSource = arguments?.getString("resolutionSource")
        resolutionWidth = arguments?.getString("resolutionWidth")?.toInt() ?: 320
        resolutionNoDouble = arguments?.getString("resolutionNoDouble") == "true"
        exitDialog = arguments?.getString("exitDialog") == "true"
        endSessionUri = arguments?.getString("endSessionUri")
        touchSticks = arguments?.getString("touchSticks") == "true"
        nextSessionUri = arguments?.getString("nextSessionUri")
        catalogPackageFile = arguments?.getString("catalogPackageFile")
        catalogInstall = arguments?.getString("catalogInstall") == "true"
        catalogProgress = arguments?.getString("catalogProgress") == "true"
        snapshotActivation = arguments?.getString("snapshotActivation") == "true"
        keyCycle = arguments?.getString("keyCycle") == "true"
        directoryPicker = arguments?.getString("directoryPicker") == "true"
        startup = arguments?.getString("startup") == "true"
        traceStartup = arguments?.getString("traceStartup") == "true"
        start()
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val before = android.os.SystemClock.elapsedRealtime()
        super.callActivityOnCreate(activity, icicle)
        com.mrjackspade.kairo.frontend.StartupFixture.created(activity, before)
    }
    override fun onStart() {
        val result = Bundle()
        if (touchSticks) {
            try {
                com.mrjackspade.kairo.frontend.TouchStickFixture.verify(this)
                result.putString("stream", "Circle pads, profile layouts and touch setup focus: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (catalogInstall) {
            try {
                result.putString("stream", com.mrjackspade.kairo.frontend.CatalogInstallFixture.verify(this, catalogPackageFile))
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        touchKeyboardUri?.let { uri ->
            try {
                result.putString("stream", com.mrjackspade.kairo.frontend.TouchKeyboardFixture.verify(this, uri))
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (keyCycle) {
            try {
                com.mrjackspade.kairo.frontend.KeyCycleFixture.verify(this)
                result.putString("stream", "D-pad/shoulder cycles, independent sequences, wrap, hats, release, codec and editor save: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (startup) {
            try {
                result.putString("stream", com.mrjackspade.kairo.frontend.StartupFixture.measure(this, traceStartup))
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (snapshotActivation) {
            try {
                com.mrjackspade.kairo.frontend.SnapshotActivationFixture.verify(this)
                result.putString("stream", "Snapshot APK reactivation, offline reuse, repair, compatibility, rollback and host defaults: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (catalogProgress) {
            try {
                com.mrjackspade.kairo.frontend.CatalogProgressFixture.verify(this)
                result.putString("stream", "Catalog progress, completion, failure, cancellation and metadata-only check: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        endSessionUri?.let { uri ->
            try {
                com.mrjackspade.kairo.frontend.EndSessionFixture.verify(this, uri, nextSessionUri)
                result.putString("stream", "Library cancellation, teardown and relaunch: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (exitDialog) {
            try {
                com.mrjackspade.kairo.frontend.ExitDialogFixture.verify(this)
                result.putString("stream", "Exit dialog keys, hats, cancel and exit: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (librarySearch) {
            try {
                result.putString("stream", LibrarySearchFixture.measure(this, traceSearch))
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        if (libraryScroll || libraryArtwork || libraryMenu || deadZone || libraryKeyboard) {
            try {
                if (libraryKeyboard) LibraryKeyboardFixture.verify(this)
                else if (deadZone) DeadZoneFixture.verify(this)
                else if (libraryMenu) LibraryMenuFixture.verify(this)
                else if (libraryArtwork) LibraryArtworkFixture.verify(this) else LibraryScrollFixture.verify(this)
                result.putString("stream", "${if (libraryKeyboard) "Android search keyboard dismissal and reopening" else if (deadZone) "Default/custom/reset deadzone and axis activation" else if (libraryMenu) "Library menu key/hat scrolling" else if (libraryArtwork) "Library artwork cache, ordering, failures and lifecycle" else "Library selection avoids row rebinding and preserves recycled-row activation"}: OK\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        catalogControllerSpeed?.let { expected ->
            try {
                val catalog = DosGameCatalog(targetContext)
                if (catalogFetch) catalog.downloadUpdate()
                val id = "sha256-dos-manifest-v1:a54ee0d6d549825fadefa6bc8516f976c31a9d7b5a91fed00007ef40d01a7cd3"
                val prefs = targetContext.getSharedPreferences("kairodos", android.content.Context.MODE_PRIVATE)
                val bindings = catalog.gameControllerBindings(id, "DOOM.zip",
                    com.mrjackspade.kairo.frontend.ControllerLayout.WITH_STICKS,
                    DosPerGameSettings(prefs, com.mrjackspade.kairo.frontend.GameSettingScope(prefs))
                        .controllerBindings(id))!!
                val speed = bindings.single { it.input == "virtual:rsright" }.mouseSpeed
                check(speed == expected) { "Resolved Doom speed $speed, expected $expected" }
                check(bindings.single { it.input == "virtual:r2" }.keys == listOf(306))
                if (controllerMouse) {
                    val downloaded = java.util.zip.ZipFile(File(targetContext.filesDir, "dos-core-update-v2.zip")).use { zip ->
                        zip.getInputStream(zip.getEntry("controller-profiles-v1.json")).use {
                            JSONObject(it.bufferedReader().readText()).getJSONObject("presets")
                        }
                    }
                    check(bindings.single { it.input == "virtual:a" }.keys == listOf(32))
                    check(bindings.single { it.input == "virtual:left" }.cycleKeys == (49..55).toList())
                    check(bindings.single { it.input == "virtual:right" }.cycleKeys == (49..55).toList())
                    check(bindings.single { it.input == "virtual:r1" }.keys == listOf(306))
                    val dukeId = "sha256-dos-manifest-v1:05cc07a7f13a69cf5e086b682cbac39a9f95c9b89c25571bd5d54e349a611158"
                    val duke = catalog.gameControllerBindings(dukeId, "Duke3D.zip",
                        com.mrjackspade.kairo.frontend.ControllerLayout.WITH_STICKS)!!
                    check(duke.single { it.input == "virtual:start" }.keys == listOf(13))
                    check(duke.single { it.input == "virtual:select" }.keys == listOf(27))
                    var failure: Throwable? = null
                    runOnMainSync { failure = runCatching { ControllerMouseFixture.verify(targetContext, downloaded) }.exceptionOrNull() }
                    failure?.let { throw it }
                }
                result.putString("stream", "Active catalog: Doom turning=$speed, RT=fire; " +
                    (if (controllerMouse) "Duke Start=Enter/Select=Escape, mapper and custom override checks passed; " else "") +
                    "APK unchanged\n")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("stream", failure.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
        try {
            if (audioOutputPolicy) {
                result.putString("stream", AudioOutputPolicyFixture.verify())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (menuNavigation) {
                ControllerMenuFixture.verify(this)
                result.putString("stream", "Controller-only mapping, slider and dialog navigation: OK")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (resolutionSource != null) {
                result.putString("stream", DukeResolutionFixture.run(this, resolutionSource!!, resolutionWidth, resolutionNoDouble))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (dosMedia) {
                DosMediaFixture.verify(this)
                result.putString("stream", "DOS FAT/ISO image browsing and program launch, catalog and standalone mounts: OK\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (dosProgramUri != null) {
                DosProgramFixture.verify(this, dosProgramUri!!)
                result.putString("stream", "DOS Run program: cancel, BAT/CMD/COM/EXE launch, working directory and normal Play: OK\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (dosPromptUri != null) {
                DosPromptFixture.verify(this, dosPromptUri!!)
                result.putString("stream", "DOS prompt: controller settings action, writable shell, normal relaunch: OK\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (directoryPicker) {
                com.mrjackspade.kairo.frontend.DirectoryPickerFixture.verify(this)
                result.putString("stream", "Shared directory picker: async load, retry, controller navigation/selection and cancellation: OK\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (stagingStorage || stagingArchive != null) {
                val message = if (stagingStorage) {
                    StagingStorageFixture.verify(targetContext.cacheDir)
                    "Staging storage migration, preserved saves, cancellation, path safety and launch configuration: OK"
                } else StagingCoreFixture.verify(this, stagingArchive!!, stagingOplMode, stagingOplDcBias)
                result.putString("stream", "$message\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (mouseAnalogUri != null) {
                result.putString("stream", MouseAnalogFixture.verify(this, mouseAnalogUri!!, mouseAnalogHardware))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (presentationUri != null) {
                val metrics = VideoPresentationFixture.measureInstalled(this, presentationUri!!, presentationGame, presentationSeconds)
                result.putString("stream", "Installed video presentation profile: $metrics\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (presentationArchive != null) {
                val metrics = VideoPresentationFixture.measure(this, presentationArchive!!,
                    presentationGame, presentationSeconds, presentationCopies, presentationSurface, presentationTiming)
                result.putString("stream", "Video presentation profile: $metrics\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (hardwareRender) GpuRenderingFixture.verify()
            else if (hardwareGame != null) GpuRenderingFixture.verifyCore(this, hardwareGame!!)
            else if (controllerMouse) {
                var failure: Throwable? = null
                runOnMainSync { failure = runCatching { ControllerMouseFixture.verify(targetContext) }.exceptionOrNull() }
                failure?.let { throw it }
            }
            else if (inputDispatch) {
                var failure: Throwable? = null
                runOnMainSync { failure = runCatching { InputDispatchFixture.verify() }.exceptionOrNull() }
                failure?.let { throw it }
            }
            else if (catalogOverrides) { CatalogOverrideFixture.verify(targetContext.cacheDir); verify() }
            else if (stateSlots) StateSlotStoreFixture.verify(targetContext.cacheDir) else verify()
            result.putString("stream", if (hardwareRender) "Shared GLES contexts, fenced frames, orientation and display lifecycle: OK\n"
                else if (hardwareGame != null) "Staging video/audio boot, pause, surface recreation, reset and exit: OK\n"
                else if (controllerMouse) "Shared controller mouse speed, persistence and Doom preset: OK\n"
                else if (catalogOverrides) "Shared catalog override transactions and DOS merge: OK\n"
                else if (inputDispatch) "Shared input routing and lifecycle: OK\n"
                else if (stateSlots) "Shared state slot transactions: OK\n"
                else "DOS catalog partial merge and invalid snapshot: OK\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "DOS catalog fixture failed: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verify() {
        val id = "sha256-dos-manifest-v1:05cc07a7f13a69cf5e086b682cbac39a9f95c9b89c25571bd5d54e349a611158"
        val shipped = targetContext.assets.open("catalog/dos/05.json").use {
            JSONObject(it.bufferedReader().readText()).getJSONObject("games").getJSONObject(id)
        }
        val partial = JSONObject().put("title", "Updated Duke")
            .put("artwork", JSONObject().put("boxArt", "art/catalog/dos/updated.webp"))
        val user = JSONObject().put("title", "My Duke")
        val merged = CatalogFieldLayers.merge(listOf(
            CatalogFieldLayers.Source("Shipped catalog", shipped),
            CatalogFieldLayers.Source("Updated catalog", partial),
            CatalogFieldLayers.Source("User override", user)
        ), DosCatalogFields::objectField, DosCatalogFields::validValue)
        check(merged.record.getString("title") == "My Duke")
        check(merged.record.getJSONObject("artwork").getString("boxArt") ==
            "art/catalog/dos/updated.webp")
        check(merged.record.getJSONObject("artwork").getString("preview") ==
            shipped.getJSONObject("artwork").getString("preview"))
        check(merged.record.getJSONObject("launch").getString("folder") ==
            shipped.getJSONObject("launch").getString("folder"))
        check(merged.record.getJSONObject("launch").getJSONObject("configs")
            .getString("dosbox.conf") == shipped.getJSONObject("launch")
            .getJSONObject("configs").getString("dosbox.conf"))
        check(merged.record.getJSONArray("tags").toString() ==
            shipped.getJSONArray("tags").toString())
        check(merged.sourceOf("title") == "User override")
        check(merged.sourceOf("artwork", "boxArt") == "Updated catalog")
        check(merged.sourceOf("artwork", "preview") == "Shipped catalog")
        val ignored = CatalogFieldLayers.merge(listOf(
            CatalogFieldLayers.Source("Shipped catalog", shipped),
            CatalogFieldLayers.Source("Invalid update", JSONObject().put("launch",
                JSONObject().put("folder", "../invalid")))
        ), DosCatalogFields::objectField, DosCatalogFields::validValue)
        check(ignored.record.getJSONObject("launch").getString("folder") ==
            shipped.getJSONObject("launch").getString("folder"))

        val validator = DosCatalogUpdate(targetContext)
        File(targetContext.filesDir, "dos-core-update-v2.zip").takeIf(File::isFile)
            ?.let(validator::validateFile)
        val valid = File(targetContext.cacheDir, "catalog-partial-fixture.zip")
        val invalid = File(targetContext.cacheDir, "catalog-invalid-fixture.zip")
        try {
            writeSnapshot(valid, id, partial)
            validator.validateFile(valid)
            writeSnapshot(invalid, id, JSONObject().put("launch",
                JSONObject().put("folder", "../invalid")))
            // Invalid fields are ignored by the layer resolver above, not audited at install.
            validator.validateFile(invalid)
        } finally {
            valid.delete()
            invalid.delete()
        }
    }

    private fun writeSnapshot(file: File, id: String, record: JSONObject) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for (index in 0..255) {
                val name = "%02x.json".format(index)
                val games = JSONObject()
                if (index == 5) games.put(id, record)
                writeEntry(zip, name, JSONObject().put("schemaVersion", 1)
                    .put("games", games))
            }
            writeEntry(zip, "folders.json", JSONObject())
            writeEntry(zip, "controller-profiles-v1.json", JSONObject()
                .put("schemaVersion", 1).put("profiles", JSONObject()))
            writeEntry(zip, "hidden-index-v1.json", JSONObject()
                .put("schemaVersion", 1).put("hidden", JSONObject()))
            writeEntry(zip, "core-v2.json", JSONObject().put("schemaVersion", 2))
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, body: JSONObject) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
