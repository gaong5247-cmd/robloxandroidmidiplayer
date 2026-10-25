package dev.gaong.robloxmidi;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class MidiParserTest {
    private static byte[] midi(byte[] events) {
        byte[] pre = { 'M','T','h','d',0,0,0,6,0,0,0,1,1,(byte)0xE0,
                'M','T','r','k',0,0,0,(byte)events.length };
        byte[] out = new byte[pre.length + events.length];
        System.arraycopy(pre,0,out,0,pre.length);
        System.arraycopy(events,0,out,pre.length,events.length);
        return out;
    }
    @Test public void parsesQuarterNote() throws Exception {
        byte[] data = midi(new byte[]{
                0,(byte)0x90,60,100,
                (byte)0x83,0x60,(byte)0x80,60,0,
                0,(byte)0xFF,0x2F,0 });
        MidiParser.Song song = MidiParser.parse(data);
        assertEquals(1, song.noteCount);
        assertEquals(2, song.events.size());
        assertTrue(song.events.get(0).down);
        assertFalse(song.events.get(1).down);
        assertEquals(500_000L, song.events.get(1).us);
    }
    @Test public void supportsTempoAndRunningStatus() throws Exception {
        byte[] data = midi(new byte[]{
                0,(byte)0xFF,0x51,3,0x03,(byte)0xD0,(byte)0x90,
                0,(byte)0x90,60,100,
                (byte)0x83,0x60,60,0,
                0,(byte)0xFF,0x2F,0 });
        MidiParser.Song song = MidiParser.parse(data);
        assertEquals(250_000L, song.durationUs);
        assertEquals(1, song.noteCount);
    }
    @Test public void rejectsInvalidHeader() throws Exception {
        try { MidiParser.parse(new byte[]{1,2,3}); fail("expected IOException"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
