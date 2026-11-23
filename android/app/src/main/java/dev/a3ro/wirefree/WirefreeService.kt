package dev.a3ro.wirefree

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors

internal data class Status(
    val running: Boolean = false,
    val online: Boolean = false,
    val earpods: Boolean = false,
    val priority: String? = null,
    val mic: String? = null
)

internal val serviceStatus = mutableStateOf(Status())

class WirefreeService : Service() {
    private val settings by lazy { Settings(this) }
    private var worker: HandlerThread? = null
    private var engine: Engine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_STICKY
        val pairing = settings.load()
        if (!settings.enabled || pairing == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val initial = Status(running = true)
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(RUNNING_NOTIFICATION, runningNotification(initial, settings.muted), types)
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        cancelResumeNotification()
        serviceStatus.value = initial
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wirefree:connection").apply { acquire() }
        val thread = HandlerThread("wirefree", Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        worker = thread
        Handler(thread.looper).let { handler ->
            handler.post {
                engine = Engine(pairing, handler).also { it.start() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        serviceStatus.value = Status()
        val thread = worker
        if (thread != null) {
            Handler(thread.looper).postAtFrontOfQueue {
                try {
                    engine?.close()
                } finally {
                    wakeLock?.release()
                    thread.quitSafely()
                }
            }
        }
        super.onDestroy()
    }

    private inner class Engine(private val pairing: Pairing, private val handler: Handler) : AutoCloseable {
        private val connectivity = getSystemService(ConnectivityManager::class.java)
        private val audio = PhoneAudio(this@WirefreeService, handler)
        private val resolver = Executors.newSingleThreadExecutor()
        private val fallback = InetSocketAddress(pairing.ip, PORT)
        private var dnsAddress: InetSocketAddress? = null
        private var resolving = false
        private var nextResolve = 0L
        private var generation = 0
        private var reconnect = true
        private var channel: DatagramChannel? = null
        private var sender = Sender(pairing.key, Direction.PHONE)
        private val receiver = Receiver(pairing.key, Direction.LAPTOP)
        private val datagram = ByteBuffer.allocate(MAX_PACKET + 1)
        private var helloDue = 0L
        private var helloCtr = -1L
        private var lastHello: Long? = null
        private var hello: LaptopHello? = null
        private var peer: InetSocketAddress? = null
        private var probeFallback = false
        private var mic: MicStream? = null
        private var lastMicSession: Int? = null
        private var micRetry = 0L
        private var silencedUntil = 0L
        private var micSeq = 0
        private var speaker: SpeakerStream? = null
        private var lastSpeaker = 0L
        private var spkFloor: Long? = null
        private var jitter = JitterBuffer()
        private var previous = Status(running = true)
        private var previousMute = settings.muted
        private val phoneName = Build.MODEL.ifBlank { "Android" }
        private var closed = false
        private var defaultNetwork: Network? = null
        private var defaultLink: LinkProperties? = null

        private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (defaultNetwork != network) reconnect = true
                defaultNetwork = network
                defaultLink = null
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    reconnect = true
                }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == defaultNetwork && defaultLink != properties) {
                    if (defaultLink != null) reconnect = true
                    defaultLink = properties
                }
            }
        }
        // The default VPN can stay up when its underlying Wi-Fi or cellular path changes.
        private val pathCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { reconnect = true }
            override fun onLost(network: Network) { reconnect = true }
        }

        fun start() {
            connectivity.registerDefaultNetworkCallback(defaultCallback, handler)
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), pathCallback, handler)
            handler.post(::tick)
        }

        private fun tick() {
            if (closed) return
            if (!settings.enabled || settings.name == null) {
                mainExecutor.execute { stopSelf() }
                return
            }
            val now = SystemClock.elapsedRealtime()
            audio.registerCallIfAllowed()
            if (reconnect || sender.exhausted) restart(now)
            resolve(now)
            var muted = settings.muted
            var online = online(now)
            var priority = priority(now)
            if (!online || hello?.wantMic != true || muted || priority != null) closeMic()
            if (!online || hello?.wantSpk != true || audio.output == null || priority != null) closeSpeaker()
            read(now)
            muted = settings.muted
            online = online(now)
            priority = priority(now)
            updateMic(now, online && hello?.wantMic == true && !muted && priority == null)
            if (!online || hello?.wantSpk != true || audio.output == null || priority != null ||
                now - lastSpeaker >= 3_000 || (speaker != null && speaker?.device?.id != audio.output?.id)) {
                closeSpeaker()
            } else if (speaker == null) {
                speaker = audio.output?.let { runCatching { SpeakerStream(it) }.getOrNull() }
            }
            val status = Status(true, online, audio.output != null, priority, mic?.let {
                if (it.device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "phone" else "EarPods"
            })
            val changed = status.earpods != previous.earpods || (status.priority != null) != (previous.priority != null) ||
                (status.mic != null) != (previous.mic != null) || muted != previousMute
            if (changed || now >= helloDue) {
                val target = peer.takeIf { online } ?: if (probeFallback) fallback else dnsAddress ?: fallback
                if (now >= helloDue) probeFallback = !probeFallback
                send(Kind.HELLO, phoneHello(phoneName, status, muted), target)
                helloDue = now + 1_000
            }
            if (status != previous || muted != previousMute) {
                previous = status
                previousMute = muted
                mainExecutor.execute {
                    if (alive) {
                        serviceStatus.value = status
                        getSystemService(NotificationManager::class.java).notify(RUNNING_NOTIFICATION,
                            runningNotification(status, settings.muted))
                    }
                }
            }
            if (online && mic != null) {
                try {
                    mic?.pump { opus ->
                        val seq = micSeq++
                        if (opus != null) send(Kind.MIC, AudioFrame(seq, opus).payload(), peer ?: fallback)
                    }
                } catch (_: Exception) {
                    closeMic()
                    micRetry = now + 500
                }
            }
            if (speaker != null) {
                try {
                    speaker?.pump { jitter.poll(now) }
                } catch (_: Exception) {
                    closeSpeaker()
                }
            }
            handler.postDelayed(::tick, if (mic != null || speaker != null) 4 else 16)
        }

        private fun priority(now: Long): String? {
            val reason = audio.priority(mic, lastMicSession, speaker?.playing == true)
            if (reason == "Android paused microphone access") silencedUntil = now + 750
            return reason ?: "Android paused microphone access".takeIf { now < silencedUntil }
        }

        private fun online(now: Long) = lastHello?.let { now - it < 3_000 } == true

        private fun restart(now: Long) {
            reconnect = false
            generation++
            runCatching { channel?.close() }
            channel = try { DatagramChannel.open().apply { configureBlocking(false); bind(null) } }
                catch (_: IOException) { null }
            sender = Sender(pairing.key, Direction.PHONE)
            micSeq = 0
            lastHello = null
            hello = null
            peer = null
            dnsAddress = null
            resolving = false
            nextResolve = now
            helloDue = 0
            probeFallback = false
            closeMic()
            closeSpeaker()
        }

        private fun resolve(now: Long) {
            if (resolving || now < nextResolve) return
            if (channel == null) restart(now)
            resolving = true
            nextResolve = now + if (online(now)) 30_000 else 3_000
            val token = generation
            resolver.execute {
                val address = runCatching { InetAddress.getAllByName(pairing.host).firstOrNull() }.getOrNull()
                handler.post {
                    if (token == generation) {
                        resolving = false
                        dnsAddress = address?.let { InetSocketAddress(it, PORT) }
                    }
                }
            }
        }

        private fun send(kind: Kind, payload: ByteArray, target: InetSocketAddress) {
            if (sender.exhausted) return
            try {
                channel?.send(ByteBuffer.wrap(sender.packet(kind, payload)), target)
            } catch (_: IOException) {
                // Connectivity callbacks and the next HELLO attempt handle a lost route.
            }
        }

        private fun read(now: Long) {
            repeat(32) {
                datagram.clear()
                val source = try { channel?.receive(datagram) as? InetSocketAddress }
                    catch (_: IOException) { null } ?: return
                val bytes = ByteArray(datagram.position())
                datagram.flip()
                datagram.get(bytes)
                var parsed: LaptopHello? = null
                val packet = receiver.receive(bytes, System.currentTimeMillis()) {
                    laptopHello(it).also { value -> parsed = value }?.ts
                } ?: return@repeat
                when (packet.kind) {
                    Kind.HELLO -> {
                        val next = (parsed ?: laptopHello(packet.payload)) ?: return@repeat
                        if (packet.newSession) {
                            helloCtr = -1
                            spkFloor = null
                            closeSpeaker()
                        }
                        lastHello = now
                        if (packet.ctr > helloCtr) {
                            if (!next.wantSpk) spkFloor = packet.ctr
                            helloCtr = packet.ctr
                            hello = next
                            peer = source
                        }
                    }
                    Kind.CMD -> when (command(packet.payload)) {
                        "toggleMute" -> { settings.muted = !settings.muted; helloDue = 0 }
                        "mute" -> { settings.muted = true; helloDue = 0 }
                        "unmute" -> { settings.muted = false; helloDue = 0 }
                    }
                    Kind.SPK -> if (online(now) && hello?.wantSpk == true && audio.output != null &&
                        (spkFloor?.let { packet.ctr > it } != false) && priority(now) == null) {
                        val frame = AudioFrame.parse(packet.payload) ?: return@repeat
                        lastSpeaker = now
                        jitter.offer(frame, now)
                    }
                    Kind.MIC -> Unit
                }
            }
        }

        private fun updateMic(now: Long, allowed: Boolean) {
            val device = audio.input
            if (!allowed || device == null || mic?.device?.id != device.id) closeMic()
            if (allowed && device != null && mic == null && now >= micRetry) {
                mic = runCatching { MicStream(device) }.getOrNull()
                lastMicSession = mic?.record?.audioSessionId ?: lastMicSession
                micRetry = now + 500
            }
        }

        private fun closeMic() {
            runCatching { mic?.close() }
            mic = null
        }

        private fun closeSpeaker() {
            runCatching { speaker?.close() }
            speaker = null
            if (lastSpeaker != 0L) jitter = JitterBuffer()
            lastSpeaker = 0
        }

        override fun close() {
            closed = true
            handler.removeCallbacksAndMessages(null)
            closeMic()
            closeSpeaker()
            audio.close()
            connectivity.unregisterNetworkCallback(defaultCallback)
            connectivity.unregisterNetworkCallback(pathCallback)
            runCatching { channel?.close() }
            resolver.shutdownNow()
        }
    }
}
