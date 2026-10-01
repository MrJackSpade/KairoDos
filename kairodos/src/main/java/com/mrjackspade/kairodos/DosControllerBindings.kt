package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerBindingsCodec
import com.mrjackspade.kairo.frontend.ControllerGuestSpec
import com.mrjackspade.kairo.frontend.ControllerLayout
import com.mrjackspade.kairo.frontend.ControllerDefaults

/** DOS key codes and libretro joypad targets; the editor and codec live in Kairo. */
internal object DosControllerBindings {
    val joystick = listOf("b", "y", "select", "start", "up", "down", "left", "right",
        "a", "x", "l1", "r1", "l2", "r2", "l3", "r3",
        "joy1up", "joy1down", "joy1left", "joy1right",
        "joy2up", "joy2down", "joy2left", "joy2right")
    private val joystickTargets = listOf(
        "joy1up" to "1 up", "joy1down" to "1 down",
        "joy1left" to "1 left", "joy1right" to "1 right",
        "b" to "1 button 1", "y" to "1 button 2",
        "joy2up" to "2 up", "joy2down" to "2 down",
        "joy2left" to "2 left", "joy2right" to "2 right",
        "a" to "2 button 1", "x" to "2 button 2")
    private val keys = DosKeyboardLayout.value.pages.flatMap { it.rows }
        .flatten().distinctBy { it.code }.sortedBy { it.code }
    // Libretro keypad codes let game-specific presets map physical controls to
    // DOS games that use the numeric keypad without adding analog-stick input.
    private val keypadCodes = 256..272
    private val keyCodes = (keys.map { it.code } + keypadCodes).distinct()
    val spec = ControllerGuestSpec("DOS", keyCodes, { code ->
        keys.firstOrNull { it.code == code }?.label ?: when (code) {
            in 256..265 -> "NumPad ${code - 256}"
            266 -> "NumPad ."
            267 -> "NumPad /"
            268 -> "NumPad *"
            269 -> "NumPad -"
            270 -> "NumPad +"
            271 -> "NumPad Enter"
            272 -> "NumPad ="
            else -> "Key $code"
        }
    }, DosKeyboardLayout.value.modifiers,
        joystickTargets,
        "D-pad: arrows; A: Enter; game-specific profiles can map the D-pad and buttons to DOS joystick controls.",
        listOf("menu" to "Open menu", "pause" to "Pause or resume",
            "restart" to "Restart", "exit" to "Exit"))
    private val codec = ControllerBindingsCodec(::defaults, { it in keyCodes }, joystick,
        spec.actions.map { it.first })

    fun parse(text: String?) = codec.parse(text).let { parsed ->
        when {
            text != null && parsed == oldDefaults() -> defaults()
            // Upgrade only an untouched saved Doom preset; customized mappings keep their speed.
            text != null && parsed == doom().map { it.copy(mouseSpeed = 1f) } -> doom()
            else -> parsed
        }
    }
    fun toJson(bindings: List<ControllerBinding>) = codec.toJson(bindings)
    fun valid(array: org.json.JSONArray) = codec.valid(array)

    fun defaults(layout: ControllerLayout) = ControllerDefaults.resolve(layout, defaults(),
        defaults() + listOf(
            ControllerBinding("virtual:lsup", joystick = "joy1up"),
            ControllerBinding("virtual:lsdown", joystick = "joy1down"),
            ControllerBinding("virtual:lsleft", joystick = "joy1left"),
            ControllerBinding("virtual:lsright", joystick = "joy1right"),
            ControllerBinding("virtual:rsup", joystick = "joy2up"),
            ControllerBinding("virtual:rsdown", joystick = "joy2down"),
            ControllerBinding("virtual:rsleft", joystick = "joy2left"),
            ControllerBinding("virtual:rsright", joystick = "joy2right")
        )).bindings

    fun builtInFor(contentId: String, layout: ControllerLayout): List<ControllerBinding>? =
        builtInFor(contentId)?.let { withSticks ->
            ControllerDefaults.resolve(layout, withoutSticks(withSticks), withSticks).bindings
        }

    fun doom(layout: ControllerLayout) = ControllerDefaults.resolve(layout, withoutSticks(doom()), doom()).bindings

    /** The existing FPS layout uses D-pad strafe and right-stick turning. Restore keyboard
     * turning to the D-pad on handhelds without sticks and strafe to the shoulders. */
    private fun withoutSticks(bindings: List<ControllerBinding>) = bindings
        .filterNot { it.input.startsWith("virtual:ls") || it.input.startsWith("virtual:rs") }
        .map { binding -> when (binding.input) {
            "virtual:left" -> ControllerBinding(binding.input, keys = listOf(276))
            "virtual:right" -> ControllerBinding(binding.input, keys = listOf(275))
            "virtual:l1" -> ControllerBinding(binding.input, keys = listOf(44))
            "virtual:r1" -> ControllerBinding(binding.input, keys = listOf(46))
            else -> binding
        } }

    /** Match catalog identities, never a ZIP filename or a user-edited title. */
    fun builtInFor(contentId: String): List<ControllerBinding>? = when (contentId) {
        "sha256-dos-manifest-v1:05cc07a7f13a69cf5e086b682cbac39a9f95c9b89c25571bd5d54e349a611158",
        "sha256-dos-manifest-v1:6fcc94dd920a5592e6780333f129931477d8496e2c41a0b2522cd553bb4264c6" -> duke3d()
        else -> null
    }

    /** Duke's shipped keyboard controls; joystick calibration is not required. */
    fun duke3d() = doom().map { binding ->
        when (binding.input) {
            "virtual:rsleft", "virtual:rsright" -> binding.copy(mouseSpeed = 2f)
            "virtual:x" -> ControllerBinding(binding.input, keys = listOf(97)) // Jump
            "virtual:y" -> ControllerBinding(binding.input, keys = listOf(122)) // Crouch
            "virtual:l2" -> ControllerBinding(binding.input, keys = listOf(59)) // Previous weapon
            "virtual:r2" -> ControllerBinding(binding.input, keys = listOf(39)) // Next weapon
            else -> binding
        }
    } + listOf(
        // Native aim keys work without toggling the game's mouse-aiming mode.
        ControllerBinding("virtual:rsup", keys = listOf(278)), // Home: aim up
        ControllerBinding("virtual:rsdown", keys = listOf(279)), // End: aim down
        ControllerBinding("button:106", keys = listOf(304)),
        ControllerBinding("button:107", keys = listOf(9)))

    /** Upgrade profiles that only saved the previous built-in layout. */
    private fun oldDefaults() = listOf(
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
    )

    fun defaults() = listOf(
        ControllerBinding("virtual:up", keys = listOf(273)),
        ControllerBinding("virtual:down", keys = listOf(274)),
        ControllerBinding("virtual:left", keys = listOf(276)),
        ControllerBinding("virtual:right", keys = listOf(275)),
        ControllerBinding("virtual:a", keys = listOf(13)),
        ControllerBinding("virtual:b", joystick = "y"),
        ControllerBinding("virtual:x", keys = listOf(32)),
        ControllerBinding("virtual:y", joystick = "b"),
        ControllerBinding("virtual:l1", joystick = "a"),
        ControllerBinding("virtual:r1", joystick = "x"),
        ControllerBinding("virtual:start", keys = listOf(27)),
        ControllerBinding("virtual:select", joystick = "select"),
        ControllerBinding("virtual:menu", action = "menu")
    )

    /** Classic Doom controls: keyboard movement/strafe and analog mouse turning. */
    fun doom() = listOf(
        ControllerBinding("virtual:up", keys = listOf(273)),
        ControllerBinding("virtual:down", keys = listOf(274)),
        ControllerBinding("virtual:left", keys = listOf(44)),
        ControllerBinding("virtual:right", keys = listOf(46)),
        ControllerBinding("virtual:lsup", keys = listOf(273)),
        ControllerBinding("virtual:lsdown", keys = listOf(274)),
        ControllerBinding("virtual:lsleft", keys = listOf(44)),
        ControllerBinding("virtual:lsright", keys = listOf(46)),
        ControllerBinding("virtual:rsleft", mouse = "moveLeft", mouseSpeed = 8f),
        ControllerBinding("virtual:rsright", mouse = "moveRight", mouseSpeed = 8f),
        ControllerBinding("virtual:a", keys = listOf(306)),
        ControllerBinding("virtual:b", keys = listOf(32)),
        ControllerBinding("virtual:x", keys = listOf(304)),
        ControllerBinding("virtual:y", keys = listOf(9)),
        ControllerBinding("virtual:l1", keys = listOf(276)),
        ControllerBinding("virtual:r1", keys = listOf(275)),
        ControllerBinding("virtual:l2", cycleKeys = (49..55).toList()),
        ControllerBinding("virtual:r2", cycleKeys = (49..55).toList()),
        ControllerBinding("virtual:start", keys = listOf(13)),
        ControllerBinding("virtual:select", keys = listOf(27)),
        ControllerBinding("virtual:menu", action = "menu")
    )
}
