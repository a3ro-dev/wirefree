package dev.a3ro.wirefree

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import io.github.jaredmdobson.concentus.OpusPacketInfo
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlin.math.abs

private fun format(channels: Int) = AudioFormat.Builder()
    .setSampleRate(RATE)
    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
    .setChannelMask(channels)
    .build()

internal class MicStream @SuppressLint("MissingPermission") constructor(val device: AudioDeviceInfo) : AutoCloseable {
    private val encoder = OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        bitrate = 32_000
        useInbandFEC = true
        packetLossPercent = 10
    }
    val record: AudioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
        .setBufferSizeInBytes(maxOf(FRAME * 4, AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .build()
    private val pcm = ShortArray(FRAME)
    private val opus = ByteArray(MAX_PACKET - HEADER - TAG - 4)
    private var filled = 0
    private var silent = 0

    init {
        try {
            check(record.setPreferredDevice(device))
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (e: Exception) {
            record.release()
            throw e
        }
    }

    fun pump(emit: (ByteArray?) -> Unit) {
        repeat(4) {
            val count = record.read(pcm, filled, FRAME - filled, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0)
            if (count == 0) return
            filled += count
            if (filled == FRAME) {
                filled = 0
                silent = if (pcm.all { abs(it.toInt()) <= 10 }) minOf(silent + 1, 10) else 0
                val packet = if (silent >= 10) null else
                    opus.copyOf(encoder.encode(pcm, 0, FRAME, opus, 0, opus.size))
                emit(packet)
            }
        }
    }

    override fun close() {
        try { record.stop() } finally { record.release() }
    }
}

internal class SpeakerStream(val device: AudioDeviceInfo) : AutoCloseable {
    private val decoder = OpusDecoder(RATE, 2)
    private val pcm = ShortArray(SPK_FRAME * 2)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(format(AudioFormat.CHANNEL_OUT_STEREO))
        .setBufferSizeInBytes(maxOf(SPK_FRAME * 4 * 8, AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
    private var offset = 0
    private var pending = 0
    val playing get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
    private var hasRouted = false

    init {
        if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames = SPK_FRAME * 2
        if (!track.setPreferredDevice(device)) {
            track.release()
            error("Headset route unavailable")
        }
    }

    // Polls the jitter buffer only once the previous frame is fully written, so no frame is dropped.
    fun pump(poll: () -> JitterBuffer.Frame?) {
        val frame = if (pending == 0) poll() else null
        if (!playing && frame != null) track.play()
        val routed = track.routedDevice
        if (routed != null) {
            check(routed.id == device.id)
            hasRouted = true
        }
        if (!hasRouted && frame == null) return
        if (frame != null) {
            pcm.fill(0)
            val decoded = when (frame) {
                is JitterBuffer.Frame.Packet -> runCatching {
                    require(OpusPacketInfo.getNumSamples(frame.opus, 0, frame.opus.size, RATE) == SPK_FRAME)
                    decoder.decode(frame.opus, 0, frame.opus.size, pcm, 0, SPK_FRAME, frame.fec)
                }.getOrElse { decoder.decode(null, 0, 0, pcm, 0, SPK_FRAME, false) }
                JitterBuffer.Frame.Missing -> decoder.decode(null, 0, 0, pcm, 0, SPK_FRAME, false)
                JitterBuffer.Frame.Silence -> SPK_FRAME
            }
            if (decoded in 1..SPK_FRAME) {
                offset = 0
                pending = SPK_FRAME * 2
            }
        }
        if (pending > 0) {
            val written = track.write(pcm, offset, pending, AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0)
            offset += written
            pending -= written
        }
    }

    override fun close() {
        try {
            if (playing) track.pause()
            track.flush()
        } finally {
            track.release()
        }
    }
}
