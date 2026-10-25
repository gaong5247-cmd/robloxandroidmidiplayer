# Roblox MIDI Android v0.2.0 — Physical-Style UHID Keyboard Preview

**Fixes the original architecture that typed into Roblox chat but not in-game pianos.**

### Major change

Replaced `InputManager.injectInputEvent()` with **four Linux UHID virtual USB keyboard devices** registered via privileged Shizuku UserService. Key events are now sent as kernel HID keyboard reports (UHID_CREATE2/UHID_INPUT2), not as synthetic `KeyEvent` objects.

- Supports up to **24 concurrent plain key slots**, across four separate 6-key HID keyboards.
- Independently groups shifted and unshifted keys to reduce QWERTY modifier collisions.
- Explicit up/down HID reports; reference counting prevents duplicate notes from releasing too early.
- Device status displays the number of UHID keyboards Android enumerated.
- C4 in-game diagnostic: open piano and test `t` before MIDI autoplay.
- UHID protocol serializer tests added to existing MIDI parser/keymap tests.

### Important requirements

- Shizuku running and authorized. Shell UID must be permitted to open `/dev/uhid`.
- Android 8.0 or later; on Android 8–10, non-rooted Shizuku generally requires a PC to start via ADB. Android 11+ supports wireless debugging start.
- Not all manufacturers allow shell access to `/dev/uhid`; the app displays an error rather than silently falling back to ineffective synthetic keys.
- Roblox's own game scripts may still reject keyboard input on mobile. Verify C4 inside the piano game; **no actual Roblox gameplay was available to CI**.
- Android may aggregate Shift across virtual keyboards; some complex mixed-modifier chords need further testing.
- Preview APK is CI debug-signed; signature may differ between builds and require uninstalling the old preview.

### Acknowledgements

Research influenced by [EDLLT/RobloxAndroidMidi](https://github.com/EDLLT/RobloxAndroidMidi) (GPL-3.0); their implementation is not copied. Our UHID byte serialization follows the public Linux userspace ABI described in `linux/uhid.h`. HuMIDI inspired the player concept.

Use in accordance with each game's rules for automated playing.
