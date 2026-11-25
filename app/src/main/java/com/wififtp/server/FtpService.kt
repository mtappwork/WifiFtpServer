package com.wififtp.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.apache.ftpserver.FtpServer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the FTP server, so it keeps running with the screen off
 * and while the app is in the background.
 */
class FtpService : Service() {

    private val lock = Any()
    private var ftpServer: FtpServer? = null
    private var worker: ExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var destroyed = false
    @Volatile private var startRequested = false
    @Volatile private var notificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationText = getString(R.string.notif_starting)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground quickly after startForegroundService().
        showForeground(notificationText)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startRequested) {
            startRequested = true
            FtpServerState.set(ServerState.Starting)
            val cfg = AppSettings(this)
            val executor = Executors.newSingleThreadExecutor()
            worker = executor
            executor.execute { runServer(cfg) }
        }
        return START_NOT_STICKY
    }

    private fun runServer(cfg: AppSettings) {
        try {
            val server = FtpServerBuilder.build(cfg)
            server.start()

            val keep = synchronized(lock) {
                if (destroyed) false else {
                    ftpServer = server
                    true
                }
            }
            if (!keep) {
                runCatching { server.stop() }
                return
            }

            acquireLocks()

            val ips = NetworkUtils.localIpv4Addresses(this)
            notificationText =
                if (ips.isEmpty()) getString(R.string.notif_no_network)
                else "ftp://${ips.first()}:${cfg.port}"
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(notificationText))

            FtpServerState.set(ServerState.Running(cfg.port))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start FTP server", t)
            if (!destroyed) {
                val root = generateSequence<Throwable>(t) { it.cause }.last()
                FtpServerState.set(ServerState.Error(root.message ?: root.javaClass.simpleName))
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        worker?.shutdown()
        stopFtpServerBlocking()
        releaseLocks()
        if (FtpServerState.state.value !is ServerState.Error) {
            FtpServerState.set(ServerState.Stopped)
        }
        super.onDestroy()
    }

    private fun stopFtpServerBlocking() {
        val server = synchronized(lock) {
            val s = ftpServer
            ftpServer = null
            s
        } ?: return
        // Stop on a helper thread (network teardown must not run on the main thread).
        val t = Thread {
            try {
                server.stop()
            } catch (e: Throwable) {
                Log.w(TAG, "Error while stopping FTP server", e)
            }
        }
        t.start()
        try {
            t.join(3000)
        } catch (_: InterruptedException) {
        }
    }

    // ---------------------------------------------------------------- locks

    private fun acquireLocks() {
        synchronized(lock) {
            if (destroyed) return
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiFtp:server").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock failed", e)
            }
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WifiFtp:wifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WifiLock failed", e)
            }
        }
    }

    private fun releaseLocks() {
        synchronized(lock) {
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (_: Exception) {
            }
            try {
                wifiLock?.takeIf { it.isHeld }?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
            wifiLock = null
        }
    }

    // --------------------------------------------------------- notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.wififtp.server.action.START"
        const val ACTION_STOP = "com.wififtp.server.action.STOP"
        private const val CHANNEL_ID = "ftp_server"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FtpService"
    }
}
