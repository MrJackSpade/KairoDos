package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerBindingsCodec
import com.mrjackspade.kairo.frontend.ControllerGuestSpec

/** DOS key codes and libretro joypad targets; the editor and codec live in Kairo. */
internal object DosControllerBindings {
    val joystick = listOf("b", "y", "select", "start", "up", "down", "left", "right",
        "a", "x", "l1", "r1")
    private val keys = DosKeyboardLayout.value.pages.flatMap { it.rows }
        .flatten().distinctBy { it.code }.sortedBy { it.code }
    private val keyCodes = keys.map { it.code }
    val spec = ControllerGuestSpec("DOS", keyCodes, { code ->
        keys.firstOrNull { it.code == code }?.label ?: "Key $code"
    }, DosKeyboardLayout.value.modifiers,
        joystick.map { it to it.uppercase() },
        "Uses the DOS game port. Games must support a joystick.",
        listOf("menu" to "Open menu", "pause" to "Pause or resume",
            "restart" to "Restart", "exit" to "Exit"))
    private val codec = ControllerBindingsCodec(::defaults, { it in keyCodes }, joystick,
        spec.actions.map { it.first })

    fun parse(text: String?) = codec.parse(text)
    fun toJson(bindings: List<ControllerBinding>) = codec.toJson(bindings)

    fun defaults() = listOf(
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
}
