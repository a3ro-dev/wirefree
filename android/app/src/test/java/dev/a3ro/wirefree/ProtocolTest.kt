package dev.a3ro.wirefree

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val now = 1_732_300_000_000L

    private fun Receiver.open(bytes: ByteArray, wall: Long = now) =
        receive(bytes, wall) { it.decodeToString().toLongOrNull() }

    @Test fun packetRoundTrip() {
        val sender = Sender(key, Direction.PHONE)
        val receiver = Receiver(key, Direction.PHONE)
        val hello = now.toString().encodeToByteArray()
        assertArrayEquals(hello, receiver.open(sender.packet(Kind.HELLO, hello))!!.payload)
        val frame = AudioFrame(0xFEDCBA98.toInt(), byteArrayOf(1, 2, 3))
        val received = receiver.open(sender.packet(Kind.MIC, frame.payload()))!!
        assertEquals(1L, received.ctr)
        val audio = AudioFrame.parse(received.payload)!!
        assertEquals(frame.seq, audio.seq)
        assertArrayEquals(frame.opus, audio.opus)
    }

    @Test fun rejectsTamperedHeaderAndCiphertext() {
        val sender = Sender(key, Direction.PHONE)
        val receiver = Receiver(key, Direction.PHONE)
        val packet = sender.packet(Kind.HELLO, now.toString().encodeToByteArray())
        for (index in listOf(2, 25, HEADER, packet.lastIndex)) {
            val changed = packet.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertNull(receiver.open(changed))
        }
        assertNotNull(receiver.open(packet))
    }

    @Test fun rejectsDuplicatesAndPacketsOutsideReplayWindow() {
        val sender = Sender(key, Direction.PHONE)
        val receiver = Receiver(key, Direction.PHONE)
        val hello = sender.packet(Kind.HELLO, now.toString().encodeToByteArray())
        assertNotNull(receiver.open(hello))
        assertNull(receiver.open(hello))
        val old = sender.packet(Kind.CMD, byteArrayOf())
        repeat(1022) { sender.packet(Kind.CMD, byteArrayOf()) }
        val boundary = sender.packet(Kind.CMD, byteArrayOf())
        val later = sender.packet(Kind.CMD, byteArrayOf())
        assertNotNull(receiver.open(later))
        assertNotNull(receiver.open(boundary))
        assertNull(receiver.open(old))
    }

    @Test fun newSessionRequiresFreshStrictlyNewerHello() {
        val receiver = Receiver(key, Direction.LAPTOP)
        fun hello(sender: Sender, ts: Long, wall: Long = now) =
            receiver.open(sender.packet(Kind.HELLO, ts.toString().encodeToByteArray()), wall)
        val first = Sender(key, Direction.LAPTOP)
        val next = Sender(key, Direction.LAPTOP)
        assertNull(hello(first, now - 120_001))
        assertNull(hello(first, now + 120_001))
        assertNotNull(hello(first, now - 120_000))
        assertNull(receiver.open(next.packet(Kind.CMD, byteArrayOf())))
        assertNull(hello(next, now - 120_000))
        assertTrue(hello(next, now)!!.newSession)
        assertNull(hello(first, now - 119_000))
        assertFalse(hello(next, now, now + 300_000)!!.newSession)
    }

    @Test fun rfc5869Sha256Vector() {
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"),
                hex("f0f1f2f3f4f5f6f7f8f9"), 42))
    }

    @Test fun jitterOrdersFramesAndConcealsGapAcrossSequenceWrap() {
        val jitter = JitterBuffer()
        jitter.offer(AudioFrame(-1, byteArrayOf(2)), 10)
        jitter.offer(AudioFrame(-2, byteArrayOf(1)), 13)
        jitter.offer(AudioFrame(1, byteArrayOf(4)), 14)
        assertNull(jitter.poll(49))
        assertArrayEquals(byteArrayOf(1), (jitter.poll(50) as JitterBuffer.Frame.Packet).opus)
        assertArrayEquals(byteArrayOf(2), (jitter.poll(60) as JitterBuffer.Frame.Packet).opus)
        jitter.offer(AudioFrame(-1, byteArrayOf(5)), 63)
        val recovered = jitter.poll(70) as JitterBuffer.Frame.Packet
        assertTrue(recovered.fec)
        assertArrayEquals(byteArrayOf(4), recovered.opus)
        assertFalse((jitter.poll(80) as JitterBuffer.Frame.Packet).fec)
        assertEquals(JitterBuffer.Frame.Missing, jitter.poll(90))
    }

    @Test fun fixedVectorDecryptsAndReproducesExactWireBytes() {
        val json = checkNotNull(javaClass.getResource("/test-vector.json")).readText()
        fun field(name: String) = checkNotNull(Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json))
            .groupValues[1]
        val secret = hex(field("key"))
        val sid = hex(field("sid"))
        val ctr = field("ctr").toLong(16)
        val plaintext = hex(field("plaintext"))
        val packet = hex(field("packet"))
        val received = Receiver(secret, Direction.PHONE).receive(packet, now) { now }!!
        assertEquals(ctr, received.ctr)
        assertArrayEquals(plaintext, received.payload)
        assertArrayEquals(packet, Sender(secret, Direction.PHONE, sid, ctr).packet(Kind.HELLO, plaintext))
    }

    @Test fun concentusEncodesDecodesAndConcealsTwentyMilliseconds() {
        val encoder = OpusEncoder(RATE, 2, OpusApplication.OPUS_APPLICATION_AUDIO)
        val decoder = OpusDecoder(RATE, 2)
        val pcm = ShortArray(FRAME * 2) { (it * 40).toShort() }
        val opus = ByteArray(MAX_PACKET - HEADER - TAG - 4)
        val size = encoder.encode(pcm, 0, FRAME, opus, 0, opus.size)
        assertEquals(FRAME, decoder.decode(opus, 0, size, pcm, 0, FRAME, false))
        assertEquals(FRAME, decoder.decode(null, 0, 0, pcm, 0, FRAME, false))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
