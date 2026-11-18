package com.crate.engine

import android.view.KeyEvent

/**
 * Linux input event codes (linux/input-event-codes.h), which crate's virtio
 * keyboard takes. Ported from iOS `LinuxKeys.swift`: a typed character maps
 * to the key that types it on a US layout (with Shift when it needs it).
 */
object LinuxKeys {
    const val ESC: Int = 1
    const val N1: Int = 2
    const val TAB: Int = 15
    const val ENTER: Int = 28
    const val LEFT_CTRL: Int = 29
    const val LEFT_SHIFT: Int = 42
    const val BACKSPACE: Int = 14
    const val SPACE: Int = 57
    const val UP: Int = 103
    const val LEFT: Int = 105
    const val RIGHT: Int = 106
    const val DOWN: Int = 108

    /** Modifiers, left and right Ctrl/Shift/Alt/Super. */
    val modifiers: Set<Int> = setOf(29, 42, 56, 125, 97, 54, 100, 126)

    // a to z, in the order HID numbers them (matches iOS letterCodes).
    private val letterCodes = intArrayOf(
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
        49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44,
    )

    private val plainChars: Map<Char, Int> = mapOf(
        '1' to 2, '2' to 3, '3' to 4, '4' to 5, '5' to 6,
        '6' to 7, '7' to 8, '8' to 9, '9' to 10, '0' to 11,
        '-' to 12, '=' to 13, '[' to 26, ']' to 27, '\\' to 43,
        ';' to 39, '\'' to 40, '`' to 41,
        ',' to 51, '.' to 52, '/' to 53, ' ' to SPACE,
        '\n' to ENTER, '\t' to TAB,
    )

    private val shiftedChars: Map<Char, Int> = mapOf(
        '!' to 2, '@' to 3, '#' to 4, '$' to 5, '%' to 6,
        '^' to 7, '&' to 8, '*' to 9, '(' to 10, ')' to 11,
        '_' to 12, '+' to 13, '{' to 26, '}' to 27, '|' to 43,
        ':' to 39, '"' to 40, '~' to 41,
        '<' to 51, '>' to 52, '?' to 53,
    )

    /** The key and whether it needs Shift, for a typed character. */
    fun stroke(c: Char): Pair<Int, Boolean>? {
        if (c in 'a'..'z') return Pair(letterCodes[c - 'a'], false)
        if (c in 'A'..'Z') return Pair(letterCodes[c - 'A'], true)
        plainChars[c]?.let { return Pair(it, false) }
        shiftedChars[c]?.let { return Pair(it, true) }
        return null
    }

    /**
     * A hardware key by Android [KeyEvent] keycode. Letters, digits, and the
     * keys the display key row / BT keyboards need; null for the rest.
     */
    fun codeForKeyCode(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            letterCodes[keyCode - KeyEvent.KEYCODE_A]
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 ->
            keyCode - KeyEvent.KEYCODE_1 + 2
        KeyEvent.KEYCODE_0 -> 11
        KeyEvent.KEYCODE_ENTER -> ENTER
        KeyEvent.KEYCODE_ESCAPE -> ESC
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F10 ->
            keyCode - KeyEvent.KEYCODE_F1 + 59
        KeyEvent.KEYCODE_F11 -> 87
        KeyEvent.KEYCODE_F12 -> 88
        KeyEvent.KEYCODE_SHIFT_LEFT -> LEFT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> LEFT_CTRL
        KeyEvent.KEYCODE_ALT_LEFT -> 56
        KeyEvent.KEYCODE_META_LEFT -> 125
        else -> null
    }
}
