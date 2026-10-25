package dev.gaong.robloxmidi;

/**
 * Backoff/circuit breaker for Shizuku UserService retries.
 * Once exhausted, allow explicit user-initiated recovery to reset the gate.
 * Never restart the Shizuku manager silently or spin in a rapid bind loop.
 */
public final class ReconnectBackoff {
    private static final long[] DELAYS_MS = {750, 1500, 3000, 6000};
    private int failures;

    public synchronized long nextDelayMs() {
        if (failures >= DELAYS_MS.length) return -1;
        return DELAYS_MS[failures++];
    }

    public synchronized int attempts() { return failures; }
    public synchronized void reset() { failures = 0; }
}