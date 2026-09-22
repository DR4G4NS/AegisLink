package dev.aegis.remote.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.aegis.remote.android.R

class AegisSessionService : Service() {
    private val binder = SessionBinder()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.session_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.session_notification_title))
                .setContentText(getString(R.string.session_notification_text))
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class SessionBinder : Binder() {
        fun service(): AegisSessionService = this@AegisSessionService
    }

    companion object {
        private const val CHANNEL_ID = "aegis-active-session"
        private const val NOTIFICATION_ID = 48291
        private val bindLock = Any()

        @Volatile
        private var bound = false

        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) = Unit

                override fun onServiceDisconnected(name: ComponentName?) {
                    bound = false
                }
            }

        fun acquire(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, AegisSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            synchronized(bindLock) {
                if (!bound) {
                    bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }
            }
        }

        fun release(context: Context) {
            val app = context.applicationContext
            synchronized(bindLock) {
                if (bound) {
                    runCatching { app.unbindService(connection) }
                    bound = false
                }
            }
            app.stopService(Intent(app, AegisSessionService::class.java))
        }

        fun start(context: Context) = acquire(context)

        fun stop(context: Context) = release(context)
    }
}
