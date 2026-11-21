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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

// v0.1.72 — guardian watchdog: foreground service di proses sendiri
// (:watchdog) yang menjaga ApiService tetap hidup. v0.1.71 gagal di
// HP ini: start foreground service dari background ditolak sistem
// (receiver/worker tidak dikecualikan), WorkManager tidak pernah
// terjadwal, dan semua lapis menelan kegagalan diam-diam — revive
// dari VM tidak pernah menyalakan API. Guardian di-start HANYA dari
// konteks foreground (MainActivity, BootReceiver, BrowserActivity —
// di samping ApiService.start yang sudah ada). Sekali jalan,
// guardian hidup di prosesnya sendiri: foreground service
// (notifikasi LOW) → uid-nya foreground → boleh memanggil
// ApiService.start() bila /ping tidak menjawab. Guardian selamat
// dari matinya proses utama (proses berbeda, prioritas foreground —
// LMK membunuh terakhir). ApiService tetap tanpa android:process →
// selalu jalan di proses utama, siapapun yang men-start-nya;
// guardian hanya pemicunya. Loop Handler 60 dtk (bukan AlarmManager
// — service foreground tidak kena defer Doze untuk Handler). Ping
// memakai ulang WatchdogWorker.isApiAlive (Socket mentah, tanpa
// proxy, tanpa larangan cleartext). Guardian bukan jalur revive VM:
// start dari background (mis. ReviveReceiver) bisa ditolak sistem,
// jadi receiver tidak memanggil start() ini.
class WatchdogGuardService : Service() {
    companion object {
        private const val TAG = "SenzWatchdog"
        const val NOTIF_ID = 1202
        private const val CHECK_INTERVAL_MS = 60_000L

        // Idempotent: startForegroundService aman bila service sudah
        // jalan (onStartCommand dipanggil ulang, loop tidak digandakan
        // — lihat onStartCommand). Tidak pernah throw: start yang
        // ditolak sistem dicatat + dilaporkan false.
        fun start(ctx: Context): Boolean {
            val intent = Intent(ctx, WatchdogGuardService::class.java)
            val canForeground = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            return try {
                if (canForeground && Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "start: gagal menyalakan guardian: ${e.message}", e)
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var loopStarted = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!ApiToken.isEnabled(this@WatchdogGuardService)) {
                Log.i(TAG, "tick: saklar API mati — guardian berhenti")
                stopSelf()
                return
            }
            if (WatchdogWorker.isApiAlive()) {
                Log.d(TAG, "tick: /ping menjawab — API hidup")
            } else {
                Log.w(TAG, "tick: /ping tidak menjawab — menyalakan ApiService")
                val started = ApiService.start(this@WatchdogGuardService)
                Log.i(TAG, "tick: ApiService.start=$started")
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val canForeground = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canForeground) {
            // Tanpa izin notifikasi: jalan tanpa foreground (pola yang
            // sama seperti ApiService/KeepAliveService) — guard melemah
            // tapi service tetap jalan dan loop tetap memeriksa.
            Log.w(TAG, "onCreate: tanpa izin notifikasi — jalan tanpa foreground")
        } else {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 &&
                mgr.getNotificationChannel(WatchdogWorker.CHANNEL_ID) == null
            ) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        WatchdogWorker.CHANNEL_ID,
                        "Watchdog API",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Pemeriksaan berkala server API lokal SenzSpace"
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
            val notif: Notification = NotificationCompat.Builder(
                this, WatchdogWorker.CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SenzSpace watchdog aktif")
                .setContentText("Menjaga server API lokal tetap hidup")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            try {
                startForeground(NOTIF_ID, notif)
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: startForeground gagal: ${e.message}", e)
            }
        }
        Log.i(TAG, "onCreate: guardian jalan (proses :watchdog)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ApiToken.isEnabled(this)) {
            Log.i(TAG, "onStartCommand: saklar API mati — guardian tidak jalan")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!loopStarted) {
            loopStarted = true
            handler.post(checkRunnable)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        loopStarted = false
        Log.i(TAG, "onDestroy: guardian berhenti")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
