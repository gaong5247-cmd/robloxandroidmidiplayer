# Roblox MIDI Android v0.5.0 — Shizuku reconnect fix (preview)

**This release targets "the piano works, then the connection drops, and reconnecting no longer restores notes."**

## Fixes

- Fixed an actual initial double-registration bug: the Shizuku UserService previously created a USB HID keyboard and immediately destroyed/recreated it after the Binder connected.
- Replaced the one-shot `bound` flag flow with a supervised service lifecycle: detect Shizuku binder death, UserService death, binding timeout, and missing Android InputDevices, then reconnect with stale callbacks invalidated.
- Added automatic retries with **bounded exponential backoff**. Repeated failures do not trigger infinite reconnect storms; manual recovery remains available.
- Prioritize **re-registering UHID in the existing healthy Shizuku UserService**, which reduces risk from Shizuku versions/ROMs that fail repeated unbind/rebind.
- Added **↻ 키보드 복구** to the floating controller, and **가상 키보드 다시 등록** to the main screen. Advanced **UserService 완전 재연결** is available only with confirmation.
- Background health monitoring checks whether Android still lists the virtual QWERTY keyboard (default expected **1/1**).
- Detect stale callbacks from older ServiceConnection instances by generation number; stale results cannot overwrite the current Binder.
- Preserves the MIDI playback position if the keyboard disconnects; play again after repair.
- Added **배터리 최적화 설정** shortcut as an optional step on Samsung and other Android devices if the Shizuku manager itself keeps stopping.
- Expanded copyable diagnostics: last lost reason, Shizuku status/UID, Binder generations, UHID registered count, retry attempts, and playback position.
- Existing MIDI, QWERTY only, 61-key layout, floating controls and mixed-Shift chord handling remain unchanged.

## Verify on-device

1. Enable Shizuku and grant the MIDI app permission.
2. The player should report **Android 인식 1/1**. Open Roblox afterwards.
3. Test a song. If input disappears, tap floating **↻ 키보드 복구** and check status.
4. Test C4 again. If still no sound despite **1/1**, fully close and reopen Roblox; then re-enter the piano.
5. If the connection drops again, use floating **진단 복사** and share the report. The root cause might be Shizuku being stopped by Android/OEM power management, UHID device removal or Roblox caching old external keyboard state.

## Test scope and caveats

Android build and unit tests are automated by GitHub Actions. **No live physical Roblox/Galaxy S26 test was performed in CI**, so this is an experimental fix, not a guarantee of uninterrupted playing on every device.

Debug-signed APK: upgrading from previous previews may require uninstalling them due to changed CI signing certificates. That resets app preferences and may require Shizuku authorization again.

No MidiConnect, no Roblox game-specific commands and no account credentials.
