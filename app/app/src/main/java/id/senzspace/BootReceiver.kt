package id.senzspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** v0.1.55 (audit API 20 Nov, AKAR 4) — hidupkan server API sesudah
 *  HP reboot tanpa buka app. Sebelum ini ApiService cuma start dari
 *  MainActivity.onCreate, jadi sesudah reboot API mati sampai Sendy
 *  buka app manual. Receiver ini jalan sekali tiap BOOT_COMPLETED;
 *  ApiToken.isEnabled = saklar "API Kontrol" di Pengaturan. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (ApiToken.isEnabled(context)) ApiService.start(context)
        }
    }
}
