# wirefree for Windows

Run `build.ps1` with a .NET 8 SDK to create `dist/wirefree.exe` and copy the available `setup/*.ps1` files to `dist/setup`. Keep the setup folder beside the executable. Run `dotnet test` from this directory to exercise the protocol library.

Start the executable from its permanent location. It registers that path in the current user's Run key. Open the tray window to pair the phone or select **Set up audio devices**. The setup button and `--setup` launch `setup/install-audio.ps1` elevated. `--uninstall-names` launches `setup/uninstall-names.ps1` elevated.

## Audio devices and defaults

| Cable | Windows endpoint | Purpose |
|---|---|---|
| Hi-Fi Cable render | android-speaker | Windows playback toward the phone |
| Hi-Fi Cable capture | wirefree speaker link | Companion captures and encodes SPK |
| VB-CABLE capture | android-mic | Apps record the phone mic here |
| VB-CABLE render | wirefree mic link | Companion plays decoded MIC here |

The companion also recognizes the four original VB-Audio names. Set both Hi-Fi Cable endpoints to the same rate (48 kHz in setup). Speaker capture is converted to 48 kHz if Windows supplies a different mix rate. The cable itself requires matching endpoint rates.

Routing uses Core Audio and IPolicyConfig per console, multimedia and communications role. The companion writes the existing endpoint IDs to the current user's `%LOCALAPPDATA%\wirefree\defaults.json` before replacing any defaults. It restores those defaults when the phone goes offline, when quitting, and on startup after a crash. If an original device is unavailable, its restoration stays pending. A manual change while routed releases the affected role until the phone goes offline. Phone priority holds the existing routes and pauses audio. The companion polls for active recording sessions on android-mic about twice a second and also reacts to endpoint notifications.

## Interop checks still needed

- Pair with a real phone, including MagicDNS and IP fallback, and move the phone between Wi-Fi and cellular.
- Verify bidirectional audio, silence suppression, device rates, mic session detection, phone priority, mute confirmation and EarPods plug/unplug.
- With the signed cables installed, verify default switching, manual overrides, offline and crash recovery, clean exit, and the elevated setup and name-restore scripts. Driver installation was left to the setup path.
- Compare against `docs/test-vector.json` once Android supplies it.

## Wire contract edge cases

The v1 audio packet has no timestamp or silence marker. A receiver cannot distinguish missing packets from suppressed audio immediately. Windows tries FEC or PLC during a short gap, then outputs silence and re-buffers the next burst. The live audio timing still needs a phone check.

The session rule compares a new SID with the timestamp of the HELLO that established the current SID. A still-fresh later HELLO from a retired SID can satisfy that rule. A fresh captured HELLO can also satisfy it after a receiver restart, when the receiver has no prior session timestamp. The receiver implements the specified checks without changing the protocol.
