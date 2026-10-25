package dev.gaong.robloxmidi;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.os.SystemClock;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real virtual HID keyboard devices via /dev/uhid, running ONLY inside a
 * Shizuku shell/root UserService (never as a normal Android app).
 *
 * One virtual keyboard is the compatibility-first default, matching tested
 * RobloxAndroidMidi's QWERTY mode. Four are optional for chords.
 * Multiple keyboards may not work in all Roblox/Android combinations.
 */
public final class UhidKeyboard implements AutoCloseable {
    public static final String NAME_PREFIX = "HuMIDI UHID Keyboard ";
    public static final int DEVICE_COUNT = 4;
    public static final int DEFAULT_DEVICE_COUNT = 1;

    private static final class KeyHold {
        final int usage;
        final boolean shift;
        final int device;
        int references = 1;
        KeyHold(int usage, boolean shift, int device) {
            this.usage = usage;
            this.shift = shift;
            this.device = device;
        }
    }

    private static final class KeyboardDevice {
        final FileDescriptor descriptor;
        final LinkedHashMap<Integer, KeyHold> keys = new LinkedHashMap<>();
        KeyboardDevice(FileDescriptor fd) { descriptor = fd; }
    }

    private final List<KeyboardDevice> devices = new ArrayList<>();
    private final Map<Integer, KeyHold> active = new HashMap<>();
    private boolean closed;

    public UhidKeyboard() throws IOException {
        this(DEFAULT_DEVICE_COUNT, UhidProtocol.BUS_USB);
    }

    public UhidKeyboard(int deviceCount, int bus) throws IOException {
        if (deviceCount != 1 && deviceCount != DEVICE_COUNT) {
            throw new IllegalArgumentException("Choose 1 or 4 keyboards");
        }
        if (bus != UhidProtocol.BUS_USB && bus != UhidProtocol.BUS_VIRTUAL) {
            throw new IllegalArgumentException("Choose USB or VIRTUAL HID bus");
        }
        try {
            for (int index = 0; index < deviceCount; index++) {
                FileDescriptor fd;
                try {
                    fd = Os.open("/dev/uhid",
                            OsConstants.O_RDWR | OsConstants.O_CLOEXEC,
                            0);
                } catch (ErrnoException e) {
                    throw new IOException("Cannot open /dev/uhid: " + e.getMessage(), e);
                }
                KeyboardDevice device = new KeyboardDevice(fd);
                devices.add(device);
                write(device, UhidProtocol.create2(NAME_PREFIX + (index + 1), bus));
            }
            // Android InputReader device enumeration is asynchronous.
            // Delay availability until the kernel has had time to register devices.
            SystemClock.sleep(800);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private static void write(KeyboardDevice device, byte[] event) throws IOException {
        try {
            int sent = Os.write(device.descriptor, event, 0, event.length);
            if (sent != event.length)
                throw new IOException("Partial UHID event: " + sent + "/" + event.length);
        } catch (ErrnoException e) {
            throw new IOException("UHID write failed: " + e.getMessage(), e);
        }
    }

    public synchronized int activeCount() { return active.size(); }
    public synchronized int deviceCount() { return closed ? 0 : devices.size(); }

    /**
     * @return true if a report was sent or an existing stroke ref-counted,
     *         false if all keyboard report slots are occupied.
     */
    public synchronized boolean send(int keyCode, boolean down, boolean shift) throws IOException {
        if (closed) throw new IOException("Virtual keyboards already closed");
        int usage = UhidProtocol.keyUsage(keyCode);
        if (usage == 0) throw new IOException("Unsupported KeyEvent: " + keyCode);
        int stroke = (usage << 1) | (shift ? 1 : 0);
        KeyHold existing = active.get(stroke);

        if (down) {
            if (existing != null) {
                existing.references++;
                return true;
            }
            KeyboardDevice best = null;
            int bestIndex = -1;
            int highestFill = -1;
            for (int i = 0; i < devices.size(); i++) {
                KeyboardDevice device = devices.get(i);
                if (device.keys.size() >= UhidProtocol.BOOT_KEY_SLOTS) continue;
                if (!device.keys.isEmpty()) {
                    // Shift is global within a keyboard HID report, so keep
                    // shifted and unshifted notes on distinct virtual devices.
                    KeyHold first = device.keys.values().iterator().next();
                    if (first.shift != shift) continue;
                }
                boolean collision = false;
                for (KeyHold key : device.keys.values()) {
                    if (key.usage == usage) { collision = true; break; }
                }
                if (collision) continue;
                // Pack existing compatible devices to keep others available.
                if (device.keys.size() > highestFill) {
                    best = device;
                    bestIndex = i;
                    highestFill = device.keys.size();
                }
            }
            if (best == null) return false;

            KeyHold hold = new KeyHold(usage, shift, bestIndex);
            best.keys.put(stroke, hold);
            try {
                report(best);
                active.put(stroke, hold);
            } catch (IOException err) {
                best.keys.remove(stroke);
                throw err;
            }
            return true;
        }

        if (existing == null) return true;
        if (--existing.references > 0) return true;
        active.remove(stroke);
        KeyboardDevice device = devices.get(existing.device);
        device.keys.remove(stroke);
        report(device);
        return true;
    }

    private static void report(KeyboardDevice device) throws IOException {
        List<Integer> usages = new ArrayList<>(device.keys.size());
        boolean shift = false;
        for (KeyHold key : device.keys.values()) {
            usages.add(key.usage);
            shift = key.shift;
        }
        write(device, UhidProtocol.input2(UhidProtocol.keyboardReport(shift, usages)));
    }

    /** Emergency release on stop, pause, disconnect or a rejected event. */
    public synchronized void releaseAll() {
        active.clear();
        for (KeyboardDevice device : devices) {
            device.keys.clear();
            try {
                write(device, UhidProtocol.input2(new byte[8]));
            } catch (IOException ignored) { }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        releaseAll();
        closed = true;
        for (KeyboardDevice device : devices) {
            try { write(device, UhidProtocol.destroy()); }
            catch (IOException ignored) { }
            try { Os.close(device.descriptor); }
            catch (ErrnoException ignored) { }
        }
        devices.clear();
    }
}
