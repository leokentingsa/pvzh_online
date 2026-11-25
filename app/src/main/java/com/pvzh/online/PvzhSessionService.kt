package com.pvzh.online

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PvzhSessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PVZH Online 联机", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("PVZH Online 正在运行")
            .setContentText("好友房间中继连接保持运行")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val room = intent?.getStringExtra(EXTRA_ROOM)
        if (room.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val session = RoomSession(
            code = room,
            role = intent.getStringExtra(EXTRA_ROLE) ?: "A",
            token = intent.getStringExtra(EXTRA_TOKEN) ?: "",
            relayHost = intent.getStringExtra(EXTRA_HOST) ?: "",
            relayPort = intent.getIntExtra(EXTRA_PORT, 0),
            relayTls = intent.getBooleanExtra(EXTRA_TLS, true)
        )
        try {
            RelayTunnel.start(session)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RelayTunnel.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "pvzh_online_session"
        const val NOTIFICATION_ID = 4271
        const val EXTRA_ROOM = "room"
        const val EXTRA_ROLE = "role"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TLS = "tls"
    }
}
