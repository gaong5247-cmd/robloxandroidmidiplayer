# Roblox MIDI Player for Android

A **HuMIDI-inspired Android reimplementation** for playing Standard MIDI Files through Roblox piano keyboard controls, built around the [Shizuku](https://shizuku.rikka.app/) UserService API. Does not require root, a touch-coordinate macro, or a keyboard accessory. It is not an official HuMIDI app, and the code is written from scratch.

**Status: experimental preview. Android APK compilation and unit tests are separate from real-world Roblox input compatibility.** Some games/client versions reject injected `KeyEvent` input entirely.

## Features

- Native Android 8.0+ (API 26), APK works on modern and older supported phones.
- Import `.mid` / Standard MIDI format 0/1 (including multiple tracks, variable tempo, sustain CC64).
- Default 61-note Roblox Virtual Piano map: MIDI C2 (36) ... C7 (96), with optional octave folding.
- Shift and QWERTY code mappings, independently emitted KeyDown / KeyUp via privileged Shizuku UserService.
- Polyphonic MIDI playback, speed 0.25x–3.0x, transpose ±24 semitones, seek ±10s.
- Simple persistent 61-character custom key map (adjust for different Roblox games).
- Foreground playback notification and draggable floating controls over Roblox.
- Floating **C4** key diagnostic for testing whether a particular Roblox piano accepts synthetic input.
- MIDI is processed entirely on-device, with no telemetry, server or account login.

## Quick start

1. Install/start **Shizuku** from its official site: https://shizuku.rikka.app/ .
2. On Android 11+, use Shizuku's wireless debugging pairing to start it without a PC. On Android 8–10, Shizuku without root generally needs ADB from a computer to start. The Shizuku server often needs restarting after each reboot.
3. Install the APK from [Releases](../../releases). Open app → **Shizuku 연결 / 권한 요청**, and approve its permission.
4. Select a `.mid` file. Tap **플로팅 플레이어 띄우기**, approve "display over other apps".
5. Open a Roblox piano that responds to keyboard input. **Test C4** in the overlay: the game should produce the same note as pressing its `t` keyboard key. If nothing happens, that Roblox environment likely rejects synthetic key events.
6. Use ▶ / ■ to play or stop. Shift-modified keys are injected individually. Some simultaneous notes share the same base physical key and cannot be perfectly represented on a QWERTY keyboard.

### Default 61-note map

C2..B2: `1 ! 2 @ 3 4 $ 5 % 6 ^ 7`  
C3..B3: `8 * 9 ( 0 q Q w W e E r`  
C4..B4: `t T y Y u i I o O p P a`  
C5..B5: `s S d D f g G h H j J k`  
C6..B6: `l L z Z x c C v V b B n`  
C7: `m`.

Custom layout requires **exactly 61 characters**; uppercase and shifted digit punctuation indicate a Shift modifier. Custom maps work for piano games using QWERTY keyboard layout; game-specific input behavior is not universally guaranteed. A future version could add named multiple profiles and 88-key mode; current version provides one saved custom profile.

## Building

Install JDK 17, Gradle 8.9 and Android SDK API 35:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Or use GitHub Actions → **Android Build and Preview Release**. Successful builds upload an artifact and update/create the preview release. A debug-signed APK is intended for testing, not Play Store production.

## Technical notes & limitations

- Shizuku **UserService** runs with shell (UID 2000) or root authority and reflects into `InputManager(Global).injectInputEvent`, with proper KeyDown / KeyUp and key source. Shizuku manages authorization; users must explicitly grant it.
- It cannot guarantee Roblox honors injected `KeyEvent` as true hardware input. This is an Android-to-Roblox compatibility limitation, not proof of successful injection.
- No Accessibility service, Shizuku-free injection, tap-coordinate macros, or unsafe root hacks.
- The default map covers 61 notes; high/low notes may be octave-folded. For 88-note pianos, a future dedicated layout is required.
- Debug builds use an ephemeral signing key on GitHub Actions. If Android reports a signature mismatch during upgrade, uninstall the old preview first.
- Playback precision depends on OS scheduling and game input handling. CC64→Space sustain is opt-in.
- Follow the Roblox game's rules for automation.

## Credits and licensing

Inspired by [HuMIDI by smyGitt](https://github.com/smyGitt/HuMidi-Roblox-Piano-Autoplayer) (MIT License, © 2026 smyGitt). This Android implementation is newly written, not the HuMIDI desktop binary port. It uses [Shizuku API](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0). See [LICENSE](LICENSE).
