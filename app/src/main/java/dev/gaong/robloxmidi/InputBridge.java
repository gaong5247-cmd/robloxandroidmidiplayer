package dev.gaong.robloxmidi;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.KeyEvent;
import rikka.shizuku.Shizuku;

/** App-process Shizuku permission, binder and UserService lifecycle. */
public final class InputBridge {
    private static InputBridge instance;
    public static synchronized InputBridge get(Context c) {
        if (instance == null) instance = new InputBridge(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private volatile IKeyboardInjector remote;
    private volatile String status = "Shizuku 연결 필요";
    private boolean bound = false;
    private final Shizuku.UserServiceArgs args;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IKeyboardInjector.Stub.asInterface(binder);
            status = "키 입력 서비스 연결됨";
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            bound = false;
            status = "키 입력 서비스 연결 끊김";
        }
    };

    private InputBridge(Context c) {
        context = c;
        args = new Shizuku.UserServiceArgs(new ComponentName(
                c.getPackageName(), InjectUserService.class.getName()))
                .daemon(false).processNameSuffix("keys").tag("roblox-midi-keyboard")
                .version(1);
        try {
            Shizuku.addBinderReceivedListenerSticky(this::maybeBind);
            Shizuku.addBinderDeadListener(() -> {
                remote = null;
                bound = false;
                status = "Shizuku 서비스 종료됨";
                PlayerController.get(context).stop();
            });
            Shizuku.addRequestPermissionResultListener((requestCode, result) -> {
                if (requestCode == 813 && result == PackageManager.PERMISSION_GRANTED) maybeBind();
                else if (requestCode == 813) status = "Shizuku 권한 거부됨";
            });
        } catch (Throwable error) {
            status = "Shizuku 초기화 오류: " + error.getClass().getSimpleName();
        }
    }

    public String status() { return status; }
    public boolean isReady() { return remote != null && serverRunning(); }
    public boolean serverRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable ignored) { return false; }
    }

    public void requestOrConnect() {
        if (!serverRunning()) {
            status = "Shizuku 앱에서 먼저 서비스를 시작하세요";
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    status = "Shizuku에서 권한을 다시 허용하세요";
                } else {
                    status = "Shizuku 권한 요청 중";
                    Shizuku.requestPermission(813);
                }
                return;
            }
            maybeBind();
        } catch (Throwable e) {
            status = "Shizuku 연결 실패: " + e.getClass().getSimpleName();
        }
    }

    private synchronized void maybeBind() {
        if (!serverRunning() || bound) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            bound = true;
            status = "키 입력 서비스 연결 중";
            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            bound = false;
            status = "UserService 실행 실패: " + error.getMessage();
        }
    }

    public void send(int code, boolean down, boolean shift) throws Exception {
        IKeyboardInjector service = remote;
        if (service == null) throw new RemoteException("Shizuku injector is not connected");
        if (!service.sendKey(code, down, shift)) {
            throw new RemoteException("Android rejected key event " + code);
        }
    }

    public void allUp() {
        IKeyboardInjector service = remote;
        if (service != null) {
            try { service.releaseAll(); } catch (RemoteException ignored) { }
        }
    }

    public void testMiddleC() {
        new Thread(() -> {
            if (!isReady()) return;
            try {
                PianoLayout.Stroke s = PlayerController.get(context).stroke(60);
                if (s == null) return;
                send(s.code, true, s.shift);
                Thread.sleep(100);
                send(s.code, false, s.shift);
            } catch (Exception error) {
                allUp();
            }
        }, "test-key").start();
    }
}
