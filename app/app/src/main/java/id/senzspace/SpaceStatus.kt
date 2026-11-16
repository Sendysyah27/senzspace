package id.senzspace

import android.content.Context

// Status per Space yang dibaca lintas proses (file di filesDir internal —
// sama untuk semua proses app, UID sama; SharedPreferences tidak dipakai
// karena cache-nya bisa basi antar proses).
// - space_<suffix>.txt: "1" = login, "0" = belum (ditulis BrowserActivity).
// - space_<suffix>_limit.txt: ada = akun Claude Space ini kena limit
//   5 jam; isinya jam reset ("10:30 PM") atau "LIMIT" bila jam tidak
//   ketemu di teks halaman (ditulis LiveBridge dari halaman web).
object SpaceStatus {
    fun readLogin(ctx: Context, suffix: String): Boolean {
        return try {
            ctx.openFileInput("space_$suffix.txt")
                .bufferedReader().readText().trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    /** Jam reset limit ("10:30 PM") / "LIMIT" / null (tidak kena limit). */
    fun readLimit(ctx: Context, suffix: String): String? {
        return try {
            ctx.openFileInput("space_${suffix}_limit.txt")
                .bufferedReader().readText().trim().ifEmpty { "LIMIT" }
        } catch (e: Exception) {
            null
        }
    }

    fun writeLimit(ctx: Context, suffix: String, reset: String) {
        try {
            val value = reset.ifEmpty { "LIMIT" }
            ctx.openFileOutput("space_${suffix}_limit.txt", Context.MODE_PRIVATE)
                .use { it.write(value.toByteArray()) }
        } catch (e: Exception) {
        }
    }

    fun clearLimit(ctx: Context, suffix: String) {
        try {
            ctx.deleteFile("space_${suffix}_limit.txt")
        } catch (e: Exception) {
        }
    }
}
