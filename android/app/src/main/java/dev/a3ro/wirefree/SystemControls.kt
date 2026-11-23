package dev.a3ro.wirefree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

internal const val RESUME = "dev.a3ro.wirefree.RESUME"
internal const val RUNNING_NOTIFICATION = 1
private const val RESUME_NOTIFICATION = 2

internal fun Context.runningNotification(status: Status, muted: Boolean): Notification {
    val action = PendingIntent.getBroadcast(this, 0, Intent(this, MuteReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE)
    return notification("connection", "Audio connection")
        .setContentTitle(if (muted) "Muted" else "wirefree")
        .setContentText(status.priority?.let { "Phone has priority · $it" } ?:
            if (status.online) "Connected to ${Settings(this).name.orEmpty()}" else "Looking for laptop")
        .setContentIntent(openApp())
        .setOngoing(true)
        .addAction(Notification.Action.Builder(
            Icon.createWithResource(this, if (muted) R.drawable.ic_mic else R.drawable.ic_muted),
            if (muted) "Unmute" else "Mute", action).build())
        .build()
}

private fun Context.openApp(resume: Boolean = false): PendingIntent {
    val intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (resume) intent.action = RESUME
    return PendingIntent.getActivity(this, if (resume) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE)
}

private fun Context.notification(channel: String, title: String): Notification.Builder {
    getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(channel, title, NotificationManager.IMPORTANCE_LOW))
    return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_notification).setOnlyAlertOnce(true)
}

internal fun Context.cancelResumeNotification() {
    getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(context)
        if (!settings.enabled || settings.name == null) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(RESUME_NOTIFICATION, context.notification("resume", "Resume after reboot")
            .setContentTitle("Tap to resume wirefree")
            .setContentText("Reconnect audio to ${settings.name}")
            .setContentIntent(context.openApp(resume = true))
            .setAutoCancel(true)
            .build())
    }
}

class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings(context)
        settings.muted = !settings.muted
        TileService.requestListeningState(context, ComponentName(context, MuteTile::class.java))
    }
}

class MuteTile : TileService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val settings by lazy { Settings(this) }

    override fun onStartListening() {
        super.onStartListening()
        settings.prefs.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    override fun onStopListening() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onStopListening()
    }

    override fun onClick() {
        settings.muted = !settings.muted
        refresh()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == "muted") refresh()
    }

    private fun refresh() {
        val muted = settings.muted
        qsTile?.apply {
            state = if (muted) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (muted) "Muted" else "wirefree"
            subtitle = if (muted) "Tap to unmute" else "Tap to mute"
            contentDescription = "wirefree microphone"
            stateDescription = if (muted) "Muted" else "Unmuted"
            icon = Icon.createWithResource(this@MuteTile, if (muted) R.drawable.ic_muted else R.drawable.ic_mic)
            updateTile()
        }
    }
}
