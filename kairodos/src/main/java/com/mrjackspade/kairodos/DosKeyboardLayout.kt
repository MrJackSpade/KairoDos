package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.GuestKeyboardLayout
import com.mrjackspade.kairo.frontend.GuestKeyboardPage
import com.mrjackspade.kairo.frontend.KeyboardKey

/** Libretro RETROK codes; the DOS backend will consume these when it exists. */
internal object DosKeyboardLayout {
    val value = GuestKeyboardLayout(
        brand = "KAIRODOS",
        pages = listOf(
            GuestKeyboardPage("ABC", listOf(
                listOf(KeyboardKey("Esc", 27, 1.3f)) +
                    "1234567890".map { KeyboardKey("$it", it.code) } +
                    KeyboardKey("Back", 8, 1.5f),
                listOf(KeyboardKey("Tab", 9, 1.3f)) +
                    "qwertyuiop".map { KeyboardKey("$it", it.code) } +
                    KeyboardKey("Enter", 13, 1.5f),
                listOf(KeyboardKey("Caps", 301, 1.3f)) +
                    "asdfghjkl".map { KeyboardKey("$it", it.code) } +
                    KeyboardKey("'", 39),
                listOf(KeyboardKey("Shift", 304, 1.5f)) +
                    "zxcvbnm".map { KeyboardKey("$it", it.code) } +
                    KeyboardKey("Shift", 303, 1.5f),
                listOf(KeyboardKey("Ctrl", 306), KeyboardKey("Alt", 308),
                    KeyboardKey("Space", 32, 5f), KeyboardKey("Alt", 307),
                    KeyboardKey("Ctrl", 305))
            )),
            GuestKeyboardPage("?123", listOf(
                listOf(KeyboardKey("Esc", 27)) +
                    "1234567890".map { KeyboardKey("$it", it.code) } + KeyboardKey("Back", 8),
                listOf(KeyboardKey("!", 49, chordShift = true), KeyboardKey("@", 50, chordShift = true),
                    KeyboardKey("#", 51, chordShift = true), KeyboardKey("$", 52, chordShift = true),
                    KeyboardKey("%", 53, chordShift = true), KeyboardKey("^", 54, chordShift = true),
                    KeyboardKey("&", 55, chordShift = true), KeyboardKey("*", 56, chordShift = true),
                    KeyboardKey("(", 57, chordShift = true), KeyboardKey(")", 48, chordShift = true)),
                listOf(KeyboardKey("-", 45), KeyboardKey("=", 61), KeyboardKey("[", 91),
                    KeyboardKey("]", 93), KeyboardKey("\\", 92), KeyboardKey(";", 59),
                    KeyboardKey("'", 39), KeyboardKey(",", 44), KeyboardKey(".", 46),
                    KeyboardKey("/", 47)),
                listOf(KeyboardKey("Tab", 9), KeyboardKey("Space", 32, 5f),
                    KeyboardKey("Enter", 13))
            ))
        ),
        modifiers = setOf(301, 303, 304, 305, 306, 307, 308),
        shiftCodes = setOf(303, 304),
        capsCode = 301,
        chordShiftCode = 304
    )
}
