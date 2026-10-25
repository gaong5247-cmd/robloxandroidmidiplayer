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
    private long runGeneration;
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
            if (song == null) { error = "MIDI 파일을 먼저 선택하세요"; return; }
            if (!bridge.isReady()) { error = "Shizuku 서비스를 먼저 연결하세요"; return; }
            if (playing) { resume(); return; }
            error = "";
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
        }
        pulses.clear();
    }

    private void resume() {
        synchronized (mutex) {
            if (!playing) return;
            anchorUs = positionUs;
            anchorNs = System.nanoTime();
            paused = false;
            mutex.notifyAll();
        }
    }

    public void stop() {
        Thread old;
        synchronized (mutex) {
            playing = false;
            paused = false;
            runGeneration++;
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
        try {
            while (playing && generation == runGeneration) {
                MidiParser.Event event = null;
                long sleepMs = 5;
                synchronized (mutex) {
                    if (!playing || generation != runGeneration) break;
                    if (paused) {
                        try { mutex.wait(150); } catch (InterruptedException e) { break; }
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
                                    (long)((next.us-now) / (1000.0*speed))));
                        }
                    } else if (now >= durationUs()) {
                        break;
                    }
                }
                if (event != null) {
                    if (!paused && playing) {
                        if (event.pedal) {
                            if (sustain) bridge.send(KeyEvent.KEYCODE_SPACE, event.down, false);
                        } else {
                            PianoLayout.Stroke mapped = stroke(event.note);
                            if (mapped != null) {
                                if (tapMode) {
                                    // Most QWERTY piano games react to each fresh KeyDown,
                                    // not to a sustained physical key state.
                                    if (event.down) pulses.tap(mapped.code, mapped.shift, tapDurationMs);
                                } else {
                                    bridge.send(mapped.code, event.down, mapped.shift);
                                }
                            }
                        }
                    }
                } else {
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { break; }
                }
            }
        } catch (Exception ex) {
            error = "연주 중 입력 실패: " + ex.getMessage();
        } finally {
            boolean current;
            synchronized (mutex) {
                current = generation == runGeneration;
                if (current) {
                    playing = false;
                    paused = false;
                    positionUs = Math.min(durationUs(), positionUs);
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
