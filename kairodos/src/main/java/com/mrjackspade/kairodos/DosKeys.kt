package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.GuestKeyboardLayout
import com.mrjackspade.kairo.frontend.PcKey
import com.mrjackspade.kairo.frontend.PcKeyboard

/**
 * KairoDos's keys: the shared PC keyboard (PcKey, PcKeyboard) in libretro RETROK codes, which the
 * DOSBox Staging bridge turns into IBM PC keys.
 */
internal object DosKeys {
    val layout: GuestKeyboardLayout = PcKeyboard.layout("KAIRODOS") { it.retroKey }

    /** The RETROK code an Android key event types, or null for one the PC has no key for. */
    fun fromAndroid(androidKey: Int): Int? = PcKey.fromAndroid(androidKey)?.retroKey

    val PcKey.retroKey: Int
        get() = when (this) {
            PcKey.ESCAPE -> 27
            PcKey.D1, PcKey.D2, PcKey.D3, PcKey.D4, PcKey.D5, PcKey.D6, PcKey.D7, PcKey.D8, PcKey.D9, PcKey.D0,
            PcKey.Q, PcKey.W, PcKey.E, PcKey.R, PcKey.T, PcKey.Y, PcKey.U, PcKey.I, PcKey.O, PcKey.P,
            PcKey.A, PcKey.S, PcKey.D, PcKey.F, PcKey.G, PcKey.H, PcKey.J, PcKey.K, PcKey.L,
            PcKey.Z, PcKey.X, PcKey.C, PcKey.V, PcKey.B, PcKey.N, PcKey.M -> label[0].code
            PcKey.MINUS -> 45
            PcKey.EQUALS -> 61
            PcKey.BACKSPACE -> 8
            PcKey.TAB -> 9
            PcKey.LEFT_BRACKET -> 91
            PcKey.RIGHT_BRACKET -> 93
            PcKey.ENTER -> 13
            PcKey.SEMICOLON -> 59
            PcKey.APOSTROPHE -> 39
            PcKey.GRAVE -> 96
            PcKey.BACKSLASH -> 92
            PcKey.COMMA -> 44
            PcKey.PERIOD -> 46
            PcKey.SLASH -> 47
            PcKey.SPACE -> 32
            PcKey.F1 -> 282
            PcKey.F2 -> 283
            PcKey.F3 -> 284
            PcKey.F4 -> 285
            PcKey.F5 -> 286
            PcKey.F6 -> 287
            PcKey.F7 -> 288
            PcKey.F8 -> 289
            PcKey.F9 -> 290
            PcKey.F10 -> 291
            PcKey.F11 -> 292
            PcKey.F12 -> 293
            PcKey.PAD_0 -> 256
            PcKey.PAD_1 -> 257
            PcKey.PAD_2 -> 258
            PcKey.PAD_3 -> 259
            PcKey.PAD_4 -> 260
            PcKey.PAD_5 -> 261
            PcKey.PAD_6 -> 262
            PcKey.PAD_7 -> 263
            PcKey.PAD_8 -> 264
            PcKey.PAD_9 -> 265
            PcKey.PAD_PERIOD -> 266
            PcKey.PAD_DIVIDE -> 267
            PcKey.PAD_MULTIPLY -> 268
            PcKey.PAD_MINUS -> 269
            PcKey.PAD_PLUS -> 270
            PcKey.PAD_ENTER -> 271
            PcKey.UP -> 273
            PcKey.DOWN -> 274
            PcKey.RIGHT -> 275
            PcKey.LEFT -> 276
            PcKey.INSERT -> 277
            PcKey.HOME -> 278
            PcKey.END -> 279
            PcKey.PAGE_UP -> 280
            PcKey.PAGE_DOWN -> 281
            PcKey.DELETE -> 127
            PcKey.NUM_LOCK -> 300
            PcKey.CAPS_LOCK -> 301
            PcKey.SCROLL_LOCK -> 302
            PcKey.RIGHT_SHIFT -> 303
            PcKey.LEFT_SHIFT -> 304
            PcKey.RIGHT_CTRL -> 305
            PcKey.LEFT_CTRL -> 306
            PcKey.RIGHT_ALT -> 307
            PcKey.LEFT_ALT -> 308
            PcKey.PRINT_SCREEN -> 316
            PcKey.PAUSE -> 19
        }
}
