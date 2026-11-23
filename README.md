# wirefree

Make your wired earphones wireless, for your laptop.

Plug your earphones (USB-C EarPods, any wired headset) into your Android phone. Your Windows laptop gets two new sound devices, **android-speaker** and **android-mic**. Laptop audio plays in your earphones, and the earphone mic is your laptop mic, wherever you are: phone on cellular, laptop on cafe wifi, it doesn't matter.

- **Always connected.** It runs over Tailscale, so there's nothing to pair each time, no Bluetooth and no same-wifi requirement.
- **Follows your earphones.** EarPods plugged in: laptop audio goes to them. Unplugged: the laptop uses its own speakers, and the phone mic stays your laptop mic.
- **Phone has priority.** A call, a voice note or a video on the phone pauses the laptop stream, and it comes back by itself afterwards.
- **Clear mute.** One big button, plus the notification, a Quick Settings tile and the laptop tray. Muted is red everywhere.
- **Private.** The phone opens its mic only while something on the laptop is actually recording.
- **End-to-end encrypted, twice.** WireGuard (Tailscale), with AES-256-GCM inside it using a key only your two devices hold. Tampered and replayed packets are dropped. See [PROTOCOL.md](docs/PROTOCOL.md).

## Get it

Download `wirefree.apk` and `wirefree-windows.zip` from [Releases](https://github.com/a3ro-dev/wirefree/releases/latest), then follow the **[setup guide](docs/SETUP.md)** (about 10 minutes, once).

## Repo

| path | what |
|---|---|
| `android/` | phone app (Kotlin, Jetpack Compose) |
| `windows/` | tray app (C# .NET 8) and `setup/` driver scripts |
| `tools/` | Python reference implementation and simulators: test either side without the other |
| `docs/` | protocol, setup guide, shared test vector |

Virtual audio cables by [VB-Audio](https://vb-audio.com/Cable/) (donationware, downloaded from their site at setup and never redistributed here).
