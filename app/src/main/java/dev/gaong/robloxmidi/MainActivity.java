package dev.gaong.robloxmidi;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Locale;

/**
 * Minimal native Android UI, without WebView, Compose or network dependencies.
 * Only the user explicitly selected MIDI file is read.
 */
public class MainActivity extends Activity {
    private static final int FILE_PICK = 11;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PlayerController player;
    private InputBridge input;
    private SharedPreferences prefs;
    private TextView status, midiName, time, speedLabel, transposeLabel, errorLabel, mapLabel;
    private TextView localHidTest, diagnosticInfo;
    private Button playButton;
    private SeekBar speedSeek;
    private CheckBox octaveFold, sustain;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("piano", MODE_PRIVATE);
        player = PlayerController.get(this);
        input = InputBridge.get(this);
        try {
            player.setLayout(prefs.getString("layout", PianoLayout.DEFAULT));
        } catch (Exception ignore) {
            player.setLayout(PianoLayout.DEFAULT);
        }
        player.setSpeed(prefs.getInt("speed", 100) / 100.0);
        player.setTranspose(prefs.getInt("transpose", 0));
        player.setFold(prefs.getBoolean("fold", true));
        player.setSustain(prefs.getBoolean("pedal", false));
        player.setTapMode(prefs.getBoolean("tap_mode", true));
        player.setTapDurationMs(prefs.getInt("tap_ms", 40));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 22, 36));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(28));
        scroll.addView(root);

        TextView header = label("HuMIDI · ANDROID", 27, Color.WHITE);
        header.setTypeface(null, 1);
        root.addView(header);
        TextView sub = label("Universal Roblox Piano • Shizuku input", 13, 0xFF94A5C3);
        root.addView(sub);
        gap(root, 24);

        status = label("", 14, 0xFFB6DDFB);
        root.addView(status);
        Button connect = button("1 · Shizuku 연결 / 상태 확인");
        root.addView(connect);
        connect.setOnClickListener(v -> {
            input.requestOrConnect();
            refresh();
        });

        Button repair = button("↻ 가상 키보드 다시 등록 (먼저 이 버튼)");
        root.addView(repair);
        repair.setOnClickListener(v -> {
            input.recoverKeyboard();
            toast("Shizuku를 유지한 채 UHID 키보드를 재등록합니다");
        });

        Button fullRestart = button("연결이 계속 안 되면 · UserService 완전 재연결");
        root.addView(fullRestart);
        fullRestart.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Shizuku UserService 완전 재연결")
                        .setMessage("키보드만 복구해도 안 될 때 사용하는 최후의 방법입니다. Android/Shizuku에 따라 재시작이 필요할 수 있습니다.")
                        .setPositiveButton("완전 재연결", (dialog, which) -> {
                            input.forceFullReconnect();
                            toast("서비스 재연결을 시작했습니다. 상태를 확인하세요.");
                        })
                        .setNegativeButton("취소", null)
                        .show());
        hint(root, "연결이 중간에 끊겼다면 '가상 키보드 다시 등록'을 먼저 누르세요. 복구되어도 Roblox가 이전 키보드를 기억하면 게임을 완전히 종료하고 다시 실행해야 합니다. Shizuku 자체가 꺼졌다면 Shizuku 앱에서 다시 시작해야 합니다.");
        Button batterySettings = button("배터리 최적화 설정 열기 (연결이 자꾸 끊길 때)");
        root.addView(batterySettings);
        batterySettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        hint(root, "휴대폰 배터리 제한이 원인이라면 Shizuku와 Roblox MIDI Player를 제한 없음으로 설정해 보세요. 이 설정만으로 복구가 보장되지는 않습니다.");
        gap(root, 18);

        midiName = label("MIDI를 선택하세요", 17, Color.WHITE);
        root.addView(midiName);
        Button openFile = button("2 · .mid 파일 선택");
        root.addView(openFile);
        openFile.setOnClickListener(v -> pickMidi());

        time = label("0:00 / 0:00", 14, 0xFFABC4DE);
        root.addView(time);
        playButton = button("▶ 재생");
        root.addView(playButton);
        playButton.setOnClickListener(v -> {
            player.togglePlay();
            refresh();
        });
        gap(root, 14);

        TextView settings = label("연주 설정", 19, Color.WHITE);
        settings.setTypeface(null, 1);
        root.addView(settings);
        speedLabel = label("", 14, Color.WHITE);
        root.addView(speedLabel);
        speedSeek = new SeekBar(this);
        speedSeek.setMax(275);
        speedSeek.setProgress(prefs.getInt("speed", 100) - 25);
        root.addView(speedSeek);
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser) return;
                int percent = value + 25;
                player.setSpeed(percent / 100.0);
                prefs.edit().putInt("speed", percent).apply();
                refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        LinearLayout transposeRow = horizontal();
        transposeLabel = label("", 15, Color.WHITE);
        Button minus = button("−");
        Button plus = button("+");
        transposeRow.addView(minus, new LinearLayout.LayoutParams(0, dp(48), 1));
        transposeRow.addView(transposeLabel, new LinearLayout.LayoutParams(0, dp(48), 2));
        transposeLabel.setGravity(Gravity.CENTER);
        transposeRow.addView(plus, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(transposeRow);
        minus.setOnClickListener(v -> transpose(-1));
        plus.setOnClickListener(v -> transpose(1));

        octaveFold = new CheckBox(this);
        octaveFold.setText("음역 밖 음을 옥타브로 접기 (C2–C7)");
        octaveFold.setTextColor(Color.WHITE);
        octaveFold.setChecked(player.fold());
        root.addView(octaveFold);
        octaveFold.setOnCheckedChangeListener((v, b) -> {
            player.setFold(b);
            prefs.edit().putBoolean("fold", b).apply();
        });
        sustain = new CheckBox(this);
        sustain.setText("MIDI 서스테인(CC64)을 Space로 입력");
        sustain.setTextColor(Color.WHITE);
        sustain.setChecked(player.sustain());
        root.addView(sustain);
        sustain.setOnCheckedChangeListener((v, b) -> {
            player.setSustain(b);
            prefs.edit().putBoolean("pedal", b).apply();
        });
        CheckBox quickMode = new CheckBox(this);
        quickMode.setText("QWERTY 탭 모드: MIDI 음 길이 대신 키를 짧게 누르기");
        quickMode.setTextColor(Color.WHITE);
        quickMode.setChecked(player.tapMode());
        root.addView(quickMode);
        quickMode.setOnCheckedChangeListener((v, checked) -> {
            player.setTapMode(checked);
            prefs.edit().putBoolean("tap_mode", checked).apply();
        });
        Button tapLength = button("키 입력 길이 변경 (기본 40ms)");
        root.addView(tapLength);
        tapLength.setOnClickListener(v -> selectTapDuration());

        hint(root, "기본 61건반은 C2~C7의 Virtual Piano 배열입니다. 게임마다 배열이 다르면 아래에서 수정할 수 있습니다.");
        gap(root, 12);

        TextView mappings = label("건반 프리셋", 19, Color.WHITE);
        mappings.setTypeface(null, 1);
        root.addView(mappings);
        mapLabel = label("", 11, 0xFF94A5C3);
        root.addView(mapLabel);
        LinearLayout mapRow = horizontal();
        Button edit = button("배열 편집");
        Button reset = button("기본값 복구");
        mapRow.addView(edit, new LinearLayout.LayoutParams(0, dp(48), 1));
        mapRow.addView(reset, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(mapRow);
        edit.setOnClickListener(v -> editLayout());
        reset.setOnClickListener(v -> {
            player.setLayout(PianoLayout.DEFAULT);
            prefs.edit().putString("layout", PianoLayout.DEFAULT).apply();
            refresh();
        });
        gap(root, 16);

        Button floating = button("3 · 로블록스 위에 플로팅 플레이어 띄우기");
        root.addView(floating);
        floating.setOnClickListener(v -> startOverlay());
        Button test = button("테스트 C4 (t 키)");
        root.addView(test);
        test.setOnClickListener(v -> input.testMiddleC());

        gap(root, 14);
        TextView hardwareHeading = label("QWERTY 하드웨어 호환성 / 진단", 19, Color.WHITE);
        hardwareHeading.setTypeface(null, 1);
        root.addView(hardwareHeading);
        Button profile = button("가상 키보드 프로필 (USB 1개 권장)");
        root.addView(profile);
        profile.setOnClickListener(v -> selectKeyboardProfile());

        Button localTest = button("① Android 앱에서 HID 키 수신 검사");
        root.addView(localTest);
        localTest.setOnClickListener(v -> {
            if (localHidTest != null) localHidTest.setText("C4 키 수신 대기 중... (1초 후 전송)");
            ui.postDelayed(() -> input.testMiddleC(), 700);
        });
        localHidTest = label("진단 대기 중: C4를 누르면 Android가 인식했는지 표시", 12, 0xFFABCFEB);
        root.addView(localHidTest);

        Button gameTest = button("② 게임용 QWERTY T-Y-U 테스트 (1초 후)");
        root.addView(gameTest);
        gameTest.setOnClickListener(v -> input.testQuickKeys());

        Button copyDiag = button("③ HID 진단 정보 복사");
        root.addView(copyDiag);
        copyDiag.setOnClickListener(v -> {
            String report = input.diagnostics() +
                    "App test: " + localHidTest.getText() + "\n" +
                    "Tap mode=" + player.tapMode() + " durationMs=" + player.tapDurationMs() + "\n" +
                    player.playbackDiagnostics();
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("QWERTY diagnostics", report));
            toast("진단 정보를 복사했습니다");
        });
        diagnosticInfo = label("", 12, 0xFF9CB5D4);
        root.addView(diagnosticInfo);
        hint(root, "테스트: 앱에서 HID 수신 확인 → Roblox를 완전히 종료한 뒤 다시 시작 → 게임의 QWERTY 모드에서 T/Y/U 입력 확인. 게임마다 모바일 키보드 입력 지원 여부가 다를 수 있습니다.");

        errorLabel = label("", 12, 0xFFFFC0B4);
        root.addView(errorLabel);
        hint(root, "주의: Android가 UHID 키보드를 인식해도 Roblox의 모든 게임이 모바일 키보드 입력을 처리하는 것은 아닙니다. 텍스트 채팅이 되는 것만으로는 확인할 수 없습니다.");
        hint(root, "원본 HuMIDI의 음악 재생 아이디어에서 영감을 받은 독립 Android 구현이며, 게임별 호환은 아직 실기기 검증이 필요합니다.");
        setContentView(scroll);
        input.requestOrConnect();
    }

    private void transpose(int offset) {
        int v = Math.max(-24, Math.min(24, player.transpose() + offset));
        player.setTranspose(v);
        prefs.edit().putInt("transpose", v).apply();
        refresh();
    }
    private void pickMidi() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, FILE_PICK);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FILE_PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = uri.getLastPathSegment() == null ? "선택한 MIDI" : uri.getLastPathSegment();
        new Thread(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IllegalArgumentException("파일을 열 수 없습니다");
                MidiParser.Song song = MidiParser.parse(stream);
                runOnUiThread(() -> {
                    player.setSong(song, name);
                    toast("MIDI 불러오기 완료 · " + song.noteCount + " notes");
                    refresh();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> toast("MIDI 오류: " + ex.getMessage()));
            }
        }, "midi-loader").start();
    }
    private void editLayout() {
        EditText editor = new EditText(this);
        editor.setText(prefs.getString("layout", PianoLayout.DEFAULT));
        editor.setTextSize(14);
        editor.setSingleLine(false);
        editor.setSelectAllOnFocus(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(18), dp(8), dp(18), 0);
        wrap.addView(editor);
        new AlertDialog.Builder(this)
                .setTitle("C2~C7, 정확히 61개 문자")
                .setMessage("낮은 음부터 입력. 대문자 또는 !@#$%^&*() = Shift 조합")
                .setView(wrap)
                .setPositiveButton("저장", (d, w) -> {
                    String text = editor.getText().toString().replaceAll("\\s+", "");
                    try {
                        player.setLayout(text);
                        prefs.edit().putString("layout", text).apply();
                        refresh();
                    } catch (Exception ex) { toast("배열 오류: " + ex.getMessage()); }
                })
                .setNegativeButton("취소", null).show();
    }
    private void selectTapDuration() {
        int[] ms = {25, 40, 60, 90, 140};
        String[] items = {"25ms (빠름)", "40ms (기본)", "60ms", "90ms", "140ms (느림)"};
        new AlertDialog.Builder(this).setTitle("QWERTY 누름 시간")
                .setItems(items, (dialog, which) -> {
                    player.setTapDurationMs(ms[which]);
                    prefs.edit().putInt("tap_ms", ms[which]).apply();
                    toast("키 누름 " + ms[which] + "ms");
                }).show();
    }

    private void selectKeyboardProfile() {
        String[] modes = {
                "USB HID 키보드 1개 (추천 / 가장 단순한 방식)",
                "USB HID 키보드 4개 (화음 우선)",
                "VIRTUAL HID 키보드 1개 (호환성 대안)",
                "VIRTUAL HID 키보드 4개 (실험용)"
        };
        new AlertDialog.Builder(this)
                .setTitle("QWERTY 가상 키보드 방식")
                .setMessage("프로필을 바꾼 다음 Roblox를 완전히 종료하고 다시 시작하세요. MidiConnect는 사용하지 않습니다.")
                .setItems(modes, (dialog, which) -> {
                    int count = (which % 2 == 0) ? 1 : 4;
                    int bus = which < 2 ? UhidProtocol.BUS_USB : UhidProtocol.BUS_VIRTUAL;
                    input.setProfile(count, bus);
                    toast("설정 저장됨. UHID 등록 후 Roblox를 재실행하세요.");
                }).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Observe what Android delivered into an actual Activity (not a text field).
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_T) {
            InputDevice device = event.getDevice();
            if (device != null && device.getName().startsWith(UhidKeyboard.NAME_PREFIX)) {
                if (localHidTest != null) {
                    localHidTest.setText("Android HID 입력 수신 성공: T key / id=" +
                            event.getDeviceId() + " source=0x" +
                            Integer.toHexString(event.getSource()) +
                            " / " + device.getName());
                }
                // Do not accidentally trigger a focused button.
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("다른 앱 위에 표시 권한을 허용한 뒤 다시 눌러주세요");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 302);
        }
        try {
            startForegroundService(new Intent(this, OverlayService.class));
            toast("로블록스를 실행해 플로팅 창에서 테스트하세요");
        } catch (Exception e) { toast("플로팅 창 시작 실패: " + e.getMessage()); }
    }
    private void refresh() {
        if (status == null) return;
        status.setText("Shizuku: " + input.status() + (input.isReady() ? " ✓" : ""));
        if (diagnosticInfo != null) {
            diagnosticInfo.setText("설정: " + input.keyboardCount() + "개 / " +
                    (input.keyboardBus() == UhidProtocol.BUS_USB ? "USB" : "VIRTUAL") +
                    " · Android 인식 " + input.registeredKeyboardCount() + "개 · " +
                    (player.tapMode() ? "QWERTY 탭 " + player.tapDurationMs() + "ms" : "QWERTY 홀드"));
        }
        midiName.setText(player.filename() + "  (" + player.notes() + " notes)");
        time.setText(PlayerController.pretty(player.positionUs()) + " / " +
                PlayerController.pretty(player.durationUs()));
        playButton.setText(player.isPlaying() ? "Ⅱ 일시정지" : "▶ 재생 / 계속");
        speedLabel.setText(String.format(Locale.US, "속도  %.2f×", player.speed()));
        transposeLabel.setText(String.format(Locale.US, "조옮김 %+d", player.transpose()));
        mapLabel.setText(prefs.getString("layout", PianoLayout.DEFAULT));
        errorLabel.setText(player.playbackSummary() +
                (player.error().isEmpty() ? "" : "\n" + player.error()) +
                (player.warning().isEmpty() ? "" : "\n" + player.warning()));
    }
    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }
    @Override protected void onPause() {
        ui.removeCallbacks(ticker);
        super.onPause();
    }
    private int dp(int px) { return (int)(px * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setPadding(0, dp(5), 0, dp(7));
        return v;
    }
    private void hint(LinearLayout parent, String text) {
        parent.addView(label(text, 12, 0xFF94A5C3));
    }
    private void gap(LinearLayout parent, int height) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }
    private Button button(String title) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF304C74);
        shape.setCornerRadius(dp(12));
        b.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        b.setLayoutParams(lp);
        return b;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
