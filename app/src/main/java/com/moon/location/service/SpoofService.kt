package com.moon.location.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.moon.location.R
import com.moon.location.config.Config
import com.moon.location.ui.MainActivity

/**
 * Drives the spoof: writes the config snapshot and keeps it fresh with a heartbeat.
 * The actual location rewriting happens in system_server.
 */
class SpoofService : Service() {

    companion object {
        const val ACTION_START = "com.moon.location.action.START"
        const val ACTION_STOP = "com.moon.location.action.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"

        private const val CHANNEL_ID = "spoof"
        private const val NOTIF_ID = 1
        private const val HEARTBEAT_MS = 3_000L

        fun start(ctx: Context, lat: Double, lng: Double) {
            ctx.startForegroundService(
                Intent(ctx, SpoofService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LNG, lng),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SpoofService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lat = 39.9087
    private var lng = 116.3975

    /** True while the user intends spoofing to run. Survives sticky restarts. */
    @Volatile private var running = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            Config.heartbeat(this@SpoofService)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                lat = intent.getDoubleExtra(EXTRA_LAT, lat)
                lng = intent.getDoubleExtra(EXTRA_LNG, lng)
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                running = true
                Config.write(this, started = true, lat = lat, lng = lng)
                restartHeartbeat()
            }
            ACTION_STOP -> {
                running = false
                handler.removeCallbacks(heartbeat)
                Config.write(this, started = false, lat = lat, lng = lng)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Sticky restart (null intent): if we were running, resume the heartbeat
                // and re-assert started=true so the lease never lapses.
                if (running) {
                    startForeground(
                        NOTIF_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                    Config.write(this, started = true, lat = lat, lng = lng)
                    restartHeartbeat()
                }
            }
        }
        return START_STICKY
    }

    private fun restartHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        // Only clear when the user actually stopped. A system kill keeps the last
        // started=true snapshot; the (now 10 min) lease is the safety net.
        if (!running) {
            Config.write(this, started = false, lat = lat, lng = lng)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "位置模拟", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("模拟位置 %.5f, %.5f".format(lat, lng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
