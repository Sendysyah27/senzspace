package id.senzspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// v0.1.71 — revive ApiService dari VM (adb shell am broadcast).
// `am startservice` untuk ApiService ditolak sistem (service
// exported=false), jadi satu-satunya jalan menyalakan API dari VM
// adalah receiver yang exported ini. Aksi tetap, tanpa data masuk:
// onReceive TIDAK membaca extra apa pun dari broadcast — pihak
// ketiga paling jauh hanya bisa menyalakan service API yang memang
// seharusnya jalan. Receiver hanya menyalakan service milik app
// sendiri; tidak membuka activity, tidak mengubah layar.
// Start langsung dari receiver di background bisa ditolak Android
// 12+ (bukan broadcast yang dikecualikan), jadi selain mencoba
// langsung, receiver juga mengantre one-time expedited work —
// worker setForeground dulu baru start (jalur yang disahkan sistem
// untuk kerja background). Bila kelak tidak dibutuhkan, hapus
// receiver ini + perintah `revive` di senz.py sekaligus.
class ReviveReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "id.senzspace.debug.action.REVIVE_API"
        private const val TAG = "SenzRevive"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: action=${intent.action}")
        if (intent.action != ACTION) {
            Log.w(TAG, "onReceive: aksi bukan REVIVE_API — abaikan")
            return
        }
        if (!ApiToken.isEnabled(context)) {
            Log.i(TAG, "onReceive: saklar API mati — abaikan")
            return
        }
        // Coba langsung dulu (gratis bila ada exemption — mis. app
        // baru saja di foreground). v0.1.74: startPlain, BUKAN start()
        // — startForegroundService dari receiver (background) membuat
        // ServiceRecord menunggu startForeground yang sistem tolak
        // lalu membunuh service lewat timeout; startService biasa
        // tidak punya penantian itu. startPlain mengembalikan Boolean
        // + mencatat throw sendiri.
        val started = ApiService.startPlain(context)
        Log.i(TAG, "onReceive: ApiService.startPlain=$started")
        // Jalur andalan: expedited one-time work.
        WatchdogWorker.enqueueNow(context)
        // Pastikan watchdog periodik juga terdaftar.
        WatchdogWorker.enqueue(context)
        Log.i(TAG, "onReceive: enqueueNow + enqueue dipanggil")
    }
}
