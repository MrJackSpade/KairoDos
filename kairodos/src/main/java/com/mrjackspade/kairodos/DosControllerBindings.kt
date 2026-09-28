package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerBindingsCodec
import com.mrjackspade.kairo.frontend.ControllerGuestSpec

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
    val spec = ControllerGuestSpec("DOS", keyCodes, { code ->
        keys.firstOrNull { it.code == code }?.label ?: "Key $code"
    }, DosKeyboardLayout.value.modifiers,
        joystickTargets,
        "D-pad: arrows; A: Enter; left stick and Y/B: joystick 1; right stick and L/R: joystick 2.",
        listOf("menu" to "Open menu", "pause" to "Pause or resume",
            "restart" to "Restart", "exit" to "Exit"))
    private val codec = ControllerBindingsCodec(::defaults, { it in keyCodes }, joystick,
        spec.actions.map { it.first })

    fun parse(text: String?) = codec.parse(text).let { parsed ->
        if (text != null && parsed == oldDefaults()) defaults() else parsed
    }
    fun toJson(bindings: List<ControllerBinding>) = codec.toJson(bindings)

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
        ControllerBinding("virtual:lsup", joystick = "joy1up"),
        ControllerBinding("virtual:lsdown", joystick = "joy1down"),
        ControllerBinding("virtual:lsleft", joystick = "joy1left"),
        ControllerBinding("virtual:lsright", joystick = "joy1right"),
        ControllerBinding("virtual:rsup", joystick = "joy2up"),
        ControllerBinding("virtual:rsdown", joystick = "joy2down"),
        ControllerBinding("virtual:rsleft", joystick = "joy2left"),
        ControllerBinding("virtual:rsright", joystick = "joy2right"),
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

    /** Classic Doom controls from its DEFAULT.CFG: arrows move/turn, comma and period strafe. */
    fun doom() = listOf(
        ControllerBinding("virtual:up", keys = listOf(273)),
        ControllerBinding("virtual:down", keys = listOf(274)),
        ControllerBinding("virtual:left", keys = listOf(276)),
        ControllerBinding("virtual:right", keys = listOf(275)),
        ControllerBinding("virtual:lsup", keys = listOf(273)),
        ControllerBinding("virtual:lsdown", keys = listOf(274)),
        ControllerBinding("virtual:lsleft", keys = listOf(44)),
        ControllerBinding("virtual:lsright", keys = listOf(46)),
        ControllerBinding("virtual:rsleft", keys = listOf(276)),
        ControllerBinding("virtual:rsright", keys = listOf(275)),
        ControllerBinding("virtual:a", keys = listOf(306)),
        ControllerBinding("virtual:b", keys = listOf(32)),
        ControllerBinding("virtual:x", keys = listOf(304)),
        ControllerBinding("virtual:y", keys = listOf(9)),
        ControllerBinding("virtual:l1", keys = listOf(51)),
        ControllerBinding("virtual:r1", keys = listOf(52)),
        ControllerBinding("virtual:l2", keys = listOf(53)),
        ControllerBinding("virtual:r2", keys = listOf(54)),
        ControllerBinding("virtual:start", keys = listOf(27)),
        ControllerBinding("virtual:select", keys = listOf(13)),
        ControllerBinding("virtual:menu", action = "menu")
    )
}
