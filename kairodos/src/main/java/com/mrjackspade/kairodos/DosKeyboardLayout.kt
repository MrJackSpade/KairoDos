package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.GuestKeyboardLayout
import com.mrjackspade.kairo.frontend.GuestKeyboardPage
import com.mrjackspade.kairo.frontend.KeyboardKey

/** Libretro RETROK codes, which the DOSBox Staging bridge turns into IBM PC keys. */
internal object DosKeyboardLayout {
    private const val NUM_LOCK = 300
    private const val SCROLL_LOCK = 302

    val value = GuestKeyboardLayout(
        brand = "KAIRODOS",
        pages = listOf(
            // The US rows, so a locked Shift shows what every key types.
            GuestKeyboardPage("ABC", listOf(
                listOf(KeyboardKey("Esc", 27, 1.3f), KeyboardKey("`", 96, shifted = "~")) +
                    "1234567890".zip("!@#\$%^&*()").map { (key, shifted) ->
                        KeyboardKey("$key", key.code, shifted = "$shifted") } +
                    listOf(KeyboardKey("-", 45, shifted = "_"), KeyboardKey("=", 61, shifted = "+"),
                        KeyboardKey("Back", 8, 1.5f)),
                listOf(KeyboardKey("Tab", 9, 1.3f)) +
                    "qwertyuiop".map { KeyboardKey("$it", it.code) } +
                    listOf(KeyboardKey("[", 91, shifted = "{"), KeyboardKey("]", 93, shifted = "}"),
                        KeyboardKey("\\", 92, shifted = "|")),
                listOf(KeyboardKey("Caps", 301, 1.4f)) +
                    "asdfghjkl".map { KeyboardKey("$it", it.code) } +
                    listOf(KeyboardKey(";", 59, shifted = ":"), KeyboardKey("'", 39, shifted = "\""),
                        KeyboardKey("Enter", 13, 1.5f)),
                listOf(KeyboardKey("Shift", 304, 1.5f)) +
                    "zxcvbnm".map { KeyboardKey("$it", it.code) } +
                    listOf(KeyboardKey(",", 44, shifted = "<"), KeyboardKey(".", 46, shifted = ">"),
                        KeyboardKey("/", 47, shifted = "?"), KeyboardKey("Shift", 303, 1.5f)),
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
            ), pickerLabel = "Symbols"),
            // The keys above and beside the IBM main block, with Shift for Shift+F-key commands.
            GuestKeyboardPage("FN", listOf(
                (1..6).map { KeyboardKey("F$it", 281 + it) },
                (7..12).map { KeyboardKey("F$it", 281 + it) },
                listOf(KeyboardKey("Esc", 27), KeyboardKey("Home", 278),
                    KeyboardKey("↑", 273), KeyboardKey("End", 279),
                    KeyboardKey("PgUp", 280), KeyboardKey("Ins", 277), KeyboardKey("PrtSc", 316)),
                listOf(KeyboardKey("Tab", 9), KeyboardKey("←", 276),
                    KeyboardKey("↓", 274), KeyboardKey("→", 275),
                    KeyboardKey("PgDn", 281), KeyboardKey("Del", 127), KeyboardKey("Pause", 19)),
                listOf(KeyboardKey("Shift", 304, 1.5f), KeyboardKey("Ctrl", 306), KeyboardKey("Alt", 308),
                    KeyboardKey("Space", 32, 3f), KeyboardKey("Enter", 13, 1.5f),
                    KeyboardKey("ScrLk", SCROLL_LOCK))
            ), pickerLabel = "Function"),
            // The IBM keypad. Its tall + and Enter keys take two cells, which light together;
            // with Num Lock off the digits act as the navigation keys their legends then show.
            GuestKeyboardPage("NUM", listOf(
                listOf(KeyboardKey("Num", NUM_LOCK), KeyboardKey("/", 267), KeyboardKey("*", 268),
                    KeyboardKey("-", 269)),
                listOf(pad("7", 263, "Home"), pad("8", 264, "↑"), pad("9", 265, "PgUp"),
                    KeyboardKey("+", 270)),
                listOf(pad("4", 260, "←"), pad("5", 261, "5"), pad("6", 262, "→"),
                    KeyboardKey("+", 270)),
                listOf(pad("1", 257, "End"), pad("2", 258, "↓"), pad("3", 259, "PgDn"),
                    KeyboardKey("Enter", 271)),
                listOf(pad("0", 256, "Ins", 2f), pad(".", 266, "Del"), KeyboardKey("Enter", 271))
            ), pickerLabel = "Keypad")
        ),
        modifiers = setOf(301, 303, 304, 305, 306, 307, 308, NUM_LOCK, SCROLL_LOCK),
        shiftCodes = setOf(303, 304),
        capsCode = 301,
        chordShiftCode = 304,
        numLockCode = NUM_LOCK,
        otherLockCodes = setOf(SCROLL_LOCK)
    )

    private fun pad(label: String, code: Int, numLockOff: String, width: Float = 1f) =
        KeyboardKey(label, code, width, numLockOff = numLockOff)
}
