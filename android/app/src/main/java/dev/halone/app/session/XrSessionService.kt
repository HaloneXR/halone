package dev.halone.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.halone.app.R

/**
 * Keeps an XR session alive while the app is not in front.
 *
 * Without this the render thread stops a few seconds after the app is
 * backgrounded, which froze the glasses on their last frame — the display would
 * hold a stale image while the IMU carried on. It also made measurement awkward,
 * since the rig had to be poked over adb to keep drawing.
 *
 * The `connectedDevice` service type is the honest description: the session
 * exists because a USB device is attached and driving an external display.
 */
class XrSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiping the app away from recents may not destroy the activity, but it always ends up here. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        onTaskRemoved?.invoke()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "XR session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active while the glasses are in use"
                setShowBadge(false)
            }
        )

        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("XR session active")
            .setContentText("Glasses connected")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOnlyAlertOnce(true)
            .build()

        val projecting = intent?.getBooleanExtra(EXTRA_PROJECTION, false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = (if (withoutDevice) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) or
                (if (projecting) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (projecting) projectionReady?.invoke()
        projectionReady = null
        // Not sticky: a session can't resume in a process that died, and a
        // restarted service would only post "Glasses connected" over nothing.
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "xr-session"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_PROJECTION = "projection"

        /** Run when the wearer swipes the app away, to give the glasses back as they were. */
        var onTaskRemoved: (() -> Unit)? = null

        /** Run once the service is in the foreground as `mediaProjection`, which a projection must wait for. */
        private var projectionReady: (() -> Unit)? = null

        /**
         * Re-declares the service so that a screen recording may capture
         * sound, after the wearer consented; [ready] runs on the main thread.
         */
        fun allowProjection(context: Context, ready: () -> Unit) {
            projectionReady = ready
            context.startForegroundService(Intent(context, XrSessionService::class.java).putExtra(EXTRA_PROJECTION, true))
        }

        /**
         * Test mode, with no glasses on USB: Android 14 refuses a
         * `connectedDevice` service then, so the service runs only while a
         * recording needs it.
         */
        @Volatile
        var withoutDevice = false

        fun endProjection(context: Context) {
            if (withoutDevice) stop(context) else start(context)
        }

        fun start(context: Context) {
            if (withoutDevice) return
            context.startForegroundService(Intent(context, XrSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, XrSessionService::class.java))
        }
    }
}
