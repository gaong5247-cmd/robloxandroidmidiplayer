# Roblox MIDI Android v0.1.0 Preview

First Android-native, HuMIDI-inspired MIDI playback preview.

- Standard MIDI 0/1 parser with tempo changes, polyphonic notes and CC64.
- C2–C7 (61-note) Virtual Piano keyboard map, octave folding and transpose.
- Android Shizuku UserService keyboard injection: key down + key up.
- Floating controls usable over Roblox, playback speed and skipping.
- Custom editable 61-key mapping stored locally.

**Compatibility warning:** Built APK and passing unit tests do NOT prove Roblox accepts injected events. Test the C4 button in the desired game before relying on autoplay. Shizuku must be running and authorized.

**Signing:** This preview is a GitHub Actions debug-signed APK. Debug keystore may change between builds, which can require uninstalling the old preview before installing an update. There is no production signing key configured.

No Roblox game code was modified, and no Roblox account credentials are required.
