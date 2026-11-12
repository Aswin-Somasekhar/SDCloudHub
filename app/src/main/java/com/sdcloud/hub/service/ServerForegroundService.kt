package com.sdcloud.hub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sdcloud.hub.MainActivity
import com.sdcloud.hub.R
import com.sdcloud.hub.dlna.SsdpAdvertiser
import com.sdcloud.hub.server.FtpServerManager
import com.sdcloud.hub.server.HttpServer
import com.sdcloud.hub.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ServerStatus(
    val isRunning: Boolean = false,
    val localIp: String = "127.0.0.1",
    val httpPort: Int = 8080,
    val ftpPort: Int = 2121,
    val rootPath: String = ""
)

class ServerForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.sdcloud.hub.START"
        const val ACTION_STOP = "com.sdcloud.hub.STOP"
        private const val CHANNEL_ID = "sd_cloud_hub_channel"
        private const val NOTIFICATION_ID = 1001

        private val _serverStatusFlow = MutableStateFlow(ServerStatus())
        val serverStatusFlow: StateFlow<ServerStatus> = _serverStatusFlow.asStateFlow()
    }

    private lateinit var powerHelper: PowerAndWifiHelper
    private lateinit var storageManager: StorageManager
    private var httpServer: HttpServer? = null
    private var ssdpAdvertiser: SsdpAdvertiser? = null
    private var ftpServerManager: FtpServerManager? = null

    private val serverUuid = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        powerHelper = PowerAndWifiHelper(this)
        storageManager = StorageManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServers()
            ACTION_STOP -> stopServers()
        }
        return START_NOT_STICKY
    }

    private fun startServers() {
        powerHelper.acquireLocks()

        val ip = powerHelper.getLocalIpAddress()
        val rootDir = storageManager.getActiveRoot()

        // 1. Start HTTP + WebDAV + Media Streaming Server (Port 8080)
        httpServer = HttpServer(this, storageManager, port = 8080, serverUuid = serverUuid, hostIp = ip)
        httpServer?.start()

        // 2. Start DLNA SSDP Discovery Announcer (UDP Port 1900)
        ssdpAdvertiser = SsdpAdvertiser(this, serverUuid, hostIp = ip, httpPort = 8080)
        ssdpAdvertiser?.start()

        // 3. Start FTP Server (Port 2121)
        ftpServerManager = FtpServerManager(port = 2121, rootDir = rootDir, anonymousAccess = true)
        ftpServerManager?.start()

        val status = ServerStatus(
            isRunning = true,
            localIp = ip,
            httpPort = 8080,
            ftpPort = 2121,
            rootPath = rootDir.absolutePath
        )
        _serverStatusFlow.value = status

        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopServers() {
        httpServer?.stop()
        httpServer = null

        ssdpAdvertiser?.stop()
        ssdpAdvertiser = null

        ftpServerManager?.stop()
        ftpServerManager = null

        powerHelper.releaseLocks()

        _serverStatusFlow.value = ServerStatus(isRunning = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(status: ServerStatus): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cloud &amp; Media Server Active")
            .setContentText("DLNA: Active | WebDAV: http://${status.localIp}:${status.httpPort}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
