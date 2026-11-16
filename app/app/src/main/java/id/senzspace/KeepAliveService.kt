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

// Guard anti-kill Multi Live (S1, perintah Sendy 21:58 WIB — "harus bener
// bener matang"): tiap proses :spaceN jalanin foreground service-nya
// sendiri (START_STICKY) supaya sistem Android menganggap proses itu
// penting dan tidak membunuhnya saat butuh RAM. Tanpa ini, proses Space
// yang lama tidak dibuka bisa mati diam-diam (cached) dan thumbnail live
// jadi basi — sesi tetap aman (cookie sudah di disk), tapi "live"-nya mati.
// Service TIDAK mematikan Space sendiri: hidup sampai Space dihapus
// (KeepAlive.stop) atau app di-uninstall / force-stop.
// Notifikasi: satu grup "SenzSpace • Space berjalan" (importance LOW,
// tidak bunyi). Kalau izin notifikasi belum dikasih (Android 13+),
// service tetap jalan sebagai started service biasa (tanpa foreground)
// — guard melemah tapi app tidak crash.
open class KeepAliveService : Service() {
    companion object {
        const val CHANNEL_ID = "senzspace_keepalive"
        const val GROUP_KEY = "senzspace_live"
        const val SUMMARY_ID = 1000
        const val EXTRA_SUFFIX = "extra_suffix"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        private var lastSummaryText: String? = null

        /** Notifikasi ringkasan grup: SATU baris di panel ("SenzSpace •
         *  N Space berjalan") yang bisa dibuka (swipe down) untuk melihat
         *  semua Space. liveCount < 0 = jumlah tidak diketahui (teks umum).
         *  Hanya ditulis ulang bila teksnya berubah (hemat baterai).
         *  Dipanggil MainActivity tiap refresh + tiap service saat start. */
        fun postSummary(ctx: Context, liveCount: Int) {
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) return
                val text = if (liveCount < 0) {
                    "Space berjalan • Multi Live aktif"
                } else {
                    "$liveCount Space berjalan • Multi Live aktif"
                }
                if (text == lastSummaryText) return
                lastSummaryText = text
                val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26 &&
                    mgr.getNotificationChannel(CHANNEL_ID) == null
                ) {
                    mgr.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "Space berjalan",
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = "Menjaga tiap Space SenzSpace tetap hidup untuk Multi Live"
                        }
                    )
                }
                val openIntent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                val pending = PendingIntent.getActivity(
                    ctx, SUMMARY_ID, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val summary: Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("SenzSpace • Space berjalan")
                    .setContentText("$text • geser ke bawah untuk melihat semua Space")
                    .setContentIntent(pending)
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setOngoing(true)
                    .build()
                mgr.notify(SUMMARY_ID, summary)
            } catch (e: Exception) {
            }
        }

        fun cancelSummary(ctx: Context) {
            try {
                lastSummaryText = null
                val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.cancel(SUMMARY_ID)
            } catch (e: Exception) {
            }
        }

        /** Segarkan ringkasan dari Beranda: tampil bila ada service Space
         *  yang hidup (dicek dari notifikasi anak yang aktif), sembunyi
         *  bila tidak ada Space yang berjalan. */
        fun refreshSummary(ctx: Context, liveCount: Int) {
            try {
                val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val anyChild = if (Build.VERSION.SDK_INT >= 23) {
                    mgr.activeNotifications.any { it.id in 1101..1110 }
                } else liveCount > 0
                if (anyChild) postSummary(ctx, liveCount) else cancelSummary(ctx)
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val suffix = intent?.getStringExtra(EXTRA_SUFFIX) ?: "space"
        val name = intent?.getStringExtra(EXTRA_SPACE_NAME) ?: "Space"
        // ID notifikasi tetap per Space (1101–1110) — jangan pakai hash,
        // tabrakan ID bikin notifikasi service lain ikut kehapus.
        val notifId = when (suffix) {
            "space1" -> 1101
            "space2" -> 1102
            "space3" -> 1103
            "space4" -> 1104
            "space5" -> 1105
            "space6" -> 1106
            "space8" -> 1108
            "space9" -> 1109
            "space10" -> 1110
            "aistudio" -> 1107
            else -> 1100
        }
        try {
            ensureChannel()
            val openIntent = Intent(this, MainActivity::class.java).apply {
                // Jangan pakai `flags = ...` di sini: Intent.setFlags()
                // mengembalikan Intent (bukan void), jadi Kotlin hanya
                // menyediakan `flags` sebagai val — pakai addFlags().
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            val pending = PendingIntent.getActivity(
                this, notifId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SenzSpace — $name berjalan")
                .setContentText("Multi Live aktif • sesi tetap hidup")
                .setContentIntent(pending)
                .setGroup(GROUP_KEY)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startForeground(notifId, notif)
            }
            // Gabung notifikasi (perintah Sendy 22:29): tanpa ringkasan
            // grup, Android menampilkan 7 baris terpisah. Pastikan satu
            // notifikasi ringkasan (ID 1000) ada supaya panel hanya
            // menampilkan SATU baris yang bisa dibuka (swipe down) untuk
            // melihat semua Space yang berjalan.
            ensureSummary()
        } catch (e: Exception) {
            // Guard gagal diam-diam; Space tetap jalan sebagai cached process.
        }
        return START_STICKY
    }

    private fun ensureSummary() {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val hasSummary = if (Build.VERSION.SDK_INT >= 23) {
                mgr.activeNotifications.any { it.id == SUMMARY_ID }
            } else false
            if (!hasSummary) postSummary(this, -1)
        } catch (e: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Space berjalan",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Menjaga tiap Space SenzSpace tetap hidup untuk Multi Live"
                }
            )
        }
    }
}

/** Satu subclass per proses :spaceN — service jalan di proses yang
 *  tertulis di AndroidManifest (bukan di proses pemanggil). */
class KeepAliveSpace1Service : KeepAliveService()
class KeepAliveSpace2Service : KeepAliveService()
class KeepAliveSpace3Service : KeepAliveService()
class KeepAliveSpace4Service : KeepAliveService()
class KeepAliveSpace5Service : KeepAliveService()
class KeepAliveSpace6Service : KeepAliveService()
class KeepAliveSpace8Service : KeepAliveService()
class KeepAliveSpace9Service : KeepAliveService()
class KeepAliveSpace10Service : KeepAliveService()
class KeepAliveAiStudioService : KeepAliveService()

/** Helper: BrowserActivity memanggil start() saat Space dibuka;
 *  MainActivity memanggil stop() saat Space dihapus. */
object KeepAlive {
    private fun serviceClass(suffix: String): Class<out KeepAliveService> = when (suffix) {
        "space1" -> KeepAliveSpace1Service::class.java
        "space2" -> KeepAliveSpace2Service::class.java
        "space3" -> KeepAliveSpace3Service::class.java
        "space4" -> KeepAliveSpace4Service::class.java
        "space5" -> KeepAliveSpace5Service::class.java
        "space6" -> KeepAliveSpace6Service::class.java
        "space8" -> KeepAliveSpace8Service::class.java
        "space9" -> KeepAliveSpace9Service::class.java
        "space10" -> KeepAliveSpace10Service::class.java
        "aistudio" -> KeepAliveAiStudioService::class.java
        else -> KeepAliveSpace1Service::class.java
    }

    fun start(ctx: Context, suffix: String, spaceName: String) {
        try {
            val intent = Intent(ctx, serviceClass(suffix)).apply {
                putExtra(KeepAliveService.EXTRA_SUFFIX, suffix)
                putExtra(KeepAliveService.EXTRA_SPACE_NAME, spaceName)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            // Guard gagal — Space tetap jalan tanpa anti-kill.
        }
    }

    fun stop(ctx: Context, suffix: String) {
        try {
            ctx.stopService(Intent(ctx, serviceClass(suffix)))
        } catch (e: Exception) {
        }
    }
}
