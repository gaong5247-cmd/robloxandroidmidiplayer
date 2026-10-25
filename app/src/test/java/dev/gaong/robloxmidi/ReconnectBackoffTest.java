package dev.gaong.robloxmidi;

import org.junit.Test;
import static org.junit.Assert.*;

/** Avoid tight reconnect loops when the server or Android input stack is down. */
public class ReconnectBackoffTest {
    @Test public void delaysGrowAndThenCap() {
        ReconnectBackoff b = new ReconnectBackoff();
        assertEquals(750L, b.nextDelayMs());
        assertEquals(1500L, b.nextDelayMs());
        assertEquals(3000L, b.nextDelayMs());
        assertEquals(6000L, b.nextDelayMs());
        assertEquals(-1L, b.nextDelayMs());
        assertEquals(4, b.attempts());
    }
    @Test public void healthyDeviceResetsAttempts() {
        ReconnectBackoff b = new ReconnectBackoff();
        b.nextDelayMs();
        b.nextDelayMs();
        b.reset();
        assertEquals(0, b.attempts());
        assertEquals(750L, b.nextDelayMs());
    }
    @Test public void stopAtAttemptLimit() {
        ReconnectBackoff b = new ReconnectBackoff();
        for (int i=0; i<4; i++) assertTrue(b.nextDelayMs() > 0);
        for (int i=0; i<7; i++) assertEquals(-1L, b.nextDelayMs());
    }
}