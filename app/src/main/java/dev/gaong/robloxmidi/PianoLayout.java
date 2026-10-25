package dev.gaong.robloxmidi;

import android.view.KeyEvent;

/**
 * Standard 61-note Roblox/Virtual Piano mapping. Uppercase and shifted
 * symbols represent keys that require Shift.
 *
 * C2 (MIDI 36) through C7 (MIDI 96), inclusive.
 */
public final class PianoLayout {
    public static final int MIN_NOTE = 36;
    public static final int MAX_NOTE = 96;
    public static final String DEFAULT =
            "1!2@34$5%6^78*9(0qQwWeErtTyYuiIoOpPasSdDfgGhHjJklLzZxcCvVbBnm";

    private final String keys;
    public PianoLayout(String text) {
        if (text == null || text.length() != 61) {
            throw new IllegalArgumentException("Exactly 61 characters (C2-C7) are required");
        }
        for (int i = 0; i < text.length(); i++) {
            if (keyCode(text.charAt(i)) == KeyEvent.KEYCODE_UNKNOWN) {
                throw new IllegalArgumentException("Unsupported key at " + i);
            }
        }
        this.keys = text;
    }

    public String getKeys() { return keys; }

    public Stroke forNote(int midi, boolean fold) {
        if (fold) {
            while (midi < MIN_NOTE) midi += 12;
            while (midi > MAX_NOTE) midi -= 12;
        }
        if (midi < MIN_NOTE || midi > MAX_NOTE) return null;
        char key = keys.charAt(midi - MIN_NOTE);
        return new Stroke(keyCode(key), isShift(key));
    }

    public static final class Stroke {
        public final int code;
        public final boolean shift;
        public Stroke(int code, boolean shift) {
            this.code = code;
            this.shift = shift;
        }
    }

    public static boolean isShift(char c) {
        return (c >= 'A' && c <= 'Z') || "!@#$%^&*()".indexOf(c) >= 0;
    }

    public static int keyCode(char ch) {
        char c = Character.toLowerCase(ch);
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + c - 'a';
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + c - '0';
        int index = "!@#$%^&*()".indexOf(ch);
        if (index >= 0) return KeyEvent.KEYCODE_0 + ((index + 1) % 10);
        return KeyEvent.KEYCODE_UNKNOWN;
    }
}
