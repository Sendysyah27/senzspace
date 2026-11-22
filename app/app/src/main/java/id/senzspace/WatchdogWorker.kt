package id.senzspace

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

// v0.1.71 — watchdog ApiService (WorkManager periodik 15 mnt, minimum
// sistem). ApiService hanya di-start dari MainActivity, BootReceiver,
// dan toggle Pengaturan — bila proses utama mati sementara Space
// selamat (atau activity Space dibuka langsung tanpa Beranda), API
// mati dan tidak ada yang menyalakan lagi; START_STICKY tidak
// me-restart service di bawah tekanan memori. Worker ini mengecek
// ApiServer lewat ping /ping tiap 15 mnt dan menyalakan ApiService
// bila tidak menjawab. Ping memakai Socket mentah (bukan
// HttpURLConnection): tanpa proxy dan tidak kena larangan cleartext
// (usesCleartextTraffic=false). Worker TIDAK menjaga KeepAlive
// per-Space — itu penjaga Space, bukan penjaga ApiService.
class WatchdogWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    companion object {
        private const val TAG = "SenzWatchdog"
        const val UNIQUE_NAME = "senzspace-watchdog"
        const val CHANNEL_ID = "senzspace_watchdog"

        // Idempotent: KEEP bila sudah terdaftar. Tidak pernah throw —
        // pemanggil dari proses Space (:spaceN) tidak boleh mati
        // karena WorkManager utamanya hidup di proses utama.
        // v0.1.74: TANPA setExpedited — periodic work TIDAK BOLEH
        // expedited di perangkat ini ("PeriodicWorkRequests cannot
        // be expedited", logcat 06:38 v0.1.73) dan enqueue gagal total.
        fun enqueue(context: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
                Log.d(TAG, "enqueue: watchdog periodik didaftarkan (KEEP)")
            } catch (e: Exception) {
                Log.w(TAG, "enqueue: gagal mendaftarkan watchdog: ${e.message}")
            }
        }

        // One-time biasa (tanpa expedited): dipakai ReviveReceiver.
        // v0.1.76: TANPA setExpedited DAN TANPA setForegroundAsync —
        // expedited work WAJIB foreground, dan promosi foreground
        // worker MENCRASHKAN proses utama (SystemForegroundService
        // memanggil startForeground DATA_SYNC, tapi merged manifest
        // tidak mendeklarasikan foregroundServiceType untuk service
        // itu → IllegalArgumentException ~0,5 dtk sesudah start).
        // Worker hanya ping socket + startPlain — tidak butuh
        // foreground; one-time biasa cukup. Tidak pernah throw.
        fun enqueueNow(context: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<WatchdogWorker>()
                    .build()
                WorkManager.getInstance(context).enqueue(req)
                Log.d(TAG, "enqueueNow: one-time work didaftarkan")
            } catch (e: Exception) {
                Log.w(TAG, "enqueueNow: gagal mendaftarkan one-time work: ${e.message}")
            }
        }

        // GET /ping harus balas HTTP 200. Timeout pendek: pemanggil
        // hanya butuh tahu server menjawab atau tidak, bukan isi
        // balasannya. v0.1.72: pindah ke companion supaya dipakai
        // ulang WatchdogGuardService (guardian :watchdog) tanpa
        // duplikasi — pola Socket mentah yang sama (tanpa proxy,
        // tidak kena larangan cleartext).
        // v0.1.75 (audit #14): kirim Authorization Bearer — /ping
        // ikut dicek token di handle(), jadi tanpa token probe
        // SELALU lapor mati dan guardian startPlain tanpa henti.
        fun isApiAlive(ctx: Context): Boolean {
            var socket: Socket? = null
            return try {
                socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", ApiServer.PORT), 2000)
                socket.soTimeout = 2000
                val out = socket.getOutputStream()
                out.write(
                    ("GET /ping HTTP/1.0\r\nHost: 127.0.0.1\r\n" +
                        "Authorization: Bearer ${ApiToken.getOrCreate(ctx)}\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                val first = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                ).readLine() ?: ""
                first.contains("200")
            } catch (e: Exception) {
                Log.w(TAG, "isApiAlive: ping gagal: ${e.message}")
                false
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun doWork(): Result {
        // Hormati saklar API Kontrol: bila dimatikan, jangan nyalakan
        // apa pun (enqueue juga dicek di pemanggil, tapi saklar bisa
        // berubah sesudah enqueue).
        if (!ApiToken.isEnabled(applicationContext)) return Result.success()
        // v0.1.76: TIDAK ADA promosi foreground di sini. doWork()
        // hanya ping socket + startPlain — worker jalan sebagai
        // background worker biasa. setForegroundAsync DIHAPUS karena
        // mencrashkan proses utama: SystemForegroundService
        // memanggil startForeground DATA_SYNC, tapi merged manifest
        // tidak mendeklarasikan foregroundServiceType untuk service
        // itu (atribut 0x0) → IllegalArgumentException ~0,5 dtk
        // sesudah start, tiap buka app (FC sejak v0.1.74 — saat
        // enqueue periodik akhirnya berhasil dan worker jalan
        // pertama kali). Try/catch di sekitar setForegroundAsync
        // TIDAK menolong: exception dilempar asinkron di main thread
        // di dalam Handler SystemForegroundService sendiri.
        if (!isApiAlive(applicationContext)) {
            Log.w(TAG, "doWork: /ping tidak menjawab — menyalakan ApiService")
            val started = ApiService.startPlain(applicationContext)
            Log.i(TAG, "doWork: ApiService.startPlain=$started")
        } else {
            Log.d(TAG, "doWork: /ping menjawab — API hidup")
        }
        return Result.success()
    }

    // v0.1.72: isApiAlive pindah ke companion object (dipakai ulang
    // WatchdogGuardService) — lihat atas.
    // v0.1.76: createForegroundInfo() DIHAPUS (lihat doWork) — worker
    // tidak lagi promosi foreground.
}
