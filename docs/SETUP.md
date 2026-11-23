# wirefree setup guide

About 10 minutes, once. After that you never connect anything by hand.

You need:
- an Android phone (Android 10 or newer) with your earphones plugged in (USB-C EarPods, or any wired headset)
- a Windows 10/11 laptop
- one free Tailscale account (sign in with Google, GitHub or Microsoft)

---

## 1. Tailscale on both devices (the always-on, encrypted link)

Tailscale gives your phone and laptop a private, end-to-end encrypted (WireGuard) link that works on any network: home wifi, cafe wifi, cellular, behind hotel firewalls. wirefree runs inside that link, with its own encryption on top.

**Laptop**
1. Install Tailscale from https://tailscale.com/download/windows and sign in.
2. Tray icon → **Preferences** → turn on **Run unattended** and **Launch at startup**, so it stays connected when you're logged out or after a reboot.

**Phone**
1. Install Tailscale from the Play Store and sign in with the **same account**.
2. Open Android **Settings → Network & internet → VPN → Tailscale (gear icon)** and turn on **Always-on VPN**. Android then keeps it connected all the time, including after a reboot.
3. Settings → Apps → Tailscale → Battery → **Unrestricted**.

> Android allows only one VPN at a time. If you use another VPN app on the phone, it will knock Tailscale off and wirefree will pause until Tailscale is back.

Check: the Tailscale app on each device should list the other one as connected.

## 2. The laptop app

1. Download `wirefree-windows.zip` from the [latest release](https://github.com/a3ro-dev/wirefree/releases/latest), extract it somewhere permanent (for example `C:\Users\<you>\wirefree`) and run `wirefree.exe`.
2. Click **Set up audio devices** in the window (or run `wirefree.exe --setup`), and approve the admin prompt.
   It downloads two free, signed virtual audio cables straight from vb-audio.com (VB-CABLE and Hi-Fi Cable). Before running anything, it checks each download's fingerprint and VB-Audio's signature.
   - VB-CABLE installs by itself.
   - Hi-Fi Cable opens its own installer window. Click **Install**, wait for *Installation Complete*, and close it.
3. **Restart Windows**, then click **Set up audio devices** once more. This second run renames the cables (Windows only shows them after the restart), so Sound settings shows:
   - Output: **android-speaker**
   - Input: **android-mic**
   - plus two helper devices called *wirefree speaker link* and *wirefree mic link*. Leave those alone.
4. wirefree starts with Windows and lives in the tray. Open it and you'll see a pairing QR code.

> VB-Audio's cables are donationware. They're free to use, and you can support the author at vb-audio.com.

## 3. The phone app

1. Download `wirefree.apk` from the [latest release](https://github.com/a3ro-dev/wirefree/releases/latest) on the phone and install it. Allow "install unknown apps" for your browser when Android asks.
2. Open wirefree → **Scan laptop code** → scan the QR on the laptop. (No camera? Copy the pairing link on the laptop and paste it on the phone.)
3. Allow microphone and notifications. Allow **unrestricted battery** when it asks. This is what keeps wirefree available all day.
4. Optional: pull down Quick Settings → edit → drag the **wirefree mute** tile in for one-tap mute.

That's it. Both screens should now say **Connected**.

---

## Daily use

You don't do anything. wirefree follows the earphones:

| | laptop sound plays on | laptop mic is |
|---|---|---|
| EarPods plugged into the phone | your EarPods | the EarPods mic |
| EarPods unplugged | the laptop's own speakers | the phone's mic |
| phone not reachable | the laptop's own speakers | the laptop's own mic |

Windows switches default devices for you, and switches back when the phone goes away. If you pick a device yourself in Sound settings, wirefree respects it.

**Mute**: the big button in the phone app, the notification's **Mute** action, the Quick Settings tile, or the button in the laptop window. Muted is red everywhere (phone screen, tile, notification, laptop tray icon), so you always know.

**Phone has priority**: when the phone needs your earphones (a call, a voice note, music or a video on the phone, another app using the mic), the laptop stream pauses and the phone gets the EarPods. The laptop does **not** jump to its speakers, so nothing blares out in a cafe. When the phone is done, the laptop stream resumes by itself.

**Privacy**: the phone only opens its mic while an app on the laptop is actually recording from `android-mic` (a call, a recorder). The rest of the time the mic is closed, and Android's green mic dot is off.

## Calls and meeting apps

Most apps follow the Windows default, so this just works. If an app pinned a specific device earlier, set its microphone to **Default** or **android-mic**, and its speaker to **Default**.

## Troubleshooting

- **Phone says "Looking for laptop"**: open Tailscale on both devices and check that both are connected to the same account. On the laptop, allow wirefree through Windows Firewall if Windows asked (Tailscale's network only).
- **No android-speaker / android-mic in Sound settings**: run **Set up audio devices** again, then restart Windows.
- **Robotic or choppy sound on bad networks**: it's usually Tailscale running through a relay. Tailscale tries a direct path first, and the relay is the fallback. It recovers automatically.
- **After a phone restart**: Android doesn't let apps reopen the mic by themselves after a reboot. wirefree shows a *Tap to resume* notification. Tap it once.
- **Echo for the other person on a call while you use the laptop speakers**: that's the phone mic hearing your laptop speakers. Plug the EarPods in, or let the meeting app's echo cancellation handle it.

## Security, in short

- Two layers of end-to-end encryption: Tailscale's WireGuard between your devices, and inside it wirefree's own AES-256-GCM with a key that only lives on your two devices (it's in the QR you scanned).
- Every packet is authenticated, so tampered, replayed or forged packets are dropped. Other devices on your tailnet can't send audio to either side.
- No servers of ours, no accounts, no analytics. Audio never touches anything except your two devices (and Tailscale's relay as encrypted bytes, only when a direct path isn't possible).
- **Forget phone** on the laptop makes a new key and locks out the old pairing at once.

Protocol details: [PROTOCOL.md](PROTOCOL.md).

## Uninstall

- Laptop: in the wirefree window choose **Quit**, delete the folder, then (optional) remove *VB-Audio Virtual Cable* and *VB-Audio Hi-Fi Cable* in Settings → Apps and restart.
- Phone: uninstall wirefree like any app.
