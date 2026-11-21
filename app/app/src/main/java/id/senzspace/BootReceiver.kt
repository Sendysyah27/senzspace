package id.senzspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** v0.1.55 (audit API 20 Nov, AKAR 4) — hidupkan server API sesudah
 *  HP reboot tanpa buka app. Sebelum ini ApiService cuma start dari
 *  MainActivity.onCreate, jadi sesudah reboot API mati sampai Sendy
 *  buka app manual. Receiver ini jalan sekali tiap BOOT_COMPLETED;
 *  ApiToken.isEnabled = saklar "API Kontrol" di Pengaturan.
 *  v0.1.57: tambah MY_PACKAGE_REPLACED — hidupkan ApiService sesudah
 *  app di-update/di-install ulang, tanpa buka app sekali. Aksi ini
 *  hanya dikirim sistem ke app sendiri, tanpa izin baru. Pola start
 *  sama persis seperti BOOT_COMPLETED (ApiService.start, yang sudah
 *  menangani cabang izin notifikasi sendiri). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (ApiToken.isEnabled(context)) ApiService.start(context)
        }
    }
}
