package dev.gaong.robloxmidi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dependency-free Standard MIDI File (SMF) 0/1 parser with tempo changes,
 * running status, multiple tracks, note-on/note-off and CC64 sustain.
 * Does not execute SysEx or read network resources.
 */
public final class MidiParser {
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final int MAX_EVENTS = 350_000;

    public static final class Event {
        public final long us;
        public final int note;
        public final boolean down;
        public final boolean pedal;
        public Event(long us, int note, boolean down, boolean pedal) {
            this.us = us;
            this.note = note;
            this.down = down;
            this.pedal = pedal;
        }
    }

    public static final class Song {
        public final List<Event> events;
        public final long durationUs;
        public final int noteCount;
        public Song(List<Event> events, long durationUs, int noteCount) {
            this.events = events;
            this.durationUs = durationUs;
            this.noteCount = noteCount;
        }
    }

    private static final class Raw {
        final long tick;
        final int order, kind, note, tempo;
        Raw(long tick, int order, int kind, int note, int tempo) {
            this.tick = tick;
            this.order = order;
            this.kind = kind;
            this.note = note;
            this.tempo = tempo;
        }
    }

    private static final class Reader {
        final byte[] bytes;
        int pos;
        final int end;
        Reader(byte[] bytes, int pos, int end) {
            this.bytes = bytes; this.pos = pos; this.end = end;
        }
        int u8() throws IOException {
            if (pos >= end) throw new IOException("Truncated MIDI");
            return bytes[pos++] & 255;
        }
        int u16() throws IOException { return (u8() << 8) | u8(); }
        long u32() throws IOException {
            return ((long)u8() << 24) | ((long)u8() << 16) | (u8() << 8) | u8();
        }
        String id() throws IOException {
            return "" + (char)u8() + (char)u8() + (char)u8() + (char)u8();
        }
        long vlq() throws IOException {
            long n = 0;
            for (int i = 0; i < 4; i++) {
                int v = u8();
                n = (n << 7) | (v & 0x7F);
                if ((v & 0x80) == 0) return n;
            }
            throw new IOException("Invalid MIDI variable-length quantity");
        }
        void skip(long length) throws IOException {
            if (length < 0 || length > end - pos) throw new IOException("Truncated MIDI event");
            pos += (int)length;
        }
    }

    public static Song parse(InputStream input) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = input.read(block)) != -1) {
            if (buf.size() + n > MAX_BYTES) throw new IOException("MIDI larger than 12 MB");
            buf.write(block, 0, n);
        }
        return parse(buf.toByteArray());
    }

    public static Song parse(byte[] bytes) throws IOException {
        Reader r = new Reader(bytes, 0, bytes.length);
        if (!"MThd".equals(r.id())) throw new IOException("Not a Standard MIDI File");
        long headerLen = r.u32();
        if (headerLen < 6 || headerLen > r.end - r.pos) throw new IOException("Invalid MIDI header");
        int format = r.u16(), tracks = r.u16(), ppq = r.u16();
        r.skip(headerLen - 6);
        if (format > 1 || tracks < 1 || tracks > 128)
            throw new IOException("Only MIDI format 0/1 (up to 128 tracks) is supported");
        if ((ppq & 0x8000) != 0 || ppq == 0)
            throw new IOException("SMPTE MIDI timing is not supported");

        List<Raw> raw = new ArrayList<>();
        int sequence = 0;
        for (int track = 0; track < tracks; track++) {
            if (!"MTrk".equals(r.id())) throw new IOException("Missing MIDI track");
            long size = r.u32();
            if (size > r.end - r.pos) throw new IOException("Truncated MIDI track");
            Reader t = new Reader(bytes, r.pos, r.pos + (int)size);
            r.skip(size);
            long tick = 0;
            int running = 0;
            while (t.pos < t.end) {
                tick += t.vlq();
                int lead = t.u8();
                int status;
                int firstData = -1;
                if (lead < 0x80) {
                    if (running == 0) throw new IOException("Missing MIDI running status");
                    status = running; firstData = lead;
                } else {
                    status = lead;
                    if (status < 0xF0) running = status;
                    else running = 0;
                }
                if (status == 0xFF) {
                    int type = t.u8();
                    long len = t.vlq();
                    if (type == 0x51 && len == 3) {
                        int tempo = (t.u8() << 16) | (t.u8() << 8) | t.u8();
                        if (tempo > 0) raw.add(new Raw(tick, sequence++, 0, 0, tempo));
                    } else {
                        t.skip(len);
                    }
                    if (type == 0x2F) break;
                } else if (status == 0xF0 || status == 0xF7) {
                    t.skip(t.vlq());
                } else if (status >= 0x80 && status < 0xF0) {
                    int channelType = status & 0xF0;
                    int a = firstData >= 0 ? firstData : t.u8();
                    int b = (channelType == 0xC0 || channelType == 0xD0) ? 0 : t.u8();
                    if (channelType == 0x90) {
                        raw.add(new Raw(tick, sequence++, b == 0 ? 1 : 2, a, 0));
                    } else if (channelType == 0x80) {
                        raw.add(new Raw(tick, sequence++, 1, a, 0));
                    } else if (channelType == 0xB0 && a == 64) {
                        raw.add(new Raw(tick, sequence++, b >= 64 ? 4 : 3, status & 0x0F, 0));
                    }
                } else {
                    throw new IOException("Unsupported MIDI status: " + status);
                }
                if (raw.size() > MAX_EVENTS) throw new IOException("Too many MIDI events");
            }
        }
        // Tempo at a tick is applied before that tick's notes. Note offs before ons.
        raw.sort(Comparator.comparingLong((Raw e) -> e.tick)
                .thenComparingInt(e -> e.kind).thenComparingInt(e -> e.order));
        List<Event> result = new ArrayList<>();
        long tick = 0;
        long micros = 0;
        int tempo = 500_000;
        int notes = 0;
        for (Raw e : raw) {
            long delta = e.tick - tick;
            micros += (delta * tempo) / ppq;
            tick = e.tick;
            if (e.kind == 0) tempo = e.tempo;
            else {
                boolean pedal = e.kind == 3 || e.kind == 4;
                boolean down = e.kind == 2 || e.kind == 4;
                result.add(new Event(micros, e.note, down, pedal));
                if (e.kind == 2) notes++;
            }
        }
        return new Song(result, micros, notes);
    }
}
