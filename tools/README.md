# WireFree protocol simulator

Run these commands from the repository root with Python 3.11 or newer.

```sh
python -m pip install cryptography
python -m unittest discover -s tools -v
```

The simulator defaults to a 440 Hz Opus tone when both the optional `opuslib`
package and its native `libopus` library are available. Install the library for
your OS and run `python -m pip install opuslib` to exercise decoding in the
apps. `--tone` explicitly selects this default. When Opus is unavailable or you
pass `--dummy`, the simulator sends random-length frames with the
`03 00 WIREFREE-DUMMY:` non-Opus marker. Use those frames for transport
statistics. Real decoders may reject them.

Keep pairing URIs and QR codes private. They contain the shared key.

## Test Windows without a phone

Start the Windows companion and copy its pairing URI. Run this on the same
machine or another machine on the tailnet. Replace the example URI with the
full copied URI and keep the quotes.

```sh
python tools/wirefree_sim.py fake-phone --uri 'wirefree://pair?...' --earpods
```

Check that the companion switches to `android-speaker` and `android-mic`. Play
audio and check for SPK packets in the simulator. Open a recorder on
`android-mic` and look for `wantMic: true` in the laptop HELLO. With Opus
available, the recording should contain the tone.

Type `earpods`, `busy`, or `muted` and press Enter to flip each phone flag.
Use the companion's mute control to exercise `toggleMute`. Quit the simulator
and check that the companion restores the original devices.

## Test Android without a laptop

Run on any tailnet machine reachable by the phone over UDP.

```sh
python tools/wirefree_sim.py fake-laptop --port 47391
```

Copy the printed pairing URI into Android, or scan the terminal QR after
installing `qrcode` with `python -m pip install qrcode`. The simulator uses the
local Tailscale MagicDNS name and IPv4 address when it can obtain them. If the
URI advertises loopback, restart with `--host YOUR_MAGICDNS_NAME --ip
YOUR_TAILSCALE_IPV4` using this machine's addresses. Each launch generates a
new pairing key. Pair again after restarting the simulator.

Type `mic on` or `mic off` and press Enter to change `wantMic`. Connect a
wired or USB-C headset and check the corresponding phone HELLO. By default
`wantSpk` follows the phone's headset and busy flags. Use `spk on`, `spk off`,
or `spk auto` to exercise the control explicitly. Type `toggleMute` and check
the phone reports its new mute state immediately and pauses or resumes MIC
as appropriate. Type `quit` to exit either simulator.

Both roles print peer HELLOs and report received audio count, sequence gaps
seen so far, reordered frames, incoming frame rate over the last report
interval, and marked dummy count. The totals reset on a new peer session.
The simulator generates and counts frames. It has no local audio playback or
capture.

## Reference vector

```sh
python tools/wirefree_sim.py vector
```

This prints the deterministic AES-GCM fixture and rewrites
[`test-vector.json`](test-vector.json) beside the script. The fixture's
`{"ts":0}` is for checking the codec. A live receiver requires a fresh
timestamp to establish a session.
