package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ControllerBinding
import com.mrjackspade.kairo.frontend.ControllerBindingsCodec
import com.mrjackspade.kairo.frontend.ControllerGuestSpec
import com.mrjackspade.kairo.frontend.JoystickPort
import com.mrjackspade.kairo.frontend.UiText

/** DOS key codes and libretro joypad targets; the editor and codec live in Kairo. */
internal object DosControllerBindings {
    val joystick = listOf("b", "y", "select", "start", "up", "down", "left", "right",
        "a", "x", "l1", "r1", "l2", "r2", "l3", "r3",
        "joy1up", "joy1down", "joy1left", "joy1right",
        "joy2up", "joy2down", "joy2left", "joy2right")
    private val joystickTargets = listOf(1, 2).flatMap { stick ->
        listOf("joy${stick}up" to UiText.res(R.string.joystick_up, stick),
            "joy${stick}down" to UiText.res(R.string.joystick_down, stick),
            "joy${stick}left" to UiText.res(R.string.joystick_left, stick),
            "joy${stick}right" to UiText.res(R.string.joystick_right, stick)) +
            (if (stick == 1) listOf("b", "y") else listOf("a", "x")).mapIndexed { index, id ->
                id to UiText.res(R.string.joystick_button, stick, index + 1)
            }
    }
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
        UiText.res(R.string.joystick_help),
        ControllerGuestSpec.STANDARD_ACTIONS, DosKeyboardLayout.value,
        listOf(JoystickPort(UiText.res(R.string.joystick_port, 1), "joy1up", "joy1down", "joy1left", "joy1right", listOf("b", "y")),
            JoystickPort(UiText.res(R.string.joystick_port, 2), "joy2up", "joy2down", "joy2left", "joy2right", listOf("a", "x"))))
    val codec = ControllerBindingsCodec({ it in storedKeyCodes }, joystick,
        spec.actions.map { it.first })

    // Catalog data and explicit user mappings must be decoded without rewriting their values.
    fun parse(text: String) = codec.parse(text)
    fun toJson(bindings: List<ControllerBinding>) = codec.toJson(bindings)
    fun valid(array: org.json.JSONArray) = codec.valid(array)
}
