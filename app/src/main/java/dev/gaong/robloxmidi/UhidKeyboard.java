package dev.gaong.robloxmidi;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shizuku UserService-backed Linux UHID keyboard.
 *
 * Keeps the v0.3 single USB-HID keyboard by default. Unlike v0.3, the
 * driver never rejects a dense chord merely because a 6-key report is full
 * or the next note has a different Shift state. Instead, it releases the
 * smallest conflicting chord and emits a fresh report with a short boundary.
 * This preserves playback at the cost of briefly shortening held notes.
 */
public final class UhidKeyboard implements AutoCloseable {
    public static final String NAME_PREFIX = "HuMIDI UHID Keyboard ";
    public static final int DEVICE_COUNT = 4;
    public static final int DEFAULT_DEVICE_COUNT = 1;

    private final List<FileDescriptor> devices = new ArrayList<>();
    private final UhidKeyState state;
    private boolean closed;
    private volatile String lastError = "";

    public UhidKeyboard() throws IOException {
        this(DEFAULT_DEVICE_COUNT, UhidProtocol.BUS_USB);
    }

    public UhidKeyboard(int deviceCount, int bus) throws IOException {
        if (deviceCount != 1 && deviceCount != DEVICE_COUNT)
            throw new IllegalArgumentException("Choose 1 or 4 keyboards");
        if (bus != UhidProtocol.BUS_USB && bus != UhidProtocol.BUS_VIRTUAL)
            throw new IllegalArgumentException("Choose USB or VIRTUAL HID bus");

        state = new UhidKeyState(deviceCount);
        try {
            for (int i = 0; i < deviceCount; i++) {
                FileDescriptor fd;
                try {
                    fd = Os.open("/dev/uhid",
                            OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
                } catch (ErrnoException e) {
                    throw new IOException("Cannot open /dev/uhid: " + e.getMessage(), e);
                }
                devices.add(fd);
                write(fd, UhidProtocol.create2(NAME_PREFIX + (i + 1), bus));
            }
            SystemClock.sleep(800);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private static void write(FileDescriptor fd, byte[] event) throws IOException {
        try {
            int sent = Os.write(fd, event, 0, event.length);
            if (sent != event.length)
                throw new IOException("Partial UHID event: " + sent + "/" + event.length);
        } catch (ErrnoException e) {
            throw new IOException("UHID write failed: " + e.getMessage(), e);
        }
    }

    public synchronized boolean send(int keyCode, boolean down, boolean shift) throws IOException {
        if (closed) throw new IOException("UHID keyboard is closed");
        int usage = UhidProtocol.keyUsage(keyCode);
        if (usage == 0) throw new IOException("Unsupported Android key: " + keyCode);

        UhidKeyState.Change next = state.change(usage, down, shift);
        if (!next.shouldWrite()) return true;

        FileDescriptor fd = devices.get(next.device);
        try {
            if (next.releasePrevious) {
                // A previous KeyDown needs time to reach Android InputReader
                // before the next incompatible modifier or 7th rollover key.
                SystemClock.sleep(4);
                write(fd, UhidProtocol.input2(new byte[8]));
                SystemClock.sleep(4);
            }
            write(fd, UhidProtocol.input2(next.report));
            return true;
        } catch (IOException problem) {
            lastError = problem.toString();
            // The software key state no longer matches kernel state.
            releaseAll();
            throw problem;
        }
    }

    public synchronized int activeCount() { return state.activeCount(); }
    public synchronized int deviceCount() { return closed ? 0 : devices.size(); }
    public synchronized String stats() {
        return "held=" + state.activeCount()
                + " downs=" + state.keyDowns()
                + " recoveries=" + state.recoveries()
                + " preempted=" + state.reclaimedKeys()
                + " lateUps=" + state.ignoredReleases()
                + (lastError.isEmpty() ? "" : " error=" + lastError);
    }

    /** Emergency release for pause/stop/disconnect or an actual UHID write error. */
    public synchronized void releaseAll() {
        state.reset();
        for (FileDescriptor fd : devices) {
            try { write(fd, UhidProtocol.input2(new byte[8])); }
            catch (IOException ignored) { lastError = ignored.toString(); }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        releaseAll();
        closed = true;
        for (FileDescriptor fd : devices) {
            try { write(fd, UhidProtocol.destroy()); }
            catch (IOException ignored) { }
            try { Os.close(fd); }
            catch (ErrnoException ignored) { }
        }
        devices.clear();
    }
}
