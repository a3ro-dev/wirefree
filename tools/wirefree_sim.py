"""Independent WireFree v1 reference codec and UDP peers."""

import argparse
import base64
import binascii
import json
import math
import random
import re
import secrets
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
from queue import Empty, SimpleQueue
from threading import Thread
from urllib.parse import parse_qs, urlencode, urlsplit

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 1
PORT = 47391
MAX_DATAGRAM = 1200
HEADER = struct.Struct("!BB16sQ")
TAG_SIZE = 16
SEQ = struct.Struct("!I")
MAX_OPUS = MAX_DATAGRAM - HEADER.size - TAG_SIZE - SEQ.size
HELLO, CMD, MIC, SPK = 0x01, 0x02, 0x10, 0x11
TYPES = {HELLO, CMD, MIC, SPK}
MUTE_COMMANDS = {"mute", "unmute", "toggleMute"}
WINDOW = 1024
WINDOW_MASK = (1 << WINDOW) - 1
FRESH_MS = 120_000
ONLINE_SECONDS = 3
FRAME_SECONDS = 0.02
COUNTER_LIMIT = 1 << 62
SEQ_MASK = (1 << 32) - 1
DUMMY_PREFIX = b"\x03\x00WIREFREE-DUMMY:"


def hkdf(ikm, salt, info, length=32):
    return HKDF(
        algorithm=hashes.SHA256(), length=length, salt=salt, info=info
    ).derive(ikm)


def derive_key(pair_key, sid, direction):
    if len(pair_key) != 32 or len(sid) != 16 or direction not in ("p2l", "l2p"):
        raise ValueError("invalid pairing key, session ID or direction")
    return hkdf(pair_key, sid, b"wirefree v1 " + direction.encode("ascii"))


def nonce(counter):
    return struct.pack("!IQ", 0, counter)


def encode_packet(cipher, kind, sid, counter, plaintext):
    """Seal a packet with an AESGCM instance for this sid and direction."""
    if kind not in TYPES or len(sid) != 16 or not 0 <= counter < COUNTER_LIMIT:
        raise ValueError("invalid packet header")
    if HEADER.size + TAG_SIZE + len(plaintext) > MAX_DATAGRAM:
        raise ValueError("packet exceeds 1200 bytes")
    aad = HEADER.pack(VERSION, kind, sid, counter)
    return aad + cipher.encrypt(nonce(counter), plaintext, aad)


def decode_header(packet):
    if not HEADER.size + TAG_SIZE <= len(packet) <= MAX_DATAGRAM:
        raise ValueError("invalid datagram size")
    version, kind, sid, counter = HEADER.unpack_from(packet)
    if version != VERSION or kind not in TYPES:
        raise ValueError("invalid version or type")
    return kind, sid, counter


def decrypt_payload(cipher, packet, counter):
    return cipher.decrypt(nonce(counter), packet[HEADER.size:], packet[:HEADER.size])


def json_bytes(obj):
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def read_object(payload):
    obj = json.loads(payload.decode("utf-8"))
    if not isinstance(obj, dict):
        raise ValueError("expected a JSON object")
    return obj


def unix_ms():
    return time.time_ns() // 1_000_000


class ReplayWindow:
    """Keep the highest counter and the previous 1023 counter positions."""

    def __init__(self):
        self.highest = None
        self.bitmap = 0

    def accept(self, counter):
        if self.highest is None:
            self.highest = counter
            self.bitmap = 1
            return True
        jump = counter - self.highest
        if jump > 0:
            self.bitmap = 1 if jump >= WINDOW else ((self.bitmap << jump) | 1) & WINDOW_MASK
            self.highest = counter
            return True
        if -jump >= WINDOW or self.bitmap & (1 << -jump):
            return False
        self.bitmap |= 1 << -jump
        return True


class Receiver:
    def __init__(self, pair_key, direction, *, remember_source=False):
        self.pair_key = pair_key
        self.direction = direction
        self.remember_source = remember_source
        self.sid = None
        self.session_ts = None
        self.source = None
        self.window = ReplayWindow()
        self.cipher = None

    def receive(self, packet, source=None, *, now_ms=None):
        """Return (type, plaintext) on acceptance, or None on a wire-level drop."""
        try:
            kind, sid, counter = decode_header(packet)
            same_session = sid == self.sid
            cipher = self.cipher if same_session else AESGCM(
                derive_key(self.pair_key, sid, self.direction)
            )
            payload = decrypt_payload(cipher, packet, counter)
            if not same_session:
                if kind != HELLO:
                    return None
                ts = read_object(payload).get("ts")
                now = unix_ms() if now_ms is None else now_ms
                if type(ts) is not int or abs(now - ts) > FRESH_MS:
                    return None
                if self.session_ts is not None and ts <= self.session_ts:
                    return None
                self.sid, self.session_ts, self.cipher = sid, ts, cipher
                self.window = ReplayWindow()
        except (InvalidTag, ValueError, RecursionError):
            return None

        if not self.window.accept(counter):
            return None
        if self.remember_source:
            self.source = source
        return kind, payload


class Sender:
    def __init__(self, pair_key, direction):
        self.pair_key = pair_key
        self.direction = direction
        self.restart()

    def restart(self):
        self.sid = secrets.token_bytes(16)
        self.cipher = AESGCM(derive_key(self.pair_key, self.sid, self.direction))
        self.counter = 0

    def packet(self, kind, payload):
        if self.counter == COUNTER_LIMIT:
            self.restart()
        if self.counter == 0 and kind != HELLO:
            raise ValueError("a new session must start with HELLO")
        packet = encode_packet(self.cipher, kind, self.sid, self.counter, payload)
        self.counter += 1
        return packet


def vector_json():
    pair_key = bytes(range(32))
    sid = bytes(range(0xA0, 0xB0))
    counter = 7
    plaintext = b'{"ts":0}'
    vector = dict(
        K=pair_key.hex(), sid=sid.hex(), ctr=counter, type=HELLO,
        plaintext=plaintext.decode(), nonce=nonce(counter).hex(),
        aad=HEADER.pack(VERSION, HELLO, sid, counter).hex(),
    )
    for direction in ("p2l", "l2p"):
        key = derive_key(pair_key, sid, direction)
        packet = encode_packet(AESGCM(key), HELLO, sid, counter, plaintext)
        vector[direction] = dict(key=key.hex(), packet=packet.hex())
    return json.dumps(vector, indent=2) + "\n"


def parse_pairing(uri):
    bad_uri = "invalid WireFree v1 pairing URI"
    try:
        parts = urlsplit(uri)
        fields = parse_qs(parts.query, strict_parsing=True)
        if (
            parts.scheme != "wirefree" or parts.netloc != "pair" or parts.path
            or parts.fragment or fields["v"] != ["1"]
            or any(len(values) != 1 for values in fields.values())
        ):
            raise ValueError
        encoded = fields["k"][0]
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", encoded):
            raise ValueError
        key = base64.urlsafe_b64decode(encoded + "=")
        port = int(fields["p"][0])
        if base64.urlsafe_b64encode(key).decode().rstrip("=") != encoded or not 1 <= port <= 65535:
            raise ValueError
        hosts = list(dict.fromkeys(fields[field][0] for field in ("h", "ip") if field in fields))
        if not hosts:
            raise ValueError
    except (KeyError, ValueError, binascii.Error):
        raise ValueError(bad_uri) from None
    return key, hosts, port


def tailscale_addresses():
    try:
        status = subprocess.check_output(
            ["tailscale", "status", "--json"], stderr=subprocess.DEVNULL, timeout=3
        )
        device = json.loads(status)["Self"]
        ip = next((ip for ip in device["TailscaleIPs"] if "." in ip), "")
        return device["DNSName"].rstrip("."), ip
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
        return "", ""


class AudioSource:
    def __init__(self, laptop, dummy):
        self.encoder = None
        if not dummy:
            try:
                import opuslib
                from opuslib.api import ctl, encoder
            except (ImportError, OSError, RuntimeError):
                pass
            else:
                channels = 2 if laptop else 1
                self.encode_opus = encoder.encode
                self.encoder = opuslib.Encoder(48_000, channels, "audio" if laptop else "voip")
                self.encoder.bitrate = 128_000 if laptop else 32_000
                encoder.encoder_ctl(self.encoder.encoder_state, ctl.set_inband_fec, 1)
                if not laptop:
                    self.encoder.packet_loss_perc = 10
                # 440 Hz repeats every five 960-sample frames at 48 kHz.
                samples = (
                    int(5000 * math.sin(math.tau * 440 * i / 48_000))
                    for i in range(5 * 960)
                )
                pcm = b"".join(struct.pack("=h", sample) * channels for sample in samples)
                size = 960 * channels * 2
                self.frames = [pcm[i:i + size] for i in range(0, len(pcm), size)]
        label = "Opus 440 Hz tone" if self.encoder else "marked non-Opus dummy payloads"
        print(f"Audio source: {label}")

    def frame(self, sequence):
        if self.encoder:
            pcm = self.frames[sequence % len(self.frames)]
            payload = self.encode_opus(self.encoder.encoder_state, pcm, 960, MAX_OPUS)
        else:
            payload = DUMMY_PREFIX + random.randbytes(random.randint(24, 240))
        return SEQ.pack(sequence & SEQ_MASK) + payload


class AudioStats:
    def __init__(self):
        self.sequences = ReplayWindow()
        self.first = None
        self.count = self.reorder = self.dummy = 0
        self.last_count = 0

    def record(self, payload):
        if len(payload) <= SEQ.size:
            return
        sequence, = SEQ.unpack_from(payload)
        highest = self.sequences.highest
        if highest is not None:
            delta = (sequence - highest + (1 << 31)) & SEQ_MASK
            sequence = highest + delta - (1 << 31)
        if self.sequences.accept(sequence):
            self.first = sequence if self.first is None else min(self.first, sequence)
            self.count += 1
            self.reorder += int(highest is not None and sequence < highest)
            self.dummy += int(payload[SEQ.size:].startswith(DUMMY_PREFIX))

    def report(self, label, elapsed):
        if self.count:
            loss = self.sequences.highest - self.first + 1 - self.count
            rate = (self.count - self.last_count) / elapsed
            self.last_count = self.count
            print(f"{label} count={self.count} loss={loss} reorder={self.reorder} "
                  f"rate={rate:.1f} fps non-Opus={self.dummy}")


class Simulator:
    def __init__(self, sock, key, args, destinations=()):
        self.laptop = args.mode == "fake-laptop"
        self.sock = sock
        self.sender = Sender(key, "l2p" if self.laptop else "p2l")
        self.receiver = Receiver(
            key, "p2l" if self.laptop else "l2p", remember_source=self.laptop
        )
        self.audio = AudioSource(self.laptop, args.dummy)
        self.stats = AudioStats()
        self.destinations = destinations
        self.destination_index = 0
        self.next_fallback = time.monotonic() + ONLINE_SECONDS
        self.last_hello = float("-inf")
        self.next_hello = self.next_audio = 0
        self.sequence = 0
        self.peer = {}
        self.state = dict(role="laptop" if self.laptop else "phone", name=args.name, app="1.0.0")
        self.speaker_auto = True
        if self.laptop:
            self.state.update(wantMic=False, wantSpk=False)
        else:
            self.state.update(earpods=args.earpods, busy=args.busy, muted=args.muted, micLive=False)

    def send(self, kind, payload):
        destination = (
            self.receiver.source if self.laptop else self.destinations[self.destination_index]
        )
        if destination is None:
            return False
        if self.sender.counter == COUNTER_LIMIT and kind != HELLO:
            self.send_hello(time.monotonic())
        try:
            self.sock.sendto(self.sender.packet(kind, payload), destination)
            return True
        except OSError as exc:
            print(f"UDP send failed: {exc}")
            return False

    def send_hello(self, now):
        if self.sender.counter == COUNTER_LIMIT:
            self.sequence = 0
        self.send(HELLO, json_bytes(dict(ts=unix_ms(), **self.state)))
        self.next_hello = now + 1

    def receive(self, packet, source, now):
        previous_sid, previous_source = self.receiver.sid, self.receiver.source
        result = self.receiver.receive(packet, source)
        if result is None:
            return
        if previous_sid != self.receiver.sid:
            self.stats = AudioStats()
        if previous_source != self.receiver.source:
            self.next_hello = 0
        kind, payload = result
        if kind == (MIC if self.laptop else SPK):
            self.stats.record(payload)
            return
        if kind not in (HELLO, CMD):
            return
        try:
            message = read_object(payload)
        except (ValueError, RecursionError):
            return
        if kind == HELLO and type(message.get("ts")) is int:
            if now - self.last_hello >= ONLINE_SECONDS:
                self.next_hello = 0
            self.last_hello = now
            self.peer = message
            print(f"Peer HELLO {json.dumps(message)}")
        elif kind == CMD and not self.laptop:
            command = message.get("cmd")
            if isinstance(command, str) and command in MUTE_COMMANDS:
                self.state["muted"] = (
                    not self.state["muted"] if command == "toggleMute" else command == "mute"
                )
                self.next_hello = 0

    def keyboard(self, command):
        words = command.lower().split()
        if words == ["quit"]:
            return False
        if self.laptop:
            if words in (["mic", "on"], ["mic", "off"]):
                self.state["wantMic"] = words[1] == "on"
                self.next_hello = 0
            elif words in (["spk", "on"], ["spk", "off"], ["spk", "auto"]):
                self.speaker_auto = words[1] == "auto"
                self.state["wantSpk"] = words[1] == "on"
                self.next_hello = 0
            elif words == ["togglemute"]:
                if self.sender.counter == 0:
                    self.send_hello(time.monotonic())
                if not self.send(CMD, json_bytes({"cmd": "toggleMute"})):
                    print("toggleMute was not sent")
            else:
                print("Commands: mic on/off, spk on/off/auto, toggleMute, quit")
        elif len(words) == 1 and words[0] in ("earpods", "busy", "muted"):
            field = words[0]
            self.state[field] = not self.state[field]
            self.next_hello = 0
        else:
            print("Commands: earpods, busy, muted, quit")
        return True

    def tick(self, now):
        online = now - self.last_hello < ONLINE_SECONDS
        if online:
            self.next_fallback = now + ONLINE_SECONDS
        elif len(self.destinations) > 1 and now >= self.next_fallback:
            self.destination_index = (self.destination_index + 1) % len(self.destinations)
            self.next_fallback = now + ONLINE_SECONDS
            self.next_hello = 0

        if self.laptop:
            if self.speaker_auto:
                want_spk = (online and self.peer.get("earpods") is True
                            and self.peer.get("busy") is False)
                if self.state["wantSpk"] != want_spk:
                    self.state["wantSpk"] = want_spk
                    self.next_hello = 0
            stream = online and self.state["wantSpk"] and self.peer.get("busy") is False
        else:
            stream = (online and self.peer.get("wantMic") is True
                      and not self.state["muted"] and not self.state["busy"])
            if stream != self.state["micLive"]:
                self.state["micLive"] = stream
                self.next_hello = 0

        if now >= self.next_hello:
            self.send_hello(now)
        if stream and now >= self.next_audio:
            if self.sender.counter == COUNTER_LIMIT:
                self.send_hello(now)
            self.send(MIC if not self.laptop else SPK, self.audio.frame(self.sequence))
            self.sequence += 1
            self.next_audio = max(self.next_audio + FRAME_SECONDS, now)
        elif not stream:
            self.next_audio = now

    def run(self):
        commands = SimpleQueue()

        def read_commands():
            for line in sys.stdin:
                commands.put(line)

        Thread(target=read_commands, daemon=True).start()
        self.keyboard("help")
        last_report = time.monotonic()
        self.sock.settimeout(FRAME_SECONDS / 2)
        while True:
            try:
                command = commands.get_nowait()
            except Empty:
                pass
            else:
                if not self.keyboard(command):
                    return
            try:
                packet, source = self.sock.recvfrom(65535)
            except (TimeoutError, ConnectionResetError, ConnectionRefusedError):
                pass
            else:
                self.receive(packet, source, time.monotonic())
            now = time.monotonic()
            self.tick(now)
            if now - last_report >= 1:
                self.stats.report("MIC" if self.laptop else "SPK", now - last_report)
                last_report = now


def laptop_uri(args, key):
    dns, ip = tailscale_addresses() if not (args.host and args.ip) else ("", "")
    host = args.host or dns or args.ip or ip or "127.0.0.1"
    ip = args.ip or ip or ""
    if not ip:
        try:
            ip = socket.gethostbyname(host)
        except OSError:
            ip = host
    return "wirefree://pair?" + urlencode(dict(
        v=VERSION, h=host, ip=ip, p=args.port, n=args.name,
        k=base64.urlsafe_b64encode(key).decode().rstrip("="),
    ))


def show_pairing(uri):
    print(uri)
    try:
        import qrcode
    except ImportError:
        return
    qr = qrcode.QRCode()
    qr.add_data(uri)
    qr.print_ascii(tty=sys.stdout.isatty())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    phone = modes.add_parser("fake-phone")
    phone.add_argument("--uri", required=True)
    for flag in ("earpods", "busy", "muted"):
        phone.add_argument(f"--{flag}", action="store_true")
    laptop = modes.add_parser("fake-laptop")
    laptop.add_argument("--port", type=int, default=PORT)
    laptop.add_argument("--host", help="advertised MagicDNS name")
    laptop.add_argument("--ip", help="advertised IPv4 fallback")
    for mode in (phone, laptop):
        mode.add_argument("--name", default=f"WireFree {mode.prog.rsplit('-', 1)[-1]} simulator")
        audio = mode.add_mutually_exclusive_group()
        audio.add_argument("--tone", action="store_true", help="prefer Opus (default)")
        audio.add_argument("--dummy", action="store_true", help="force marked non-Opus frames")
    modes.add_parser("vector")
    args = parser.parse_args()

    if args.mode == "vector":
        vector = vector_json()
        Path(__file__).with_name("test-vector.json").write_text(vector, encoding="utf-8", newline="\n")
        print(vector, end="")
        return

    sys.stdout.reconfigure(line_buffering=True)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            if args.mode == "fake-laptop":
                if not 1 <= args.port <= 65535:
                    raise ValueError("port must be between 1 and 65535")
                sock.bind(("", args.port))
                key = secrets.token_bytes(32)
                show_pairing(laptop_uri(args, key))
                Simulator(sock, key, args).run()
            else:
                key, hosts, port = parse_pairing(args.uri)
                destinations = []
                for host in hosts:
                    try:
                        destinations.append((socket.gethostbyname(host), port))
                    except OSError:
                        continue
                if not destinations:
                    raise ValueError("could not resolve either address in the pairing URI")
                Simulator(sock, key, args, list(dict.fromkeys(destinations))).run()
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
