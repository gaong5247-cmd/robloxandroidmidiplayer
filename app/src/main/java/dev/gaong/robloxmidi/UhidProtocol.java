package dev.gaong.robloxmidi;

import android.view.KeyEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Independently implemented Linux UHID event serializer.
 *
 * Layout comes from the published Linux uapi linux/uhid.h:
 * uhid_event.type is a native-endian u32, followed by a packed union.
 * Short UHID events are explicitly zero-padded by the Linux kernel.
 *
 * The HID report is a standard 8-byte boot-style keyboard report:
 * modifier bitmap, reserved, and six simultaneous key usages.
 */
public final class UhidProtocol {
    private UhidProtocol() {}

    public static final int UHID_DESTROY = 1;
    public static final int UHID_CREATE2 = 11;
    public static final int UHID_INPUT2 = 12;
    public static final int BOOT_KEY_SLOTS = 6;

    private static final byte[] REPORT_DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x06, (byte)0xA1, 0x01,
            0x05, 0x07, 0x19, (byte)0xE0, 0x29, (byte)0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte)0x95, 0x08, (byte)0x81, 0x02,
            (byte)0x95, 0x01, 0x75, 0x08, (byte)0x81, 0x03,
            (byte)0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte)0x81, 0x00,
            (byte)0xC0
    };

    public static byte[] descriptor() { return REPORT_DESCRIPTOR.clone(); }

    private static ByteBuffer event(int type, int payloadSize) {
        return ByteBuffer.allocate(4 + payloadSize)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(type);
    }

    /**
     * struct uhid_create2_req:
     * name[128], phys[64], uniq[64], rd_size:u16, bus:u16,
     * vendor/product/version/country:u32 each, rd_data[...] .
     * The header is exactly 276 bytes; the kernel zero-fills the rest.
     */
    public static byte[] create2(String deviceName) {
        byte[] name = deviceName.getBytes(StandardCharsets.UTF_8);
        if (name.length >= 128) throw new IllegalArgumentException("UHID name too long");
        ByteBuffer bb = event(UHID_CREATE2, 276 + REPORT_DESCRIPTOR.length);
        byte[] field = new byte[128];
        System.arraycopy(name, 0, field, 0, name.length);
        bb.put(field);
        bb.put(new byte[64]); // phys
        bb.put(new byte[64]); // uniq
        bb.putShort((short)REPORT_DESCRIPTOR.length);
        bb.putShort((short)0x03);   // BUS_USB; used as virtual USB HID device
        bb.putInt(0x1234);         // synthetic vendor ID, not a real device claim
        bb.putInt(0x484D);         // product "HM"
        bb.putInt(1);              // version
        bb.putInt(0);              // country
        bb.put(REPORT_DESCRIPTOR);
        return bb.array();
    }

    public static byte[] input2(byte[] report) {
        if (report == null || report.length != 8) {
            throw new IllegalArgumentException("Expected 8-byte keyboard report");
        }
        return event(UHID_INPUT2, 2 + report.length)
                .putShort((short)report.length).put(report).array();
    }

    public static byte[] destroy() {
        return event(UHID_DESTROY, 0).array();
    }

    public static byte[] keyboardReport(boolean shifted, Collection<Integer> usages) {
        if (usages.size() > BOOT_KEY_SLOTS)
            throw new IllegalArgumentException("Maximum 6 keys per virtual keyboard");
        byte[] r = new byte[8];
        if (shifted && !usages.isEmpty()) r[0] = 0x02; // Left Shift
        int offset = 2;
        for (int usage : usages) {
            if (usage <= 0 || usage > 0x65)
                throw new IllegalArgumentException("Invalid HID key usage " + usage);
            r[offset++] = (byte)usage;
        }
        return r;
    }

    /** Android KeyEvent code -> USB HID keyboard-page usage ID. */
    public static int keyUsage(int androidCode) {
        if (androidCode >= KeyEvent.KEYCODE_A && androidCode <= KeyEvent.KEYCODE_Z)
            return 0x04 + androidCode - KeyEvent.KEYCODE_A;
        if (androidCode >= KeyEvent.KEYCODE_1 && androidCode <= KeyEvent.KEYCODE_9)
            return 0x1E + androidCode - KeyEvent.KEYCODE_1;
        if (androidCode == KeyEvent.KEYCODE_0) return 0x27;
        if (androidCode == KeyEvent.KEYCODE_SPACE) return 0x2C;
        return 0;
    }
}
