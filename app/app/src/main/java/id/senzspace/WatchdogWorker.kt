package id.senzspace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
        const val UNIQUE_NAME = "senzspace-watchdog"
        const val CHANNEL_ID = "senzspace_watchdog"
        const val NOTIF_ID = 1201

        // Idempotent: KEEP bila sudah terdaftar. Tidak pernah throw —
        // pemanggil dari proses Space (:spaceN) tidak boleh mati
        // karena WorkManager utamanya hidup di proses utama.
        fun enqueue(context: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
            } catch (_: Exception) {
            }
        }

        // One-time expedited: dipakai ReviveReceiver. Start langsung
        // dari receiver di background hampir pasti ditolak (bukan
        // broadcast yang dikecualikan sistem), jadi serahkan ke
        // worker — worker setForeground dulu baru start, jalur yang
        // disahkan sistem untuk kerja background. Tidak pernah throw.
        fun enqueueNow(context: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<WatchdogWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueue(req)
            } catch (_: Exception) {
            }
        }
    }

    override fun doWork(): Result {
        // Hormati saklar API Kontrol: bila dimatikan, jangan nyalakan
        // apa pun (enqueue juga dicek di pemanggil, tapi saklar bisa
        // berubah sesudah enqueue).
        if (!ApiToken.isEnabled(applicationContext)) return Result.success()
        // Promosikan worker ke foreground service SEBELUM start
        // ApiService: startForegroundService dari background ditolak
        // Android 12+ (ForegroundServiceStartNotAllowedException).
        // Expedited saja tidak cukup — worker wajib setForeground.
        // Tunggu promosi selesai supaya start di bawah legal; bila
        // promosi gagal, tetap lanjut (ApiService.start punya
        // fallback sendiri dan tidak pernah throw).
        try {
            setForegroundAsync(createForegroundInfo())
                .get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        if (!isApiAlive()) {
            ApiService.start(applicationContext)
        }
        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val mgr = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Watchdog API",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Pemeriksaan berkala server API lokal SenzSpace"
                }
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SenzSpace watchdog")
            .setContentText("Memeriksa server API lokal")
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIF_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    // GET /ping harus balas HTTP 200. Timeout pendek: worker hanya
    // butuh tahu server menjawab atau tidak, bukan isi balasannya.
    private fun isApiAlive(): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", ApiServer.PORT), 2000)
            socket.soTimeout = 2000
            val out = socket.getOutputStream()
            out.write(
                "GET /ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n"
                    .toByteArray(Charsets.UTF_8)
            )
            out.flush()
            val first = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
            ).readLine() ?: ""
            first.contains("200")
        } catch (_: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}
