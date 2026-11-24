package com.example.data.dubbing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class DubbingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMPLETE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Tapos na! Filipino Dubbed MP4 Ready"
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "I-tap para i-preview o i-share."
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                postCompletionNotification(this, title, message, isError = false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FAIL -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Hindi natapos ang dubbing"
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Buksan ang app para makita ang detalye."
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                postCompletionNotification(this, title, message, isError = true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Gizmooh Filipino Dubber"
                val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Pinoproseso sa background..."
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
                val notification = buildProgressNotification(this, title, message, progress)
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIF_ID_PROGRESS, notification, fgsType)
                return START_NOT_STICKY
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "dubbing_progress_channel"
        const val NOTIF_ID_PROGRESS = 7011
        const val NOTIF_ID_DONE = 7012
        const val EXTRA_OPEN_DUBBER = "extra_open_dubber"

        private const val ACTION_UPDATE = "com.example.dubbing.UPDATE"
        private const val ACTION_COMPLETE = "com.example.dubbing.COMPLETE"
        private const val ACTION_FAIL = "com.example.dubbing.FAIL"
        private const val ACTION_STOP = "com.example.dubbing.STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val EXTRA_PROGRESS = "extra_progress"

        fun startOrUpdate(
            context: Context,
            title: String,
            message: String,
            progressPercent: Int?
        ) {
            val appCtx = context.applicationContext
            ensureChannel(appCtx)
            val intent = Intent(appCtx, DubbingForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PROGRESS, progressPercent ?: -1)
            }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }
        }

        fun completeAndStop(context: Context, title: String, message: String) {
            val appCtx = context.applicationContext
            ensureChannel(appCtx)
            val intent = Intent(appCtx, DubbingForegroundService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
            }
            runCatching {
                appCtx.startService(intent)
            }.onFailure {
                postCompletionNotification(appCtx, title, message, isError = false)
            }
        }

        fun failAndStop(context: Context, title: String, message: String) {
            val appCtx = context.applicationContext
            ensureChannel(appCtx)
            val intent = Intent(appCtx, DubbingForegroundService::class.java).apply {
                action = ACTION_FAIL
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
            }
            runCatching {
                appCtx.startService(intent)
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val existing = nm.getNotificationChannel(CHANNEL_ID)
                if (existing == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.dub_notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(R.string.dub_notif_channel_desc)
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }

        private fun createOpenDubberPendingIntent(context: Context): PendingIntent {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_DUBBER, true)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, 701, launchIntent, flags)
        }

        private fun buildProgressNotification(
            context: Context,
            title: String,
            message: String,
            progressPercent: Int
        ): Notification {
            val isNearDone = progressPercent in 75..99
            val displayTitle = if (isNearDone) {
                "Patapos na! ($progressPercent%) · $title"
            } else if (progressPercent in 0..74) {
                "$title ($progressPercent%)"
            } else {
                title
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(displayTitle)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(createOpenDubberPendingIntent(context))

            if (progressPercent in 0..100) {
                builder.setProgress(100, progressPercent, false)
            } else {
                builder.setProgress(100, 0, true)
            }
            return builder.build()
        }

        private fun postCompletionNotification(
            context: Context,
            title: String,
            message: String,
            isError: Boolean
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setPriority(if (isError) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(createOpenDubberPendingIntent(context))
                .build()

            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIF_ID_DONE, notification)
            }
        }
    }
}
