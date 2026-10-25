package dev.gaong.robloxmidi;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Launched by Shizuku with shell/root identity, NOT as a normal app Service.
 * Injects distinct down/up events via privileged InputManager.
 *
 * Roblox may reject synthetic key events, even if Android accepts them.
 * The app provides an in-game test before any compatibility claims.
 */
public class InjectUserService extends IKeyboardInjector.Stub {
    private final Object manager;
    private final Method inject;
    private final Map<Integer, Long> downTimes = new HashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();

    public InjectUserService() throws Exception {
        Object instance = null;
        Method method = null;
        // API 34+ moved implementation details to InputManagerGlobal.
        for (String className : new String[]{
                "android.hardware.input.InputManagerGlobal",
                "android.hardware.input.InputManager"}) {
            try {
                Class<?> type = Class.forName(className);
                Method getInstance = type.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object obj = getInstance.invoke(null);
                Method m = type.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
                m.setAccessible(true);
                instance = obj;
                method = m;
                break;
            } catch (ReflectiveOperationException ignored) {
                // Try the other Android framework entry point.
            }
        }
        if (instance == null || method == null) {
            throw new IllegalStateException("Android InputManager injection API unavailable");
        }
        manager = instance;
        inject = method;
    }

    public InjectUserService(Context context) throws Exception {
        this();
    }

    private boolean emit(int keyCode, int action, int meta, long downTime) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                downTime, now, action, keyCode, 0, meta,
                -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        try {
            return Boolean.TRUE.equals(inject.invoke(manager, event, 0));
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public synchronized boolean sendKey(int keyCode, boolean down, boolean shift) {
        if (keyCode < KeyEvent.KEYCODE_0 || keyCode > KeyEvent.KEYCODE_Z) {
            if (keyCode != KeyEvent.KEYCODE_SPACE) return false;
        }
        long now = SystemClock.uptimeMillis();
        int previous = counts.containsKey(keyCode) ? counts.get(keyCode) : 0;
        if (down) {
            counts.put(keyCode, previous + 1);
            if (previous > 0) return true; // The same physical key cannot be held twice.
            downTimes.put(keyCode, now);
            if (shift) {
                boolean a = emit(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN,
                        KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON, now);
                boolean b = emit(keyCode, KeyEvent.ACTION_DOWN,
                        KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON, now);
                boolean c = emit(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, 0, now);
                return a && b && c;
            }
            return emit(keyCode, KeyEvent.ACTION_DOWN, 0, now);
        }
        if (previous <= 0) return true;
        if (previous > 1) {
            counts.put(keyCode, previous - 1);
            return true;
        }
        counts.remove(keyCode);
        Long started = downTimes.remove(keyCode);
        return emit(keyCode, KeyEvent.ACTION_UP, 0, started == null ? now : started);
    }

    @Override
    public synchronized void releaseAll() {
        for (Map.Entry<Integer, Long> entry : downTimes.entrySet()) {
            emit(entry.getKey(), KeyEvent.ACTION_UP, 0, entry.getValue());
        }
        counts.clear();
        downTimes.clear();
        emit(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, 0, SystemClock.uptimeMillis());
        emit(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP, 0, SystemClock.uptimeMillis());
    }

    @Override
    public void destroy() {
        releaseAll();
        System.exit(0);
    }
}
