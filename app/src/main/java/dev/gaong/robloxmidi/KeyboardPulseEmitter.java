package dev.gaong.robloxmidi;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Send independent short QWERTY presses instead of holding each MIDI note.
 * Fast songs may contain hundreds of notes per second; releases are scheduled
 * on one executor and never block the MIDI scheduler thread.
 *
 * Repeated notes reliably retrigger after releasing the previous key.
 */
public final class KeyboardPulseEmitter {
    private final InputBridge bridge;
    private final ScheduledThreadPoolExecutor releases;
    private final Object guard = new Object();
    private final Map<Integer, ScheduledFuture<?>> pending = new HashMap<>();
    private final Map<Integer, Long> generations = new HashMap<>();
    private long sequence;
    private volatile long skippedTaps;
    private volatile long releaseFailures;
    private volatile String releaseError = "";

    public long skippedTaps() { return skippedTaps; }
    public long releaseFailures() { return releaseFailures; }
    public String releaseError() { return releaseError; }

    public KeyboardPulseEmitter(InputBridge bridge) {
        this.bridge = bridge;
        releases = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "midi-keyup");
            thread.setDaemon(true);
            return thread;
        });
        releases.setRemoveOnCancelPolicy(true);
    }

    public boolean tap(int keyCode, boolean shift, int holdMs) throws Exception {
        int token = (keyCode << 1) | (shift ? 1 : 0);
        synchronized (guard) {
            ScheduledFuture<?> old = pending.remove(token);
            if (old != null) {
                old.cancel(false);
                generations.remove(token);
                bridge.trySend(keyCode, false, shift);
                // A real KeyUp report must be observed before repeated KeyDown.
                Thread.sleep(4);
            }
            if (!bridge.trySend(keyCode, true, shift)) {
                skippedTaps++;
                return false;
            }
            final long generation = ++sequence;
            generations.put(token, generation);
            ScheduledFuture<?> future = releases.schedule(() -> {
                synchronized (guard) {
                    Long live = generations.get(token);
                    if (live == null || live != generation) return;
                    try {
                        if (!bridge.trySend(keyCode, false, shift)) {
                            releaseFailures++;
                            releaseError = "Temporary release overflow";
                        }
                    } catch (Exception failure) {
                        releaseFailures++;
                        releaseError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                        bridge.allUp();
                    } finally {
                        pending.remove(token);
                        generations.remove(token);
                    }
                }
            }, Math.max(20, Math.min(150, holdMs)), TimeUnit.MILLISECONDS);
            pending.put(token, future);
            return true;
        }
    }

    /** Prevent stuck keys on pause, seek, stop, backend failure or new song. */
    public void clear() {
        synchronized (guard) {
            for (ScheduledFuture<?> task : pending.values()) task.cancel(false);
            pending.clear();
            generations.clear();
            bridge.allUp();
        }
    }
}
