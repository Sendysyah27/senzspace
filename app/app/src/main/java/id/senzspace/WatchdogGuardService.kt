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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import org.json.JSONObject

// v0.1.72 — guardian watchdog: foreground service di proses sendiri
// (:watchdog) yang menjaga ApiService tetap hidup. v0.1.71 gagal di
// HP ini: start foreground service dari background ditolak sistem
// (receiver/worker tidak dikecualikan), WorkManager tidak pernah
// terjadwal, dan semua lapis menelan kegagalan diam-diam — revive
// dari VM tidak pernah menyalakan API. Guardian di-start dari
// MainActivity/BrowserActivity (start()) atau BootReceiver
// (startPlain); sejak v0.1.75 keduanya startService biasa —
// startForegroundService dihapus dari seluruh codebase. Sekali jalan,
// guardian hidup di prosesnya sendiri: foreground service
// (notifikasi LOW) → uid-nya foreground → boleh memanggil
// ApiService.startPlain() bila /ping tidak menjawab. Guardian selamat
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

        // v0.1.75: start() kini SAMA dengan startPlain — startService
        // biasa, tanpa startForegroundService. Alasan sama seperti
        // ApiService.start(): pemanggil "foreground"
        // (MainActivity/BrowserActivity) menembak saat app belum
        // foreground (rekreasi pasca-install) dan penantian FGS yang
        // tercipta tidak pernah terpenuhi bila onCreate melewati
        // startForeground (probe isAppVisible benar). Promosi ke
        // foreground tetap terjadi di onCreate bila app visible.
        // Idempotent: start aman bila service sudah jalan
        // (onStartCommand dipanggil ulang, loop tidak digandakan).
        // Tidak pernah throw: start yang ditolak sistem dicatat +
        // dilaporkan false.
        fun start(ctx: Context): Boolean {
            return startPlain(ctx)
        }

        // v0.1.74: start biasa (bukan startForegroundService) — tanpa
        // timeout FGS. Dipakai BootReceiver (background): guardian
        // jalan sebagai background service sampai app foreground, lalu
        // mempromosikan diri lewat startForeground di onCreate bila
        // isAppVisible. Pola sama seperti ApiService.startPlain.
        fun startPlain(ctx: Context): Boolean {
            return try {
                ctx.startService(Intent(ctx, WatchdogGuardService::class.java))
                true
            } catch (e: Exception) {
                Log.e(TAG, "startPlain: gagal menyalakan guardian: ${e.message}", e)
                false
            }
        }

        // v0.1.78: extra + promote() — sama seperti ApiService (lihat
        // sana): service yang sudah jalan tidak mengulang onCreate,
        // jadi promosi ulang butuh pemicu lewat onStartCommand.
        const val EXTRA_PROMOTE = "extra_promote"

        /** v0.1.78: minta guardian yang sudah jalan mempromosikan diri
         *  ke foreground (startService biasa + extra). */
        fun promote(ctx: Context): Boolean {
            return try {
                ctx.startService(
                    Intent(ctx, WatchdogGuardService::class.java).apply {
                        putExtra(EXTRA_PROMOTE, true)
                    }
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "promote: start gagal: ${e.message}", e)
                false
            }
        }

        // v0.1.78 — MemoryGuard: shed terkendali (bukan dibunuh LMK).
        // Broadcast dalam-app saja (pengirim setPackage + receiver
        // dinamis NOT_EXPORTED di BrowserActivity) — tidak ada
        // receiver di manifest, tidak ada aksi sistem.
        const val ACTION_SHED_SPACE = "id.senzspace.debug.action.SHED_SPACE"
        const val EXTRA_SHED_SLOT = "extra_shed_slot"

        /** Ambang shed: lowMemory ATAU RAM tersedia di bawah ini. */
        const val SHED_AVAIL_MB = 300L

        private const val GUARD_HEARTBEAT_FILE = "guard-heartbeat"
        private const val GUARD_STATE_FILE = "guard-state.json"

        /** Kirim perintah shed ke SATU slot (dipakai tick guard +
         *  POST /guard). v0.1.79: selain broadcast, JUGA menulis
         *  marker pending filesDir/shed-pending-<slot>-<epochMs>.json
         *  (atomik tmp+rename) supaya tick guard bisa menghitung
         *  shed yang dikirim dari proses lain (main) — counter
         *  HANYA dimutasi tick guard (satu penulis). Tick guard
         *  sendiri (memoryGuard) TIDAK menulis marker
         *  (writePendingMarker=false) karena menaikkan counter
         *  langsung — kalau dua-duanya jalan, satu shed dihitung
         *  ganda. */
        fun sendShedBroadcast(ctx: Context, slot: Int, writePendingMarker: Boolean = true) {
            try {
                val intent = Intent(ACTION_SHED_SPACE).apply {
                    setPackage(ctx.packageName)
                    putExtra(EXTRA_SHED_SLOT, slot)
                }
                ctx.sendBroadcast(intent)
                if (writePendingMarker) {
                    val now = System.currentTimeMillis()
                    val obj = JSONObject().apply {
                        put("slot", slot)
                        put("sent_ms", now)
                        put("source", "manual")
                    }
                    val f = File(ctx.filesDir, "shed-pending-$slot-$now.json")
                    val tmp = File(ctx.filesDir, "shed-pending-$slot-$now.json.tmp")
                    tmp.writeText(obj.toString())
                    if (!tmp.renameTo(f)) f.writeText(obj.toString())
                }
                Log.i(TAG, "sendShedBroadcast: slot=$slot")
            } catch (e: Exception) {
                Log.w(TAG, "sendShedBroadcast gagal: ${e.message}")
            }
        }

        /** v0.1.85 (audit F-API-02): tulis marker penolakan shed
         *  (dipakai POST /guard saat target foreground / mati —
         *  broadcast TIDAK dikirim). Pola sama seperti marker pending
         *  shed-pending (atomik tmp+rename); tick guard mengonsumsi
         *  marker ini jadi shed_refused_count — counter HANYA
         *  dimutasi tick guard (satu penulis, seperti shed_count). */
        fun writeShedRefusedMarker(ctx: Context, slot: Int, reason: String) {
            try {
                val now = System.currentTimeMillis()
                val obj = JSONObject().apply {
                    put("slot", slot)
                    put("refused_ms", now)
                    put("reason", reason)
                }
                val f = File(ctx.filesDir, "shed-refused-$slot-$now.json")
                val tmp = File(ctx.filesDir, "shed-refused-$slot-$now.json.tmp")
                tmp.writeText(obj.toString())
                if (!tmp.renameTo(f)) f.writeText(obj.toString())
                Log.i(TAG, "writeShedRefusedMarker: slot=$slot reason=$reason")
            } catch (e: Exception) {
                Log.w(TAG, "writeShedRefusedMarker gagal: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var loopStarted = false

    // v0.1.78 — penghitung shed (di memori guard + ditulis ke
    // guard-state.json tiap tick supaya bisa dibaca API).
    private var shedCount = 0
    private var lastShedMs = 0L

    // v0.1.79 — shed yang TERBUKTI mendarat (marker konfirmasi
    // shed-<suffix>.json dari Space sesudah webView.destroy()).
    // Watermark per suffix supaya marker yang sama tidak dihitung
    // dua kali (dipulihkan dari guard-state.json).
    private var shedConfirmedCount = 0
    private val lastConfirmedShedMsBySuffix = mutableMapOf<String, Long>()

    // v0.1.85 (audit F-API-02) — shed yang DITOLAK mentah-mentah
    // (target foreground / mati, marker shed-refused-<slot>-<ms>.json
    // dari proses utama). Dipulihkan dari guard-state.json.
    private var shedRefusedCount = 0

    // v0.1.86 (§3 P0-C) — revive otomatis Space mati: antrean slot
    // yang terdeteksi hilang (in-memory; dibangun ulang tiap tick
    // dari perbandingan registry vs proses hidup) + penghitung
    // revive (dipulihkan dari guard-state.json, seperti shed).
    private val reviveQueue = mutableSetOf<Int>()
    private var reviveCount = 0
    private var lastReviveMs = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!ApiToken.isEnabled(this@WatchdogGuardService)) {
                Log.i(TAG, "tick: saklar API mati — guardian berhenti")
                stopSelf()
                return
            }
            if (WatchdogWorker.isApiAlive(this@WatchdogGuardService)) {
                Log.d(TAG, "tick: /ping menjawab — API hidup")
            } else {
                Log.w(TAG, "tick: /ping tidak menjawab — menyalakan ApiService")
                val started = ApiService.startPlain(this@WatchdogGuardService)
                Log.i(TAG, "tick: ApiService.startPlain=$started")
            }
            // v0.1.78: MemoryGuard + heartbeat tiap tick (60 dtk).
            try {
                memoryGuardTick()
            } catch (e: Exception) {
                Log.w(TAG, "tick: MemoryGuard gagal: ${e.message}")
            }
            // v0.1.86 (§1 P0-A): retry promosi foreground berkala —
            // HANYA bila probe visible baru saja benar di tick ini
            // (bukan buta). Tanpa ini, promosi hanya terjadi di
            // onResume (AK-01/AK-02).
            try {
                if (ApiService.isAppVisible(this@WatchdogGuardService)) {
                    GuardPromoter.promoteAll(this@WatchdogGuardService)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tick: promoteAll gagal: ${e.message}")
            }
            // v0.1.86 (§3 P0-C): revive Space yang mati.
            try {
                reviveDeadSpaces()
            } catch (e: Exception) {
                Log.w(TAG, "tick: revive gagal: ${e.message}")
            }
            try {
                writeGuardState()
            } catch (e: Exception) {
                Log.w(TAG, "tick: tulis guard-state gagal: ${e.message}")
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        // v0.1.73: startForeground PERTAMA — notifikasi minimal dulu,
        // langsung. PendingIntent + teks panjang menyusul SESUDAH
        // foreground.
        // v0.1.74: startForeground HANYA bila app sedang foreground
        // (ApiService.isAppVisible); bila di background, panggilan itu
        // DITOLAK sistem — lewati sama sekali dan jalan terus sebagai
        // background service (loop tick tetap memeriksa /ping).
        // try/catch + Log.w sebagai jaring pengaman bila ROM melempar
        // alih-alih sekadar menolak.
        Log.i(TAG, "onCreate: masuk")
        super.onCreate()
        // v0.1.78: blok foreground dipindah ke promoteToForeground()
        // (bisa dipicu ulang dari onStartCommand via EXTRA_PROMOTE).
        // Pulihkan penghitung shed dari state file — guardian bisa
        // di-restart sistem, angka tidak boleh reset ke 0 diam-diam.
        restoreShedCounters()
        promoteToForeground()
        Log.i(TAG, "onCreate: guardian jalan (proses :watchdog)")
    }

    /** v0.1.78 — promosi ke foreground yang bisa dipicu ulang (bukan
     *  cuma sekali di onCreate). Isi = blok startForeground lama dari
     *  onCreate (v0.1.73/74): tanpa izin notifikasi (SDK 33+) jalan
     *  tanpa foreground; bila app di background, panggilan dilewati
     *  dan flag guard_needs_promotion diset supaya onResume berikut
     *  mencoba lagi. */
    private fun promoteToForeground() {
        val canForeground = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canForeground) {
            // Tanpa izin notifikasi: jalan tanpa foreground (pola yang
            // sama seperti ApiService/KeepAliveService) — guard melemah
            // tapi service tetap jalan dan loop tetap memeriksa.
            Log.w(TAG, "promoteToForeground: tanpa izin notifikasi — jalan tanpa foreground")
            return
        }
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
        val notif: Notification = NotificationCompat.Builder(
            this, WatchdogWorker.CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SenzSpace watchdog aktif")
            .setOngoing(true)
            .build()
        Log.i(TAG, "promoteToForeground: startForeground")
        if (ApiService.isAppVisible(this)) {
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
            val full: Notification = NotificationCompat.Builder(
                this, WatchdogWorker.CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SenzSpace watchdog aktif")
                .setContentText("Menjaga server API lokal tetap hidup")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            mgr.notify(NOTIF_ID, full)
        } catch (e: Exception) {
            Log.w(TAG, "promoteToForeground: lengkapi notifikasi gagal: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ApiToken.isEnabled(this)) {
            Log.i(TAG, "onStartCommand: saklar API mati — guardian tidak jalan")
            stopSelf()
            return START_NOT_STICKY
        }
        // v0.1.78: promosi ulang dari GuardPromoter (lihat ApiService).
        if (intent?.getBooleanExtra(EXTRA_PROMOTE, false) == true) {
            promoteToForeground()
        }
        if (!loopStarted) {
            loopStarted = true
            handler.post(checkRunnable)
        }
        return START_STICKY
    }

    /** v0.1.78 — MemoryGuard: saat RAM kritis, lepas (shed) SATU Space
     *  background per tick SECARA TERKENDALI — bukan dibunuh acak oleh
     *  LMK. Korban = Space yang prosesnya hidup, sedang TIDAK terlihat
     *  di foreground, snapshot terakhirnya TIDAK generating, dan paling
     *  lama tidak aktif (LRU). Bila semua Space background sedang
     *  generating → shed NOL, dicatat di log. URL terakhir Space yang
     *  di-shed tersimpan di snapshot (v0.1.77) — /open memulihkannya. */
    private fun memoryGuardTick() {
        // v0.1.79: konsumsi marker shed dulu (setiap tick, apa pun
        // kondisi RAM) — shed manual + konfirmasi dari Space.
        try {
            consumeShedMarkers()
        } catch (e: Exception) {
            Log.w(TAG, "memoryGuard: konsumsi marker shed gagal: ${e.message}")
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val availMb = info.availMem / (1024 * 1024)
        if (!info.lowMemory && availMb >= SHED_AVAIL_MB) return
        val procs = try {
            am.runningAppProcesses ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val prefix = "$packageName:"
        val foregroundSuffixes = procs.filter {
            it.processName.startsWith(prefix) &&
                (it.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        }.mapNotNull {
            it.processName.substringAfter(":", "").takeIf { s -> s.isNotEmpty() }
        }.toSet()
        val liveSuffixes = procs.mapNotNull { it.processName }
            .filter { it.startsWith(prefix) }
            .map { it.substringAfter(":") }
            .toSet()
        var victimSlot = -1
        var victimIdle = Long.MAX_VALUE
        for (entry in SpaceRegistry.load(this)) {
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            if (suffix in foregroundSuffixes) continue
            if (suffix !in liveSuffixes) continue
            if (snapshotGenerating(suffix)) continue
            val idle = lastActivityMs(suffix)
            if (idle < victimIdle) {
                victimIdle = idle
                victimSlot = entry.slot
            }
        }
        if (victimSlot < 0) {
            Log.i(
                TAG,
                "memoryGuard: RAM kritis (avail=${availMb}MB low=${info.lowMemory}) " +
                    "tapi tidak ada Space yang bisa di-shed"
            )
            return
        }
        // v0.1.79: tick guard TIDAK menulis marker pending
        // (writePendingMarker=false) — counter dinaikkan langsung
        // di bawah; kalau marker juga ditulis, shed dihitung ganda
        // (langsung + konsumsi marker).
        sendShedBroadcast(this, victimSlot, writePendingMarker = false)
        shedCount++
        lastShedMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "memoryGuard: shed slot=$victimSlot " +
                "(avail=${availMb}MB low=${info.lowMemory})"
        )
    }

    /** v0.1.86 (§3 P0-C) — revive otomatis Space yang mati.
     *  Tiap tick: bandingkan registry (slot yang "seharusnya hidup"
     *  = terdaftar DAN pernah dibuka — lihat everOpened) dengan
     *  proses yang sedang hidup. Slot yang hilang masuk antrean
     *  revive. Revive dari antrean HANYA bila DUA syarat terpenuhi:
     *  (a) API hidup (tanpa API tidak ada jalur /open internal);
     *  (b) tidak ada app asing di foreground — top package adalah
     *  SenzSpace sendiri, atau tidak ada app foreground sama sekali
     *  (layar mati / lock screen). Bila app asing foreground
     *  (mis. TikTok com.ss.android.ugc.trill) -> revive TETAP di
     *  antrean, coba lagi tick berikut — JANGAN merebut layar
     *  pengguna yang sedang memakai app lain. Revive maks 2 slot
     *  per tick supaya RAM tidak melonjak sekaligus sesudah
     *  kematian massal; revive ditahan juga saat RAM kritis
     *  (MemoryGuard akan shed lagi — hindari churn revive/shed).
     *  TIDAK ada state "Space dimatikan" di registry (SpaceRegistry
     *  hanya slot/nama/warna/url; Space yang DIHAPUS user tidak ada
     *  di registry) — jadi revive tidak pernah membangkitkan Space
     *  yang sengaja dihapus. Mekanisme revive = jalur /open internal
     *  yang sama dengan POST /open (SpaceLauncher.open +
     *  GuardPromoter.promoteAll); overlay permission membebaskan
     *  launch dari BAL (v0.1.42). */
    private fun reviveDeadSpaces() {
        if (!WatchdogWorker.isApiAlive(this)) {
            Log.d(TAG, "revive: API tidak menjawab — antrean ditahan")
            return
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        try {
            am.getMemoryInfo(memInfo)
        } catch (_: Exception) {
        }
        if (memInfo.lowMemory || memInfo.availMem / (1024 * 1024) < SHED_AVAIL_MB) {
            Log.d(TAG, "revive: RAM kritis — antrean ditahan")
            return
        }
        val procs = try {
            am.runningAppProcesses ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val prefix = "$packageName:"
        val liveSuffixes = procs.mapNotNull { it.processName }
            .filter { it.startsWith(prefix) }
            .map { it.substringAfter(":") }
            .toSet()
        val foreignForeground = procs.any {
            it.importance ==
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                !it.processName.startsWith(packageName)
        }
        for (entry in SpaceRegistry.load(this)) {
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            if (suffix in liveSuffixes) {
                reviveQueue.remove(entry.slot)
                continue
            }
            if (!everOpened(suffix)) continue
            reviveQueue.add(entry.slot)
        }
        if (reviveQueue.isEmpty()) return
        if (foreignForeground) {
            Log.i(
                TAG,
                "revive: app asing di foreground — ${reviveQueue.size} " +
                    "slot tetap antre ($reviveQueue)"
            )
            return
        }
        for (slot in reviveQueue.sorted().take(2)) {
            val entry = SpaceRegistry.load(this)
                .firstOrNull { it.slot == slot } ?: continue
            try {
                SpaceLauncher.open(this, entry)
                GuardPromoter.promoteAll(this)
                reviveQueue.remove(slot)
                reviveCount++
                lastReviveMs = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "revive: slot=$slot (${entry.name}) dibuka ulang " +
                        "via /open internal"
                )
            } catch (e: Exception) {
                Log.w(TAG, "revive: slot=$slot gagal: ${e.message}")
            }
        }
    }

    /** Proxy "pernah dibuka": file snapshot api-page-<suffix>.json
     *  ada (ditulis live capture saat Space dibuka). Space yang
     *  belum pernah dibuka tidak punya file ini. */
    private fun everOpened(suffix: String): Boolean {
        return try {
            File(filesDir, "api-page-$suffix.json").isFile
        } catch (_: Exception) {
            false
        }
    }

    /** v0.1.79 — konsumsi marker shed (satu-satunya tempat counter
     *  berubah selain kenaikan langsung memoryGuard):
     *  (1) shed-pending-<slot>-<epochMs>.json — shed yang dikirim
     *  dari proses lain (POST /guard): hapus sesudah dibaca,
     *  shedCount++, lastShedMs = maks(sent_ms).
     *  (2) shed-<suffix>.json — konfirmasi Space sesudah
     *  webView.destroy(): JANGAN dihapus (bukti terakhir yang
     *  dibaca verifikasi live); hitung sekali per marker baru
     *  (watermark per suffix di lastConfirmedShedMsBySuffix).
     *  v0.1.85 (audit F-API-02):
     *  (3) shed-refused-<slot>-<epochMs>.json — shed yang DITOLAK
     *  POST /guard (target foreground / mati): hapus sesudah
     *  dibaca, shedRefusedCount++. */
    private fun consumeShedMarkers() {
        try {
            val refused = filesDir.listFiles { f ->
                f.isFile && f.name.startsWith("shed-refused-") &&
                    f.name.endsWith(".json")
            } ?: emptyArray()
            for (f in refused) {
                var refusedMs = 0L
                var reason = ""
                try {
                    val obj = JSONObject(f.readText())
                    refusedMs = obj.optLong("refused_ms", 0L)
                    reason = obj.optString("reason", "")
                } catch (_: Exception) {
                }
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                shedRefusedCount++
                Log.i(
                    TAG,
                    "consumeShedMarkers: shed ditolak dikonsumsi " +
                        "(refused_ms=$refusedMs, reason=$reason, " +
                        "shed_refused_count=$shedRefusedCount)"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "consumeShedMarkers: penolakan gagal: ${e.message}")
        }
        try {
            val pending = filesDir.listFiles { f ->
                f.isFile && f.name.startsWith("shed-pending-") &&
                    f.name.endsWith(".json")
            } ?: emptyArray()
            for (f in pending) {
                var sentMs = 0L
                try {
                    sentMs = JSONObject(f.readText()).optLong("sent_ms", 0L)
                } catch (_: Exception) {
                }
                if (sentMs <= 0) {
                    sentMs = f.name.removePrefix("shed-pending-")
                        .removeSuffix(".json").substringAfterLast("-")
                        .toLongOrNull() ?: 0L
                }
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                shedCount++
                if (sentMs > lastShedMs) lastShedMs = sentMs
                Log.i(
                    TAG,
                    "consumeShedMarkers: shed manual dikonsumsi " +
                        "(sent_ms=$sentMs, shed_count=$shedCount)"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "consumeShedMarkers: pending gagal: ${e.message}")
        }
        try {
            for (slot in 1..10) {
                val suffix = SpaceRegistry.suffixForSlot(slot)
                val f = File(filesDir, "shed-$suffix.json")
                if (!f.isFile) continue
                val shedMs = try {
                    JSONObject(f.readText()).optLong("shed_ms", 0L)
                } catch (_: Exception) {
                    0L
                }
                val seen = lastConfirmedShedMsBySuffix[suffix] ?: 0L
                if (shedMs > 0 && shedMs > seen) {
                    shedConfirmedCount++
                    lastConfirmedShedMsBySuffix[suffix] = shedMs
                    Log.i(
                        TAG,
                        "consumeShedMarkers: shed terkonfirmasi $suffix " +
                            "(shed_ms=$shedMs, confirmed=$shedConfirmedCount)"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "consumeShedMarkers: konfirmasi gagal: ${e.message}")
        }
    }

    /** Snapshot terakhir Space ini bilang generating? File tidak ada /
     *  tidak bisa dibaca = tidak generating (Space belum pernah
     *  snapshot — prosesnya pun biasanya belum hidup). */
    private fun snapshotGenerating(suffix: String): Boolean {
        return try {
            val f = File(filesDir, "api-page-$suffix.json")
            if (!f.isFile) false
            else JSONObject(f.readText()).optBoolean("generating", false)
        } catch (_: Exception) {
            false
        }
    }

    /** Aktivitas terakhir Space (untuk LRU): DOM/piksel terakhir
     *  berubah (file _live/<suffix>-status.txt) atau mtime thumbnail;
     *  0 = tidak pernah aktif. */
    private fun lastActivityMs(suffix: String): Long {
        return try {
            val f = FileStore.liveStatusFile(this, suffix)
            if (f.exists()) {
                val p = f.readText().trim().split("|")
                val dom = p.getOrNull(1)?.toLongOrNull() ?: 0L
                val pix = p.getOrNull(2)?.toLongOrNull() ?: 0L
                maxOf(dom, pix).takeIf { it > 0 } ?: thumbMs(suffix)
            } else {
                thumbMs(suffix)
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun thumbMs(suffix: String): Long {
        return try {
            val t = FileStore.liveThumbFile(this, suffix)
            if (t.exists()) t.lastModified() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** v0.1.78 — heartbeat + state guard (atomik tmp+rename, pola
     *  api-token v0.1.77): guard-heartbeat = epoch ms tick terakhir
     *  saja; guard-state.json = heartbeat + shed_count + last_shed_ms
     *  + fgs per Space. v0.1.79: + shed_confirmed_count +
     *  last_confirmed_shed_ms (watermark per suffix). Dibaca
     *  /status, /spaces, /guard di proses utama. */
    private fun writeGuardState() {
        val now = System.currentTimeMillis()
        try {
            val hb = File(filesDir, GUARD_HEARTBEAT_FILE)
            val tmpHb = File(filesDir, "$GUARD_HEARTBEAT_FILE.tmp")
            tmpHb.writeText(now.toString())
            if (!tmpHb.renameTo(hb)) hb.writeText(now.toString())
        } catch (_: Exception) {
        }
        try {
            val obj = JSONObject().apply {
                put("heartbeat_ms", now)
                put("shed_count", shedCount)
                put("last_shed_ms", lastShedMs)
                put("shed_confirmed_count", shedConfirmedCount)
                // v0.1.85 (audit F-API-02): shed yang ditolak.
                put("shed_refused_count", shedRefusedCount)
                // v0.1.86 (§3 P0-C): antrean + hitung revive otomatis.
                val reviveArr = org.json.JSONArray()
                for (slot in reviveQueue.sorted()) reviveArr.put(slot)
                put("revive_queue", reviveArr)
                put("revive_count", reviveCount)
                put("last_revive_ms", lastReviveMs)
                val confirmed = JSONObject()
                for ((suffix, ms) in lastConfirmedShedMsBySuffix) {
                    confirmed.put(suffix, ms)
                }
                put("last_confirmed_shed_ms", confirmed)
                val fgs = JSONObject()
                for ((suffix, fg) in foregroundServiceMap()) fgs.put(suffix, fg)
                put("fgs", fgs)
            }
            val f = File(filesDir, GUARD_STATE_FILE)
            val tmp = File(filesDir, "$GUARD_STATE_FILE.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) f.writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    /** KeepAlive service mana yang sedang foreground — via
     *  ActivityManager.getRunningServices (milik app sendiri).
     *  Dipakai /spaces field fgs. */
    private fun foregroundServiceMap(): Map<String, Boolean> {
        val out = mutableMapOf<String, Boolean>()
        for (slot in 1..10) out[SpaceRegistry.suffixForSlot(slot)] = false
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val running = am.getRunningServices(Int.MAX_VALUE) ?: return out
            for (svc in running) {
                // className = nama kelas (id.senzspace.KeepAlive...),
                // BUKAN applicationId — tanpa suffix ".debug".
                val cls = svc.service.className
                if (!cls.startsWith("id.senzspace.KeepAlive")) continue
                val suffix = when {
                    cls.endsWith("KeepAliveAiStudioService") -> "aistudio"
                    else -> {
                        val m = Regex("KeepAliveSpace(\\d+)Service").find(cls)
                        if (m != null) "space${m.groupValues[1]}" else null
                    }
                } ?: continue
                if (svc.foreground) out[suffix] = true
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Pulihkan penghitung shed dari state file (best effort). */
    private fun restoreShedCounters() {
        try {
            val f = File(filesDir, GUARD_STATE_FILE)
            if (f.isFile) {
                val obj = JSONObject(f.readText())
                shedCount = obj.optInt("shed_count", 0)
                lastShedMs = obj.optLong("last_shed_ms", 0L)
                shedConfirmedCount = obj.optInt("shed_confirmed_count", 0)
                // v0.1.85 (audit F-API-02): pulihkan penolakan.
                shedRefusedCount = obj.optInt("shed_refused_count", 0)
                // v0.1.86 (§3 P0-C): pulihkan hitung revive (antrean
                // dibangun ulang tiap tick — tidak perlu dipulihkan).
                reviveCount = obj.optInt("revive_count", 0)
                lastReviveMs = obj.optLong("last_revive_ms", 0L)
                val confirmed = obj.optJSONObject("last_confirmed_shed_ms")
                if (confirmed != null) {
                    val keys = confirmed.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        lastConfirmedShedMsBySuffix[k] = confirmed.optLong(k, 0L)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        loopStarted = false
        Log.i(TAG, "onDestroy: guardian berhenti")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
