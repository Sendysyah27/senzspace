package id.senzspace

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File

// Pratinjau file di dalam app (Fungsi View, v0.1.13): gambar, teks, dan
// video dibuka langsung tanpa keluar SenzSpace. Tipe lain (audio, PDF,
// zip, dll) menampilkan tombol "Buka dengan app lain" (FileProvider).
// Dibuka dari FileManagerActivity yang sudah lewat Kunci App, jadi tidak
// ada LockGate di sini — layar ini hidup di dalam sesi yang sama.
class FileViewerActivity : Activity() {

    companion object {
        const val EXTRA_FILE = "file_path"
        private const val MAX_TEXT_BYTES = 512 * 1024L
        private const val MAX_IMAGE_SIDE = 2048
    }

    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.1.31: ikuti sensor putar HP (berlaku walau auto-rotate sistem mati).
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        CrashLog.install(this)
        val path = intent.getStringExtra(EXTRA_FILE)
        val file = if (path != null) File(path) else null
        if (file == null || !file.exists() || !file.isFile) {
            Toast.makeText(this, "File tidak ditemukan", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Bar atas: tutup | nama file | Buka (app lain)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Tutup"
            setBackgroundColor(Color.parseColor("#00000000"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { finish() }
        }
        val nameView = TextView(this).apply {
            text = file.name
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(4); rightMargin = dp(8) }
        }
        val openBtn = Button(this).apply {
            text = "Buka"
            setTextColor(Color.parseColor("#5B5BD6"))
            setBackgroundColor(Color.parseColor("#00000000"))
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { openExternally(file) }
        }
        topBar.addView(closeBtn)
        topBar.addView(nameView)
        topBar.addView(openBtn)
        root.addView(topBar)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#24344D"))
        })

        // Isi menurut tipe file.
        val ext = file.extension.lowercase()
        when {
            ext in listOf("jpg", "jpeg", "png", "webp", "gif") ->
                root.addView(imageView(file))
            ext in listOf("md", "txt", "srt", "json", "csv", "html", "htm") ->
                root.addView(textView(file))
            ext in listOf("mp4", "mov", "webm") ->
                root.addView(videoView(file))
            else -> root.addView(unsupportedView(file))
        }

        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        try {
            videoView?.pause()
        } catch (e: Exception) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Gambar: decode dengan sampling supaya gambar besar tidak OOM. */
    private fun imageView(file: File): ImageView {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > MAX_IMAGE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
        return ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    /** Teks: dibaca UTF-8, dibatasi 512KB supaya HP tidak ngos-ngosan. */
    private fun textView(file: File): ScrollView {
        val text = if (file.length() > MAX_TEXT_BYTES) {
            "File terlalu besar untuk dilihat di sini " +
                "(${FileStore.formatSize(file.length())}).\n" +
                "Pakai tombol Buka di atas untuk membuka dengan app lain."
        } else {
            try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                "Tidak bisa membaca file ini: ${e.message}"
            }
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(tv)
        }
    }

    /** Video: VideoView + kontrol bawaan (putar/jeda/cari). */
    private fun videoView(file: File): VideoView {
        val controller = MediaController(this)
        return VideoView(this).apply {
            setVideoURI(android.net.Uri.fromFile(file))
            setMediaController(controller)
            keepScreenOn = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnPreparedListener { it.start() }
            videoView = this
        }
    }

    /** Tipe lain: jelaskan + tombol Buka ke app lain. */
    private fun unsupportedView(file: File): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        box.addView(TextView(this).apply {
            text = "Tidak bisa dilihat di sini"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E8ECF4"))
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "${file.name}\n${FileStore.formatSize(file.length())} • " +
                FileStore.mimeType(file)
            textSize = 13f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        box.addView(Button(this).apply {
            text = "Buka dengan app lain"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5B5BD6"))
            setOnClickListener { openExternally(file) }
        })
        return box
    }

    /** Buka ke app lain (sama seperti Buka di File Manager). */
    private fun openExternally(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, FileStore.mimeType(file))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Buka dengan"))
        } catch (e: Exception) {
            Toast.makeText(
                this, "Tidak ada app untuk membuka file ini", Toast.LENGTH_LONG
            ).show()
        }
    }
}
