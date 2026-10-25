package dev.gaong.robloxmidi;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import android.view.KeyEvent;
import static org.junit.Assert.*;

/** Fast local Linux UHID serialization tests; no Android hardware required. */
public class UhidProtocolTest {
    private static int u32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    private static int u16(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }
    @Test public void create2HasCorrectKernelLayout() {
        byte[] msg = UhidProtocol.create2("HuMIDI test");
        assertEquals(11, u32(msg, 0));
        assertEquals('H', msg[4]);
        assertEquals(0, msg[4 + 127]);
        assertEquals(UhidProtocol.descriptor().length, u16(msg, 4 + 256));
        assertEquals(3, u16(msg, 4 + 258)); // BUS_USB
        assertEquals(0x1234, u32(msg, 4 + 260));
        assertEquals(UhidProtocol.descriptor().length + 4 + 276, msg.length);
    }
    @Test public void input2ReportHasCorrectHeader() {
        byte[] report = UhidProtocol.keyboardReport(true, Arrays.asList(0x17, 0x1C));
        assertEquals(8, report.length);
        assertEquals(0x02, report[0]);
        assertEquals(0x17, report[2]);
        assertEquals(0x1C, report[3]);
        assertEquals(0, report[4]);
        byte[] message = UhidProtocol.input2(report);
        assertEquals(12, u32(message, 0));
        assertEquals(8, u16(message, 4));
        assertEquals(14, message.length);
        assertEquals(0x17, message[8]);
    }
    @Test public void zeroReportReleasesEverything() {
        byte[] message = UhidProtocol.input2(UhidProtocol.keyboardReport(false, Collections.emptyList()));
        for (int i=6; i<14; i++) assertEquals(0, message[i]);
        assertEquals(1, u32(UhidProtocol.destroy(), 0));
    }
    @Test public void mapsAndroidKeysToUsbHidUsages() {
        assertEquals(0x17, UhidProtocol.keyUsage(KeyEvent.KEYCODE_T));
        assertEquals(0x1E, UhidProtocol.keyUsage(KeyEvent.KEYCODE_1));
        assertEquals(0x27, UhidProtocol.keyUsage(KeyEvent.KEYCODE_0));
        assertEquals(0x2C, UhidProtocol.keyUsage(KeyEvent.KEYCODE_SPACE));
        assertEquals(0, UhidProtocol.keyUsage(KeyEvent.KEYCODE_ENTER));
    }
    @Test public void supportsBusProfiles() {
        byte[] usb = UhidProtocol.create2("USB keyboard", UhidProtocol.BUS_USB);
        byte[] virtual = UhidProtocol.create2("Virtual keyboard", UhidProtocol.BUS_VIRTUAL);
        assertEquals(3, u16(usb, 4 + 258));
        assertEquals(6, u16(virtual, 4 + 258));
        assertEquals(11, u32(usb, 0));
        assertEquals(11, u32(virtual, 0));
    }
    @Test public void rejectsUnknownBus() {
        try {
            UhidProtocol.create2("Invalid", 99);
            fail("Unexpected bus accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
    @Test public void rejectsOverflow() {
        try {
            UhidProtocol.keyboardReport(false, Arrays.asList(4,5,6,7,8,9,10));
            fail("Expected an overflow error");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
