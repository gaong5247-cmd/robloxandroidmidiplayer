package dev.gaong.robloxmidi;

import android.content.Context;

/**
 * Shizuku UserService running with shell/root privileges.
 * Old InputManager.injectInputEvent() is deliberately NOT used: Roblox can
 * accept it in a text field while ignoring it for game keyboard events.
 *
 * The Service creates real kernel UHID devices and submits HID reports,
 * rather than guessing a deviceId on synthetic Android KeyEvents.
 */
public final class InjectUserService extends IKeyboardInjector.Stub {
    private UhidKeyboard keyboards;
    private String state;

    public InjectUserService() {
        try {
            keyboards = new UhidKeyboard();
            state = "UHID_READY: " + keyboards.deviceCount() + " keyboards created";
        } catch (Exception e) {
            state = "UHID_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public InjectUserService(Context context) {
        this();
    }

    @Override public synchronized String getBackendStatus() {
        if (keyboards == null) return state;
        return state + " | held=" + keyboards.activeCount();
    }

    @Override public synchronized boolean sendKey(int keyCode, boolean down, boolean shift) {
        if (keyboards == null) return false;
        try {
            boolean sent = keyboards.send(keyCode, down, shift);
            if (!sent) state = "UHID_READY: no free key slots (polyphony limit)";
            return sent;
        } catch (Exception e) {
            state = "UHID_FAILED: " + e.getMessage();
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
