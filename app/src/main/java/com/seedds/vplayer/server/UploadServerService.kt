package com.seedds.vplayer.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.seedds.vplayer.R
import com.seedds.vplayer.app.MainActivity
import com.seedds.vplayer.app.VPlayerApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the upload server alive and the radio awake while it is running.
 *
 * The server object itself lives in the application, not here; this service
 * exists so the process is not treated as a background app while a laptop is
 * mid-transfer. Android cuts network and CPU to cached processes, which would
 * stall an upload the moment the user switched apps.
 */
class UploadServerService : LifecycleService() {

    private val controller by lazy { (application as VPlayerApp).container.serverController }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        lifecycleScope.launch {
            combine(controller.state, controller.activeUploadCount) { state, uploads -> state to uploads }
                .collect { (state, uploads) ->
                    if (state.isRunning) {
                        notify(buildNotification(state.runningPort, uploads))
                    }
                    // The radio and CPU are only pinned while bytes are
                    // actually moving; an idle server has no claim on either.
                    if (uploads > 0) acquireLocks() else releaseLocks()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    controller.stop()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, UploadServerController.DEFAULT_PORT)
                    ?: UploadServerController.DEFAULT_PORT
                startForegroundCompat(buildNotification(port, 0))
                lifecycleScope.launch { controller.start(port) }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Android 15 gives a data-sync foreground service a bounded budget and then
     * asks it to leave. Shut the server down cleanly rather than being killed.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        lifecycleScope.launch {
            controller.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notify(notification: Notification) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(port: Int?, activeUploads: Int): Notification {
        val address = (application as VPlayerApp).container.lanAddressMonitor.currentAddress()
        val target = when {
            address != null && port != null -> "http://$address:$port"
            port != null -> "Running on port $port"
            else -> "Running"
        }
        val text = when {
            activeUploads == 1 -> "$target — 1 upload in progress"
            activeUploads > 1 -> "$target — $activeUploads uploads in progress"
            else -> target
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, UploadServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Upload server")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Upload server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the local upload server is running."
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        runCatching {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release() }
        runCatching { wifiLock?.takeIf(WifiManager.WifiLock::isHeld)?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_START = "com.seedds.vplayer.action.START_SERVER"
        const val ACTION_STOP = "com.seedds.vplayer.action.STOP_SERVER"
        const val EXTRA_PORT = "port"

        private const val CHANNEL_ID = "upload-server"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "VPlayer:uploads"
        private const val WIFI_LOCK_TAG = "VPlayer:uploads"

        /** A safety net: an upload that somehow never finishes cannot pin the CPU forever. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

        fun start(context: Context, port: Int) {
            val intent = Intent(context, UploadServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, UploadServerService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
