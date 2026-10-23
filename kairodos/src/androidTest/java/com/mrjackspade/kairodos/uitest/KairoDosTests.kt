package com.mrjackspade.kairodos.uitest

import com.mrjackspade.kairo.uitest.AppUnderTest
import com.mrjackspade.kairo.uitest.KairoUiTest
import com.mrjackspade.kairo.uitest.suites.FirstRunTests
import com.mrjackspade.kairo.uitest.suites.GameTests
import com.mrjackspade.kairo.uitest.suites.LibraryTests
import com.mrjackspade.kairo.uitest.suites.SearchTests
import com.mrjackspade.kairo.uitest.suites.WordingTests
import com.mrjackspade.kairo.uitest.suites.MenuTests
import com.mrjackspade.kairo.uitest.suites.ControllerTests
import com.mrjackspade.kairo.uitest.suites.TouchTests
import com.mrjackspade.kairo.uitest.suites.LaunchTests
import com.mrjackspade.kairo.uitest.suites.LibraryManagementTests
import com.mrjackspade.kairo.uitest.suites.SettingsTests
import org.junit.Test

/**
 * KairoDos as the shared UI tests see it. Its test resources are two eXoDOS game ZIPs, Digger and
 * Alley Cat, which the library lists as installers until their first launch installs them.
 */
object KairoDosApp : AppUnderTest() {
    override val name = "KairoDos"
    override val brand = "KAIRODOS"
    override val folderStepTitle = "Choose a DOS folder"
    override val biosTitle: String? = null
    override val biosResource: String? = null
    override val bootGame = "Digger (1983) - Installer"
    // Its first launch installs it; it runs as the installed game.
    override val runningTitle = "Digger (1983)"
    override val separateGames = listOf("Digger (1983) - Installer", "Alley Cat - Installer")
    override val searchTerm = "Digger"
    // SDL key codes: the cursor keys, Return and Space (DosControllerBindings).
    override val arrowKeys = listOf(273, 274, 276, 275)
    override val returnKey = 13
    override val spaceKey = 32
    override val bootGameFile = "games/Digger (1983).zip"
    override val otherGame = "Alley Cat - Installer"
    override val hasStates = false
}

class KairoDosFirstRunTests : FirstRunTests() { override val app = KairoDosApp }
class KairoDosLibraryTests : LibraryTests() { override val app = KairoDosApp }
class KairoDosSearchTests : SearchTests() { override val app = KairoDosApp }
class KairoDosGameTests : GameTests() { override val app = KairoDosApp }
class KairoDosWordingTests : WordingTests() { override val app = KairoDosApp }
class KairoDosMenuTests : MenuTests() { override val app = KairoDosApp }
class KairoDosControllerTests : ControllerTests() { override val app = KairoDosApp }
class KairoDosTouchTests : TouchTests() { override val app = KairoDosApp }
class KairoDosLaunchTests : LaunchTests() { override val app = KairoDosApp }
class KairoDosLibraryManagementTests : LibraryManagementTests() { override val app = KairoDosApp }
class KairoDosSettingsTests : SettingsTests() { override val app = KairoDosApp }

/** DOS's own behavior: an eXoDOS installer installs itself on its first launch, then runs. */
class DosTests : KairoUiTest() {
    override val app = KairoDosApp

    @Test
    fun anInstallerInstallsAndRunsOnItsFirstLaunch() {
        screens.completeFirstRun().open(KairoDosApp.bootGame).play()
        driver.waitFrames(120)
        val probe = driver.probe()
        expect.equal("the game's error after installing", "", probe.details["error"].orEmpty())
        val picture = driver.pictures.game("installed")
        expect.that("the installed game draws", picture.litFraction() > 0.02) { "lit ${picture.litFraction()}" }
    }

    /** The emulated video card a player chooses is kept when the app starts again. */
    @Test
    fun theVideoCardChoiceIsKept() {
        screens.completeFirstRun()
        screens.library.openMenu().find(com.mrjackspade.kairo.uitest.By.item("f-graphics")).tap()
        val graphics = driver.page("Graphics").waitUntilShown()
        screens.choose(graphics, "video")
        val chosen = graphics.find(com.mrjackspade.kairo.uitest.By.item("video")).find(com.mrjackspade.kairo.uitest.By.id("value")).text()
        session.relaunch()
        screens.library.waitUntilShown().openMenu().find(com.mrjackspade.kairo.uitest.By.item("f-graphics")).tap()
        expect.equal("the video card after restarting", chosen, driver.page("Graphics").waitUntilShown()
            .find(com.mrjackspade.kairo.uitest.By.item("video")).find(com.mrjackspade.kairo.uitest.By.id("value")).text())
    }

    /** The ARM64 dynamic recompiler is built in, selected, and runs code it generated. */
    @Test
    fun theDynamicCoreRunsGeneratedCode() {
        screens.completeFirstRun().open(KairoDosApp.bootGame).play()
        driver.waitFrames(120)
        val probe = driver.probe()
        expect.equal("dynrec built in", "1", probe.details["cpu.dynrec"])
        expect.equal("the dynamic core selected", "1", probe.details["cpu.dynamic"])
        expect.that("generated blocks have run", (probe.details["cpu.executed"]?.toLongOrNull() ?: 0) > 0) {
            probe.details["cpu.executed"].orEmpty()
        }
    }
}
