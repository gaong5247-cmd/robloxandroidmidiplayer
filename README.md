# Roblox Android MIDI Player

**Experimental Android HuMIDI-inspired MIDI player using Linux UHID hardware-style keyboards via Shizuku.**

## Why UHID?

The v0.1 preview sent synthetic Android `KeyEvent` objects through `InputManager.injectInputEvent`. On Roblox mobile, these can appear in chat text fields without generating the keyboard events used by piano games.

**v0.2** opens `/dev/uhid` from a Shizuku UserService with shell/root identity and registers **four virtual USB HID keyboards**. MIDI note events are translated into *physical-style HID reports* for the Android input system. This approach is based on publicly documented Linux UHID ABI behavior and on research of the existing [EDLLT/RobloxAndroidMidi project](https://github.com/EDLLT/RobloxAndroidMidi). No code is copied from that GPL-3.0 project.

## Features

- Standard MIDI 0/1 file parser: tempo changes, multiple tracks, note lengths, CC64 sustain.
- Default **61-note Roblox Virtual Piano keymap (C2–C7)**, plus editable custom layout.
- QWERTY notes including Shift combinations, octave folding, transpose ±24 semitones.
- Four virtual keyboards × six key slots each for simultaneous chords.
- 0.25x–3.0x speed; play/pause/stop, seek.
- Floating controller over Roblox; Android foreground playback service.
- UHID registration diagnostic, C4 (default `t`) game test; stop releases all held keys.
- Entirely on device, no telemetry, no Roblox credentials.

### Installation and diagnosis

1. Install [Shizuku](https://shizuku.rikka.app/download/) and activate its service. Android **11+** can activate without a computer using Wireless Debugging; Android 8–10 generally require ADB from a computer unless rooted.
2. Install [latest v0.2 UHID preview APK](../../releases/latest) and grant Shizuku authorization.
3. The app should display **UHID 하드웨어 키보드 서비스 활성화 · Android 인식 4/4개**. If it reports `UHID_FAILED: Cannot open /dev/uhid`, your device/ROM blocks access for shell, so this backend cannot work on that phone.
4. Choose a `.mid` file, enable the floating controller, and switch to a Roblox piano.
5. Make sure the in-game piano's QWERTY/keyboard input option is enabled, close the Roblox chat field and tap **C4** in the floating controller. Check that C4 plays in the game. If no sound, Android registering a UHID keyboard alone doesn't prove the particular game supports keyboard input on mobile.
6. If the test plays, press ▶. Change the 61-key mapping for games with different layouts.

### Caveats

- **No guarantee across all Roblox experiences or Android manufacturers.** Shell permission and Roblox input support are separate requirements.
- A 6-key USB HID report has a six-key limit. Four devices give 24 simultaneous plain key slots, but different shift combinations can still interact in Android's aggregate modifier state.
- Some Roblox games intentionally handle only touch input on mobile, even if the phone recognizes a keyboard.
- Preview is debug-signed. On update if Android reports "App not installed" due to signature mismatch, uninstall old preview first; user preferences are not preserved by uninstall.
- Follow Roblox game rules when using automation.

### Build

GitHub Actions builds every commit to `main` with JDK 17, Gradle 8.9, and Android SDK 35, running the parser, keymap and UHID protocol tests before publishing the debug-signed APK in an experimental Release.

```sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

### Original projects and license

The app is independently written under MIT, inspired by [HuMIDI by smyGitt](https://github.com/smyGitt/HuMidi-Roblox-Piano-Autoplayer) (MIT), with research referencing [RobloxAndroidMidi by EDLLT](https://github.com/EDLLT/RobloxAndroidMidi) (GPL-3.0), without copying its code. The [Shizuku API](https://github.com/RikkaApps/Shizuku-API) is Apache-2.0. Linux userspace UHID definitions are a public kernel ABI.
