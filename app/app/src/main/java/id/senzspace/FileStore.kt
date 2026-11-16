package id.senzspace

import android.content.Context
import java.io.File

// File Manager S1 (PRD F7): folder kerja per Space + Inbox Bersama + Arsip.
// Lokasi: app-specific external files dir (Android/data/.../files/SenzSpace)
// — masih "di dalam app" (D5), bukan folder publik; ekspor ke Download
// publik (D5) adalah slice berikutnya. DownloadManager menulis ke sini
// (tidak bisa ke filesDir internal — itu proses lain).
// Isi file HANYA dibaca/dipindah/dihapus oleh app; tidak ada sesi/secret
// yang disimpan di sini (cookie tetap di WebView data dir masing-masing).
object FileStore {
    const val ROOT = "SenzSpace"

    data class Folder(val key: String, val name: String, val color: String)

    val FOLDERS = listOf(
        Folder("space1", "Claude 1", "#5B5BD6"),
        Folder("space2", "Claude 2", "#0EA5A5"),
        Folder("space3", "Claude 3", "#E07A5F"),
        Folder("space4", "Claude 4", "#E5B567"),
        Folder("space5", "Claude 5", "#7BC47F"),
        Folder("space6", "Claude 6", "#D66BA5"),
        Folder("aistudio", "AI Studio", "#38BDF8"),
        Folder("space8", "Space 8", "#A78BFA"),
        Folder("space9", "Space 9", "#22D3EE"),
        Folder("space10", "Space 10", "#FB7185"),
        Folder("_inbox", "_Inbox-Bersama", "#8A94A6"),
        Folder("_arsip", "_Arsip-Episode", "#8A94A6"),
        Folder("_log-crash", "_Log-Crash", "#8A94A6")
    )

    fun folderByKey(key: String): Folder? = FOLDERS.find { it.key == key }

    fun rootDir(ctx: Context): File {
        return ctx.getExternalFilesDir(ROOT)
            ?: File(ctx.filesDir, ROOT)
    }

    fun folderDir(ctx: Context, key: String): File {
        return File(rootDir(ctx), key)
    }

    /** Buat semua folder bila belum ada (dipanggil saat app dibuka). */
    fun ensure(ctx: Context) {
        for (f in FOLDERS) {
            folderDir(ctx, f.key).mkdirs()
        }
    }

    fun listFiles(ctx: Context, key: String): List<File> {
        val dir = folderDir(ctx, key)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun count(ctx: Context, key: String): Int = listFiles(ctx, key).size

    fun totalBytes(ctx: Context, key: String): Long =
        listFiles(ctx, key).sumOf { it.length() }

    /** Pindah file ke folder tujuan. Nama bentrok → tambah (1), (2), dst. */
    fun move(ctx: Context, file: File, destKey: String): File? {
        val destDir = folderDir(ctx, destKey)
        destDir.mkdirs()
        var dest = File(destDir, file.name)
        if (dest.exists()) {
            val base = file.nameWithoutExtension
            val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
            var i = 1
            while (dest.exists()) {
                dest = File(destDir, "$base ($i)$ext")
                i++
            }
        }
        return if (file.renameTo(dest)) dest else null
    }

    fun delete(file: File): Boolean = file.delete()

    /** Ekspor file ke folder publik Download/SenzSpace (D5) — sadar dipilih
     *  pengguna per file; folder kerja utama tetap di dalam app.
     *  Android 10+: MediaStore (tanpa izin tulis). Di bawah itu: tulis
     *  langsung ke folder publik. Kembalikan nama file bila berhasil. */
    fun exportToDownloads(ctx: Context, file: File): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        "Download/SenzSpace"
                    )
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                file.name
            } else {
                val destDir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "SenzSpace"
                )
                destDir.mkdirs()
                var dest = File(destDir, file.name)
                if (dest.exists()) {
                    val base = file.nameWithoutExtension
                    val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
                    var i = 1
                    while (dest.exists()) {
                        dest = File(destDir, "$base ($i)$ext")
                        i++
                    }
                }
                file.copyTo(dest, overwrite = false)
                dest.name
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatDate(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("id", "ID"))
        return sdf.format(java.util.Date(millis))
    }

    fun iconFor(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif" -> "🖼️"
            "mp4", "mov", "webm" -> "🎬"
            "mp3", "wav", "ogg", "m4a" -> "🎵"
            "pdf" -> "📕"
            "zip", "rar", "7z" -> "📦"
            "md", "txt", "srt", "json", "csv" -> "📝"
            else -> "📄"
        }
    }
}
