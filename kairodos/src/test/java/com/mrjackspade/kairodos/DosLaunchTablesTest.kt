package com.mrjackspade.kairodos

import com.mrjackspade.kairo.LaunchTablesTest

/** What KairoDos builds as it launches (Kairo#125, KairoDos#133); its library is DOS games, not disk images. */
class DosLaunchTablesTest : LaunchTablesTest() {
    override fun tables() = LaunchTables(keyboards = listOf(DosKeys.layout), others = mapOf(
        "controller spec" to { DosControllerBindings.spec }, "controller bindings" to { DosControllerBindings.codec },
        "video hardware" to { DosVideoHardware.choices }, "machine options" to { DosCatalog.options },
        "drives" to { DosCatalog.drives }))
}
