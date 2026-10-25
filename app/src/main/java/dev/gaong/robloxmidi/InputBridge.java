package dev.gaong.robloxmidi;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Shizuku UserService + Android UHID lifecycle manager.
 *
 * Initial bind, Shizuku restart, UserService death, missing Android input
 * device and manual reconnect are different failure modes. In-place UHID
 * re-enumeration is preferred to an expensive Shizuku UserService unbind,
 * which has known failure modes on some Android/Shizuku versions.
 *
 * All lifecycle state writes run on main; RPC config runs on a single
 * background executor. A generation number discards callbacks from stale
 * ServiceConnections and stale configuration operations.
 */
public final class InputBridge {
    private static InputBridge instance;

    public static synchronized InputBridge get(Context context) {
        if (instance == null) instance = new InputBridge(context.getApplicationContext());
        return instance;
    }

    private static final int SHIZUKU_PERMISSION_REQUEST = 813;
    private static final long WATCHDOG_MS = 2500L;
    private static final long BIND_TIMEOUT_MS = 12000L;
    private static final long DEVICE_ENUMERATION_GRACE_MS = 3500L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService configuration = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "uhid-reconnection");
        t.setDaemon(true);
        return t;
    });
    private final ReconnectBackoff backoff = new ReconnectBackoff();
    private final Shizuku.UserServiceArgs args;

    private volatile IKeyboardInjector remote;
    private volatile String status = "Shizuku 연결 필요";
    private volatile boolean backendReady;
    private volatile int requestedCount;
    private volatile int requestedBus;
    private volatile String lastDisconnect = "없음";
    private volatile String lastRepair = "없음";
    private volatile long lastHealthyAt;
    private volatile long lastLostAt;
    private long continuouslyHealthySince;
    private volatile int successfulRecoveries;
    private volatile int disconnectCount;

    private ServiceConnection activeConnection;
    // Shizuku's Java callback cache can outlive a dead server Binder. Keep
    // the old registration until a new server is available to clear it.
    private ServiceConnection staleConnection;
    private int bindGeneration;
    private volatile int configureGeneration;
    private boolean bound;
    private boolean recoveryScheduled;
    private int recoveryTicket;
    private int missingCycles;
    private int configurationFailures;
    private long configuredAt;
    private long lastProfileChangeAt;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                inspectHealth();
            } catch (Throwable error) {
                status = "연결 점검 실패: " + error.getClass().getSimpleName();
            } finally {
                main.postDelayed(this, WATCHDOG_MS);
            }
        }
    };

    private InputBridge(Context app) {
        context = app;
        android.content.SharedPreferences p = app.getSharedPreferences("piano", Context.MODE_PRIVATE);
        requestedCount = p.getInt("keyboard_count", 1) == 4 ? 4 : 1;
        requestedBus = p.getInt("keyboard_bus", UhidProtocol.BUS_USB)
                == UhidProtocol.BUS_VIRTUAL ? UhidProtocol.BUS_VIRTUAL : UhidProtocol.BUS_USB;

        // Bump on API changes to ensure Shizuku loads the updated UserService
        // rather than continuing to run a stale service from an older APK.
        args = new Shizuku.UserServiceArgs(new ComponentName(
                app.getPackageName(), InjectUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("uhid-keyboard")
                .tag("roblox-midi-keyboard")
                .version(5);

        Shizuku.OnBinderReceivedListener received = this::onShizukuAvailable;
        Shizuku.OnBinderDeadListener dead = this::onShizukuDead;
        Shizuku.OnRequestPermissionResultListener permission = (request, result) -> {
            if (request != SHIZUKU_PERMISSION_REQUEST) return;
            if (result == PackageManager.PERMISSION_GRANTED) bindIfNeeded();
            else status = "Shizuku 권한 거부됨 · 설정에서 허용해 주세요";
        };

        try {
            Shizuku.addBinderReceivedListenerSticky(received);
            Shizuku.addBinderDeadListener(dead);
            Shizuku.addRequestPermissionResultListener(permission);
        } catch (Throwable error) {
            status = "Shizuku 초기화 실패: " + error.getMessage();
        }
        main.postDelayed(watchdog, WATCHDOG_MS);
    }

    public int keyboardCount() { return requestedCount; }
    public int keyboardBus() { return requestedBus; }
    public boolean isReady() {
        IKeyboardInjector service = remote;
        return backendReady && service != null
                && service.asBinder().isBinderAlive() && serverRunning();
    }
    public boolean serverRunning() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable ignored) { return false; }
    }

    private boolean hasPermission() {
        try {
            return serverRunning()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The regular connect button can recover a dead service. If it is still
     * healthy it will not unnecessarily unregister a working HID device.
     */
    public void requestOrConnect() {
        main.post(() -> {
            if (!serverRunning()) {
                status = "Shizuku가 중단됨 · Shizuku 앱에서 다시 시작해 주세요";
                return;
            }
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        status = "Shizuku에서 앱 권한을 다시 허용해 주세요";
                    } else {
                        status = "Shizuku 접근 권한 요청 중";
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
                    }
                    return;
                }
            } catch (Throwable error) {
                status = "Shizuku 권한 확인 실패: " + error.getMessage();
                return;
            }

            if (isReady() && registeredKeyboardCount() >= requestedCount) {
                status = "QWERTY UHID 키보드 정상 연결됨";
                return;
            }
            backoff.reset();
            recoveryTicket++;
            recoveryScheduled = false;
            if (remote != null && remote.asBinder().isBinderAlive()) {
                reconfigureInPlace("연결 버튼으로 UHID 상태 복구");
            } else {
                clearBinding(false);
                bindIfNeeded();
            }
        });
    }

    /** User-facing safe repair that keeps Shizuku alive when possible. */
    public void recoverKeyboard() {
        main.post(() -> {
            backoff.reset();
            recoveryTicket++;
            recoveryScheduled = false;
            if (!serverRunning()) {
                status = "Shizuku 자체가 꺼졌습니다. Shizuku를 먼저 시작해 주세요";
                return;
            }
            if (!hasPermission()) {
                requestOrConnect();
                return;
            }
            reconfigureInPlace("사용자 요청: 가상 키보드 다시 등록");
        });
    }

    /**
     * Advanced fallback: explicitly replace a genuinely stuck UserService
     * after in-place reset fails. This may need a Shizuku restart on affected
     * ROMs; it is intentionally NEVER part of the normal recovery path.
     */
    public void forceFullReconnect() {
        main.post(() -> {
            backoff.reset();
            recoveryTicket++;
            recoveryScheduled = false;
            if (!serverRunning()) {
                status = "Shizuku 서비스를 먼저 다시 시작해야 합니다";
                return;
            }
            stopPlayingForDisconnect("Shizuku 사용자 서비스 완전 재연결");
            lastRepair = "사용자 요청: UserService 완전 재연결";
            status = "기존 UHID 서비스 정리 중...";
            clearBinding(true);
            main.postDelayed(this::bindIfNeeded, 900);
        });
    }

    public void setProfile(int count, int bus) {
        if (count != 1 && count != 4) throw new IllegalArgumentException("1 or 4 keyboards");
        if (bus != UhidProtocol.BUS_USB && bus != UhidProtocol.BUS_VIRTUAL)
            throw new IllegalArgumentException("USB or VIRTUAL bus");

        main.post(() -> {
            requestedCount = count;
            requestedBus = bus;
            lastProfileChangeAt = SystemClock.elapsedRealtime();
            context.getSharedPreferences("piano", Context.MODE_PRIVATE).edit()
                    .putInt("keyboard_count", count)
                    .putInt("keyboard_bus", bus).apply();
            recoverKeyboard();
        });
    }

    private void onShizukuAvailable() {
        main.post(() -> {
            if (!serverRunning()) return;
            if (staleConnection != null) {
                // Provider-level Binder restart can leave Shizuku's local
                // ShizukuServiceConnection cache pointing to an old server.
                try { Shizuku.unbindUserService(args, staleConnection, false); }
                catch (Throwable ignored) { }
                staleConnection = null;
                backoff.reset(); // A genuinely new Shizuku session gets fresh retries.
            }
            if (bound && remote != null && remote.asBinder().isBinderAlive()) return;
            if (bound) {
                // A previous pending/dead service may be wedged. Remove its
                // callbacks before creating a new binder connection object.
                clearBinding(false);
            }
            bindIfNeeded();
        });
    }

    private void onShizukuDead() {
        main.post(() -> {
            stopPlayingForDisconnect("Shizuku 서비스 종료");
            staleConnection = activeConnection;
            lastDisconnect = "Shizuku Binder 사망";
            lastLostAt = SystemClock.elapsedRealtime();
            disconnectCount++;
            clearLocalOnly();
            status = "Shizuku 서비스 종료됨 · 재시작하면 자동 재연결합니다";
        });
    }

    private void bindIfNeeded() {
        if (bound || remote != null) return;
        if (!serverRunning()) {
            status = "Shizuku 앱에서 서비스를 시작해 주세요";
            return;
        }
        if (!hasPermission()) {
            status = "Shizuku 권한 허용 필요";
            return;
        }

        final int generation = ++bindGeneration;
        activeConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                main.post(() -> {
                    if (generation != bindGeneration) return;
                    if (binder == null || !binder.isBinderAlive()) {
                        handleDeadService(generation, "서비스에서 유효한 Binder를 받지 못함");
                        return;
                    }
                    IKeyboardInjector service = IKeyboardInjector.Stub.asInterface(binder);
                    remote = service;
                    backendReady = false;
                    status = "Shizuku 연결됨 · 가상 키보드 초기화 중";
                    try {
                        binder.linkToDeath(() -> main.post(() ->
                                handleDeadService(generation, "키보드 UserService 프로세스 종료")), 0);
                    } catch (RemoteException error) {
                        handleDeadService(generation, "UserService Binder가 이미 종료됨");
                        return;
                    }
                    configureAsync(service, generation, "새 서비스 연결");
                });
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                main.post(() -> handleDeadService(generation,
                        "Shizuku UserService 연결 해제"));
            }
        };

        bound = true;
        status = "키보드 UserService 바인딩 중...";
        try {
            Shizuku.bindUserService(args, activeConnection);
        } catch (Throwable error) {
            status = "서비스 바인딩 실패: " + error.getMessage();
            bound = false;
            clearBinding(false);
            scheduleRecovery("UserService 바인딩 실패", false);
            return;
        }

        main.postDelayed(() -> {
            if (generation == bindGeneration && remote == null && bound) {
                handleDeadService(generation, "UserService 연결 대기 시간 초과");
            }
        }, BIND_TIMEOUT_MS);
    }

    private void configureAsync(IKeyboardInjector service, int generation, String reason) {
        if (service == null || !serverRunning()) {
            scheduleRecovery(reason + ": 서비스 없음", false);
            return;
        }

        final int config = ++configureGeneration;
        final int desiredCount = requestedCount;
        final int desiredBus = requestedBus;
        backendReady = false;
        status = "가상 키보드 다시 등록 중: " + reason;
        lastRepair = reason;

        configuration.execute(() -> {
            boolean ok = false;
            String remoteState = "";
            try {
                if (remote != service || configureGeneration != config) return;
                ok = service.configureKeyboard(desiredCount, desiredBus);
                remoteState = service.getBackendStatus();
            } catch (Throwable error) {
                remoteState = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            final boolean result = ok;
            final String report = remoteState;
            main.post(() -> {
                if (generation != bindGeneration || configureGeneration != config
                        || remote != service) return;
                if (result && report.startsWith("UHID_READY:")) {
                    backendReady = true;
                    configuredAt = SystemClock.elapsedRealtime();
                    missingCycles = 0;
                    continuouslyHealthySince = 0;
                    configurationFailures = 0;
                    successfulRecoveries++;
                    status = "UHID 등록 완료 · Android 장치 확인 중";
                    // A newly created UHID may not be enumerated immediately.
                    // The watchdog checks its actual InputDevice registration.
                    main.postDelayed(() -> checkDeviceEnumeration(generation),
                            DEVICE_ENUMERATION_GRACE_MS);
                } else {
                    backendReady = false;
                    configurationFailures++;
                    status = "UHID 등록 실패: " + report;
                    scheduleRecovery("UHID 재등록 실패", configurationFailures >= 2);
                }
            });
        });
    }

    private void reconfigureInPlace(String reason) {
        IKeyboardInjector service = remote;
        if (service != null && service.asBinder().isBinderAlive() && serverRunning()) {
            stopPlayingForDisconnect(reason);
            configureAsync(service, bindGeneration, reason);
        } else {
            stopPlayingForDisconnect(reason);
            clearBinding(false);
            bindIfNeeded();
        }
    }

    /**
     * Handles both Android missing the input device and Shizuku disconnecting.
     * Rebind is only requested when the remote service is actually dead.
     */
    private void handleDeadService(int generation, String reason) {
        if (generation != bindGeneration) return;
        lastDisconnect = reason;
        lastLostAt = SystemClock.elapsedRealtime();
        disconnectCount++;
        stopPlayingForDisconnect(reason);
        status = reason + " · 재연결 준비";
        clearBinding(false);
        scheduleRecovery(reason, false);
    }

    private void stopPlayingForDisconnect(String reason) {
        PlayerController player = PlayerController.get(context);
        if (player.isPlaying() || player.isPaused()) {
            player.onInputDisconnected(reason);
        }
    }

    private void clearLocalOnly() {
        bindGeneration++;
        configureGeneration++;
        bound = false;
        activeConnection = null;
        remote = null;
        backendReady = false;
        configuredAt = 0;
        missingCycles = 0;
        continuouslyHealthySince = 0;
        recoveryTicket++;
        recoveryScheduled = false;
    }

    /**
     * Clear Shizuku's cached callback registration. Never call remove=true
     * automatically: repeated UserService unbinds are not reliable on some
     * Shizuku/ROM combinations.
     */
    private void clearBinding(boolean killRemoteService) {
        ServiceConnection previous = activeConnection;
        clearLocalOnly();
        if (previous == null || !serverRunning()) return;
        if (killRemoteService) {
            try { Shizuku.unbindUserService(args, previous, true); }
            catch (Throwable error) {
                status = "기존 UserService 종료 실패: " + error.getMessage();
            }
        }
        try {
            Shizuku.unbindUserService(args, previous, false);
        } catch (Throwable error) {
            // The dead binder may already have removed this connection.
            lastRepair = "이전 Binder 정리 경고: " + error.getMessage();
        }
    }

    private void scheduleRecovery(String reason, boolean tryRebind) {
        if (!serverRunning()) {
            status = "Shizuku가 중단됨 · Shizuku 앱에서 재시작해 주세요";
            return;
        }
        if (recoveryScheduled) return;

        long delay = backoff.nextDelayMs();
        if (delay < 0) {
            status = "자동 복구 4회 실패 · 복구 버튼을 눌러 주세요 ("
                    + reason + ")";
            return;
        }

        recoveryScheduled = true;
        final int ticket = ++recoveryTicket;
        status = "연결 복구 " + backoff.attempts() + "/4 예정 · "
                + reason;
        main.postDelayed(() -> {
            if (ticket != recoveryTicket) return;
            recoveryScheduled = false;
            if (!serverRunning() || !hasPermission()) return;
            IKeyboardInjector service = remote;
            if (!tryRebind && service != null
                    && service.asBinder().isBinderAlive()) {
                reconfigureInPlace("자동 복구: " + reason);
            } else {
                clearBinding(false);
                bindIfNeeded();
            }
        }, delay);
    }

    private void checkDeviceEnumeration(int generation) {
        if (generation != bindGeneration || !backendReady) return;
        int seen = registeredKeyboardCount();
        if (seen >= requestedCount) {
            missingCycles = 0;
            long now = SystemClock.elapsedRealtime();
            lastHealthyAt = now;
            if (continuouslyHealthySince == 0) continuouslyHealthySince = now;
            if (now - continuouslyHealthySince >= 20000L) backoff.reset();
            status = "QWERTY 키보드 연결 완료 · Android " + seen + "개 인식";
        } else {
            continuouslyHealthySince = 0;
            missingCycles++;
            status = "가상 키보드 인식 누락 (" + seen + "/"
                    + requestedCount + ") · 재등록 점검 중";
            if (missingCycles >= 2) {
                scheduleRecovery("Android가 UHID 키보드를 인식하지 못함", false);
            }
        }
    }

    /** 2.5-second lightweight watchdog. Never performs synchronous RPC. */
    private void inspectHealth() {
        if (!serverRunning()) {
            if (bound || remote != null || backendReady) onShizukuDead();
            return;
        }
        IKeyboardInjector service = remote;
        if (service != null && !service.asBinder().isBinderAlive()) {
            handleDeadService(bindGeneration, "키보드 서비스 Binder 응답 중단");
            return;
        }
        if (!bound && service == null && hasPermission() && backoff.attempts() < 4) {
            if (!recoveryScheduled) bindIfNeeded();
            return;
        }
        if (!backendReady) return;
        long now = SystemClock.elapsedRealtime();
        if (now - configuredAt < DEVICE_ENUMERATION_GRACE_MS) return;
        int seen = registeredKeyboardCount();
        if (seen >= requestedCount) {
            missingCycles = 0;
            lastHealthyAt = now;
            if (continuouslyHealthySince == 0) continuouslyHealthySince = now;
            // A keyboard flapping every ~7 seconds must not reset the
            // recovery budget every time it briefly becomes available.
            if (now - continuouslyHealthySince >= 20000L) backoff.reset();
        } else {
            continuouslyHealthySince = 0;
            missingCycles++;
            if (missingCycles == 1) {
                status = "Android 가상 키보드 연결 끊김 감지";
            }
            if (missingCycles >= 2 && !recoveryScheduled) {
                lastDisconnect = "Android InputDevice가 사라짐 (" + seen
                        + "/" + requestedCount + ")";
                lastLostAt = now;
                disconnectCount++;
                stopPlayingForDisconnect(lastDisconnect);
                backendReady = false;
                scheduleRecovery(lastDisconnect, false);
            }
        }
    }

    public int registeredKeyboardCount() {
        try {
            int seen = 0;
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && d.getName().startsWith(UhidKeyboard.NAME_PREFIX)) seen++;
            }
            return seen;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public String status() {
        if (!serverRunning()) return "Shizuku 종료됨 · 앱에서 다시 시작 필요";
        if (!backendReady) return status;
        return status + " · Android 인식 " + registeredKeyboardCount()
                + "/" + requestedCount;
    }

    public String diagnostics() {
        StringBuilder text = new StringBuilder();
        text.append("Roblox QWERTY MIDI reconnect diagnostics\n");
        text.append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        text.append("Shizuku alive: ").append(serverRunning()).append('\n');
        text.append("Shizuku permission: ").append(hasPermission()).append('\n');
        text.append("Shizuku UID: ");
        try { text.append(serverRunning() ? Shizuku.getUid() : "not available"); }
        catch (Throwable error) { text.append("error"); }
        text.append('\n');
        text.append("UserService binder attached: ").append(bound).append('\n');
        text.append("Remote alive: ").append(remote != null
                && remote.asBinder().isBinderAlive()).append('\n');
        text.append("UHID backend ready: ").append(isReady()).append('\n');
        text.append("Profile: ").append(requestedCount).append(" keyboards, bus=")
                .append(requestedBus).append('\n');
        text.append("Android enumerated: ").append(registeredKeyboardCount())
                .append(" keyboards\n");
        text.append("Connection generation: ").append(bindGeneration).append('\n');
        text.append("Reconnection attempts: ").append(backoff.attempts()).append("/4\n");
        text.append("Successful device resets: ").append(successfulRecoveries).append('\n');
        text.append("Disconnect count: ").append(disconnectCount).append('\n');
        text.append("Last disconnect reason: ").append(lastDisconnect).append('\n');
        text.append("Last repair reason: ").append(lastRepair).append('\n');
        text.append("Last disconnect elapsedMs: ").append(lastLostAt).append('\n');
        text.append("Last healthy elapsedMs: ").append(lastHealthyAt).append('\n');
        text.append("Status: ").append(status).append('\n');
        IKeyboardInjector service = remote;
        if (service != null && service.asBinder().isBinderAlive()) {
            try { text.append("UserService: ").append(service.getBackendStatus()).append('\n'); }
            catch (Throwable error) { text.append("UserService error: ")
                    .append(error.getMessage()).append('\n'); }
        }
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.getName().startsWith(UhidKeyboard.NAME_PREFIX)) {
                text.append("Device id=").append(id)
                        .append(", keyboardType=").append(d.getKeyboardType())
                        .append(", source=0x").append(Integer.toHexString(d.getSources()))
                        .append(", vendor=").append(d.getVendorId())
                        .append(", product=").append(d.getProductId()).append('\n');
            }
        }
        text.append("Android keyboard ready != Roblox QWERTY gameplay supported.\n");
        return text.toString();
    }

    public boolean trySend(int code, boolean down, boolean shift) throws Exception {
        IKeyboardInjector service = remote;
        if (service == null || !backendReady || !service.asBinder().isBinderAlive()) {
            throw new RemoteException("UHID not ready; reconnect pending");
        }
        try {
            if (service.sendKey(code, down, shift)) return true;
            String report = service.getBackendStatus();
            if (report.startsWith("UHID_READY:")) return false;
            backendReady = false;
            status = "UHID 입력 오류: " + report;
            main.post(() -> {
                if (remote == service) {
                    lastDisconnect = "UHID input failure: " + report;
                    lastLostAt = SystemClock.elapsedRealtime();
                    disconnectCount++;
                    stopPlayingForDisconnect(lastDisconnect);
                    scheduleRecovery(lastDisconnect, false);
                }
            });
            throw new RemoteException("UHID driver error: " + report);
        } catch (RemoteException error) {
            if (!service.asBinder().isBinderAlive() || !serverRunning()) {
                main.post(() -> {
                    if (remote == service) {
                        handleDeadService(bindGeneration, "UHID 입력 중 Binder 연결 끊김");
                    }
                });
            }
            throw error;
        }
    }

    public void send(int code, boolean down, boolean shift) throws Exception {
        if (!trySend(code, down, shift))
            throw new RemoteException("Temporary UHID key capacity limit");
    }

    public void allUp() {
        IKeyboardInjector service = remote;
        if (service == null || !service.asBinder().isBinderAlive()) return;
        try { service.releaseAll(); }
        catch (RemoteException ignored) { }
    }

    public void testMiddleC() { sendTest(new int[]{60}, 150, 350); }
    public void testQuickKeys() { sendTest(new int[]{60, 62, 64}, 40, 700); }

    private void sendTest(int[] notes, long holdMs, long initialDelayMs) {
        if (!isReady()) {
            status = "UHID 연결 없음 · 복구 버튼을 눌러 주세요";
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(initialDelayMs);
                for (int note : notes) {
                    PianoLayout.Stroke stroke = PlayerController.get(context).stroke(note);
                    if (stroke == null) continue;
                    send(stroke.code, true, stroke.shift);
                    try { Thread.sleep(holdMs); }
                    finally { send(stroke.code, false, stroke.shift); }
                    Thread.sleep(100);
                }
                status = "HID 키 전송 완료 · Roblox에서 소리 확인 필요";
            } catch (Exception error) {
                allUp();
                status = "HID 입력 테스트 실패: " + error.getMessage();
            }
        }, "qwerty-test").start();
    }
}
