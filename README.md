# Roblox Android MIDI Player — Universal QWERTY mode

An experimental **Android QWERTY MIDI player**, inspired by HuMIDI, intended for Roblox piano games which support physical keyboard input.

**No MidiConnect protocol, no Roblox modifications, no coordinate-tap macro.** We only use standard QWERTY keyboard scancodes through a Linux UHID virtual keyboard registered by a Shizuku UserService.

## Why the v0.3 compatibility reset?

The original v0.1 tried synthetic Android KeyEvents. They could type into Roblox chat but didn't trigger game controls. v0.2 added UHID, but started four keyboards and held notes for their full MIDI duration. In [EDLLT's Android Roblox MIDI player's QWERTY source](https://github.com/EDLLT/RobloxAndroidMidi), the author uses **one** UHID keyboard with short key taps and reports QWERTY mode working in supported piano experiences.

v0.3 changes the defaults to match that simpler input model and adds tests that can distinguish "Shizuku accepted an input request" from "Android actually delivered a physical-style key press". This is not a guarantee that Roblox will recognize the input in every experience or on every Android device.

## Features

- Android 8+ native APK; no desktop software required once Shizuku is started.
- MIDI format 0/1 playback with tempo changes, multiple tracks, chords, MIDI CC64.
- QWERTY Virtual Piano 61 notes from C2–C7 (MIDI 36–96), octave fold and transpose.
- **Short QWERTY taps by default (40 ms)** with configurable 25/40/60/90/140 ms options; hold-mode toggle for games that support note length.
- Fast note retriggering with delayed key-up scheduling that does not block the MIDI event loop.
- Default profile **one USB UHID virtual keyboard**. Optional USB 4-keyboard, VIRTUAL 1-keyboard, VIRTUAL 4-keyboard profiles for troubleshooting / chords.
- On-screen floating player above Roblox, T/Y/U QWERTY sequence, C4 key test.
- **Device diagnostic report** displays how many UHID input devices Android enumerated.
- **Local receive test**: while the player app is visible, sending a C4 HID note updates the app *only if Android dispatched the T KeyEvent from our named UHID device*, not merely because the service says "sent".
- No internet account, Roblox credentials, access to other apps' data, or midi-connect protocol.

## How to test Android and Roblox separately

1. Install [Shizuku](https://shizuku.rikka.app/download/), start it and grant authorization to this player. On Android 11+ you can start Shizuku with Android Wireless Debugging (no PC required); on Android 8–10 non-root Shizuku normally needs PC ADB to start.
2. Install APK v0.3 from the [GitHub Releases page](../../releases); see the **preview version**.
3. In the app, check "UHID 준비됨" and "Android 인식 1/1". If it says "0/1" or "UHID_FAILED", copy the diagnostic report and troubleshoot Android device creation before trying Roblox.
4. Tap **① Android 앱에서 HID 키 수신 검사**. The test emits MIDI C4, mapped to the QWERTY **T key**. The diagnostic status changes to "Android HID 입력 수신 성공" only if the real Activity receives a KeyEvent tagged with our UHID device.
5. **Completely close/force-stop Roblox after the keyboards are already registered. Restart Roblox** and enter a QWERTY-compatible piano experience. Avoid focusing the in-game chat box. Enable any in-game keyboard/QWERTY mode or playing focus.
6. From the floating player, tap **QWERTY T-Y-U 입력 시험**. The input sequence is delayed so the overlay click finishes first. Check that the game plays C4/D4/E4. If so, load a .mid and play.
7. If Android's local test succeeds but Roblox does not react, a software-only test cannot force a Roblox experience to accept mobile external keyboard events. Try **USB 1-keyboard** first, then VIRTUAL 1-keyboard, and **restart Roblox after profile changes**. If available, test whether the game recognizes a real Bluetooth/USB keyboard T key on the same device. No real-keyboard response indicates a game limitation rather than a MIDI mapping bug.

### QWERTY input profiles

| Profile | Keys/chords | Purpose |
|---|---:|---|
| USB 1 keyboard (default) | 6 HID usages | Follows EDLLT's compatible architecture |
| USB 4 keyboards | up to 24 usages | High polyphony, may be less compatible |
| Virtual 1 keyboard | 6 | Android HID bus alternative |
| Virtual 4 keyboards | up to 24 | Experimental |

For more robust piano songs, start with tap mode and 40ms. A 61-note mapping is not universal for all Roblox games; use the editable custom layout if needed.

### Troubleshooting decision tree

- Shizuku not connected → start and authorize it.
- UHID_FAILED opening /dev/uhid → ROM blocks shell, no QWERTY device created.
- Android shows 0/X keyboards → input device registration failure or delay; reconnect.
- Android local key reception fails → OS input stack issue; try USB vs VIRTUAL bus and 1 keyboard.
- Android local test succeeds but Roblox does not → check physical Bluetooth/USB keyboard and game keyboard UI, then restart Roblox with virtual device already registered. QWERTY automation cannot be guaranteed in Roblox experiences that only accept touch input on mobile.
- Test keys work but song doesn't → change tap/hold, key duration, speed and layout.

## Build

GitHub Actions run JDK 17 + Android SDK 35 + Gradle 8.9:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Every successful commit to `main` builds an **experimental prerelease APK**. It is debug-signed with a potentially ephemeral CI key, so uninstall an older version if Android reports a signature mismatch. Reinstall resets preferences.

## Research and credits

- Original HuMIDI idea: [smyGitt/HuMidi-Roblox-Piano-Autoplayer](https://github.com/smyGitt/HuMidi-Roblox-Piano-Autoplayer) (MIT).
- QWERTY architecture research: [EDLLT/RobloxAndroidMidi](https://github.com/EDLLT/RobloxAndroidMidi) (GPL-3.0). Code here is independently implemented.
- Linux UHID input packet structure: `linux/uhid.h`, with standard USB HID keyboard descriptor.
- Shizuku API: [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0).

Please respect the rules of any Roblox experience you use.
