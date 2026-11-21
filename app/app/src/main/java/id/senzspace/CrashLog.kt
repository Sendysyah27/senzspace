package id.senzspace

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Penangkap crash (temuan HP 20:35 WIB 16 Nov: FC masih muncul di build S1
// saat login akun 3-6, tanpa logcat yang bisa ditarik — adbd mati, rish mati).
// Handler ini menulis jejak crash ke folder _Log-Crash (File Manager) SEBELUM
// proses mati, supaya penyebab FC bisa dibaca + diekspor, bukan ditebak.
// Dipasang di tiap Activity utama (Main/Browser/File Manager) karena tiap
// proses (:space1..:space6, :aistudio, utama) punya handler sendiri.
object CrashLog {
    private const val FOLDER = "_log-crash"

    fun folderKey(): String = FOLDER

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = File(FileStore.rootDir(ctx), FOLDER)
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "crash-$stamp.txt")
                val info = StringBuilder()
                info.append("SenzSpace ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
                info.append("Waktu: $stamp\n")
                info.append("HP: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ")
                info.append("Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
                info.append("Proses: ${ctx.javaClass.simpleName} / thread: ${thread.name}\n\n")
                info.append(android.util.Log.getStackTraceString(error))
                file.writeText(info.toString())
            } catch (ignored: Exception) {
                // Penangkap crash tidak boleh ikut crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
