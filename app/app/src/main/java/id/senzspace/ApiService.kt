package id.senzspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// Service penjaga API kontrol lokal (v0.1.36): menjalankan ApiServer
// di proses utama (START_STICKY) supaya API tetap bisa dipanggil
// walau Beranda ditutup. Notifikasi LOW, satu baris, tidak bunyi.
// Bila izin notifikasi belum dikasih, server tetap jalan sebagai
// started service biasa (pola yang sama seperti KeepAliveService);
// startForegroundService TIDAK dipanggil dalam keadaan itu supaya
// sistem tidak menuduh service tidak kunjung foreground.
class ApiService : Service() {
    companion object {
        const val CHANNEL_ID = "senzspace_api"
        const val NOTIF_ID = 1200

        fun start(ctx: Context) {
            val intent = Intent(ctx, ApiService::class.java)
            val canForeground = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            try {
                if (canForeground && Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                try {
                    ctx.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ApiService::class.java))
        }
    }

    private var server: ApiServer? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Tanpa izin notifikasi: jalan tanpa foreground.
            server = ApiServer(applicationContext).also { it.start() }
            return
        }
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "API kontrol",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Server API lokal SenzSpace (localhost) untuk kontrol API"
                }
            )
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pending = PendingIntent.getActivity(
            this, NOTIF_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SenzSpace API aktif")
            .setContentText(
                "Kontrol lokal di 127.0.0.1:${ApiServer.PORT} — hanya dari HP ini"
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_ID, notif)
        } catch (_: Exception) {
        }
        server = ApiServer(applicationContext).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
