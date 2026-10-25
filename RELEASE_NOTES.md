# Roblox Android MIDI Player v0.3.0 — QWERTY Compatibility Preview

**QWERTY ONLY. This release does not implement or require MidiConnect.**

## Changes from v0.2

- Replaced "4 virtual keyboards + long key hold" defaults with **1 virtual USB HID keyboard + 40ms key tap**, based on an existing Android Roblox QWERTY player implementation.
- Added user-selectable **USB 1 / USB 4 / VIRTUAL 1 / VIRTUAL 4** UHID configurations.
- Real UHID registration is now resettable through Shizuku, avoiding the need to manually restart the Shizuku daemon when testing device profiles.
- Added **rapid retrigger scheduling** for repeated MIDI notes; configurable 25–140ms key tap duration and optional sustain/hold mode.
- Added an **on-device key event reception test** that detects whether the Activity actually receives its own QWERTY T key from the named UHID device, instead of only checking the service returned success.
- Added the 3-key T-Y-U keyboard test in the floating controls, delayed slightly after touch to avoid keyboard/game focus confusion.
- Added Android InputDevice enumeration and a copyable diagnostic report; clear error states for UHID/OS registration problems.
- Updated HID keyboard descriptor with LED output support, bus selection and blocking device writes.

## Test order

1. Start Shizuku and approve authorization.
2. The app must show **Android 인식 1/1**.
3. Run the app's **① Android 앱에서 HID 키 수신 검사**. A pass should say **Android HID 입력 수신 성공**.
4. With the virtual keyboard already registered, **force-stop and reopen Roblox**. Enable the game's normal QWERTY keyboard input; do not focus chat.
5. Use the floating overlay **QWERTY T-Y-U 입력 시험**. Look for three notes, then try MIDI playback.

**Important:** Not all Roblox piano experiences recognize external keyboards on the Android client. A local Android HID reception pass does not prove a game receives a key. If a real physical USB/Bluetooth keyboard also fails in the same Roblox piano, this is likely a game/mobile support limitation. No external remote server or root-based bypass is involved.

Release APK is a GitHub Actions debug-signed experimental preview. Device tests still needed.
