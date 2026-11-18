package net.pdd.comagic.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class AgentStreamService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionID = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val endpoint = SettingsStore(this).load()
        if (sessionID.isBlank() || !endpoint.ready) {
            stopSelf()
            return START_NOT_STICKY
        }
        AgentRepository.start(endpoint, sessionID)
        return START_STICKY
    }

    override fun onDestroy() {
        AgentRepository.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent 连接", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Comagic 正在接收 agent 输出")
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionID"
        private const val CHANNEL_ID = "agent-stream"
        private const val NOTIFICATION_ID = 4101

        fun intent(context: Context, sessionID: String): Intent =
            Intent(context, AgentStreamService::class.java).putExtra(EXTRA_SESSION_ID, sessionID)
    }
}
