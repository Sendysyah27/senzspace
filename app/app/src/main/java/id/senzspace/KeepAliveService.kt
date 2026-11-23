package id.senzspace

import android.app.ActivityManager
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
import android.util.Log
import androidx.core.app.NotificationCompat

// Guard anti-kill Multi Live (S1): tiap proses :spaceN menjalankan
// foreground service-nya sendiri (START_STICKY) supaya sistem Android
// menganggap proses itu
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
        private const val TAG = "SenzWatchdog"
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
        // v0.1.73: startForeground PERTAMA — notifikasi minimal dulu
        // (icon + judul saja), langsung. PendingIntent (perlu baca
        // registry) + teks menyusul sesudah foreground lewat notify.
        // v0.1.74: startForeground HANYA bila app sedang foreground
        // (ApiService.isAppVisible); bila di background, panggilan itu
        // DITOLAK sistem — lewati sama sekali dan jalan terus sebagai
        // background service. try/catch + Log.w sebagai jaring
        // pengaman bila ROM melempar alih-alih sekadar menolak.
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ensureChannel()
            val minimal: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SenzSpace — $name berjalan")
                .setGroup(GROUP_KEY)
                .setOngoing(true)
                .build()
            if (ApiService.isAppVisible(this)) {
                try {
                    startForeground(notifId, minimal)
                } catch (e: Exception) {
                    Log.w(TAG, "onStartCommand: startForeground ditolak — jalan sebagai background service: ${e.message}")
                    // v0.1.78: tandai supaya onResume berikut mempromosikan ulang.
                    GuardPromoter.markNeedsPromotion(this)
                }
            } else {
                Log.i(TAG, "onStartCommand: app di background — lewati startForeground, jalan sebagai background service")
                // v0.1.78: tandai supaya onResume berikut mempromosikan ulang.
                GuardPromoter.markNeedsPromotion(this)
            }
        }
        try {
            // v0.1.31: tap notifikasi Space ini LANGSUNG membuka Space-nya
            // (bukan cuma Beranda). Intent menarget activity Space itu
            // dengan nama/warna/URL dari registry.
            val openIntent = spaceOpenIntent(suffix, name)
            val pending = PendingIntent.getActivity(
                this, notifId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SenzSpace — $name berjalan")
                .setContentText("Tap untuk membuka Space ini")
                .setContentIntent(pending)
                .setGroup(GROUP_KEY)
                .setOngoing(true)
                .build()
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(notifId, notif)
            // Gabung notifikasi: tanpa ringkasan
            // grup, Android menampilkan 7 baris terpisah. Pastikan satu
            // notifikasi ringkasan (ID 1000) ada supaya panel hanya
            // menampilkan SATU baris yang bisa dibuka (swipe down) untuk
            // melihat semua Space yang berjalan.
            ensureSummary()
        } catch (e: Exception) {
            // Pengayaan notifikasi gagal; notifikasi minimal tetap ada.
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

    /** v0.1.31: intent untuk membuka Space bersuffix ini (dipakai
     *  notifikasi per-Space). Jatuh kembali ke Beranda bila slot
     *  tidak dikenal. */
    private fun spaceOpenIntent(suffix: String, spaceName: String): Intent {
        val slot = when (suffix) {
            "space1" -> 1
            "space2" -> 2
            "space3" -> 3
            "space4" -> 4
            "space5" -> 5
            "space6" -> 6
            "aistudio" -> 7
            "space8" -> 8
            "space9" -> 9
            "space10" -> 10
            else -> null
        } ?: return Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val entry = try {
            SpaceRegistry.load(this).firstOrNull { it.slot == slot }
        } catch (e: Exception) {
            null
        }
        return Intent(this, SpaceRegistry.activityForSlot(slot)).apply {
            // Jangan pakai `flags = ...`: Intent.setFlags() mengembalikan
            // Intent (bukan void), jadi Kotlin hanya menyediakan `flags`
            // sebagai val — pakai addFlags().
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(MainActivity.EXTRA_SPACE_NAME, entry?.name ?: spaceName)
            putExtra(MainActivity.EXTRA_SPACE_COLOR, entry?.color ?: "#5B5BD6")
            putExtra(MainActivity.EXTRA_URL, entry?.url ?: "https://claude.ai")
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
            // v0.1.75: startService biasa — startForegroundService
            // dihapus dari seluruh codebase. Promosi ke foreground
            // tetap terjadi di onStartCommand bila app visible.
            ctx.startService(intent)
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

/** v0.1.78 — GuardPromoter: promosikan semua service guard menjadi
 *  foreground service saat app PASTI foreground. Latar: service hanya
 *  mempromosikan diri di onCreate/onStartCommand bila app sedang
 *  visible (isAppVisible, v0.1.74); Space hampir selalu di-start dari
 *  background (API / boot / revive) → service jalan sebagai background
 *  service → proses Space = cached → LMK membunuh paling dulu. Sekali
 *  dipromosikan, service TETAP foreground walau app kembali background
 *  (yang dilarang Android 12+ hanya MEMULAI FGS dari background, bukan
 *  mempertahankannya). Dipanggil dari MainActivity.onResume +
 *  BrowserActivity.onResume (bila flag guard_needs_promotion set) dan
 *  handler API /open (sesudah Space dibawa ke depan). */
object GuardPromoter {
    private const val TAG = "SenzWatchdog"
    private const val PREFS = "senzspace_ui"
    private const val KEY_NEEDS_PROMOTION = "guard_needs_promotion"

    fun markNeedsPromotion(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NEEDS_PROMOTION, true).apply()
        } catch (_: Exception) {
        }
    }

    fun needsPromotion(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NEEDS_PROMOTION, false)
        } catch (_: Exception) {
            false
        }
    }

    fun clearNeedsPromotion(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NEEDS_PROMOTION, false).apply()
        } catch (_: Exception) {
        }
    }

    /** Promosi bila flag set (dipakai onResume). */
    fun promoteIfNeeded(ctx: Context) {
        if (needsPromotion(ctx)) promoteAll(ctx)
    }

    /** Promosikan SEMUA service guard yang sedang jalan. KeepAlive
     *  hanya untuk Space yang prosesnya sedang hidup — jangan
     *  bangkitkan Space yang sudah mati (RAM justru bertambah, LMK
     *  makin cepat membunuh). ApiService + WatchdogGuardService selalu
     *  dipromosikan (bila saklar API hidup). Idempotent: startService
     *  aman bila service sudah jalan. */
    fun promoteAll(ctx: Context) {
        try {
            if (ApiToken.isEnabled(ctx)) {
                ApiService.promote(ctx)
                WatchdogGuardService.promote(ctx)
            }
            val guardOn = try {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean("guard_enabled", true)
            } catch (_: Exception) {
                true
            }
            if (guardOn) {
                val live = liveSpaceSuffixes(ctx)
                for (entry in SpaceRegistry.load(ctx)) {
                    val suffix = SpaceRegistry.suffixForSlot(entry.slot)
                    if (suffix in live) KeepAlive.start(ctx, suffix, entry.name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "promoteAll gagal: ${e.message}")
        }
        clearNeedsPromotion(ctx)
    }

    /** Suffix Space yang prosesnya sedang hidup. */
    private fun liveSpaceSuffixes(ctx: Context): Set<String> {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.mapNotNull { it.processName }
                ?.filter { it.startsWith(ctx.packageName + ":") }
                ?.map { it.substringAfter(":") }
                ?.filter { it.startsWith("space") || it == "aistudio" }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
