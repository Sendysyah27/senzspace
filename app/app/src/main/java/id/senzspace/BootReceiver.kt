package id.senzspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** v0.1.55 — hidupkan server API sesudah HP reboot tanpa buka app.
 *  Sebelum ini ApiService hanya start dari MainActivity.onCreate,
 *  jadi sesudah reboot API mati sampai app dibuka manual. Receiver
 *  ini jalan sekali tiap BOOT_COMPLETED;
 *  ApiToken.isEnabled = saklar "API Kontrol" di Pengaturan.
 *  v0.1.57: tambah MY_PACKAGE_REPLACED — hidupkan ApiService sesudah
 *  app di-update/di-install ulang, tanpa buka app sekali. Aksi ini
 *  hanya dikirim sistem ke app sendiri, tanpa izin baru. Pola start
 *  sama persis seperti BOOT_COMPLETED (ApiService.start, yang sudah
 *  menangani cabang izin notifikasi sendiri).
 *  v0.1.71: daftarkan juga watchdog API (WorkManager 15 mnt) di
 *  kedua aksi — watchdog yang menghidupkan lagi ApiService bila
 *  proses utama mati di luar reboot/update.
 *  v0.1.72: nyalakan juga guardian watchdog (:watchdog) di kedua
 *  aksi — proses sendiri yang memeriksa /ping tiap 60 dtk dan
 *  menyalakan ApiService bila tidak menjawab.
 *  v0.1.74: dari receiver (background) JANGAN PERNAH pakai
 *  startForegroundService — sistem menolak startForeground saat HP
 *  tidur ("not allowed due to bg restriction") lalu membunuh kedua
 *  service lewat timeout FGS. Pakai startPlain (startService biasa):
 *  service jalan sebagai background service (API tetap menjawab
 *  poll begitu) dan mempromosikan diri ke foreground sendiri bila
 *  app foreground (isAppVisible di onCreate). */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SenzWatchdog"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (ApiToken.isEnabled(context)) {
                val apiStarted = ApiService.startPlain(context)
                val guardStarted = WatchdogGuardService.startPlain(context)
                Log.i(
                    TAG,
                    "onReceive ${intent.action}: " +
                        "ApiService.startPlain=$apiStarted guardPlain=$guardStarted"
                )
                WatchdogWorker.enqueue(context)
            } else {
                Log.i(
                    TAG,
                    "onReceive ${intent.action}: saklar API mati — " +
                        "tidak menyalakan apa pun"
                )
            }
        }
    }
}
