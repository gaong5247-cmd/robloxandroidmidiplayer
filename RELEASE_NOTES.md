# Roblox MIDI Android v0.4.0 — Stability Preview

**Fix for 7-second cutoffs under dense QWERTY MIDI playback.**

- Root cause candidate confirmed in v0.3 source: `UhidKeyboard` returned `false` on 6-key rollover or a mixed shifted/unshifted chord, and that error was propagated to `PlayerController` and terminated the entire song.
- New `UhidKeyState` allocator automatically reclaims incompatible held keys, sends explicit key-up, then sends the next key-down. No MIDI song should stop merely because a normal chord exceeds a single keyboard's limitations.
- Keeps the **working one-keyboard USB UHID QWERTY mode**; no MidiConnect or game-specific commands.
- A 4ms boundary on emergency release/repress gives Android time to observe the change.
- Repeated-note key-up/retrigger now has a brief 4ms gap to improve re-detection.
- Transient key saturation is treated as an individual skipped note, not a reason to abort the full MIDI.
- Added `재생 중 / 곡 종료 / 입력 오류로 중단` status, per-song note counters, stop timestamp and visible error display on floating controller.
- Floating controller has **진단 복사** to copy Shizuku UHID recovery stats and MIDI error position into the clipboard.
- Automated regression tests include mixed Shift/plain chords, seventh-key rollover and 6,000 stress-note transitions.

**Testing:** GitHub Actions compiles the Android APK and runs JUnit. Passing Actions does **not** verify real Roblox behavior on the user's Galaxy device. If the player still stops, use the new floating **진단 복사** button to capture the exact stop cause and position.

The APK is preview/debug signed. If an update fails due to signature mismatch, uninstall the older preview before installing (this resets settings). No credentials or Roblox accounts are accessed.
