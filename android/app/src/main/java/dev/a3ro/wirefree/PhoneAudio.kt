package dev.a3ro.wirefree

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

internal class PhoneAudio(private val context: Context, private val handler: Handler) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private var players = audio.activePlaybackConfigurations.count(::isPlaying)
    private var recordings = audio.activeRecordingConfigurations
    private var inCall = false
    private var unregisterCall: (() -> Unit)? = null
    var output: AudioDeviceInfo? = null
        private set
    var input: AudioDeviceInfo? = null
        private set

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refreshDevices()
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            players = configs.count(::isPlaying)
        }
    }
    private val recording = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            recordings = configs
        }
    }

    init {
        refreshDevices()
        audio.registerAudioDeviceCallback(devices, handler)
        audio.registerAudioPlaybackCallback(playback, handler)
        audio.registerAudioRecordingCallback(recording, handler)
    }

    private fun refreshDevices() {
        output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        input = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        } ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    fun priority(mic: MicStream?, lastMicSession: Int?, playing: Boolean): String? {
        val ownSession = mic?.record?.audioSessionId
        return when {
            inCall || audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_IN_COMMUNICATION -> "Call in progress"
            recordings.any { it.clientAudioSessionId != lastMicSession } -> "Another app is recording"
            mic?.record?.activeRecordingConfiguration?.isClientSilenced == true ||
                recordings.any { it.clientAudioSessionId == ownSession && it.isClientSilenced } ->
                "Android paused microphone access"
            // The list is anonymized, so our own track can't be told apart: subtract it.
            players > if (playing) 1 else 0 -> "Media is playing on your phone"
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun registerCallIfAllowed() {
        if (unregisterCall != null || telephony == null ||
            context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        inCall = state != TelephonyManager.CALL_STATE_IDLE
                    }
                }
                telephony.registerTelephonyCallback({ handler.post(it) }, callback)
                unregisterCall = { telephony.unregisterTelephonyCallback(callback) }
            } else {
                val listener = object : PhoneStateListener({ handler.post(it) }) {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        inCall = state != TelephonyManager.CALL_STATE_IDLE
                    }
                }
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                unregisterCall = { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: SecurityException) {
            // Audio mode still covers calls when phone permission is declined.
        } catch (_: UnsupportedOperationException) {
        }
    }

    override fun close() {
        unregisterCall?.invoke()
        audio.unregisterAudioDeviceCallback(devices)
        audio.unregisterAudioPlaybackCallback(playback)
        audio.unregisterAudioRecordingCallback(recording)
    }
}

// shortcut: the playing state is only exposed through toString ("state:started"); idle system
// SoundPool players otherwise count as media. Revisit if a future Android changes that format.
internal fun isPlaying(c: android.media.AudioPlaybackConfiguration) = c.toString().contains("state:started")
