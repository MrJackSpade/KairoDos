package com.mrjackspade.kairodos.uitest

import com.mrjackspade.kairo.uitest.AppUnderTest
import com.mrjackspade.kairo.uitest.KairoUiTest
import com.mrjackspade.kairo.uitest.suites.FirstRunTests
import com.mrjackspade.kairo.uitest.suites.GameTests
import com.mrjackspade.kairo.uitest.suites.LibraryTests
import com.mrjackspade.kairo.uitest.suites.SearchTests
import com.mrjackspade.kairo.uitest.suites.WordingTests
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
    override val separateGames = listOf("Digger (1983) - Installer", "Alley Cat - Installer")
    override val searchTerm = "Digger"
}

class KairoDosFirstRunTests : FirstRunTests() { override val app = KairoDosApp }
class KairoDosLibraryTests : LibraryTests() { override val app = KairoDosApp }
class KairoDosSearchTests : SearchTests() { override val app = KairoDosApp }
class KairoDosGameTests : GameTests() { override val app = KairoDosApp }
class KairoDosWordingTests : WordingTests() { override val app = KairoDosApp }

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
