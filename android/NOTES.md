# wirefree for Android

Install the debug APK, keep Tailscale active on both devices, then scan or paste the pairing link shown by the Windows companion. Paste works on phones without Google Play services or when the scanner module is unavailable. Pairing starts the connection after microphone permission is granted. The mute control stays available while the connection is off.

## Android behavior

- The enabled service runs in the foreground and can be restarted by Android through `START_STICKY` after a process kill. Android may delay that restart. Force-stop and system restrictions can prevent it.
- After reboot and first unlock, the receiver shows **Tap to resume wirefree** if the connection was enabled. Tapping opens the activity and starts the service when it is visible. Android 14+ restricts microphone foreground services from boot, and Android 15+ also restricts media playback starts from boot. This app uses the same resume flow on every supported Android version. If notifications are disabled, open wirefree and turn **Enable connection** on.
- Add the **wirefree** tile from Quick Settings editing. It toggles the persisted mute setting, including when the connection is off. When muted it is active, labeled **Muted**, and shows a slashed mic. System UI controls the tile color. The app uses red for its muted control.
- Wirefree asks once about battery optimization. The running connection uses a partial wake lock for its audio and network loop while the screen is off. Battery and VPN behavior can still vary by device.
- **Detect phone calls** optionally enables the telephony listener. Audio mode, playback, and recording detection continue without that permission. Android anonymizes playback configurations, so the app uses the active-player count minus its own playing track. Device selection uses Android's per-stream routing preference.

## Build

Use JDK 17 and Android SDK 36. Set the SDK path in the ignored `local.properties`, then run `./gradlew test assembleDebug` from this directory. `./gradlew assembleRelease` produces an unsigned APK unless the ignored `keystore.properties` supplies `storeFile` (relative to this directory or absolute), `storePassword`, `keyAlias`, and `keyPassword`.

## Protocol interoperability

The fixed public test vector in `../docs/test-vector.json` is a phone-to-laptop HELLO. Its byte fields, including the eight-byte big-endian counter, are hex. The JVM test decrypts the vector and reproduces the packet byte for byte.

The microphone sequence advances for every 20 ms slot, including suppressed silence. With no silence marker or sequence rule in the contract, the receiver cannot tell initial silence from loss. It applies short FEC/PLC concealment, fades to silence, and buffers again when packets resume. Session freshness follows the contract's wall-clock window and strictly increasing timestamp rule.
