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
        private const val TAG = "SenzWatchdog"

        // v0.1.75: start() kini SAMA dengan startPlain — startService
        // biasa, tanpa startForegroundService. v0.1.74 menunjukkan
        // pemanggil "foreground" (MainActivity/BrowserActivity) juga
        // menembak saat app belum foreground (rekreasi pasca-install):
        // startForegroundService menciptakan penantian FGS yang tidak
        // pernah terpenuhi bila onCreate melewati startForeground
        // (probe isAppVisible benar), lalu sistem membunuh service.
        // Promosi ke foreground tetap terjadi di onCreate bila app
        // visible. Kembalikan Boolean — true bila start terpanggil,
        // false bila ditolak sistem (tidak pernah ditelan diam-diam).
        fun start(ctx: Context): Boolean {
            return startPlain(ctx)
        }

        // v0.1.73: start biasa (bukan startForegroundService) — tanpa
        // timeout FGS. Dipakai guardian (uid-nya sudah foreground)
        // untuk menyalakan ApiService yang lambat start tanpa dibunuh
        // sistem; ApiService mempromosikan diri ke foreground lewat
        // startForeground pertama di onCreate-nya sendiri.
        // v0.1.74: dipakai JUGA semua pemanggil dari background
        // (BootReceiver, ReviveReceiver, WatchdogWorker.doWork) —
        // startForegroundService dari background membuat ServiceRecord
        // menunggu startForeground yang sistem TOLAK mentah-mentah
        // ("not allowed due to bg restriction") lalu membunuh service
        // lewat timeout; startService biasa tidak punya penantian itu.
        fun startPlain(ctx: Context): Boolean {
            return try {
                ctx.startService(Intent(ctx, ApiService::class.java))
                true
            } catch (e: Exception) {
                Log.e(TAG, "startPlain: start gagal: ${e.message}", e)
                false
            }
        }

        // v0.1.78: extra untuk memicu promoteToForeground() dari
        // onStartCommand — service yang sudah jalan sebagai background
        // tidak mengulang onCreate, jadi tanpa extra ini start ulang
        // tidak mempromosikan apa pun.
        const val EXTRA_PROMOTE = "extra_promote"

        /** v0.1.78: minta service yang sudah jalan mempromosikan diri
         *  ke foreground (startService biasa + extra — tanpa
         *  startForegroundService, pelajaran v0.1.74/75). Dipakai
         *  GuardPromoter.promoteAll() saat app foreground. */
        fun promote(ctx: Context): Boolean {
            return try {
                ctx.startService(
                    Intent(ctx, ApiService::class.java).apply {
                        putExtra(EXTRA_PROMOTE, true)
                    }
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "promote: start gagal: ${e.message}", e)
                false
            }
        }

        // v0.1.74: probe murah apakah app sedang foreground (ada
        // activity yang terlihat). Dipakai onCreate/onStartCommand
        // sebelum startForeground(): bila app di background, panggilan
        // itu DITOLAK sistem dan — bila service di-start lewat
        // startForegroundService — ServiceRecord menunggu sampai
        // dibunuh timeout. Ambang FOREGROUND (100) / VISIBLE (200)
        // saja: TOP_SLEEPING (150, layar mati) dan FOREGROUND_SERVICE
        // (125, service lain yang sudah foreground) TIDAK dihitung —
        // keduanya bukan activity yang terlihat. Probe baca proses app
        // sendiri lewat ActivityManager — lintas proses, tanpa static
        // antar-proses (UX 31: static hidup per-proses). Bila probe
        // gagal, kembalikan true supaya perilaku lama (coba
        // startForeground + try/catch) yang berlaku.
        fun isAppVisible(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                    as ActivityManager
                val procs = am.runningAppProcesses ?: return true
                procs.any {
                    it.processName.startsWith(ctx.packageName) &&
                        (it.importance ==
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                            it.importance ==
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "isAppVisible: probe gagal — anggap foreground: ${e.message}")
                true
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ApiService::class.java))
        }
    }

    private var server: ApiServer? = null

    override fun onCreate() {
        // v0.1.73: startForeground PERTAMA — notifikasi minimal dulu,
        // langsung. Channel + PendingIntent + teks panjang menyusul
        // SESUDAH foreground.
        // v0.1.74: startForeground HANYA bila app sedang foreground
        // (isAppVisible); bila di background, panggilan itu DITOLAK
        // sistem — lewati sama sekali dan jalan terus sebagai
        // background service (API tetap menjawab poll begitu,
        // 06:36–06:38 v0.1.73). try/catch + Log.w sebagai jaring
        // pengaman bila ROM melempar alih-alih sekadar menolak.
        // v0.1.78: blok foreground dipindah ke promoteToForeground()
        // (bisa dipicu ulang dari onStartCommand via EXTRA_PROMOTE).
        Log.i(TAG, "onCreate: masuk")
        super.onCreate()
        // v0.1.85 (audit CODE-15): saklar API mati = service tidak
        // perlu jalan sama sekali — jangan buat server di bawah.
        // Semua pemanggil start() sudah mengecek isEnabled; ini
        // jaring pengaman supaya ApiServer TIDAK PERNAH mendengarkan
        // saat saklar mati, dari jalur mana pun ia di-start.
        if (!ApiToken.isEnabled(this)) {
            Log.i(TAG, "onCreate: saklar API mati — service berhenti")
            stopSelf()
            return
        }
        promoteToForeground()
        server = ApiServer(applicationContext).also { it.start() }
        // v0.1.78: mutual watchdog — API memastikan guard jalan
        // (guard memastikan API jalan di tick-nya); start biasa,
        // tanpa startForegroundService (pelajaran v0.1.74/75).
        if (ApiToken.isEnabled(this)) WatchdogGuardService.startPlain(this)
    }

    /** v0.1.78 — promosi ke foreground yang bisa dipicu ulang (bukan
     *  cuma sekali di onCreate): GuardPromoter memanggil promote()
     *  saat app foreground, dan onStartCommand dengan EXTRA_PROMOTE
     *  menjalankan blok yang sama. Isi = blok startForeground lama
     *  dari onCreate (v0.1.73/74): tanpa izin notifikasi (SDK 33+)
     *  jalan tanpa foreground; bila app di background, panggilan
     *  dilewati dan flag guard_needs_promotion diset supaya onResume
     *  berikut mencoba lagi. */
    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Tanpa izin notifikasi: jalan tanpa foreground.
            Log.w(TAG, "promoteToForeground: tanpa izin notifikasi — jalan tanpa foreground")
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
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SenzSpace API aktif")
            .setOngoing(true)
            .build()
        Log.i(TAG, "promoteToForeground: startForeground")
        if (isAppVisible(this)) {
            try {
                startForeground(NOTIF_ID, notif)
                Log.i(TAG, "promoteToForeground: foreground jalan")
            } catch (e: Exception) {
                Log.w(TAG, "promoteToForeground: startForeground ditolak — jalan sebagai background service: ${e.message}")
                GuardPromoter.markNeedsPromotion(this)
            }
        } else {
            Log.i(TAG, "promoteToForeground: app di background — lewati startForeground, jalan sebagai background service")
            GuardPromoter.markNeedsPromotion(this)
        }
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            val pending = PendingIntent.getActivity(
                this, NOTIF_ID, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val full: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SenzSpace API aktif")
                .setContentText(
                    "Kontrol lokal di 127.0.0.1:${ApiServer.PORT} — hanya dari HP ini"
                )
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            mgr.notify(NOTIF_ID, full)
        } catch (e: Exception) {
            Log.w(TAG, "promoteToForeground: lengkapi notifikasi gagal: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.1.78: promosi ulang dari GuardPromoter — service sudah
        // jalan sebagai background (onCreate tidak jalan lagi), jadi
        // startService biasa saja tidak mempromosikan. Extra ini
        // menjalankan blok startForeground yang sama seperti onCreate.
        if (intent?.getBooleanExtra(EXTRA_PROMOTE, false) == true) {
            promoteToForeground()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
