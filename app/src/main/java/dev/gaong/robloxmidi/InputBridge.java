package dev.gaong.robloxmidi;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.InputDevice;
import rikka.shizuku.Shizuku;

/**
 * Shizuku authorization and UHID keyboard connection health.
 * The app only claims "ready" after the privileged service actually opens
 * /dev/uhid and sends the Linux CREATE2 reports.
 */
public final class InputBridge {
    private static InputBridge instance;
    public static synchronized InputBridge get(Context context) {
        if (instance == null) instance = new InputBridge(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private volatile IKeyboardInjector remote;
    private volatile String status = "Shizuku 연결 필요";
    private volatile boolean backendReady;
    private boolean bound;
    private final Shizuku.UserServiceArgs args;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IKeyboardInjector.Stub.asInterface(binder);
            try {
                String diagnostic = remote.getBackendStatus();
                backendReady = diagnostic.startsWith("UHID_READY:");
                status = backendReady ? "UHID 하드웨어 키보드 서비스 활성화" : diagnostic;
            } catch (Exception error) {
                backendReady = false;
                status = "UHID 상태 확인 실패: " + error.getMessage();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            bound = false;
            backendReady = false;
            status = "Shizuku 키보드 연결 종료됨";
            PlayerController.get(context).stop();
        }
    };

    private InputBridge(Context context) {
        this.context = context;
        args = new Shizuku.UserServiceArgs(new ComponentName(
                context.getPackageName(), InjectUserService.class.getName()))
                .daemon(false).processNameSuffix("uhid-keyboard")
                .tag("roblox-midi-keyboard").version(2);
        try {
            Shizuku.addBinderReceivedListenerSticky(this::maybeBind);
            Shizuku.addBinderDeadListener(() -> {
                remote = null;
                bound = false;
                backendReady = false;
                status = "Shizuku가 종료됐습니다. 다시 시작하세요";
                PlayerController.get(context).stop();
            });
            Shizuku.addRequestPermissionResultListener((request, grant) -> {
                if (request == 813 && grant == PackageManager.PERMISSION_GRANTED) maybeBind();
                else if (request == 813) status = "Shizuku 권한이 거부됐습니다";
            });
        } catch (Throwable e) {
            status = "Shizuku 초기화 실패: " + e.getMessage();
        }
    }

    /** Check if Android InputReader enumerated our actual virtual devices. */
    public int registeredKeyboardCount() {
        int total = 0;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device != null && device.getName().startsWith(UhidKeyboard.NAME_PREFIX)) {
                    total++;
                }
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return total;
    }

    public String status() {
        if (!backendReady) return status;
        int devices = registeredKeyboardCount();
        if (devices < 0) return status + " (Android 인식 상태 확인 불가)";
        return status + " · Android 인식 " + devices + "/" +
                UhidKeyboard.DEVICE_COUNT + "개";
    }

    public boolean isReady() {
        return remote != null && backendReady && serverRunning();
    }

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
                    status = "Shizuku 권한을 다시 허용하세요";
                } else {
                    status = "Shizuku 권한 요청 중...";
                    Shizuku.requestPermission(813);
                }
                return;
            }
            maybeBind();
        } catch (Throwable e) {
            status = "Shizuku 연결 실패: " + e.getMessage();
        }
    }

    private synchronized void maybeBind() {
        if (!serverRunning() || bound) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            bound = true;
            status = "UHID 가상 키보드 생성 중...";
            Shizuku.bindUserService(args, connection);
        } catch (Throwable e) {
            bound = false;
            backendReady = false;
            status = "UHID 서비스 시작 실패: " + e.getMessage();
        }
    }

    public void send(int code, boolean down, boolean shift) throws Exception {
        IKeyboardInjector service = remote;
        if (service == null || !backendReady)
            throw new RemoteException("UHID keyboard service not ready");
        try {
            if (!service.sendKey(code, down, shift)) {
                String detail = service.getBackendStatus();
                if (detail.startsWith("UHID_FAILED:")) {
                    backendReady = false;
                    status = detail;
                }
                throw new RemoteException("UHID refused key: " + detail);
            }
        } catch (RemoteException e) {
            if (!serverRunning()) {
                backendReady = false;
                status = "Shizuku 연결 끊김";
            }
            throw e;
        }
    }

    public void allUp() {
        IKeyboardInjector service = remote;
        if (service != null) {
            try { service.releaseAll(); } catch (RemoteException ignored) { }
        }
    }

    /**
     * Real event dispatch test; Android input enumeration alone cannot
     * establish that Roblox's piano scripts actually handle the key.
     */
    public void testMiddleC() {
        if (!isReady()) {
            status = "UHID 연결 필요: Shizuku와 장치 상태를 확인하세요";
            return;
        }
        new Thread(() -> {
            try {
                PianoLayout.Stroke s = PlayerController.get(context).stroke(60);
                if (s == null) return;
                send(s.code, true, s.shift);
                Thread.sleep(120);
                send(s.code, false, s.shift);
                status = "C4 HID 키 전송 완료 · Roblox에서 소리가 나는지 확인하세요";
            } catch (Exception e) {
                allUp();
                status = "UHID C4 테스트 실패: " + e.getMessage();
            }
        }, "uhid-c4-test").start();
    }
}
