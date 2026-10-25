package dev.gaong.robloxmidi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pure-Java model of USB HID keyboard rollover and modifier constraints.
 *
 * QWERTY piano games mostly react to new KeyDown events, so if one keyboard
 * runs out of slots or needs the opposite Shift modifier, a fresh chord gets
 * a new key-down after an explicit release report rather than stopping playback.
 *
 * The policy is intentionally independent of Android and /dev/uhid so it can
 * be regression-tested against the dense-chord crash.
 */
public final class UhidKeyState {
    private static final class Hold {
        final int token;
        final int usage;
        final boolean shift;
        final int device;
        int references = 1;

        Hold(int token, int usage, boolean shift, int device) {
            this.token = token;
            this.usage = usage;
            this.shift = shift;
            this.device = device;
        }
    }

    public static final class Change {
        public final int device;
        public final byte[] report;
        public final boolean releasePrevious;
        public final int reclaimedKeys;

        private Change(int device, byte[] report, boolean releasePrevious, int reclaimedKeys) {
            this.device = device;
            this.report = report;
            this.releasePrevious = releasePrevious;
            this.reclaimedKeys = reclaimedKeys;
        }

        public boolean shouldWrite() { return device >= 0; }
        static Change none() { return new Change(-1, null, false, 0); }
    }

    private final List<LinkedHashMap<Integer, Hold>> devices = new ArrayList<>();
    private final Map<Integer, Hold> held = new HashMap<>();
    private long recoveries;
    private long reclaimedKeys;
    private long keyDowns;
    private long ignoredReleases;

    public UhidKeyState(int deviceCount) {
        if (deviceCount < 1 || deviceCount > 8)
            throw new IllegalArgumentException("Invalid virtual keyboard count: " + deviceCount);
        for (int i = 0; i < deviceCount; i++) devices.add(new LinkedHashMap<>());
    }

    public synchronized int activeCount() { return held.size(); }
    public synchronized int deviceCount() { return devices.size(); }
    public synchronized long recoveries() { return recoveries; }
    public synchronized long reclaimedKeys() { return reclaimedKeys; }
    public synchronized long keyDowns() { return keyDowns; }
    public synchronized long ignoredReleases() { return ignoredReleases; }

    private static int token(int usage, boolean shift) {
        return (usage << 1) | (shift ? 1 : 0);
    }

    private static byte[] report(LinkedHashMap<Integer, Hold> keys) {
        ArrayList<Integer> usages = new ArrayList<>(keys.size());
        boolean shift = false;
        for (Hold h : keys.values()) {
            shift = h.shift;
            usages.add(h.usage);
        }
        return UhidProtocol.keyboardReport(shift, usages);
    }

    /**
     * Returns an HID report transition; "releasePrevious" explicitly sends
     * the all-zero report before any incompatible chord or rollover.
     * No ordinary dense chord causes a fatal false return.
     */
    public synchronized Change change(int usage, boolean down, boolean shift) {
        if (usage <= 0 || usage > 0x65)
            throw new IllegalArgumentException("Unsupported HID usage: " + usage);

        int key = token(usage, shift);
        Hold active = held.get(key);

        if (!down) {
            if (active == null) {
                ignoredReleases++;
                return Change.none();
            }
            if (--active.references > 0) return Change.none();
            held.remove(key);
            LinkedHashMap<Integer, Hold> keyboard = devices.get(active.device);
            keyboard.remove(key);
            return new Change(active.device, report(keyboard), false, 0);
        }

        keyDowns++;
        if (active != null) {
            active.references++;
            return Change.none();
        }

        int chosen = -1;
        int bestFill = -1;
        for (int i = 0; i < devices.size(); i++) {
            LinkedHashMap<Integer, Hold> keyboard = devices.get(i);
            if (keyboard.size() >= UhidProtocol.BOOT_KEY_SLOTS) continue;
            if (!keyboard.isEmpty() &&
                    keyboard.values().iterator().next().shift != shift) continue;

            boolean sameUsage = false;
            for (Hold candidate : keyboard.values()) {
                if (candidate.usage == usage) {
                    sameUsage = true;
                    break;
                }
            }
            if (sameUsage) continue;
            if (keyboard.size() > bestFill) {
                chosen = i;
                bestFill = keyboard.size();
            }
        }

        boolean release = false;
        int reclaimed = 0;
        if (chosen < 0) {
            // All keyboards are occupied or require a conflicting modifier.
            // Reclaim the device with the fewest held keys, minimizing loss.
            chosen = 0;
            for (int i = 1; i < devices.size(); i++) {
                if (devices.get(i).size() < devices.get(chosen).size()) chosen = i;
            }
            LinkedHashMap<Integer, Hold> victim = devices.get(chosen);
            reclaimed = victim.size();
            for (Hold lost : victim.values()) held.remove(lost.token);
            victim.clear();
            recoveries++;
            reclaimedKeys += reclaimed;
            release = true;
        }

        LinkedHashMap<Integer, Hold> target = devices.get(chosen);
        Hold fresh = new Hold(key, usage, shift, chosen);
        target.put(key, fresh);
        held.put(key, fresh);
        return new Change(chosen, report(target), release, reclaimed);
    }

    public synchronized void reset() {
        held.clear();
        for (LinkedHashMap<Integer, Hold> keyboard : devices) keyboard.clear();
    }
}
