package dev.gaong.robloxmidi;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Regression: playback previously died at the first mixed-shift chord or 7th
 * simultaneously pressed key because UhidKeyboard returned false.
 */
public class UhidKeyStateTest {
    @Test public void firstNormalDownProducesKeyboardReport() {
        UhidKeyState state = new UhidKeyState(1);
        UhidKeyState.Change first = state.change(0x17, true, false);
        assertTrue(first.shouldWrite());
        assertFalse(first.releasePrevious);
        assertEquals(0x17, first.report[2] & 0xff);
        assertEquals(1, state.activeCount());
    }

    @Test public void mixedShiftChordDoesNotAbortPlayback() {
        UhidKeyState state = new UhidKeyState(1);
        assertTrue(state.change(0x17, true, false).shouldWrite());
        UhidKeyState.Change sharp = state.change(0x1C, true, true);
        assertTrue(sharp.shouldWrite());
        assertTrue("Must issue key-up before incompatible Shift", sharp.releasePrevious);
        assertEquals(1, sharp.reclaimedKeys);
        assertEquals(0x02, sharp.report[0] & 0xff);
        assertEquals(0x1C, sharp.report[2] & 0xff);
        assertEquals(1, state.activeCount());
        assertFalse("Preempted notes' eventual releases are harmless",
                state.change(0x17, false, false).shouldWrite());
        assertEquals(1, state.activeCount());
        assertEquals(1, state.recoveries());
    }

    @Test public void sevenNoteChordRecoversInsteadOfRefusing() {
        UhidKeyState state = new UhidKeyState(1);
        for (int i = 0; i < 6; i++) {
            UhidKeyState.Change next = state.change(0x04 + i, true, false);
            assertTrue(next.shouldWrite());
            assertFalse(next.releasePrevious);
        }
        UhidKeyState.Change seventh = state.change(0x0A, true, false);
        assertTrue(seventh.shouldWrite());
        assertTrue(seventh.releasePrevious);
        assertEquals(6, seventh.reclaimedKeys);
        assertEquals(1, state.activeCount());
        assertEquals(1, state.recoveries());
        assertEquals(0x0A, seventh.report[2] & 0xff);
    }

    @Test public void duplicateNotesReferenceCounted() {
        UhidKeyState state = new UhidKeyState(1);
        assertTrue(state.change(0x17, true, false).shouldWrite());
        assertFalse(state.change(0x17, true, false).shouldWrite());
        assertFalse(state.change(0x17, false, false).shouldWrite());
        assertEquals(1, state.activeCount());
        assertTrue(state.change(0x17, false, false).shouldWrite());
        assertEquals(0, state.activeCount());
    }

    @Test public void fourKeyboardsKeepModifierGroupsSeparate() {
        UhidKeyState state = new UhidKeyState(4);
        assertTrue(state.change(0x17, true, false).shouldWrite());
        UhidKeyState.Change sharp = state.change(0x1C, true, true);
        assertEquals(1, sharp.device);
        assertFalse(sharp.releasePrevious);
        assertEquals(2, state.activeCount());
    }

    @Test public void overrunAtSecondSevenDoesNotThrow() {
        // Synthetic 7s "song": simple single notes, then sudden thick chord.
        UhidKeyState state = new UhidKeyState(1);
        for (int t = 0; t < 7; t++) {
            int key = 0x17 + (t % 3);
            assertTrue(state.change(key, true, false).shouldWrite());
            assertTrue(state.change(key, false, false).shouldWrite());
        }
        for (int note = 0; note < 11; note++) {
            // Alternating shift/normal and more than six usages.
            UhidKeyState.Change change =
                    state.change(0x04 + note, true, note % 2 == 0);
            assertTrue("Should never reject note at 7s boundary", change.shouldWrite());
        }
        assertTrue(state.recoveries() > 0);
        assertEquals(11 + 7, state.keyDowns());
    }

    @Test public void sustainedDenseMidiSequenceKeepsAcceptingNotes() {
        UhidKeyState state = new UhidKeyState(1);
        // Represents repeated mixed black/white notes, chords and overlap
        // over a long MIDI excerpt, including more than 6 held usages.
        for (int i = 0; i < 6_000; i++) {
            int usage = 4 + (i % 26);
            boolean shift = (i % 5) == 0;
            UhidKeyState.Change pressed = state.change(usage, true, shift);
            assertTrue("Every attempted press must produce a report or be ref-counted",
                    pressed.shouldWrite());
            assertTrue(state.activeCount() <= 6);
            if ((i % 4) == 0) {
                state.change(usage, false, shift);
            }
        }
        assertTrue(state.recoveries() > 0);
        assertEquals(6_000L, state.keyDowns());
        assertTrue(state.activeCount() <= 6);
    }

    @Test public void resetClearsAllKeysWithoutLosingCounters() {
        UhidKeyState state = new UhidKeyState(1);
        state.change(0x17, true, false);
        state.reset();
        assertEquals(0, state.activeCount());
        assertEquals(1, state.keyDowns());
        assertFalse(state.change(0x17, false, false).shouldWrite());
    }
}
