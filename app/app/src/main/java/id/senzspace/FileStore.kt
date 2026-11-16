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
    const val LIVE = "_live"

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
        Folder("_log-crash", "_Log-Crash", "#8A94A6"),
        // Log diagnosis unduh blob (v0.1.15) — alasan gagal per unduhan.
        Folder("_log-blob", "_Log-Blob", "#8A94A6"),
        // Thumbnail live Multi Live — kelihatan di File Manager supaya
        // bisa dicek langsung bila tile Beranda abu-abu (debug).
        Folder(LIVE, "_Live (thumbnail)", "#8A94A6")
    )

    fun folderByKey(key: String): Folder? = FOLDERS.find { it.key == key }

    fun rootDir(ctx: Context): File {
        return ctx.getExternalFilesDir(ROOT)
            ?: File(ctx.filesDir, ROOT)
    }

    /** Folder thumbnail live Multi Live (S1): tiap proses :spaceN menulis
     *  tangkapan layar WebView-nya ke sini tiap 5 detik
     *  (<suffix>.png); Dashboard (proses utama) hanya MEMBACA file ini
     *  untuk tile grid — tidak ada sesi/secret di sini, cuma gambar. */
    fun liveDir(ctx: Context): File = File(rootDir(ctx), LIVE)

    fun liveThumbFile(ctx: Context, suffix: String): File =
        File(liveDir(ctx), "$suffix.jpg")

    fun folderDir(ctx: Context, key: String): File {
        return File(rootDir(ctx), key)
    }

    /** Buat semua folder bila belum ada (dipanggil saat app dibuka). */
    fun ensure(ctx: Context) {
        for (f in FOLDERS) {
            folderDir(ctx, f.key).mkdirs()
        }
        liveDir(ctx).mkdirs()
    }

    fun listFiles(ctx: Context, key: String): List<File> {
        val dir = folderDir(ctx, key)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** Entri satu folder (subfolder dulu, lalu file) untuk File Manager
     *  native v2: folder bisa dibuka (drill-down), file bisa dibuka /
     *  dibagikan / dipindah / disalin / diganti nama. */
    fun listEntries(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        val all = dir.listFiles() ?: return emptyList()
        val folders = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = all.filter { it.isFile }.sortedByDescending { it.lastModified() }
        return folders + files
    }

    /** Nama file yang belum dipakai di folder (bentrok → tambah (1), (2)). */
    fun uniqueFile(dir: File, name: String): File {
        var dest = File(dir, name)
        if (!dest.exists()) return dest
        val base = dest.nameWithoutExtension
        val ext = dest.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (dest.exists()) {
            dest = File(dir, "$base ($i)$ext")
            i++
        }
        return dest
    }

    /** Salin file ke folder tujuan (nama bentrok → tambah (1), (2), dst). */
    fun copy(ctx: Context, file: File, destKey: String): File? {
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
        return try {
            if (file.isDirectory) file.copyRecursively(dest) else file.copyTo(dest, overwrite = false)
            dest
        } catch (e: Exception) {
            null
        }
    }

    /** Ganti nama file di tempat (nama bentrok → tambah (1), (2), dst). */
    fun rename(file: File, newName: String): File? {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\")) return null
        var dest = File(file.parentFile, clean)
        if (dest.exists() && dest != file) {
            val base = dest.nameWithoutExtension
            val ext = dest.extension.let { if (it.isEmpty()) "" else ".$it" }
            var i = 1
            while (dest.exists()) {
                dest = File(file.parentFile, "$base ($i)$ext")
                i++
            }
        }
        return if (file.renameTo(dest)) dest else null
    }

    /** Buat subfolder di dalam folder kerja (mis. per episode di _Arsip). */
    fun newFolder(parent: File, name: String): File? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\")) return null
        val dir = File(parent, clean)
        if (dir.exists()) return null
        return if (dir.mkdirs()) dir else null
    }

    /** Total ukuran semua file di bawah satu folder (rekursif, untuk
     *  info penyimpanan di Pengaturan). */
    fun totalBytesRecursive(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    fun mimeType(file: File): String {
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
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

    /** Hapus file atau folder beserta isinya (rekursif untuk folder). */
    fun delete(file: File): Boolean =
        if (file.isDirectory) file.deleteRecursively() else file.delete()

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

    /** Ikon vektor minimalis per tipe file (ganti emoji, v0.1.13).
     *  Stroke putih di XML; warna akhir diatur tint di pemanggil. */
    fun iconResFor(file: File): Int {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif" -> R.drawable.ic_fm_image
            "mp4", "mov", "webm" -> R.drawable.ic_fm_video
            "mp3", "wav", "ogg", "m4a" -> R.drawable.ic_fm_audio
            "zip", "rar", "7z" -> R.drawable.ic_fm_archive
            "md", "txt", "srt", "json", "csv", "html", "htm" -> R.drawable.ic_fm_code
            else -> R.drawable.ic_fm_file
        }
    }

    /** Bisa dilihat di dalam app (Fungsi View, v0.1.13)? Gambar, teks,
     *  dan video bisa; audio/PDF/zip/dll dilempar ke app lain (Buka). */
    fun isPreviewable(file: File): Boolean {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif",
            "md", "txt", "srt", "json", "csv", "html", "htm",
            "mp4", "mov", "webm" -> true
            else -> false
        }
    }
}
