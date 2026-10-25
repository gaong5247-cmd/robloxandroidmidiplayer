package dev.gaong.robloxmidi;

import android.content.Context;
import android.view.KeyEvent;
import java.util.List;

/**
 * Precise monotonic-clock MIDI scheduler that survives Activity backgrounding
 * while OverlayService foreground service is active.
 */
public final class PlayerController {
    private static PlayerController instance;
    public static synchronized PlayerController get(Context c) {
        if (instance == null) instance = new PlayerController(c.getApplicationContext());
        return instance;
    }

    private final InputBridge bridge;
    private final KeyboardPulseEmitter pulses;
    private volatile boolean tapMode = true;
    private volatile int tapDurationMs = 40;
    private volatile long runGeneration;
    private final Object mutex = new Object();
    private volatile PianoLayout layout = new PianoLayout(PianoLayout.DEFAULT);
    private volatile MidiParser.Song song;
    private volatile String filename = "MIDI를 선택하세요";
    private volatile boolean fold = true;
    private volatile boolean sustain = false;
    private volatile int transpose = 0;
    private volatile double speed = 1.0;
    private volatile boolean playing, paused;
    private volatile long positionUs;
    private volatile String error = "";
    private volatile String lastWarning = "";
    private volatile String playbackState = "대기 중";
    private volatile long requestedKeyDowns;
    private volatile long deliveredKeyDowns;
    private volatile long skippedKeyDowns;
    private volatile long lastStopUs;
    private Thread worker;
    private int index;
    private long anchorNs;
    private long anchorUs;

    private PlayerController(Context c) {
        bridge = InputBridge.get(c);
        pulses = new KeyboardPulseEmitter(bridge);
    }

    public String filename() { return filename; }
    public boolean hasSong() { return song != null; }
    public boolean isPlaying() { return playing && !paused; }
    public boolean isPaused() { return paused && playing; }
    public double speed() { return speed; }
    public int transpose() { return transpose; }
    public boolean fold() { return fold; }
    public boolean sustain() { return sustain; }
    public boolean tapMode() { return tapMode; }
    public int tapDurationMs() { return tapDurationMs; }
    public void setTapMode(boolean value) {
        stop();
        tapMode = value;
    }
    public void setTapDurationMs(int ms) {
        tapDurationMs = Math.max(20, Math.min(150, ms));
    }
    public String error() { return error; }
    public String warning() { return lastWarning; }
    public String playbackState() { return playbackState; }
    public long requestedKeyDowns() { return requestedKeyDowns; }
    public long deliveredKeyDowns() { return deliveredKeyDowns; }
    public long skippedKeyDowns() { return skippedKeyDowns; }
    public long lastStopUs() { return lastStopUs; }
    public String playbackSummary() {
        return playbackState + " | 노트 " + deliveredKeyDowns + "/" + requestedKeyDowns
                + (skippedKeyDowns > 0 ? " | 누락 " + skippedKeyDowns : "")
                + (pulses.releaseFailures() > 0
                        ? " | 릴리스 오류 " + pulses.releaseFailures() : "");
    }
    public String playbackDiagnostics() {
        return "Playback state=" + playbackState + "\n"
                + "Playback position=" + pretty(positionUs()) + " / " + pretty(durationUs()) + "\n"
                + "Last stop position=" + pretty(lastStopUs) + "\n"
                + "MIDI file=" + filename + "\n"
                + "Note events delivered=" + deliveredKeyDowns
                    + " attempted=" + requestedKeyDowns
                    + " skipped=" + skippedKeyDowns + "\n"
                + "Tap release errors=" + pulses.releaseFailures()
                    + " last=" + pulses.releaseError() + "\n"
                + "Last warning=" + lastWarning + "\n"
                + "Last error=" + error + "\n";
    }
    public int notes() { return song == null ? 0 : song.noteCount; }
    public long durationUs() { return song == null ? 0 : song.durationUs; }
    public long positionUs() {
        synchronized (mutex) {
            if (playing && !paused) return Math.min(durationUs(), clockPositionLocked());
            return positionUs;
        }
    }

    public PianoLayout.Stroke stroke(int note) {
        return layout.forNote(note + transpose, fold);
    }

    public void setSong(MidiParser.Song value, String name) {
        stop();
        synchronized (mutex) {
            song = value;
            filename = name;
            positionUs = 0;
            index = 0;
            error = "";
            lastWarning = "";
            playbackState = "MIDI 준비됨";
            requestedKeyDowns = 0;
            deliveredKeyDowns = 0;
            skippedKeyDowns = 0;
            lastStopUs = 0;
        }
    }
    public void setLayout(String keys) { layout = new PianoLayout(keys); stop(); }
    public void setFold(boolean value) { fold = value; }
    public void setSustain(boolean value) { sustain = value; }
    public void setTranspose(int semitones) {
        transpose = Math.max(-24, Math.min(24, semitones));
    }
    public void setSpeed(double factor) {
        synchronized (mutex) {
            if (playing && !paused) {
                anchorUs = clockPositionLocked();
                anchorNs = System.nanoTime();
            }
            speed = Math.max(0.25, Math.min(3, factor));
        }
    }

    private long clockPositionLocked() {
        return anchorUs + (long)((System.nanoTime() - anchorNs) * speed / 1000.0);
    }
    private int findIndex(List<MidiParser.Event> events, long at) {
        int lo=0, hi=events.size();
        while (lo < hi) {
            int mid=(lo+hi) >>> 1;
            if (events.get(mid).us < at) lo=mid+1;
            else hi=mid;
        }
        return lo;
    }

    public void seek(long micros) {
        synchronized (mutex) {
            positionUs = Math.max(0, Math.min(durationUs(), micros));
            if (song != null) index = findIndex(song.events, positionUs);
            anchorUs = positionUs;
            anchorNs = System.nanoTime();
        }
        pulses.clear();
    }

    public void togglePlay() {
        if (!playing || (positionUs >= durationUs() && durationUs() > 0)) play();
        else if (paused) resume();
        else pause();
    }

    public void play() {
        synchronized (mutex) {
            if (song == null) {
                error = "MIDI 파일을 먼저 선택하세요";
                playbackState = "재생 거부";
                return;
            }
            if (!bridge.isReady()) {
                error = "Shizuku UHID 연결이 끊어졌습니다";
                playbackState = "재생 거부";
                return;
            }
            if (playing) { resume(); return; }
            error = "";
            lastWarning = "";
            playbackState = "재생 중";
            requestedKeyDowns = 0;
            deliveredKeyDowns = 0;
            skippedKeyDowns = 0;
            lastStopUs = 0;
            pulses.resetDiagnostics();
            if (positionUs >= song.durationUs) positionUs = 0;
            index = findIndex(song.events, positionUs);
            playing = true;
            paused = false;
            anchorUs = positionUs;
            anchorNs = System.nanoTime();
            long generation = ++runGeneration;
            worker = new Thread(() -> runPlayer(generation), "midi-scheduler");
            worker.start();
        }
    }

    public void pause() {
        synchronized (mutex) {
            if (!playing || paused) return;
            positionUs = clockPositionLocked();
            paused = true;
            playbackState = "일시정지";
        }
        pulses.clear();
    }

    private void resume() {
        synchronized (mutex) {
            if (!playing) return;
            anchorUs = positionUs;
            anchorNs = System.nanoTime();
            paused = false;
            playbackState = "재생 중";
            mutex.notifyAll();
        }
    }

    /**
     * Preserve the MIDI timeline when the Shizuku UserService disappears.
     * After a healthy keyboard reconnect, Play can continue from this position
     * rather than restarting the song at 0:00.
     */
    public void onInputDisconnected(String reason) {
        Thread old;
        synchronized (mutex) {
            if (!playing) return;
            if (!paused) {
                positionUs = Math.min(durationUs(), Math.max(0, clockPositionLocked()));
            }
            lastStopUs = positionUs;
            playing = false;
            paused = false;
            runGeneration++;
            playbackState = "키보드 연결 끊김";
            error = "Shizuku/UHID 연결 끊김 @ " + pretty(positionUs) + ": " + reason;
            lastWarning = "키보드 재연결 후 ▶ 버튼을 눌러 이어서 재생하세요";
            old = worker;
            worker = null;
            mutex.notifyAll();
        }
        if (old != null) old.interrupt();
        pulses.clear();
    }

    public void stop() {
        Thread old;
        synchronized (mutex) {
            playing = false;
            paused = false;
            runGeneration++;
            lastStopUs = positionUs;
            if (!"입력 오류로 중단".equals(playbackState)) playbackState = "사용자 정지";
            positionUs = 0;
            index = 0;
            mutex.notifyAll();
            old = worker;
            worker = null;
        }
        if (old != null) old.interrupt();
        pulses.clear();
    }

    private void runPlayer(long generation) {
        boolean completed = false;
        try {
            while (playing && generation == runGeneration) {
                MidiParser.Event event = null;
                long sleepMs = 5;
                synchronized (mutex) {
                    if (!playing || generation != runGeneration) break;
                    if (paused) {
                        try { mutex.wait(150); }
                        catch (InterruptedException ignored) { break; }
                        continue;
                    }
                    long now = clockPositionLocked();
                    positionUs = Math.max(0, Math.min(durationUs(), now));
                    if (index < song.events.size()) {
                        MidiParser.Event next = song.events.get(index);
                        if (next.us <= now) {
                            event = next;
                            index++;
                        } else {
                            sleepMs = Math.max(1, Math.min(12,
                                    (long)((next.us - now) / (1000.0 * speed))));
                        }
                    } else if (now >= durationUs()) {
                        completed = true;
                        break;
                    }
                }

                if (event == null) {
                    try { Thread.sleep(sleepMs); }
                    catch (InterruptedException ignored) { break; }
                    continue;
                }
                if (!playing || paused || generation != runGeneration) continue;

                if (event.pedal) {
                    if (sustain) {
                        boolean accepted = bridge.trySend(KeyEvent.KEYCODE_SPACE,
                                event.down, false);
                        if (!accepted) {
                            lastWarning = "서스테인 이벤트 일시 누락 @ "
                                    + pretty(positionUs);
                        }
                    }
                    continue;
                }

                PianoLayout.Stroke key = stroke(event.note);
                if (key == null) continue;
                if (event.down) requestedKeyDowns++;

                // A dense chord can exceed one USB keyboard's 6-key limit,
                // or combine natural/sharp notes with incompatible Shift.
                // Ordinary chord saturation must never terminate the song.
                boolean accepted;
                if (tapMode) {
                    if (!event.down) continue;
                    accepted = pulses.tap(key.code, key.shift, tapDurationMs);
                } else {
                    accepted = bridge.trySend(key.code, event.down, key.shift);
                }
                if (event.down) {
                    if (accepted) {
                        deliveredKeyDowns++;
                    } else {
                        skippedKeyDowns++;
                        lastWarning = "화음 처리 중 일부 건반 누락 @ "
                                + pretty(positionUs) + " (곡은 계속 재생)";
                    }
                }
            }
        } catch (Exception failure) {
            if (generation == runGeneration) {
                lastStopUs = positionUs;
                playbackState = "입력 오류로 중단";
                error = "재생 위치 " + pretty(positionUs)
                        + "에서 중단: " + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage();
            }
        } finally {
            boolean current;
            synchronized (mutex) {
                current = generation == runGeneration;
                if (current) {
                    playing = false;
                    paused = false;
                    positionUs = Math.min(durationUs(), positionUs);
                    lastStopUs = positionUs;
                    if (!"입력 오류로 중단".equals(playbackState)) {
                        playbackState = completed ? "곡 종료" : "재생 정지";
                    }
                    worker = null;
                }
            }
            if (current) pulses.clear();
        }
    }

    public static String pretty(long micros) {
        long seconds = micros / 1_000_000L;
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
