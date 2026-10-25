# Roblox Android MIDI Player — Universal QWERTY

**Shizuku + Linux UHID-based Android MIDI player for Roblox piano experiences that accept physical QWERTY keyboard input.** This is a HuMIDI-inspired native Android app, not a touch-coordinate clicker. It does not require MidiConnect or send a game-specific protocol.

## v0.5.0: Shizuku / UHID reconnect reliability

The v0.4 application could lose keyboard input after Shizuku or its UserService disconnected, and pressing the ordinary Connect button could leave a stale service connection untouched. v0.5 changes the lifecycle:

1. **One device registration at startup:** the old UserService created the keyboard in its constructor and re-created it immediately after binding. Now it waits for the client and registers a single configured device once.
2. **In-place UHID recovery first:** a new **↻ 가상 키보드 다시 등록** action releases the old input device and rebuilds it while retaining the existing Shizuku session. This avoids unnecessary UserService unbind/rebind cycles.
3. **UserService death detection:** Binder death and disconnection callbacks invalidate the prior connection state. Old callback registrations are cleaned up, and a new UserService can be bound without restarting the entire app.
4. **Shizuku manager restart support:** when the Shizuku Binder is lost, playback stops with an explicit reason; when the Shizuku server comes back, the app attempts a fresh bind with permission verification.
5. **Android input-device watchdog:** every 2.5 seconds the app checks for missing UHID keyboard devices. Stable device disappearance triggers a bounded recovery attempt.
6. **Exponential backoff:** 750ms, 1.5s, 3s and 6s retry delays, followed by a clear manual action instead of infinite reconnect loops. The counter only resets after ~20 seconds of continuous healthy input-device enumeration, so a device that disconnects every seven seconds does not loop forever.
7. **Resumable MIDI timeline:** the song position is preserved on a connection loss. After recovery, ▶ resumes near the point where the virtual keyboard disconnected.
8. **Diagnostics:** the floating controls support copying Shizuku status, Binder generation, last disconnection cause, input-device count, and MIDI playback position.

### Download/install

Install the APK from [Releases](../../releases/tag/v0.5.0-reconnect-preview). This is a **preview/debug-signed** build. Because GitHub Actions uses a temporary debug signing key, Android may reject installing over an earlier preview; uninstall the previous version if needed, which clears app settings.

Android 8.0+ supported at the APK level. Shizuku must be installed and authorized:
- Android 11+: Shizuku can be activated locally through Wireless Debugging.
- Android 8–10: on unrooted phones, starting Shizuku normally requires a PC running ADB.
- Samsung/other OEMs may terminate background services under aggressive battery policy. If you find Shizuku *itself* stopped, restart it from Shizuku before opening the MIDI app. Optional battery optimization settings are linked from the player UI.

### How to recover if Roblox piano keys stop working

1. In the app, check the status: **Shizuku 서비스 종료됨**, **UHID 등록 실패**, and **Android 인식 0/1** are distinct diagnoses.
2. If Shizuku is running, press **↻ 가상 키보드 다시 등록** in the app or **↻ 키보드 복구** in the floating panel. Do not force-restart Shizuku first; the repair tries to keep it alive.
3. Wait until the app shows **Android 인식 1/1** in the default profile.
4. Make sure Roblox chat is closed, the piano is active and its QWERTY input option is enabled. Test **C4 (T)** or **T–Y–U**.
5. If the game stays silent after Android shows 1/1, **fully close Roblox and launch it again with the HID keyboard already registered.** Some game/client versions only re-check input modality when joining an experience.
6. If in-place repair still fails, use **UserService 완전 재연결** from the main player. This is a more disruptive fallback. On certain Shizuku/ROM versions, repeated UserService unbinds may fail until Shizuku is restarted.
7. When Shizuku itself has stopped, activate it from the Shizuku app; Android applications cannot unilaterally restore privileged Shizuku service identity without a new authorized start.
8. If this still breaks, use **진단 복사** in the floating player, then share the status text and whether it failed before or after seven seconds. The report intentionally omits Roblox credentials.

### QWERTY features

- MIDI Standard Format 0/1 with tempo changes, multiple tracks and note velocities interpreted as note-on/off; CC64 sustain optionally maps to Space.
- 61-key C2–C7 QWERTY piano layout with custom character mapping, octave folding, transpose ±24.
- USB HID one keyboard default; optional four-key or BUS_VIRTUAL HID alternatives.
- Short QWERTY taps (25–140ms, 40ms default) and optional note-length hold mode.
- Dense chord support without stopping the song at six-key overflow / conflicting Shift usage.
- Floating player for play/pause/stop, seek, speed, C4 test, T–Y–U, manual reconnect, diagnostics.

### Limitations

- **Android's UHID device being present does not guarantee Roblox accepts its keyboard events** in every piano experience, nor that a running experience will follow a newly attached keyboard without rejoining.
- Reconnecting a restarted Shizuku server requires Shizuku to be running again. Apps cannot silently restart a privileged Shizuku server whose ADB/session authorization has ended.
- The self-healing mechanisms are exercised by unit-tested policy and Android APK compilation, not yet verified on this user's Galaxy S26 and Roblox client. In the event of an intermittent OEM or Shizuku provider bug, manual restart may remain necessary.
- The APK is not signed for Play Store production. Debug signatures may change across CI builds.
- Follow each Roblox experience's rules regarding automated inputs.

### Build and tests

Runs on GitHub Actions with JDK 17, Gradle 8.9 and Android SDK 35:
```sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```
Unit tests cover MIDI parsing, key layouts, USB HID packet structure, mixed-Shift/rollover chords, 6,000 consecutive key actions and bounded reconnect backoff.

### Research, credits and license

- Original HuMIDI concept: [smyGitt/HuMidi-Roblox-Piano-Autoplayer](https://github.com/smyGitt/HuMidi-Roblox-Piano-Autoplayer) (MIT).
- QWERTY HID implementation research: [EDLLT/RobloxAndroidMidi](https://github.com/EDLLT/RobloxAndroidMidi) (GPL-3.0), referenced but not copied.
- Shizuku API: [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0).
- Android UHID behavior reference: [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0), researched but not copied.
- This repository's independently written code is MIT licensed.
