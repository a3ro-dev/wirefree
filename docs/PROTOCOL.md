# wirefree protocol v1

The single source of truth shared by the Android app (`android/`) and the Windows companion (`windows/`).
Change it only in this file, and change both sides together.

## Roles

- **Phone** (Android): owns the EarPods and the microphone. It decides mute and phone priority.
- **Laptop** (Windows): owns the two virtual endpoints `android-speaker` and `android-mic`, and the default-device switching.

## Transport

- UDP. The laptop listens on port **47391** on all interfaces.
- The phone sends to `host:47391`, where `host` comes from pairing: the laptop's Tailscale MagicDNS name, with its Tailscale IPv4 address as a fallback.
- The laptop replies to the source address of the **latest authenticated** packet, so the phone can roam (cellular to wifi) freely.
- Tailscale (WireGuard) gives the network path and the first layer of end-to-end encryption. The layer below is on top of that, so other devices on the tailnet can't talk to either side.
- Max datagram: 1200 bytes. All integers are big-endian.

## Pairing

1. The laptop generates a 32-byte random `K` once. It is stored protected by DPAPI (CurrentUser).
2. The laptop shows a QR code and a copyable text of the same URI:
   `wirefree://pair?v=1&h=<magicdns name>&ip=<tailscale ipv4>&p=47391&n=<laptop display name>&k=<base64url(K), no padding>`
3. The phone scans it (or pastes it) and stores `K` encrypted with an Android Keystore AES-GCM key that never leaves the Keystore.
4. Re-pairing on the laptop ("Forget phone") generates a new `K`, and every old phone stops working.

`K` never travels anywhere except inside that QR or text, and it is never logged.

## Keys

For each session, the sender picks a random 16-byte `sid` at start-up (one per process run and per network restart). Then:

```
key = HKDF-SHA256(ikm = K, salt = sid, info = "wirefree v1 " + dir, L = 32)
dir = "p2l" (phone→laptop) | "l2p" (laptop→phone)
```

The cipher is AES-256-GCM with a 16-byte tag.
The nonce is the 12 bytes `0x00000000 || ctr(8)`. `ctr` starts at 0 for every new `sid` and goes up by 1 for each packet. It never wraps: at 2^62 the sender starts a new `sid`.
Because every `sid` gets its own key, a (key, nonce) pair is never reused, even across restarts.

## Packet

```
offset size field
0      1    version = 1
1      1    type
2      16   sid
18     8    ctr
26     n    ciphertext || tag(16)
```

AAD = bytes 0..25 (the whole header). Plaintext = the type-specific payload.

### Receiver rules (both sides, apply them in this order)

1. Drop the packet if it is shorter than 42 bytes, if `version != 1`, or if the type is unknown.
2. Derive (and cache) the key for `sid`. Decrypt, and drop on tag failure. Don't log the packet contents.
3. **Sessions.** The receiver keeps one `currentSid` and `currentSessionTs` for the peer.
   - If `sid == currentSid`, apply the replay window: keep the highest `ctr` seen plus a 1024-bit sliding bitmap, and drop duplicates and anything older than the window.
   - If `sid != currentSid`, accept it only when `type == HELLO` and its payload `ts` satisfies `|now - ts| <= 120 s` and `ts > currentSessionTs`. If so, switch `currentSid`, reset the replay window, and set `currentSessionTs = ts`. Otherwise drop the packet.
   This stops replays of old sessions: their HELLO is either stale or older than the current session.
4. Laptop only: remember the packet's source `ip:port` as the phone's address.

Each side starts its own session by sending HELLO first, and keeps sending HELLO every 1 s.

## Types

| type | name | direction | payload |
|------|------|-----------|---------|
| 0x01 | HELLO  | both | UTF-8 JSON, see below |
| 0x02 | CMD    | both | UTF-8 JSON `{"cmd":"mute"\|"unmute"\|"toggleMute"}` |
| 0x10 | MIC    | phone→laptop | `seq u32` + one Opus packet (48 kHz, mono, 20 ms) |
| 0x11 | SPK    | laptop→phone | `seq u32` + one Opus packet (48 kHz, stereo, 20 ms) |

### HELLO, phone → laptop (every 1 s, and right away on any change)

```json
{"ts":1732300000000,"role":"phone","name":"Pixel 8","earpods":true,"muted":false,"busy":false,"micLive":true,"app":"1.0.0"}
```
- `ts`: unix time in ms.
- `earpods`: a wired or USB-C headset output is connected (`TYPE_USB_HEADSET`, `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`).
- `muted`: the user muted the mic on the phone. The phone is the source of truth for mute.
- `busy`: phone priority is active (a call is in progress, another app is playing or recording audio, or our mic capture is being silenced).
- `micLive`: the phone is capturing and sending MIC right now.

### HELLO, laptop → phone (every 1 s, and right away on any change)

```json
{"ts":1732300000000,"role":"laptop","name":"akshat-laptop","wantMic":true,"wantSpk":true,"app":"1.0.0"}
```
- `wantMic`: at least one app on Windows is recording from `android-mic` (checked through its active capture sessions). The phone opens its mic **only** while `wantMic && !muted && !busy`, so the phone mic is never held when nobody is listening.
- `wantSpk`: the laptop is routing to `android-speaker` and will send SPK, which means the phone reported `earpods && !busy`.

### CMD

The laptop tray can send `toggleMute`. The phone applies it and confirms with an immediate HELLO.

## Audio

- Opus through Concentus on both sides (Java: `io.github.jaredmdobson:concentus`, C#: `Concentus` on NuGet), so the encoder and decoder behave the same.
- MIC: 48 kHz mono, 20 ms frames, VOIP application, 32 kbps, in-band FEC on, expected packet loss 10%.
- SPK: 48 kHz stereo, 20 ms frames, AUDIO application, 128 kbps, FEC on.
- Silence: while the source is digitally silent (peak < -70 dBFS for 200 ms), the sender sends nothing. The receiver plays silence through the gap.
- Jitter buffer on the receiver: 60 ms target, 200 ms max. Late packets are dropped. A missing packet is concealed with the decoder's PLC, or with FEC when the next packet is already there. Order comes from `seq`.

## Liveness

- A peer is **online** while a valid HELLO has arrived in the last 3 s.
- When the phone goes offline, the laptop restores the user's original default devices within 3 s.

## Laptop routing table

| phone state | default output (console + multimedia + communications) | default input |
|---|---|---|
| online, `earpods && !busy` | `android-speaker` | `android-mic` |
| online, no earpods | user's original output (laptop speakers) | `android-mic` |
| online, `busy` (phone priority) | keeps whatever it was; the laptop stops sending SPK, so laptop audio goes quiet instead of jumping to the speakers in public | keeps whatever it was; MIC pauses |
| offline | user's original output | user's original input |

"User's original" means the defaults saved before wirefree first changed them. They are saved to disk, so a crash still restores them on the next start.
If the user picks a different default device by hand, wirefree adopts it as the new "original" and doesn't fight them.

## Known limits (v1)

- **Receiver restart.** A receiver that restarts loses `currentSessionTs`, so a HELLO captured in the last 120 s could reopen an old session. Capturing one requires being a node on your own tailnet, because Tailscale encrypts everything on the wire. A challenge-response session start is planned for v2.
- **Reordering.** Each side ignores a HELLO older than the newest one it has seen, and only moves the reply address on a higher counter, so a late packet can't roll back mute/busy state or the route.
