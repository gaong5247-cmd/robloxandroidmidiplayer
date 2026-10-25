package dev.gaong.robloxmidi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Touch-friendly controls floating over Roblox without taking keyboard focus. */
public class OverlayService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private View overlay;
    private PlayerController player;
    private InputBridge bridge;
    private TextView label;
    private Button play;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (overlay != null) {
                label.setText(player.filename() + "\n" +
                        PlayerController.pretty(player.positionUs()) + " / " +
                        PlayerController.pretty(player.durationUs()) +
                        "   " + String.format(java.util.Locale.US, "%.2f×", player.speed()) +
                        "\n" + player.playbackSummary() +
                        "\n" + bridge.status() +
                        (player.error().isEmpty() ? "" : "\n⚠ " + player.error()));
                play.setText(player.isPlaying() ? "Ⅱ" : "▶");
                handler.postDelayed(this, 300);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        player = PlayerController.get(this);
        bridge = InputBridge.get(this);
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String channel = "roblox_midi_playback";
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(
                channel, "Roblox MIDI playback", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Roblox MIDI Player")
                .setContentText("로블록스 피아노 플로팅 컨트롤 실행 중")
                .setOngoing(true).build();
        startForeground(103, notification);
        if (overlay == null) createOverlay();
        return START_STICKY;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF50719B);
        return bg;
    }
    private int dp(int pixels) {
        return (int)(pixels * getResources().getDisplayMetrics().density + 0.5f);
    }
    private TextView text(String s, int sp) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(sp);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }
    private Button small(String title, LinearLayout row, Runnable callback) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(0xFF2C4670));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        row.addView(b, lp);
        b.setOnClickListener(v -> callback.run());
        return b;
    }

    private void createOverlay() {
        if (Build.VERSION.SDK_INT < 26) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.setBackground(rounded(0xEF17243A));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView grab = text("≡  MIDI PLAYER  ·  끌어서 이동", 12);
        titleRow.addView(grab, new LinearLayout.LayoutParams(0, dp(38), 1));
        Button close = small("×", titleRow, this::stopSelf);
        close.getLayoutParams().width = dp(38);
        root.addView(titleRow);
        label = text("", 12);
        label.setMaxLines(7);
        root.addView(label);

        LinearLayout controls = new LinearLayout(this);
        play = small("▶", controls, () -> {
            player.togglePlay();
            if (!player.error().isEmpty())
                Toast.makeText(this, player.error(), Toast.LENGTH_SHORT).show();
        });
        small("■", controls, player::stop);
        small("C4", controls, () -> InputBridge.get(this).testMiddleC());
        root.addView(controls);
        LinearLayout secondary = new LinearLayout(this);
        small("−10s", secondary, () -> player.seek(player.positionUs() - 10_000_000L));
        small("+10s", secondary, () -> player.seek(player.positionUs() + 10_000_000L));
        small("−速", secondary, () -> player.setSpeed(player.speed() - 0.10));
        small("+速", secondary, () -> player.setSpeed(player.speed() + 0.10));
        root.addView(secondary);
        LinearLayout testing = new LinearLayout(this);
        small("QWERTY T-Y-U 입력 시험", testing, bridge::testQuickKeys);
        small("상태 확인", testing, () ->
                Toast.makeText(this, bridge.status(), Toast.LENGTH_LONG).show());
        root.addView(testing);
        params = new WindowManager.LayoutParams(
                dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(85);

        grab.setOnTouchListener(new View.OnTouchListener() {
            private int originX, originY;
            private float touchX, touchY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        originX = params.x;
                        originY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = originX + (int)(event.getRawX() - touchX);
                        params.y = originY + (int)(event.getRawY() - touchY);
                        if (overlay != null) windows.updateViewLayout(overlay, params);
                        return true;
                    default: return true;
                }
            }
        });
        overlay = root;
        try {
            windows.addView(overlay, params);
            handler.post(tick);
        } catch (RuntimeException ex) {
            overlay = null;
            Toast.makeText(this, "오버레이 표시 실패: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        player.stop();
        if (overlay != null) {
            windows.removeView(overlay);
            overlay = null;
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
