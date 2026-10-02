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
    private val keyCodes = (keys.map { it.code } + keypadCodes + 96).distinct()
    val spec = ControllerGuestSpec("DOS", keyCodes, { code ->
        keys.firstOrNull { it.code == code }?.label ?: when (code) {
            96 -> "`"
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

}
