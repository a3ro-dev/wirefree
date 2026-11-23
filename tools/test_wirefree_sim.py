import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import wirefree_sim as sim


class ProtocolTests(unittest.TestCase):
    key = bytes(range(32))
    sid = bytes(range(0xA0, 0xB0))
    source = ("127.0.0.1", 47391)
    now = 1_732_300_000_000

    def setUp(self):
        self.receiver = sim.Receiver(self.key, "p2l", remember_source=True)

    def seal(self, kind, counter, plaintext, *, sid=None, direction="p2l"):
        sid = self.sid if sid is None else sid
        cipher = AESGCM(sim.derive_key(self.key, sid, direction))
        return sim.encode_packet(cipher, kind, sid, counter, plaintext)

    def hello(self, counter=0, *, ts=None, **kwargs):
        return self.seal(sim.HELLO, counter, sim.json_bytes(
            {"ts": self.now if ts is None else ts}
        ), **kwargs)

    def receive(self, packet, source=None):
        return self.receiver.receive(packet, source or self.source, now_ms=self.now)

    def test_round_trip_both_directions(self):
        for direction, kind in (("p2l", sim.MIC), ("l2p", sim.SPK)):
            with self.subTest(direction=direction):
                self.receiver = sim.Receiver(self.key, direction)
                hello = self.hello(direction=direction)
                self.assertEqual(self.receive(hello), (sim.HELLO, sim.json_bytes({"ts": self.now})))
                audio = sim.SEQ.pack(10) + b"frame"
                self.assertEqual(self.receive(self.seal(kind, 1, audio, direction=direction)),
                                 (kind, audio))

    def test_malformed_and_authenticated_header_tamper(self):
        packet = self.hello()
        for index in (0, 1, 2, 25, 26, len(packet) - 1):
            with self.subTest(index=index):
                damaged = bytearray(packet)
                damaged[index] ^= 3 if index == 1 else 1
                self.assertIsNone(self.receive(bytes(damaged)))
        for malformed in (packet[:-1], packet[:41], packet + bytes(1200), b"garbage"):
            self.assertIsNone(self.receive(malformed))
        self.assertIsNone(self.receiver.sid)
        self.assertIsNone(self.receiver.source)
        self.assertIsNotNone(self.receive(packet))

    def test_replay_including_initial_hello_does_not_move_source(self):
        for packet in (self.hello(), self.seal(sim.CMD, 1, b'{"cmd":"toggleMute"}')):
            self.assertIsNotNone(self.receive(packet))
            self.assertIsNone(self.receive(packet, ("127.0.0.2", 42)))
            self.assertEqual(self.receiver.source, self.source)

    def test_new_session_requires_fresh_strictly_newer_hello(self):
        self.assertIsNotNone(self.receive(self.hello(ts=self.now - 1)))
        new_sid = bytes(16)
        for ts in (self.now - sim.FRESH_MS - 1, self.now + sim.FRESH_MS + 1,
                   self.now - 2, self.now - 1):
            with self.subTest(ts=ts):
                self.assertIsNone(self.receive(self.hello(ts=ts, sid=new_sid)))
        self.assertIsNone(self.receive(self.seal(sim.MIC, 1, b"frame", sid=new_sid)))
        self.assertEqual(self.receiver.sid, self.sid)
        self.assertIsNotNone(self.receive(self.hello(ts=self.now + sim.FRESH_MS, sid=new_sid)))
        self.assertIsNone(self.receive(self.hello(counter=1, ts=self.now, sid=self.sid)))
        self.assertIsNotNone(self.receive(self.seal(sim.CMD, 1, b'{"cmd":"mute"}', sid=new_sid)))

    def test_same_session_hello_uses_replay_window_and_keeps_session_timestamp(self):
        self.assertIsNotNone(self.receive(self.hello()))
        self.assertIsNotNone(self.receive(self.hello(2, ts=self.now + sim.FRESH_MS + 1)))
        self.assertIsNotNone(self.receive(self.hello(1, ts=self.now - sim.FRESH_MS - 1)))
        self.assertEqual(self.receiver.session_ts, self.now)
        self.assertEqual(self.receiver.window.highest, 2)

    def test_replay_window_edges_and_far_future_jump(self):
        window = sim.ReplayWindow()
        self.assertTrue(window.accept(2048))
        self.assertTrue(window.accept(2048 - 1023))
        self.assertFalse(window.accept(2048 - 1023))
        self.assertFalse(window.accept(2048 - 1024))
        self.assertFalse(window.accept(2048 - 1025))
        future = 1 << 61
        self.assertTrue(window.accept(future))
        self.assertEqual(window.bitmap, 1)
        self.assertFalse(window.accept(2048))
        self.assertTrue(window.accept(future - 1023))
        self.assertTrue(window.accept(future + 1))
        self.assertFalse(window.accept(future - 1023))

    def test_rfc_5869_case_1(self):
        self.assertEqual(sim.hkdf(
            b"\x0b" * 22, bytes(range(13)), bytes(range(0xF0, 0xFA)), 42
        ).hex(), "3cb25f25faacd57a90434f64d0362f2a"
                 "2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

    def test_vector_matches_checked_in_file(self):
        fixture = Path(__file__).with_name("test-vector.json")
        self.assertEqual(fixture.read_text(encoding="utf-8"), sim.vector_json())

    def test_datagram_limit(self):
        self.receive(self.hello())
        budget = sim.MAX_DATAGRAM - sim.HEADER.size - sim.TAG_SIZE
        payload = bytes(budget)
        packet = self.seal(sim.MIC, 1, payload)
        self.assertEqual(len(packet), 1200)
        self.assertEqual(self.receive(packet), (sim.MIC, payload))
        with self.assertRaises(ValueError):
            self.seal(sim.MIC, 2, payload + b"x")

    def test_audio_stats_track_reorder_loss_and_wrap(self):
        stats = sim.AudioStats()
        for sequence in (sim.SEQ_MASK - 1, 1, sim.SEQ_MASK, sim.SEQ_MASK):
            stats.record(sim.SEQ.pack(sequence) + sim.DUMMY_PREFIX)
        self.assertEqual((stats.count, stats.dummy, stats.reorder), (3, 3, 1))
        self.assertEqual(stats.sequences.highest - stats.first + 1 - stats.count, 1)

    def test_missing_native_opus_falls_back_to_marked_dummy_frames(self):
        with patch("builtins.__import__", side_effect=Exception("Could not find Opus library.")):
            with redirect_stdout(io.StringIO()) as output:
                audio = sim.AudioSource(laptop=False, dummy=False)
        self.assertIn("non-Opus", output.getvalue())
        self.assertTrue(audio.frame(0)[sim.SEQ.size:].startswith(sim.DUMMY_PREFIX))


if __name__ == "__main__":
    unittest.main()
