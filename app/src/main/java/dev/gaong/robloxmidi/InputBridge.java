package dev.gaong.robloxmidi;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Shizuku UserService wrapper with fail-closed configuration and input
 * diagnostics. Kernel UHID registration != Roblox gameplay recognition.
 */
public final class InputBridge {
    private static InputBridge instance;
    public static synchronized InputBridge get(Context context) {
        if (instance == null) instance = new InputBridge(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService configuration = Executors.newSingleThreadExecutor(r -> {
        Thread worker = new Thread(r, "uhid-config");
        worker.setDaemon(true);
        return worker;
    });
    private volatile IKeyboardInjector remote;
    private volatile String status = "Shizuku 연결 필요";
    private volatile boolean backendReady;
    private volatile int requestedCount;
    private volatile int requestedBus;
    private volatile boolean bound;
    private final Shizuku.UserServiceArgs args;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IKeyboardInjector.Stub.asInterface(binder);
            status = "UHID 장치 준비 중";
            applyProfileAsync();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            bound = false;
            backendReady = false;
            status = "키보드 연결 끊김";
            PlayerController.get(context).stop();
        }
    };

    private InputBridge(Context app) {
        context = app;
        android.content.SharedPreferences p = app.getSharedPreferences("piano", Context.MODE_PRIVATE);
        requestedCount = p.getInt("keyboard_count", 1);
        if (requestedCount != 4) requestedCount = 1;
        requestedBus = p.getInt("keyboard_bus", UhidProtocol.BUS_USB);
        if (requestedBus != UhidProtocol.BUS_VIRTUAL) requestedBus = UhidProtocol.BUS_USB;

        args = new Shizuku.UserServiceArgs(new ComponentName(
                app.getPackageName(), InjectUserService.class.getName()))
                .daemon(false).processNameSuffix("uhid-keyboard")
                .tag("roblox-midi-keyboard").version(3);

        try {
            Shizuku.addBinderReceivedListenerSticky(this::maybeBind);
            Shizuku.addBinderDeadListener(() -> {
                remote = null;
                bound = false;
                backendReady = false;
                status = "Shizuku 종료됨";
                PlayerController.get(context).stop();
            });
            Shizuku.addRequestPermissionResultListener((request, grant) -> {
                if (request == 813 && grant == PackageManager.PERMISSION_GRANTED) maybeBind();
                else if (request == 813) status = "Shizuku 권한 거부됨";
            });
        } catch (Throwable e) {
            status = "Shizuku 초기화 실패: " + e.getClass().getSimpleName();
        }
    }

    public int keyboardCount() { return requestedCount; }
    public int keyboardBus() { return requestedBus; }

    /**
     * Re-create virtual input devices. Call before starting/restarting Roblox.
     * Values are persisted, and the Android game must be re-opened to pick up
     * initial keyboard capability in experiences that check only on start.
     */
    public void setProfile(int count, int bus) {
        if (count != 1 && count != 4) throw new IllegalArgumentException("1 or 4 devices");
        if (bus != UhidProtocol.BUS_USB && bus != UhidProtocol.BUS_VIRTUAL) {
            throw new IllegalArgumentException("USB or VIRTUAL bus");
        }
        PlayerController.get(context).stop();
        requestedCount = count;
        requestedBus = bus;
        context.getSharedPreferences("piano", Context.MODE_PRIVATE).edit()
                .putInt("keyboard_count", count).putInt("keyboard_bus", bus).apply();
        status = "가상 키보드 재연결 중...";
        backendReady = false;
        applyProfileAsync();
    }

    private void applyProfileAsync() {
        configuration.execute(() -> {
            IKeyboardInjector target = remote;
            if (target == null) {
                status = "Shizuku 연결 대기 중";
                return;
            }
            try {
                // The UserService constructor registers a single USB keyboard,
                // so no costly reset is needed when using the recommended profile.
                boolean okay = (requestedCount == 1 && requestedBus == UhidProtocol.BUS_USB)
                        || target.configureKeyboard(requestedCount, requestedBus);
                String report = target.getBackendStatus();
                if (remote != target) return;
                backendReady = okay && report.startsWith("UHID_READY:");
                status = backendReady
                        ? "UHID 준비됨 · USB 키보드 입력 (QWERTY)"
                        : "UHID 생성 실패: " + report;
            } catch (Throwable error) {
                backendReady = false;
                status = "UHID 연결 실패: " + error.getMessage();
            }
        });
    }

    public boolean isReady() { return backendReady && remote != null && serverRunning(); }
    public boolean serverRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable ignored) { return false; }
    }

    public void requestOrConnect() {
        if (!serverRunning()) {
            status = "Shizuku 앱에서 서비스를 먼저 시작하세요";
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    status = "Shizuku 권한을 설정에서 허용하세요";
                } else {
                    status = "Shizuku 권한 요청 중";
                    Shizuku.requestPermission(813);
                }
            } else {
                maybeBind();
            }
        } catch (Throwable error) {
            status = "연결 실패: " + error.getMessage();
        }
    }

    private synchronized void maybeBind() {
        if (!serverRunning() || bound) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            status = "Shizuku UHID 서비스 시작 중";
            bound = true;
            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            bound = false;
            backendReady = false;
            status = "Shizuku 서비스 시작 실패: " + error.getMessage();
        }
    }

    public int registeredKeyboardCount() {
        int found = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.getName().startsWith(UhidKeyboard.NAME_PREFIX)) found++;
        }
        return found;
    }

    public String status() {
        if (!backendReady) return status;
        int devices = registeredKeyboardCount();
        return status + " · Android 인식 " + devices + "/" + requestedCount
                + (requestedBus == UhidProtocol.BUS_USB ? " USB" : " VIRTUAL");
    }

    public String diagnostics() {
        StringBuilder message = new StringBuilder();
        message.append("Roblox MIDI QWERTY diagnostic\n");
        message.append("Android SDK=").append(Build.VERSION.SDK_INT).append('\n');
        message.append("Shizuku ping=").append(serverRunning()).append('\n');
        message.append("Backend connected=").append(isReady()).append('\n');
        message.append("Profile: devices=").append(requestedCount).append(" bus=")
                .append(requestedBus).append('\n');
        message.append("Status: ").append(status).append('\n');
        IKeyboardInjector service = remote;
        if (service != null) {
            try {
                message.append("Service: ").append(service.getBackendStatus()).append('\n');
            } catch (Exception error) {
                message.append("Service error: ").append(error.toString()).append('\n');
            }
        }
        int found = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || !d.getName().startsWith(UhidKeyboard.NAME_PREFIX)) continue;
            found++;
            message.append("#").append(found).append(" id=").append(d.getId())
                    .append(" name=").append(d.getName())
                    .append(" keyboardType=").append(d.getKeyboardType())
                    .append(" source=0x").append(Integer.toHexString(d.getSources()))
                    .append(" vendor=").append(d.getVendorId())
                    .append(" product=").append(d.getProductId()).append('\n');
        }
        message.append("Android UHID input devices found=").append(found).append('\n');
        message.append("UHID registered != Roblox gameplay key accepted.\n");
        return message.toString();
    }

    public void send(int code, boolean down, boolean shift) throws Exception {
        IKeyboardInjector service = remote;
        if (service == null || !backendReady) {
            throw new RemoteException("UHID not ready; check diagnostics");
        }
        try {
            if (!service.sendKey(code, down, shift)) {
                String info = service.getBackendStatus();
                if (info.startsWith("UHID_FAILED")) {
                    backendReady = false;
                    status = info;
                }
                throw new RemoteException("UHID key rejected: " + info);
            }
        } catch (RemoteException error) {
            if (!serverRunning()) {
                backendReady = false;
                status = "Shizuku disconnected";
            }
            throw error;
        }
    }

    public void allUp() {
        IKeyboardInjector service = remote;
        if (service == null) return;
        try { service.releaseAll(); } catch (RemoteException ignored) { }
    }

    public void testMiddleC() {
        sendTest(new int[]{60}, 160, 300);
    }

    public void testQuickKeys() {
        sendTest(new int[]{60, 62, 64}, 35, 1100);
    }

    private void sendTest(int[] notes, long holdMs, long initialDelayMs) {
        if (!isReady()) {
            status = "테스트 불가: 먼저 Shizuku UHID를 연결하세요";
            return;
        }
        status = "QWERTY 키 입력 테스트 예약됨";
        new Thread(() -> {
            try {
                Thread.sleep(initialDelayMs);
                for (int midi : notes) {
                    PianoLayout.Stroke stroke = PlayerController.get(context).stroke(midi);
                    if (stroke == null) continue;
                    send(stroke.code, true, stroke.shift);
                    try { Thread.sleep(holdMs); }
                    finally { send(stroke.code, false, stroke.shift); }
                    Thread.sleep(90);
                }
                status = notes.length + "개 HID 키 전송 완료 · 게임 인식은 별도 확인 필요";
            } catch (Exception error) {
                allUp();
                status = "QWERTY 테스트 실패: " + error.getMessage();
            }
        }, "qwerty-diagnostic").start();
    }
}
