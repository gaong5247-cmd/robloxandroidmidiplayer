package dev.gaong.robloxmidi;
import org.junit.Test;
import static org.junit.Assert.*;
public class PianoLayoutTest {
    @Test public void has61Notes() {
        assertEquals(61, PianoLayout.DEFAULT.length());
    }
    @Test public void foldsOctaves() {
        PianoLayout p = new PianoLayout(PianoLayout.DEFAULT);
        assertNotNull(p.forNote(108, true));
        assertNull(p.forNote(108, false));
    }
    @Test public void mappedC4IsT() {
        PianoLayout p = new PianoLayout(PianoLayout.DEFAULT);
        assertEquals(PianoLayout.keyCode('t'), p.forNote(60,false).code);
        assertFalse(p.forNote(60,false).shift);
        assertTrue(p.forNote(61,false).shift);
    }
}
