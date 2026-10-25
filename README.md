# Roblox Android MIDI Player — Universal QWERTY

A HuMIDI-inspired Android MIDI player that sends **QWERTY USB HID keyboard events** to Roblox piano games through Shizuku. No MidiConnect, no remote server, no on-screen keyboard macro.

**v0.4.0 stability preview:** Addresses a reported problem where MIDI playback worked for about seven seconds and then stopped. A mixed Shift + normal chord or a seventh simultaneously held key used to make the entire playback worker exit. The virtual keyboard now releases a conflicting chord and continues, and the playback scheduler keeps a recoverable overflow from ending the song.

> Build/unit tests cannot verify the exact user's phone, Roblox experience or MIDI. This is an experimental fix pending device validation; brief note clipping is possible in extremely dense chords.

## Features

- Standard MIDI Format 0/1 support: tracks, tempo changes, Note On/Off, MIDI CC64.
- 61-key QWERTY keyboard layout, C2–C7, user-editable; octave folding and transposition.
- Shizuku UserService creating Linux UHID virtual keyboard devices via `/dev/uhid`, delivered as kernel HID reports rather than Android's text input.
- **One USB virtual keyboard is the recommended default** for broad Roblox compatibility; 4-keyboard and Linux Virtual bus profiles can be selected.
- **No "song stops because of chord" bug:** if the six-key HID report is full, or a black-key Shift modifier conflicts with an active unshifted key, the driver emits a release report and retriggers subsequent keys. This trades a shortened chord for continuous playback.
- 25–140 ms QWERTY taps (40 ms default) and optional MIDI-duration hold mode.
- Safely handles repeated notes, resets held keys on stop/pause and records asynchronous release failures.
- A floating play/pause/stop panel with C4 and T–Y–U tests, timeline, speed, **playback state, last error and a diagnostics-copy button**.
- Playback diagnostics include **current and last stop time, note attempts/successes/skips, and the last error**, and Shizuku diagnostics report **UHID recovery and preempted-key counts**.

## Install

1. Install/start [Shizuku](https://shizuku.rikka.app/download/) and authorize the player. Non-root Android 11+ has an on-device Wireless Debugging path to start Shizuku; Android 8–10 generally require initial PC ADB.
2. Download the experimental v0.4.0 stability APK from [Releases](../../releases).
3. Start the app → Shizuku connection → verify `UHID 준비됨 · Android 인식 1/1`.
4. Select a `.mid` song and open the floating overlay.
5. Fully reopen Roblox **after** the virtual keyboard was registered, focus an in-game QWERTY piano, close chat, and test C4 or T–Y–U.
6. Play the MIDI. The panel now shows whether playback is running, completed, manually stopped or stopped due to an actual input error.

## If it cuts off around 7 seconds again

1. Note whether the time continues advancing while sound stops. If it keeps moving, game input or note processing may be failing; if playback state says `입력 오류로 중단`, it is an app/backend exception.
2. In the floating player tap **진단 복사**. Paste the copied report into the issue/chat (it contains event counters and backend status; no credentials).
3. Look at `recoveries=` in service diagnostics. This counter increases when overlapping Shift or >6 held notes require an automatic release/repress; it should no longer abort playback.
4. If notes are still missing, try 25ms key taps and **USB 4 keyboards** for greater polyphony. Some Roblox/mobile configurations recognize one virtual keyboard more reliably than four. Restore USB 1 if 4 no longer works.
5. If the app reports `곡 종료` at about 7 seconds, the parsed MIDI timeline may itself be shorter than expected; the diagnostic report includes parsed duration. Try another MIDI.
6. If the app process disappears or Shizuku disconnects, check foreground notification, battery optimization policy and the Shizuku status on the phone.

The default is still broadly reusable QWERTY keyboard input, **not** Visual Pianos-specific and **not** MidiConnect. Some Roblox games do not support external keyboard input on mobile, so broad compatibility is a goal rather than a guarantee.

## Engineering and build

- Pure Java `UhidKeyState` models the HID six-key limit and Shift-group allocation. When necessary it reclaims the smallest active report and explicitly issues an all-up report before a new press.
- `UhidKeyboard` sends HID reports via shell/root Shizuku context; IO failures still stop playback with a visible error.
- `PlayerController` logs the stop position and counts; temporary key occupancy is handled without terminating playback.
- JUnit regressions cover Shift conflicts, seven-note chords, duplicate key references, and 6,000 densely spaced note presses.
- GitHub Actions uses JDK 17, Gradle 8.9, Android SDK 35:
  ```sh
  gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
  ```

The preview APK is debug-signed by CI. When upgrading between previews, you may need to uninstall the previous debug build if signing certificates differ (this resets local settings).

## Credits

Built as an independent Android reimplementation inspired by [HuMIDI](https://github.com/smyGitt/HuMidi-Roblox-Piano-Autoplayer) (MIT). QWERTY UHID research refers to [EDLLT/RobloxAndroidMidi](https://github.com/EDLLT/RobloxAndroidMidi) (GPL-3.0) and [scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0); their source is not copied. Shizuku is [Apache-2.0](https://github.com/RikkaApps/Shizuku-API). Please respect each Roblox game's rules for automated playing.
