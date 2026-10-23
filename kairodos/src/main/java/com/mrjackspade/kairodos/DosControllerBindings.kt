package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerBindingsCodec
import com.mrjackspade.kairo.frontend.ControllerGuestSpec
import com.mrjackspade.kairo.frontend.ControllerLayout
import com.mrjackspade.kairo.frontend.ControllerDefaults
import com.mrjackspade.kairo.frontend.JoystickPort

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
    private val keyCodes = keys.map { it.code }
    // Keypad = (272) has no IBM key and reaches no guest key, so it is not offered. Earlier
    // mappings may still hold it, and decoding them must not fall back to the defaults.
    private val storedKeyCodes = keyCodes.toSet() + 272
    val spec = ControllerGuestSpec("DOS", keyCodes, { code ->
        when (code) {
            in 256..265 -> "NumPad ${code - 256}"
            266 -> "NumPad ."
            267 -> "NumPad /"
            268 -> "NumPad *"
            269 -> "NumPad -"
            270 -> "NumPad +"
            271 -> "NumPad Enter"
            272 -> "NumPad ="
            300 -> "Num Lock"
            302 -> "Scroll Lock"
            316 -> "Print Screen"
            else -> keys.firstOrNull { it.code == code }?.label ?: "Key $code"
        }
    }, DosKeyboardLayout.value.modifiers,
        joystickTargets,
        "The D-pad is the arrow keys and joystick 1; game profiles can map any button to a DOS joystick control.",
        listOf("menu" to "Open menu", "pause" to "Pause or resume",
            "restart" to "Restart", "exit" to "Exit"), DosKeyboardLayout.value,
        listOf(JoystickPort("Joystick 1", "joy1up", "joy1down", "joy1left", "joy1right", listOf("b", "y")),
            JoystickPort("Joystick 2", "joy2up", "joy2down", "joy2left", "joy2right", listOf("a", "x"))))
    val codec = ControllerBindingsCodec(::defaults, { it in storedKeyCodes }, joystick,
        spec.actions.map { it.first })

    // Catalog data and explicit user mappings must be decoded without rewriting their values.
    fun parse(text: String?) = codec.parse(text)
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

    fun defaults() = listOf(
        // The D-pad is the arrow keys and joystick 1, so a controller without sticks reaches both.
        ControllerBinding("virtual:up", keys = listOf(273), joystick = "joy1up"),
        ControllerBinding("virtual:down", keys = listOf(274), joystick = "joy1down"),
        ControllerBinding("virtual:left", keys = listOf(276), joystick = "joy1left"),
        ControllerBinding("virtual:right", keys = listOf(275), joystick = "joy1right"),
        // A and B are Enter and Space; X and Y are joystick 1's buttons.
        ControllerBinding("virtual:a", keys = listOf(13)),
        ControllerBinding("virtual:b", keys = listOf(32)),
        ControllerBinding("virtual:x", joystick = "b"),
        ControllerBinding("virtual:y", joystick = "y"),
        ControllerBinding("virtual:l1", joystick = "a"),
        ControllerBinding("virtual:r1", joystick = "x"),
        ControllerBinding("virtual:l2", keys = listOf(282)),
        ControllerBinding("virtual:r2", keys = listOf(283)),
        ControllerBinding("virtual:start", keys = listOf(27)),
        ControllerBinding("virtual:select", joystick = "select"),
        ControllerBinding("virtual:menu", action = "menu")
    )

}
