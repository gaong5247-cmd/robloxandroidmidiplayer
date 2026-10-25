package dev.gaong.robloxmidi;

import android.content.Context;

/**
 * Runs in a Shizuku UserService process with shell/root privileges.
 * Uses /dev/uhid devices, never InputManager.injectInputEvent.
 *
 * QWERTY keyboard profile can be reset/re-enumerated without restarting Shizuku.
 */
public final class InjectUserService extends IKeyboardInjector.Stub {
    private UhidKeyboard keyboards;
    private String state = "UHID_STARTING";

    public InjectUserService() {
        // No device at construction: the app will configure exactly once after
        // the Binder connection has been established. The old implementation
        // created a device here, then immediately destroyed it and re-created
        // it on first bind, causing unnecessary Android device churn.
        state = "UHID_PENDING: waiting for keyboard profile";
    }

    public InjectUserService(Context context) {
        this();
    }

    @Override public synchronized boolean configureKeyboard(int count, int bus) {
        boolean replacing = keyboards != null;
        if (replacing) {
            keyboards.close();
            keyboards = null;
            // Device removal is asynchronous in Android InputReader. Avoid
            // immediately re-creating an identically named HID on the same tick.
            android.os.SystemClock.sleep(250);
        }
        state = "UHID_STARTING";
        try {
            keyboards = new UhidKeyboard(count, bus);
            state = "UHID_READY: " + keyboards.deviceCount() +
                    " keyboard(s), bus=" + bus + ", pid=" + android.os.Process.myPid();
            return true;
        } catch (Exception exception) {
            state = "UHID_FAILED: " + exception.getClass().getSimpleName() + ": "
                    + exception.getMessage();
            return false;
        }
    }

    @Override public synchronized String getBackendStatus() {
        return state + (keyboards == null ? "" : " | " + keyboards.stats());
    }

    @Override public synchronized boolean sendKey(int keyCode, boolean down, boolean shift) {
        if (keyboards == null) return false;
        try {
            boolean sent = keyboards.send(keyCode, down, shift);
            if (!sent) {
                state = "UHID_READY: no free key slots";
            }
            return sent;
        } catch (Exception exception) {
            state = "UHID_FAILED: " + exception.getMessage();
            keyboards.releaseAll();
            return false;
        }
    }

    @Override public synchronized void releaseAll() {
        if (keyboards != null) keyboards.releaseAll();
    }

    @Override public synchronized void destroy() {
        if (keyboards != null) {
            keyboards.close();
            keyboards = null;
        }
        System.exit(0);
    }
}
