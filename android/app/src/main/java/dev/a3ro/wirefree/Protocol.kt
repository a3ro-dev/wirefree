package dev.a3ro.wirefree

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.BitSet
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val PORT = 47391
internal const val MAX_PACKET = 1200
internal const val HEADER = 26
internal const val TAG = 16
internal const val COUNTER_LIMIT = 1L shl 62
internal const val RATE = 48_000
internal const val FRAME = 960
internal const val FRAME_MS = 20L

internal enum class Kind(val id: Byte) { HELLO(1), CMD(2), MIC(16), SPK(17) }
internal enum class Direction(val label: String) { PHONE("p2l"), LAPTOP("l2p") }

internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 32))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt.ifEmptyBytes(), "HmacSHA256"))
    mac.init(SecretKeySpec(mac.doFinal(ikm), "HmacSHA256"))
    val output = ByteArray(length)
    var previous = ByteArray(0)
    for (offset in output.indices step 32) {
        mac.update(previous)
        mac.update(info)
        previous = mac.doFinal(byteArrayOf((offset / 32 + 1).toByte()))
        previous.copyInto(output, offset, 0, minOf(32, length - offset))
    }
    return output
}

private fun ByteArray.ifEmptyBytes() = if (isEmpty()) ByteArray(32) else this

internal fun sessionKey(key: ByteArray, sid: ByteArray, direction: Direction) =
    SecretKeySpec(hkdf(key, sid, "wirefree v1 ${direction.label}".toByteArray(), 32), "AES")

private fun cipher(mode: Int, key: SecretKeySpec, ctr: Long, header: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(ctr).array()))
        updateAAD(header)
    }

internal class Sender(
    secret: ByteArray,
    direction: Direction,
    private val sid: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes),
    private var ctr: Long = 0
) {
    private val key = sessionKey(secret, sid, direction)
    val exhausted get() = ctr >= COUNTER_LIMIT

    fun packet(kind: Kind, payload: ByteArray): ByteArray {
        require(!exhausted && payload.size <= MAX_PACKET - HEADER - TAG)
        val header = ByteBuffer.allocate(HEADER).put(1).put(kind.id).put(sid).putLong(ctr).array()
        return header + cipher(Cipher.ENCRYPT_MODE, key, ctr++, header).doFinal(payload)
    }
}

internal class ReplayWindow {
    private var highest = -1L
    private var seen = BitSet(1024)

    fun accept(ctr: Long): Boolean {
        if (ctr !in 0 until COUNTER_LIMIT) return false
        if (ctr > highest) {
            val shift = ctr - highest
            val shifted = BitSet(1024)
            if (shift < 1024) {
                var bit = seen.nextSetBit(0)
                while (bit >= 0 && bit + shift < 1024) {
                    shifted.set(bit + shift.toInt())
                    bit = seen.nextSetBit(bit + 1)
                }
            }
            seen = shifted
            highest = ctr
        }
        val distance = highest - ctr
        if (distance >= 1024 || seen[distance.toInt()]) return false
        seen.set(distance.toInt())
        return true
    }
}

internal data class Received(val kind: Kind, val ctr: Long, val payload: ByteArray, val newSession: Boolean)

internal class Receiver(private val secret: ByteArray, private val direction: Direction) {
    private var sid: ByteArray? = null
    private var key: SecretKeySpec? = null
    private var sessionTs = Long.MIN_VALUE
    private var replay = ReplayWindow()

    fun receive(
        packet: ByteArray, now: Long, helloTimestamp: (ByteArray) -> Long?
    ): Received? {
        if (packet.size !in (HEADER + TAG)..MAX_PACKET || packet[0] != 1.toByte()) return null
        val kind = Kind.entries.firstOrNull { it.id == packet[1] } ?: return null
        val candidate = packet.copyOfRange(2, 18)
        val ctr = ByteBuffer.wrap(packet, 18, 8).long
        if (ctr !in 0 until COUNTER_LIMIT) return null
        val changed = !candidate.contentEquals(sid)
        val candidateKey = if (changed) sessionKey(secret, candidate, direction) else checkNotNull(key)
        val payload = try {
            cipher(Cipher.DECRYPT_MODE, candidateKey, ctr, packet.copyOfRange(0, HEADER))
                .doFinal(packet, HEADER, packet.size - HEADER)
        } catch (_: GeneralSecurityException) {
            return null
        }
        if (changed) {
            if (kind != Kind.HELLO) return null
            val ts = helloTimestamp(payload) ?: return null
            if (ts !in (now - 120_000)..(now + 120_000) || ts <= sessionTs) return null
            sid = candidate
            key = candidateKey
            sessionTs = ts
            replay = ReplayWindow()
        }
        if (!replay.accept(ctr)) return null
        return Received(kind, ctr, payload, changed)
    }
}

internal data class AudioFrame(val seq: Int, val opus: ByteArray) {
    fun payload() = ByteBuffer.allocate(4 + opus.size).putInt(seq).put(opus).array()

    companion object {
        fun parse(payload: ByteArray): AudioFrame? {
            if (payload.size < 5) return null
            return AudioFrame(ByteBuffer.wrap(payload).int, payload.copyOfRange(4, payload.size))
        }
    }
}

internal class JitterBuffer {
    sealed interface Frame {
        data class Packet(val opus: ByteArray, val fec: Boolean = false) : Frame
        data object Missing : Frame
        data object Silence : Frame
    }

    private val packets = mutableMapOf<Int, ByteArray>()
    private var next = 0
    private var due = Long.MIN_VALUE
    private var started = false
    private var missing = 0

    fun offer(frame: AudioFrame, now: Long) {
        if (due == Long.MIN_VALUE || (missing >= 3 && packets.isEmpty())) {
            next = frame.seq
            due = now + 3 * FRAME_MS
            started = false
            missing = 0
        }
        // Signed subtraction gives the modular distance across u32 wrap.
        val distance = frame.seq - next
        if (distance < 0 && !started && distance >= -3 &&
            packets.keys.all { it - frame.seq < 10 }) {
            next = frame.seq
        } else if (distance !in 0..9) {
            return
        }
        packets.putIfAbsent(frame.seq, frame.opus)
    }

    fun poll(now: Long): Frame? {
        if (due == Long.MIN_VALUE || now < due) return null
        if (now - due >= 10 * FRAME_MS) {
            // Skip elapsed playout slots after a scheduler stall.
            next += ((now - due) / FRAME_MS).toInt()
            packets.keys.removeAll { it - next < 0 }
            due = now
        }
        started = true
        due += FRAME_MS
        val packet = packets.remove(next++)
        if (packet != null) {
            missing = 0
            return Frame.Packet(packet)
        }
        missing++
        val following = packets[next]
        return when {
            following != null -> Frame.Packet(following, fec = true)
            missing <= 3 -> Frame.Missing
            else -> Frame.Silence
        }
    }
}
