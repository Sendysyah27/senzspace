package id.senzspace

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import java.io.FileOutputStream

/**
 * Browser per Space (S0).
 *
 * Isolasi: subclass memanggil WebView.setDataDirectorySuffix() SEBELUM
 * WebView pertama dibuat di proses itu. Tiap subclass jalan di prosesnya
 * sendiri (lihat AndroidManifest), jadi suffix + direktori data berbeda
 * per Space — cookie/storage tidak bocor antar Space.
 *
 * Login Google: WebViewClient standar (redirect di WebView yang sama),
 * JavaScript + DOM storage aktif. Kalau situs memaksa login ulang,
 * itu status platform, bukan error diam-diam — pengguna melihat halaman
 * login aslinya.
 */
abstract class BrowserActivity : AppCompatActivity() {

    protected abstract val dataDirectorySuffix: String

    companion object {
        /** Split-screen (v0.1.21): slot Space yang harus dibuka
         *  berdampingan begitu Space peminta tampil. Satu tembakan —
         *  dibersihkan setelah dipakai supaya resume berikutnya tidak
         *  membuka split lagi. */
        @Volatile
        private var pendingSplitSlot: Int? = null
        fun requestSplitWith(slot: Int) { pendingSplitSlot = slot }

        // setDataDirectorySuffix() HANYA boleh dipanggil sekali seumur hidup
        // satu proses Android — panggilan kedua melempar IllegalStateException.
        // Proses :spaceN TETAP HIDUP setelah Activity keluar (Android
        // menyimpan proses kosong), jadi masuk lagi ke Space yang sama
        // memanggil onCreate kedua kali di proses yang sama. Tanpa penjaga
        // ini, keluar-masuk Space = FC — kejadian HP 20:53-20:57 di SEMUA
        // Space (bukan cuma Space 7). Static ini hidup per proses (tiap
        // proses punya classloader sendiri), dan tiap proses hanya punya
        // satu suffix — jadi sekali setel sudah cukup selamanya.
        @Volatile
        private var dataDirectorySuffixSet = false
    }

    private lateinit var webView: WebView
    private lateinit var rootLayout: LinearLayout
    private var topBarView: android.view.View? = null
    private var swipeTrackingTopBar = false

    /** Geser top bar (v0.1.21): geser kiri = Space aktif berikutnya,
     *  geser kanan = Space aktif sebelumnya. Hanya Space yang chip-nya
     *  "Aktif" (sudah login) yang dilewati — perintah Sendy 06:45. */
    private val spaceSwipeDetector by lazy {
        android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (kotlin.math.abs(dx) < dp(80)) return false
                    if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return false
                    if (kotlin.math.abs(velocityX) < 200) return false
                    switchSpace(if (dx < 0) 1 else -1)
                    return true
                }
            }
        )
    }
    private lateinit var downloadBtn: android.widget.ImageButton
    private lateinit var downloadBadge: android.widget.TextView

    /** dp → px (top bar ikon 44dp, badge, dsb). */
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
    private val lockLauncher = LockGate.register(this)
    private var popupView: WebView? = null
    private var currentUrl = ""
    private var spaceName = "Space"
    private var homeUrl = "https://claude.ai"
    private var activityDestroyed = false
    private var pendingBlobFileName = ""
    private var pendingHttpFileName = "download"
    private var httpWatchdogSeq = 0
    private var httpMetaReceived = false

    /** Listener unduhan (PRD F6): blob: → jembatan JS, data: → decode
     *  langsung, http(s) → fetch WebView (v0.1.24). */
    private val downloadListener = android.webkit.DownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
        val fileName = android.webkit.URLUtil.guessFileName(
            dlUrl, contentDisposition, mimeType
        )
        when {
            dlUrl.startsWith("blob:") -> downloadBlob(dlUrl, fileName)
            dlUrl.startsWith("data:") -> downloadDataUrl(dlUrl, fileName)
            else -> downloadHttpViaWebView(dlUrl, fileName)
        }
    }

    /** Ekstensi subresource statis — shouldInterceptRequest TIDAK
     *  mengambilnya (WebView muat sendiri seperti biasa). Dokumen
     *  (.html/.htm sengaja TIDAK ada di sini) tetap diambil untuk
     *  disisipi skrip penangkap blob. */
    private val STATIC_EXTS = setOf(
        "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "svg",
        "ico", "woff", "woff2", "ttf", "otf", "mp3", "wav", "ogg",
        "mp4", "webm", "mov", "json", "xml", "map", "txt", "pdf", "zip"
    )

    /** Callback unduhan file-chooser yang sedang menunggu pilihan user.
     *  WAJIB dipanggil tepat sekali (dengan array URI atau null). */
    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /** Pemilih file sistem untuk <input type="file"> di WebView.
     *  Didaftar di sini (bukan di onCreate) supaya siap sebelum WebView
     *  pertama kali dipakai; dipanggil dari onShowFileChooser. */
    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileChooserCallback
        fileChooserCallback = null
        if (cb == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data!!
        val uris = mutableListOf<android.net.Uri>()
        // Pilih banyak: ClipData; pilih satu: data.data.
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            data.data?.let { uris.add(it) }
        }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    // Multi Live v2 (S1, perintah Sendy 21:58 WIB — "bener bener direct
    // live"): tile Beranda harus menampilkan halaman web yang sedang
    // berjalan sedekat mungkin dengan live. Mekanismenya tiga lapis:
    // (1) heartbeat tiap 2 detik selama Space di belakang;
    // (2) tangkap langsung tiap halaman selesai dimuat saat di belakang;
    // (3) MutationObserver di halaman (jembatan JS) — tiap DOM berubah
    // (mis. Claude sedang mengetik jawaban) picu tangkap, debounce 400ms
    // di JS + jeda minimum 1 detik di native. Tangkapannya VERTIKAL
    // 240×320 (3:4, bagian ATAS halaman — sesuai layar HP) format JPEG
    // supaya cepat, disimpan sebagai _live/<suffix>.jpg.
    // Jujur soal batas: WebView tiap Space hidup di prosesnya sendiri
    // (isolasi sesi, D3) dan Android tidak mengizinkan WebView proses
    // lain ditempel langsung di Beranda — jadi "live" di sini = gambar
    // langsung dari WebView yang sedang jalan, bukan WebView-nya sendiri.
    private val liveHandler = Handler(Looper.getMainLooper())
    private var liveRunning = false
    private var isForeground = false
    private var lastCaptureMs = 0L

    /** Interval tangkap live (Pengaturan → "Refresh Multi Live",
     *  default 2 detik). Dibaca segar tiap tick supaya ganti pengaturan
     *  langsung terasa tanpa buka-tutup Space. */
    private fun liveIntervalMs(): Long {
        return try {
            getSharedPreferences("senzspace_ui", MODE_PRIVATE)
                .getLong("live_interval_ms", 2000L).coerceIn(1000L, 10000L)
        } catch (e: Exception) {
            2000L
        }
    }

    private val liveRunnable = object : Runnable {
        override fun run() {
            if (!liveRunning) return
            if (!isForeground) captureLiveThumbnail()
            liveHandler.postDelayed(this, liveIntervalMs())
        }
    }

    private fun startLiveCapture() {
        if (liveRunning) return
        liveRunning = true
        liveHandler.postDelayed(liveRunnable, liveIntervalMs())
    }

    private fun stopLiveCapture() {
        liveRunning = false
        liveHandler.removeCallbacks(liveRunnable)
    }

    private fun captureLiveThumbnail() {
        if (!::webView.isInitialized) return
        // v0.1.20: Live dibekukan dari Beranda (tahan lama tile →
        // Matikan Live) — thumbnail berhenti update, browser + sesi
        // tetap jalan. Satu titik cek ini menutup semua pemicu tangkap
        // (timer 2 dtk, MutationObserver, onPageFinished).
        if (LiveFreeze.isFrozen(this, dataDirectorySuffix)) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureMs < 1000) return
        lastCaptureMs = now
        try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0 || webView.contentHeight <= 0) return
            // Vertikal 3:4 — ambil PITA TENGAH viewport (bukan atas):
            // sapaan Claude ("What shall we think through?") adanya di
            // tengah layar; tangkapan atas (v0.1.9) bikin tile Claude 3–6
            // hitam polos (bukti screenshot HP 22:21). Skala lebar pas
            // 240px, geser vertikal supaya tengah viewport yang kepotong.
            val tw = 240
            val th = 320
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            val scale = tw.toFloat() / w
            canvas.scale(scale, scale)
            val scaledH = h * scale
            val offsetY = ((scaledH - th) / 2f).coerceAtLeast(0f) / scale
            canvas.translate(0f, -offsetY)
            webView.draw(canvas)
            val file = FileStore.liveThumbFile(this, dataDirectorySuffix)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bmp.recycle()
            // Bersihkan file PNG lama (format v0.1.8) supaya tidak menumpuk.
            val oldPng = java.io.File(file.parentFile, "$dataDirectorySuffix.png")
            if (oldPng.exists()) oldPng.delete()
        } catch (e: Exception) {
            // Thumbnail boleh gagal; jangan ganggu browsing.
        }
    }

    /** Jembatan JS untuk Multi Live v2: halaman memanggil onChanged()
     *  tiap DOM berubah, onLimit/onLimitCleared tiap status limit Claude
     *  berubah. Dipanggil dari thread binder — lempar ke UI. Tidak ada
     *  isi halaman yang keluar: onChanged tanpa argumen, onLimit hanya
     *  membawa jam reset ("10:30 PM") untuk chip Beranda. */
    private inner class LiveBridge {
        @android.webkit.JavascriptInterface
        fun onChanged() {
            liveHandler.post {
                if (!isForeground) captureLiveThumbnail()
            }
        }

        @android.webkit.JavascriptInterface
        fun onLimit(reset: String) {
            liveHandler.post {
                SpaceStatus.writeLimit(this@BrowserActivity, dataDirectorySuffix, reset)
            }
        }

        @android.webkit.JavascriptInterface
        fun onLimitCleared() {
            liveHandler.post {
                SpaceStatus.clearLimit(this@BrowserActivity, dataDirectorySuffix)
            }
        }
    }

    private fun injectLiveObserver() {
        if (!::webView.isInitialized) return
        try {
            webView.evaluateJavascript(
                "(function(){if(window.__senzLive)return;window.__senzLive=true;" +
                    "var t=null;var ob=new MutationObserver(function(){" +
                    "clearTimeout(t);t=setTimeout(function(){" +
                    "window.SenzLive.onChanged();},400);});" +
                    "if(document.body){ob.observe(document.body," +
                    "{childList:true,subtree:true,characterData:true});}" +
                    // Deteksi limit 5 jam Claude: modal "You hit your 5-hour
                    // message limit. It resets at 10:30 PM" — cek tiap 5 dtk,
                    // lapor hanya bila status berubah (biar chip Beranda
                    // jujur tanpa harus buka tiap Space).
                    "window.__senzLimitState='';" +
                    "function checkLimit(){try{var txt=document.body?document.body.innerText:'';" +
                    "var hit=txt.indexOf('5-hour message limit')>=0;" +
                    "var m=txt.match(/resets at ([0-9:]+ [AP]M)/);" +
                    "var reset=m?m[1]:'';var st=hit?('LIMIT:'+reset):'OK';" +
                    "if(window.__senzLimitState!==st){window.__senzLimitState=st;" +
                    "if(hit){window.SenzLive.onLimit(reset);}else{window.SenzLive.onLimitCleared();}}" +
                    "}catch(e){}};checkLimit();setInterval(checkLimit,5000);})();",
                null
            )
        } catch (e: Exception) {
        }
    }

    // Panel log kuning S0.2 SUDAH DILEPAS (perintah Sendy 21:58 WIB).
    // logEvent() sekarang no-op — crash tetap ditangkap CrashLog
    // (file _Log-Crash), bukan panel di layar.
    private fun logEvent(msg: String) {
    }

    /** S0.3: pop-up login Google harus jadi WebView popup ASLI (ditampilkan,
     *  bukan dialihkan ke WebView utama). Alasannya: halaman callback OAuth
     *  Claude didesain jalan di dalam pop-up dan menyerahkan token lewat
     *  window.opener.postMessage ke halaman claude.ai yang membukanya.
     *  Kalau URL login dimuat di WebView utama, halaman claude.ai-nya sudah
     *  pergi (navigasi ke Google) dan callback-nya tidak punya opener —
     *  hasilnya: login "selesai" tapi halaman putih kosong. Bukti 19:13:
     *  panel log S0.2 hanya menampilkan warning JS dari komponen Material
     *  Google (VfPpkd-..., halaman accounts.google.com) tanpa error —
     *  alur OAuth-nya yang patah, bukan halaman yang gagal muat. */
    private fun openPopupWindow(view: WebView, resultMsg: Message): Boolean {
        val popup = WebView(view.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace(" Version/4.0", "")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    v: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    logEvent("POPUP MULAI: $url")
                }

                override fun onPageFinished(v: WebView, url: String) {
                    logEvent("POPUP SELESAI: $url")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    closePopupWindow()
                }

                // File chooser (v0.1.12): tanpa override ini, <input
                // type="file"> di halaman (mis. "Choose files or photos"
                // di Claude) DIAM — tidak ada respon sama sekali (temuan
                // HP 23:21). WebView tidak membuka pemilih file sendiri;
                // app yang harus menampilkannya lalu menyerahkan URI
                // pilihan ke callback halaman.
                override fun onShowFileChooser(
                    view: WebView,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                    fileChooserParams: WebChromeClient.FileChooserParams
                ): Boolean {
                    // Batalkan chooser sebelumnya bila ada (callback lama
                    // tidak boleh digantung — halaman menunggu jawabannya).
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    try {
                        val acceptTypes = fileChooserParams.acceptTypes
                        val mimeType = acceptTypes.firstOrNull {
                            it.isNotBlank() && it != "*/*"
                        } ?: "*/*"
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_GET_CONTENT
                        ).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = mimeType
                            if (fileChooserParams.mode ==
                                WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                            ) {
                                putExtra(
                                    android.content.Intent.EXTRA_ALLOW_MULTIPLE, true
                                )
                            }
                            addFlags(
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        fileChooserLauncher.launch(
                            android.content.Intent.createChooser(
                                intent, "Pilih file"
                            )
                        )
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        filePathCallback.onReceiveValue(null)
                        Toast.makeText(
                            this@BrowserActivity,
                            "Tidak bisa membuka pemilih file: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return true
                }
            }
        }
        popupView = popup
        // Tampilkan pop-up di tempat WebView utama; halaman claude.ai
        // tetap hidup di belakang (tidak dinavigasi pergi) supaya bisa
        // menerima token lewat window.opener.
        rootLayout.addView(popup, 1)
        webView.visibility = android.view.View.GONE
        popup.visibility = android.view.View.VISIBLE
        (resultMsg.obj as WebView.WebViewTransport).webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun closePopupWindow() {
        // S0.4: JANGAN hancurkan pop-up di dalam callback onCloseWindow —
        // WebView masih menyelesaikan callback penutupan itu dan destroy()
        // sinkron di dalamnya bikin crash native ("SenzSpace has stopped",
        // kejadian HP 20:16: login Space 2 selesai → kembali ke beranda
        // malah FC). Tunda teardown ke antrean UI thread supaya callback
        // selesai dulu baru pop-up dilepas + dihancurkan.
        val popup = popupView ?: return
        popupView = null
        rootLayout.post {
            if (activityDestroyed) return@post
            rootLayout.removeView(popup)
            popup.destroy()
            webView.visibility = android.view.View.VISIBLE
            logEvent("POPUP ditutup — kembali ke $currentUrl")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        // WAJIB sebelum WebView pertama dibuat di proses ini — DAN hanya
        // sekali per proses (lihat companion: penjaga dataDirectorySuffixSet).
        if (!dataDirectorySuffixSet) {
            try {
                WebView.setDataDirectorySuffix(dataDirectorySuffix)
                dataDirectorySuffixSet = true
            } catch (e: IllegalStateException) {
                // Suffix sudah disetel (mis. proses dipakai ulang) — lanjut
                // dengan suffix yang sudah ada, itu sudah benar.
                dataDirectorySuffixSet = true
            }
        }
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        super.onCreate(savedInstanceState)

        val name = intent.getStringExtra(MainActivity.EXTRA_SPACE_NAME) ?: "Space"
        spaceName = name
        val color = intent.getStringExtra(MainActivity.EXTRA_SPACE_COLOR) ?: "#5B5BD6"
        val url = intent.getStringExtra(MainActivity.EXTRA_URL) ?: "https://claude.ai"
        homeUrl = url

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout = root

        // Aturan anti-salah-akun (PRD F4): nama + warna Space aktif
        // tampil permanen di top bar. Fungsi top bar (lengkap, v0.1.10):
        // ⌄ Minimize = kembali ke Beranda, Space tetap berjalan;
        // ↻ Muat ulang halaman; ⌂ Beranda Space (URL awal Space ini);
        // ⬇ Unduhan (badge jumlah file; tap → File Manager folder ini).
        // Sengaja TIDAK ada tombol Tutup/Matikan Space di sini — Space
        // harus tetap hidup untuk Multi Live + guard anti-kill; yang mau
        // menghapus Space pakai tahan-lama di Beranda.
        val topBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(color))
            setPadding(24, 24, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val topBar = TextView(this).apply {
            text = "● $name  —  SenzSpace S1"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            // Satu baris, dipotong "…" bila sempit (jendela mengambang /
            // split-screen): nama yang wrap 4 baris (kejadian HP 23:08)
            // mendorong tombol keluar layar.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Ikon top bar (v0.1.12): vektor garis minimalis gaya Feather/
        // Lucide (bukan lagi huruf ⌄ ↻ ⌂ ⬇ yang tampilannya beda-beda
        // tiap HP). Tiap ikon 44dp (area sentuh nyaman) + deskripsi
        // buat pembaca layar.
        fun topBarIcon(iconRes: Int, description: String): android.widget.ImageButton =
            android.widget.ImageButton(this).apply {
                setImageResource(iconRes)
                contentDescription = description
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#00000000"))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
        val minimizeBtn = topBarIcon(R.drawable.ic_chevron_down, "Minimize")
        minimizeBtn.setOnClickListener { goToDashboard() }
        val reloadBtn = topBarIcon(R.drawable.ic_refresh, "Muat ulang")
        reloadBtn.setOnClickListener { if (::webView.isInitialized) webView.reload() }
        val homeBtn = topBarIcon(R.drawable.ic_home, "Beranda Space")
        homeBtn.setOnClickListener { if (::webView.isInitialized) webView.loadUrl(homeUrl) }
        // Unduhan: ikon + badge angka kecil di pojok kanan atas
        // (jumlah file di folder Space ini; tap → File Manager).
        val downloadWrap = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        downloadBtn = topBarIcon(R.drawable.ic_download, "Unduhan")
        downloadBtn.setOnClickListener {
            val intent = android.content.Intent(this, FileManagerActivity::class.java)
            intent.putExtra(FileManagerActivity.EXTRA_FOLDER, dataDirectorySuffix)
            startActivity(intent)
        }
        downloadBadge = TextView(this).apply {
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.badge_bg)
            visibility = android.view.View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            )
        }
        downloadWrap.addView(downloadBtn)
        downloadWrap.addView(downloadBadge)
        topBarLayout.addView(topBar)
        topBarLayout.addView(minimizeBtn)
        topBarLayout.addView(reloadBtn)
        topBarLayout.addView(homeBtn)
        topBarLayout.addView(downloadWrap)
        root.addView(topBarLayout)
        topBarView = topBarLayout

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            // S0.1 — perbaikan blank putih setelah login (temuan HP 16 Nov 18:27):
            // cookie pihak ketiga diterima + UA dibersihkan dari "; wv".
            // Pop-up login ditangani WebChromeClient.onCreateWindow ->
            // openPopupWindow (pop-up asli, S0.3).
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowContentAccess = true
            settings.allowFileAccess = true
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace(" Version/4.0", "")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    currentUrl = url
                    logEvent("MULAI: $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    logEvent("SELESAI: $url")
                    // S0.4: paksa tulis cookie sesi ke disk tiap halaman
                    // selesai dimuat. WebView hanya menulis cookie ke disk
                    // secara berkala; kalau app mati mendadak (FC) sebelum
                    // sempat ditulis, sesi login hilang — kejadian HP 20:17:
                    // Space 1 balik ke halaman login setelah FC Space 2.
                    CookieManager.getInstance().flush()
                    // S1: tandai status login Space ini di file
                    // space_<suffix>.txt ("1" = login, "0" = belum) supaya
                    // chip di Dashboard jujur. Hanya bendera 1/0 — BUKAN
                    // cookie/sesi, jadi aman disimpan sebagai file biasa.
                    saveLoginState(url)
                    // Multi Live v2: pasang pengamat DOM + tangkap langsung
                    // bila halaman selesai dimuat saat Space di belakang.
                    injectLiveObserver()
                    // v0.1.16: penangkap blob (cache/inject) DIHAPUS — blob
                    // null-origin tidak bisa ditangkap dari frame utama.
                    if (!isForeground) captureLiveThumbnail()
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    return interceptDocument(request)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    // S0.3: catat juga gagal muat subresource (mis. bundle JS
                    // Claude) — halaman bisa blank putih padahal frame utama
                    // sukses, dan itu tidak kelihatan kalau hanya main frame
                    // yang dicatat.
                    if (request.isForMainFrame) {
                        logEvent("HTTP ${errorResponse.statusCode}: ${request.url}")
                    } else {
                        logEvent("HTTP-sub ${errorResponse.statusCode}: ${request.url}")
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        logEvent("ERROR: ${error.description} ${request.url}")
                        Toast.makeText(
                            this@BrowserActivity,
                            "Gagal muat: ${error.description}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        logEvent("ERR-sub: ${error.description} ${request.url}")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(
                    consoleMessage: android.webkit.ConsoleMessage
                ): Boolean {
                    logEvent(
                        "JS ${consoleMessage.messageLevel()}: " +
                            "${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return true
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message
                ): Boolean {
                    // S0.3: pop-up asli (lihat openPopupWindow) — JANGAN
                    // dialihkan ke WebView utama, callback OAuth butuh opener.
                    return openPopupWindow(view, resultMsg)
                }

                override fun onCloseWindow(window: WebView) {
                    closePopupWindow()
                }

                // File chooser (v0.1.12): tanpa override ini, <input
                // type="file"> di halaman (mis. "Choose files or photos"
                // di Claude) DIAM — tidak ada respon sama sekali (temuan
                // HP 23:21). WebView tidak membuka pemilih file sendiri;
                // app yang harus menampilkannya lalu menyerahkan URI
                // pilihan ke callback halaman.
                override fun onShowFileChooser(
                    view: WebView,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                    fileChooserParams: WebChromeClient.FileChooserParams
                ): Boolean {
                    // Batalkan chooser sebelumnya bila ada (callback lama
                    // tidak boleh digantung — halaman menunggu jawabannya).
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    try {
                        val acceptTypes = fileChooserParams.acceptTypes
                        val mimeType = acceptTypes.firstOrNull {
                            it.isNotBlank() && it != "*/*"
                        } ?: "*/*"
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_GET_CONTENT
                        ).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = mimeType
                            if (fileChooserParams.mode ==
                                WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                            ) {
                                putExtra(
                                    android.content.Intent.EXTRA_ALLOW_MULTIPLE, true
                                )
                            }
                            addFlags(
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        fileChooserLauncher.launch(
                            android.content.Intent.createChooser(
                                intent, "Pilih file"
                            )
                        )
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        filePathCallback.onReceiveValue(null)
                        Toast.makeText(
                            this@BrowserActivity,
                            "Tidak bisa membuka pemilih file: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return true
                }
            }
        }
        root.addView(webView)
        // Jembatan JS Multi Live v2 (MutationObserver): halaman hanya boleh
        // memanggil SenzLive.onChanged() tanpa argumen — tidak ada data
        // halaman yang keluar, cuma sinyal "halaman berubah".
        webView.addJavascriptInterface(LiveBridge(), "SenzLive")
        // Jembatan unduh blob (v0.1.11): isi file dikirim halaman
        // sebagai base64 — sekarang datang dari frame mana pun (frame
        // utama atau iframe) lewat postMessage, lihat blobInjectScript.
        webView.addJavascriptInterface(BlobDownloadBridge(), "SenzBlob")
        webView.addJavascriptInterface(HttpDownloadBridge(), "SenzHttp")

        // Unduhan per Space (PRD F6): listener WAJIB ada — pelajaran
        // v0.1.16: TANPA DownloadListener, WebView TIDAK mengunduh
        // apa-apa (tombol unduh mati total, temuan HP 01:19). Chromium
        // di WebView tidak punya download manager bawaan seperti Chrome;
        // app yang harus menangani unduhan lewat listener ini.
        webView.setDownloadListener(downloadListener)

        setContentView(root)

        // S0.1 — perbaikan blank putih setelah login (temuan HP 16 Nov 18:27):
        // cookie pihak ketiga + UA dibersihkan. S0.2: panel log. S0.3: pop-up
        // login jadi WebView popup asli (window.opener utuh) — lihat
        // openPopupWindow; pengalihan URL pop-up ke WebView utama (S0.1/S0.2)
        // TERBUKTI bikin callback OAuth kehilangan opener → blank putih.

        if (savedInstanceState == null) {
            webView.loadUrl(url)
        }
        startLiveCapture()
        // Guard anti-kill: service foreground di proses Space ini supaya
        // sistem tidak mematikan Space yang lama tidak dibuka. Hormati
        // pengaturan (Pengaturan → Guard anti-kill): bila dimatikan user,
        // jangan nyalakan lagi diam-diam.
        val guardOn = try {
            getSharedPreferences("senzspace_ui", MODE_PRIVATE)
                .getBoolean("guard_enabled", true)
        } catch (e: Exception) {
            true
        }
        if (guardOn) KeepAlive.start(this, dataDirectorySuffix, spaceName)
    }

    /** Teruskan sentuhan yang MULAI di top bar ke detektor geser
     *  (v0.1.21). Sentuhan lain (WebView, tombol) tidak disentuh —
     *  detector hanya mengamati; tap/klik tetap jalan normal. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeTrackingTopBar =
                    topBarView?.let { ev.y <= it.bottom } ?: false
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (swipeTrackingTopBar) spaceSwipeDetector.onTouchEvent(ev)
                swipeTrackingTopBar = false
            }
        }
        if (swipeTrackingTopBar) spaceSwipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Pindah ke Space AKTIF berikutnya (arah +1) / sebelumnya (-1).
     *  Pool = Space yang sudah login; yang "Belum login" dilewatin.
     *  Bila tidak ada Space aktif lain, beri tahu (bukan diam). */
    private fun switchSpace(direction: Int) {
        val spaces = try {
            SpaceRegistry.load(this)
        } catch (e: Exception) {
            return
        }
        if (spaces.size < 2) {
            android.widget.Toast.makeText(this, "Hanya 1 Space", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val active = spaces.filter {
            SpaceStatus.readLogin(this, SpaceRegistry.suffixForSlot(it.slot))
        }
        val pool = when {
            active.size >= 2 -> active
            active.isEmpty() -> spaces
            else -> {
                android.widget.Toast.makeText(
                    this, "Tidak ada Space aktif lain", android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        val idx = pool.indexOfFirst {
            SpaceRegistry.suffixForSlot(it.slot) == dataDirectorySuffix
        }
        val next = when {
            idx >= 0 -> pool[(idx + direction + pool.size) % pool.size]
            direction > 0 -> pool.first()
            else -> pool.last()
        }
        android.widget.Toast.makeText(this, "→ ${next.name}", android.widget.Toast.LENGTH_SHORT).show()
        val intent = android.content.Intent(this, SpaceRegistry.activityForSlot(next.slot)).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(MainActivity.EXTRA_SPACE_NAME, next.name)
            putExtra(MainActivity.EXTRA_SPACE_COLOR, next.color)
            putExtra(MainActivity.EXTRA_URL, next.url)
        }
        startActivity(intent)
    }

    /** Buka Space lain berdampingan (split-screen, v0.1.21):
     *  FLAG_ACTIVITY_LAUNCH_ADJACENT minta sistem menaruh Space baru
     *  di panel sebelah. Bila HP menolak split, activity dibuka biasa
     *  (tanpa crash) — itu batas HP, bukan bug app. */
    private fun openSplitSpace(slot: Int) {
        val entry = try {
            SpaceRegistry.load(this).firstOrNull { it.slot == slot }
        } catch (e: Exception) {
            null
        } ?: return
        val intent = android.content.Intent(this, SpaceRegistry.activityForSlot(slot)).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_SPACE_NAME, entry.name)
            putExtra(MainActivity.EXTRA_SPACE_COLOR, entry.color)
            putExtra(MainActivity.EXTRA_URL, entry.url)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = android.content.Intent(this, SpaceRegistry.activityForSlot(slot)).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_SPACE_NAME, entry.name)
                putExtra(MainActivity.EXTRA_SPACE_COLOR, entry.color)
                putExtra(MainActivity.EXTRA_URL, entry.url)
            }
            startActivity(fallback)
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        LockGate.check(this, lockLauncher)
        refreshDownloadCount()
        // v0.1.21: jalankan permintaan split-screen (sekali saja).
        val split = pendingSplitSlot
        if (split != null) {
            pendingSplitSlot = null
            val mySlot = try {
                SpaceRegistry.load(this).firstOrNull {
                    SpaceRegistry.suffixForSlot(it.slot) == dataDirectorySuffix
                }?.slot
            } catch (e: Exception) {
                null
            }
            if (mySlot != null && split != mySlot) openSplitSpace(split)
        }
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

    /** Badge ⬇ di top bar = jumlah file di folder Space ini. */
    private fun refreshDownloadCount() {
        if (!::downloadBtn.isInitialized || !::downloadBadge.isInitialized) return
        val n = try {
            FileStore.count(this, dataDirectorySuffix)
        } catch (e: Exception) {
            0
        }
        if (n > 0) {
            downloadBadge.text = if (n > 99) "99+" else "$n"
            downloadBadge.visibility = android.view.View.VISIBLE
        } else {
            downloadBadge.visibility = android.view.View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        // S0.4: tulis cookie sesi ke disk tiap app ditinggalkan, supaya sesi
        // tetap ada walau proses mati mendadak setelahnya.
        if (::webView.isInitialized) {
            CookieManager.getInstance().flush()
        }
        // Multi Live: begitu pengguna meninggalkan Space ini, langsung
        // ambil satu tangkapan segar supaya tile Beranda tidak menunggu
        // 5 detik untuk gambar pertama.
        captureLiveThumbnail()
    }

    /** S1: apakah URL ini berarti Space sedang login? Aturan default untuk
     *  Claude: host claude.ai dengan path selain "/" dan "/login".
     *  (Halaman "/" saat logout tampil sebagai landing "The AI for problem
     *  solvers" — itu BUKAN login.) AI Studio menimpa aturan ini. */
    protected open fun isLoggedIn(url: String): Boolean {
        return try {
            val u = android.net.Uri.parse(url)
            val path = u.path ?: ""
            u.host == "claude.ai" && path != "/" && path != "/login" &&
                !path.contains("/login")
        } catch (e: Exception) {
            false
        }
    }

    private fun saveLoginState(url: String) {
        try {
            val flag = if (isLoggedIn(url)) "1" else "0"
            openFileOutput("space_${dataDirectorySuffix}.txt", MODE_PRIVATE)
                .use { it.write(flag.toByteArray()) }
        } catch (e: Exception) {
            // Status chip boleh gagal diam-diam; login tetap jalan.
        }
    }

    // ---------- Unduhan (F6): http(s) via DownloadManager, blob:/data:
    // langsung ditulis ke folder Space ----------

    /** Tulis satu baris diagnosis ke _Log-Blob/blob-debug.txt (bisa
     *  dibaca Jhon lewat bridge + dilihat Sendy di File Manager). */
    private fun logBlobDiag(info: String) {
        Thread {
            try {
                val dir = java.io.File(FileStore.rootDir(this), "_log-blob")
                dir.mkdirs()
                val sdf = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale("id", "ID")
                )
                val line = "${sdf.format(java.util.Date())} [$dataDirectorySuffix] $info\n"
                java.io.File(dir, "blob-debug.txt").appendText(line)
            } catch (e: Exception) {
            }
        }.start()
    }

    /** Unduhan http(s) lewat WebView itu sendiri (v0.1.24 — Rencana B):
     *  hop 0 ke claude.ai ditolak 403 walau cookie + header browser
     *  dikirim dari HttpURLConnection (bukti log 07:19: "UNDUH hop 0:
     *  claude.ai -> 403") — server mengenali jejak TLS non-browser.
     *  fetch() dari halaman memakai jaringan WebView sendiri: cookie
     *  lengkap, header browser asli, TLS Chrome. Isi di-streaming
     *  lewat SenzHttp per potongan (ReadableStream) supaya file besar
     *  tidak menumpuk di RAM di sisi mana pun. */
    private fun downloadHttpViaWebView(dlUrl: String, fileName: String) {
        if (!::webView.isInitialized) return
        pendingHttpFileName = fileName
        val host = try { java.net.URL(dlUrl).host } catch (e: Exception) { dlUrl }
        logBlobDiag("UNDUH-WEB mulai: $host")
        android.widget.Toast.makeText(this, "Mengunduh $fileName…", android.widget.Toast.LENGTH_SHORT).show()
        val seq = ++httpWatchdogSeq
        httpMetaReceived = false
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (seq == httpWatchdogSeq && !httpMetaReceived) {
                logBlobDiag("UNDUH-WEB: tidak ada respon dari WebView (SenzHttp?)")
                android.widget.Toast.makeText(
                    this, "Unduhan tidak merespon — coba lagi", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }, 6000)
        // v0.1.26: daftarkan ulang jembatan tepat sebelum dipakai +
        // selidiki konteks JS — v0.1.25 membuktikan JS tidak pernah
        // lapor balik ("fetch mulai" tidak muncul). URL dikirim lewat
        // window.__senzDlUrl (JSON-escaped) supaya isi URL tidak bisa
        // merusak string JS. Hasil probe + hasil eksekusi JS dicatat.
        webView.addJavascriptInterface(HttpDownloadBridge(), "SenzHttp")
        webView.evaluateJavascript("typeof window.SenzHttp") { v ->
            logBlobDiag("UNDUH-WEB probe SenzHttp: $v")
        }
        webView.evaluateJavascript(
            "window.__senzDlUrl=" + org.json.JSONObject.quote(dlUrl) + ";", null
        )
        val js = "(function(){try{" +
            "if(!window.SenzHttp){return;}" +
            "window.SenzHttp.onDiag('fetch mulai');" +
            "fetch(window.__senzDlUrl,{credentials:'include'}).then(function(r){" +
            "if(!r.ok){window.SenzHttp.onError('server menolak ('+r.status+')');return null;}" +
            "window.SenzHttp.onDiag('fetch ok total='+(r.headers.get('Content-Length')||'?'));" +
            "var cd=r.headers.get('Content-Disposition')||'';" +
            "var nm='';" +
            "var m=/filename\\*?=([^;]+)/.exec(cd);" +
            "if(m){nm=decodeURIComponent(m[1].replace(/^UTF-8''/,'').replace(/\"/g,''));}" +
            "window.SenzHttp.onMeta(r.headers.get('Content-Length')||'0',nm);" +
            "var reader=r.body.getReader();" +
            "var pump=function(){return reader.read().then(function(res){" +
            "if(res.done){window.SenzHttp.onDone();return;}" +
            "var v=res.value;var bin='';" +
            "for(var j=0;j<v.length;j+=0x8000){" +
            "bin+=String.fromCharCode.apply(null,v.subarray(j,j+0x8000));}" +
            "window.SenzHttp.onChunk(btoa(bin));" +
            "return pump();});};" +
            "return pump();}).catch(function(e){" +
            "window.SenzHttp.onError(''+(e&&e.message?e.message:e));});" +
            "}catch(e){try{window.SenzHttp.onError('js '+e.message);}catch(_){}})();"
        try {
            webView.evaluateJavascript(js) { v ->
                logBlobDiag("UNDUH-WEB js-result: $v")
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Gagal mengunduh: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Jembatan unduhan http(s) via WebView (v0.1.24). Satu unduhan
     *  aktif dalam satu waktu — unduhan kedua menimpa state yang
     *  pertama (sama seperti jalur blob). */
    private inner class HttpDownloadBridge {
        private var out: java.io.FileOutputStream? = null
        private var dest: java.io.File? = null
        private var baseName = ""
        private var total = 0L
        private var written = 0L
        private var lastPct = -1
        private var notifId = 0

        @android.webkit.JavascriptInterface
        fun onDiag(msg: String) {
            logBlobDiag("UNDUH-WEB diag: $msg")
        }

        @android.webkit.JavascriptInterface
        fun onMeta(totalStr: String, cdName: String) {
            httpMetaReceived = true
            total = totalStr.toLongOrNull() ?: 0L
            baseName = cdName.ifBlank { pendingHttpFileName }
            notifId = 2000 + (System.currentTimeMillis() % 90).toInt()
            written = 0L
            lastPct = -1
            out = null
            dest = null
            showProgress(0, "Mengunduh $baseName…")
        }

        @android.webkit.JavascriptInterface
        fun onChunk(base64: String) {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                if (out == null) {
                    // Potongan pertama: tentukan nama final dari isi
                    // (sniff) sebelum file dibuat.
                    val finalName = fixExtension(baseName, bytes)
                    val dir = FileStore.folderDir(this@BrowserActivity, dataDirectorySuffix)
                    dir.mkdirs()
                    dest = FileStore.uniqueFile(dir, finalName)
                    out = java.io.FileOutputStream(dest)
                }
                out?.write(bytes)
                written += bytes.size
                if (total > 0) {
                    val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct
                        showProgress(pct, "Mengunduh ${dest?.name ?: baseName}…")
                    }
                }
            } catch (e: Exception) {
                finishError("gagal menulis (${e.message})")
            }
        }

        @android.webkit.JavascriptInterface
        fun onDone() {
            try { out?.close() } catch (e: Exception) {}
            out = null
            val d = dest
            cancelProgress()
            if (d == null) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@BrowserActivity, "Gagal mengunduh: file kosong", android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }
            logBlobDiag("UNDUH-WEB: ${d.name} ($written B)")
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@BrowserActivity,
                    "Tersimpan di folder $spaceName: ${d.name}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                refreshDownloadCount()
            }
        }

        @android.webkit.JavascriptInterface
        fun onError(reason: String) {
            finishError(reason)
        }

        private fun finishError(reason: String) {
            try { out?.close() } catch (e: Exception) {}
            out = null
            try { dest?.delete() } catch (e: Exception) {}
            dest = null
            cancelProgress()
            logBlobDiag("UNDUH-WEB GAGAL: $baseName — $reason")
            runOnUiThread {
                android.widget.Toast.makeText(this@BrowserActivity, "Gagal mengunduh: $reason", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        private fun showProgress(pct: Int, label: String) {
            try {
                val b = androidx.core.app.NotificationCompat.Builder(
                    this@BrowserActivity, KeepAliveService.CHANNEL_ID
                )
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(label)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(100, pct, false)
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(notifId, b.build())
            } catch (e: Exception) {
            }
        }

        private fun cancelProgress() {
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(notifId)
            } catch (e: Exception) {
            }
        }
    }

    /** Perbaiki ekstensi dari isi file (magic bytes) bila nama
     *  tidak punya ekstensi yang benar: .bin/.txt dari tebakan
     *  WebView diganti bila isinya WAV/MP3/PDF/ZIP/PNG/JPG/MP4.
     *  Nama asli dari anchor (mis. .wav/.md) tidak diubah. */
    private fun fixExtension(name: String, bytes: ByteArray): String {
        val sniffed = sniffExtension(bytes) ?: return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0) name.substring(dot + 1).lowercase() else ""
        if (ext == sniffed) return name
        if (ext.isEmpty() || ext == "bin" || ext == "txt") {
            val base = if (dot >= 0) name.substring(0, dot) else name
            return "$base.$sniffed"
        }
        return name
    }

    private fun sniffExtension(b: ByteArray): String? {
        fun eq(off: Int, vararg vs: Int) =
            b.size > off + vs.size - 1 &&
                vs.withIndex().all { (i, v) -> b[off + i] == v.toByte() }
        if (eq(0, 0x52, 0x49, 0x46, 0x46) && eq(8, 0x57, 0x41, 0x56, 0x45)) return "wav"
        if (eq(0, 0x49, 0x44, 0x33) || eq(0, 0xFF, 0xFB)) return "mp3"
        if (eq(0, 0x25, 0x50, 0x44, 0x46)) return "pdf"
        if (eq(0, 0x50, 0x4B, 0x03, 0x04)) return "zip"
        if (eq(0, 0x89, 0x50, 0x4E, 0x47)) return "png"
        if (eq(0, 0xFF, 0xD8, 0xFF)) return "jpg"
        if (eq(4, 0x66, 0x74, 0x79, 0x70)) return "mp4"
        return null
    }

    /** Ambil isi blob (v0.1.17): 1) cache frame utama (Claude,
     *  blob:https://...), 2) tanya semua iframe via postMessage
     *  (AI Studio, blob:null/... — cache hidup di iframe pembuat,
     *  dipasang lewat injeksi dokumen), 3) kalau 6 detik tidak ada
     *  jawaban, XHR terakhir, lalu gagal. Bendera __senzBlobDone
     *  mencegah simpan ganda bila dua jalur sama-sama berhasil. */
    private fun downloadBlob(dlUrl: String, fileName: String) {
        if (!::webView.isInitialized) return
        pendingBlobFileName = fileName
        val escaped = dlUrl.replace("\\", "\\\\").replace("'", "\\'")
        val js = "(function(){try{" +
            "var url='$escaped';" +
            "window.__senzPendingBlobUrl=url;" +
            "window.__senzBlobDone=false;" +
            "var name='';" +
            "try{name=(window.__senzBlobName&&window.__senzBlobName(url))||'';}catch(e){}" +
            "if(!name){try{var as=document.querySelectorAll('a[download]');" +
            "for(var ai=0;ai<as.length;ai++){" +
            "if(as[ai].href===url&&as[ai].getAttribute('download'))" +
            "{name=as[ai].getAttribute('download');break;}}}catch(e){}}" +
            "var cached='';" +
            "try{cached=(window.__senzBlobGet&&window.__senzBlobGet(url))||'';}catch(e){}" +
            "try{window.SenzBlob.onBlobDiag('dl url='+url.slice(0,60)+',iframes='+document.querySelectorAll('iframe').length+',hit='+(cached!==''));}catch(e){}" +
            "if(cached){window.__senzBlobDone=true;window.SenzBlob.onBlobData(cached,name);return;}" +
            "var fs=document.querySelectorAll('iframe');" +
            "for(var i=0;i<fs.length;i++){" +
            "try{fs[i].contentWindow.postMessage({type:'senz-get-blob',url:url},'*');}catch(e){}}" +
            "setTimeout(function(){try{" +
            "if(window.__senzBlobDone)return;" +
            "var xhr=new XMLHttpRequest();" +
            "xhr.open('GET',url,true);" +
            "xhr.responseType='blob';" +
            "xhr.timeout=30000;" +
            "xhr.onload=function(){var r=new FileReader();" +
            "r.onloadend=function(){if(window.__senzBlobDone)return;" +
            "window.__senzBlobDone=true;var s=r.result||'';var i=s.indexOf(',');" +
            "window.SenzBlob.onBlobData(i>=0?s.substring(i+1):s,name);};" +
            "r.onerror=function(){window.SenzBlob.onBlobError('reader-error');};" +
            "r.readAsDataURL(xhr.response);};" +
            "xhr.onerror=function(){window.SenzBlob.onBlobError('xhr-error status='+xhr.status);};" +
            "xhr.ontimeout=function(){window.SenzBlob.onBlobError('xhr-timeout');};" +
            "xhr.send();}catch(e){window.SenzBlob.onBlobError('js-exception '+e.message);}},6000);" +
            "}catch(e){window.SenzBlob.onBlobError('js-exception '+e.message);}})();"
        try {
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadDataUrl(dlUrl: String, fileName: String) {
        try {
            val comma = dlUrl.indexOf(',')
            val meta = dlUrl.substring(5, comma)
            val dataPart = dlUrl.substring(comma + 1)
            val bytes = if (meta.contains(";base64")) {
                android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            saveDownloadedBytes(fileName, bytes)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Jembatan unduh blob: halaman (frame mana pun, lewat postMessage)
     *  mengirim isi file sebagai base64 + nama asli dari atribut
     *  download anchor (bisa kosong). Dipanggil dari thread binder —
     *  decode + tulis di thread pekerja. */
    private inner class BlobDownloadBridge {
        @android.webkit.JavascriptInterface
        fun onBlobData(base64: String, fileName: String) {
            // Nama asli dari atribut download anchor (mis.
            // "Generated Audio ... .wav", "PROJECT STATE.md") MENANG
            // atas nama tebakan WebView (UUID + .bin/.txt) — tebakan
            // itulah yang bikin unduhan Claude 4 jadi .bin dan AI
            // Studio jadi .txt di v0.1.18 (isi file tetap benar).
            val guessed = pendingBlobFileName.ifEmpty { "download" }
            var name = if (fileName.isNotBlank()) fileName else guessed
            Thread {
                try {
                    val bytes = android.util.Base64.decode(
                        base64, android.util.Base64.DEFAULT
                    )
                    name = fixExtension(name, bytes)
                    saveDownloadedBytes(name, bytes)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@BrowserActivity,
                            "Gagal mengunduh: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }


        /** Diagnosis dari halaman — ditulis ke _Log-Blob. */
        @android.webkit.JavascriptInterface
        fun onBlobDiag(info: String) {
            logBlobDiag(info)
        }

        @android.webkit.JavascriptInterface
        fun onBlobError(reason: String) {
            logBlobDiag("GAGAL: $reason")
            runOnUiThread {
                Toast.makeText(
                    this@BrowserActivity,
                    "Gagal mengunduh file (blob tidak bisa dibaca) — coba tekan lagi tombol unduhnya",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Skrip penangkap blob (v0.1.17, nama diperbaiki v0.1.19) —
     *  disisipkan ke SETIAP dokumen
     *  (frame utama + iframe) lewat shouldInterceptRequest, jadi
     *  berjalan di konteks yang SAMA dengan pembuat blob — termasuk
     *  iframe sandboxed blob:null/. Isi blob di-cache base64; antar
     *  frame bicara via postMessage (tetap jalan lintas origin);
     *  frame utama meneruskan jawaban ke jembatan native SenzBlob. */
    /** Skrip penangkap blob (v0.1.17) — disisipkan ke SETIAP dokumen
     *  (frame utama + iframe) lewat shouldInterceptRequest, jadi
     *  berjalan di konteks yang SAMA dengan pembuat blob — termasuk
     *  iframe sandboxed blob:null/. Isi blob di-cache base64; antar
     *  frame bicara via postMessage (tetap jalan lintas origin);
     *  frame utama meneruskan jawaban ke jembatan native SenzBlob.
     *  v0.1.18: + perangkap iframe srcdoc/data: (lihat trap di bawah).
     *  Bukti log 01:32 WIB: injeksi masuk ke dokumen utama + 2 iframe
     *  layanan (auth_warmup, bscframe) TAPI tidak ada jawaban
     *  postMessage — pembuat blob adalah iframe yang dokumennya TIDAK
     *  lewat jaringan (srcdoc/about:blank, dibuat saat generate).
     *  Perangkap ini menangkap iframe itu saat dimasukkan ke DOM /
     *  saat srcdoc di-set, lalu menyisipkan skrip yang sama ke
     *  dalamnya. */
    private fun blobInjectScript(): String {
        val core = "(function(){try{" +
            "if(window.__senzBlobCache)return;" +
            "window.__senzBlobCache={};" +
            "window.__senzBlobNames={};" +
            "window.__senzBlobDone=false;" +
            "window.__senzBlobGet=function(u){return window.__senzBlobCache[u]||'';};" +
            "window.__senzBlobName=function(u){return window.__senzBlobNames[u]||'';};" +
            "var _create=URL.createObjectURL.bind(URL);" +
            "URL.createObjectURL=function(b){" +
            "var u=_create(b);" +
            "try{if(b&&b.size&&b.size<30*1024*1024){" +
            "var keys=Object.keys(window.__senzBlobCache);" +
            "if(keys.length>=5){delete window.__senzBlobCache[keys[0]];}" +
            "var r=new FileReader();" +
            "r.onloadend=function(){var s=r.result||'';var i=s.indexOf(',');" +
            "window.__senzBlobCache[u]=i>=0?s.substring(i+1):s;};" +
            "r.readAsDataURL(b);}}catch(e){}" +
            "return u;};" +
            "var _revoke=URL.revokeObjectURL.bind(URL);" +
            "URL.revokeObjectURL=function(u){" +
            "setTimeout(function(){" +
            "try{_revoke(u);}catch(e){}" +
            "try{delete window.__senzBlobCache[u];}catch(e){}},60000);};" +
            "var _click=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){" +
            "try{var h=this.href||'';var d=this.getAttribute('download');" +
            "if(h.indexOf('blob:')===0&&d){window.__senzBlobNames[h]=d;}}catch(e){}" +
            "return _click.call(this);};" +
            "try{var _dd=Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype,'download');" +
            "if(_dd&&_dd.set){var _ds=_dd.set;" +
            "Object.defineProperty(HTMLAnchorElement.prototype,'download',{configurable:true,enumerable:_dd.enumerable,get:_dd.get,set:function(v){try{var h=this.href||'';if(h.indexOf('blob:')===0&&v){window.__senzBlobNames[h]=String(v);}}catch(e){}return _ds.call(this,v);}});}}catch(e){}" +
            "window.addEventListener('message',function(ev){" +
            "try{var m=ev.data||{};" +
            "if(m&&m.type==='senz-get-blob'&&m.url){" +
            "var data=window.__senzBlobGet(m.url)||'';" +
            "var name=window.__senzBlobName(m.url)||'';" +
            "var reply={type:'senz-blob-data',url:m.url,data:data,name:name};" +
            "if(window.parent&&window.parent!==window){window.parent.postMessage(reply,'*');}" +
            "else if(data&&window.SenzBlob){window.SenzBlob.onBlobData(data,name);}" +
            "}else if(m&&m.type==='senz-blob-data'&&window.parent===window){" +
            "if(m.url===window.__senzPendingBlobUrl&&m.data&&!window.__senzBlobDone){" +
            "var nm=m.name||'';" +
            "try{nm=nm||window.__senzBlobName(m.url)||'';}catch(e){}" +
            "window.__senzBlobDone=true;window.SenzBlob.onBlobData(m.data,nm);}" +
            "}}catch(e){}});" +
            "}catch(e){}})();"
        // Perangkap iframe (v0.1.18) — HANYA di frame utama. Menangkap
        // iframe srcdoc / data:text/html saat dimasukkan ke DOM atau
        // saat srcdoc/src di-set, lalu menyisipkan CORE yang sama ke
        // dalamnya. Tulis '<scr'+'ipt>' (bukan literal '</script>')
        // supaya tidak memutus tag <script> injeksi ini sendiri.
        val trap = "(function(){try{" +
            "if(window.parent!==window)return;" +
            "var CORE=" + org.json.JSONObject.quote(core) + ";" +
            "var wrap=function(h){try{" +
            "if(!h||h.indexOf('__senzBlobCache')!==-1)return h;" +
            "return h+'<scr'+'ipt>'+CORE+'</scr'+'ipt>';}catch(e){return h;}};" +
            "var diag=function(m){try{if(window.SenzBlob)window.SenzBlob.onBlobDiag(m);}catch(e){}};" +
            "var rewriteDataSrc=function(s){try{" +
            "if(!s||s.indexOf('data:text/html')!==0)return s;" +
            "var comma=s.indexOf(',');if(comma<0)return s;" +
            "var head=s.substring(0,comma+1);var raw=s.substring(comma+1);" +
            "var b64=head.indexOf(';base64')!==-1;" +
            "var body=b64?atob(raw):decodeURIComponent(raw);" +
            "var w=wrap(body);if(w===body)return s;" +
            "diag('TRAP data: len='+body.length);" +
            "return head+(b64?btoa(w):encodeURIComponent(w));" +
            "}catch(e){return s;}};" +
            "var trapOne=function(f){try{" +
            "if(!f||f.__senzTrapped)return;f.__senzTrapped=true;" +
            "var sd=null;try{sd=f.getAttribute('srcdoc');}catch(e){}" +
            "if(sd){var w=wrap(sd);" +
            "if(w!==sd){f.setAttribute('srcdoc',w);" +
            "diag('TRAP srcdoc sandbox='+(f.getAttribute('sandbox')||'')+' len='+sd.length);}return;}" +
            "var s='';try{s=f.getAttribute('src')||'';}catch(e){}" +
            "var rs=rewriteDataSrc(s);if(rs!==s){f.setAttribute('src',rs);}" +
            "}catch(e){}};" +
            "var trapNode=function(n){try{" +
            "if(!n)return;" +
            "if(n.tagName==='IFRAME')trapOne(n);" +
            "if(n.querySelectorAll){var fs=n.querySelectorAll('iframe');" +
            "for(var i=0;i<fs.length;i++)trapOne(fs[i]);}" +
            "}catch(e){}};" +
            "var _append=Node.prototype.appendChild;" +
            "Node.prototype.appendChild=function(n){try{trapNode(n);}catch(e){}return _append.call(this,n);};" +
            "var _insert=Node.prototype.insertBefore;" +
            "Node.prototype.insertBefore=function(n,r){try{trapNode(n);}catch(e){}return _insert.call(this,n,r);};" +
            "var _replace=Node.prototype.replaceChild;" +
            "Node.prototype.replaceChild=function(n,o){try{trapNode(n);}catch(e){}return _replace.call(this,n,o);};" +
            "var _setAttr=Element.prototype.setAttribute;" +
            "Element.prototype.setAttribute=function(k,v){try{" +
            "var kk=String(k).toLowerCase();" +
            "if(this.tagName==='IFRAME'&&kk==='srcdoc'){v=wrap(String(v));}" +
            "else if(this.tagName==='IFRAME'&&kk==='src'){v=rewriteDataSrc(String(v));}" +
            "}catch(e){}return _setAttr.call(this,k,v);};" +
            "try{var _d=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'srcdoc');" +
            "if(_d&&_d.set){var _s=_d.set;" +
            "Object.defineProperty(HTMLIFrameElement.prototype,'srcdoc',{configurable:true,enumerable:_d.enumerable,get:_d.get,set:function(v){try{v=wrap(String(v));}catch(e){}return _s.call(this,v);}});}}catch(e){}" +
            "try{var _d2=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'src');" +
            "if(_d2&&_d2.set){var _s2=_d2.set;" +
            "Object.defineProperty(HTMLIFrameElement.prototype,'src',{configurable:true,enumerable:_d2.enumerable,get:_d2.get,set:function(v){try{v=rewriteDataSrc(String(v));}catch(e){}return _s2.call(this,v);}});}}catch(e){}" +
            "try{var _obs=new MutationObserver(function(muts){" +
            "for(var i=0;i<muts.length;i++){var m=muts[i];" +
            "for(var j=0;j<m.addedNodes.length;j++)trapNode(m.addedNodes[j]);}});" +
            "_obs.observe(document.documentElement||document,{childList:true,subtree:true});}catch(e){}" +
            "}catch(e){}})();"
        return core + trap
    }

    /** Sisipkan skrip penangkap blob ke HTML: sebelum </body> (fallback:
     *  tempel di akhir). Hapus juga <meta> CSP supaya skrip inline
     *  tidak diblokir. */
    private fun injectBlobScript(html: String): String {
        var out = html
        try {
            out = out.replace(
                Regex(
                    "<meta[^>]*http-equiv\\s*=\\s*[\"'']?Content-Security-Policy[\"'']?[^>]*>",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            val script = "<script>" + blobInjectScript() + "</script>"
            val idx = out.lastIndexOf("</body>", ignoreCase = true)
            out = if (idx >= 0) {
                out.substring(0, idx) + script + out.substring(idx)
            } else {
                out + script
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Inti v0.1.17: ambil dokumen HTML (frame utama + iframe), sisipkan
     *  skrip penangkap blob, kembalikan tanpa header CSP.
     *  Dipanggil dari shouldInterceptRequest (thread IO WebView).
     *  Hanya GET tanpa Range + URL yang kelihatan seperti dokumen
     *  (tanpa ekstensi statis) yang diambil; sisanya null (WebView
     *  muat sendiri seperti biasa). Gagal apa pun → null (halaman
     *  tetap jalan, cuma tanpa injeksi). */
    private fun interceptDocument(
        request: WebResourceRequest
    ): android.webkit.WebResourceResponse? {
        try {
            if (request.method != "GET") return null
            if (request.requestHeaders.containsKey("Range")) return null
            val urlStr = request.url.toString()
            if (!urlStr.startsWith("http://") &&
                !urlStr.startsWith("https://")
            ) return null
            val path = request.url.path ?: ""
            val last = path.substringAfterLast('/', "")
            if (last.contains('.')) {
                val ext = last.substringAfterLast('.', "").lowercase()
                if (ext in STATIC_EXTS) return null
            }
            val skipHeaders = setOf(
                "If-None-Match", "If-Modified-Since", "If-Range", "Range",
                "Accept-Encoding", "Content-Length", "Host", "Connection"
            )
            val conn = (java.net.URL(urlStr).openConnection()
                as java.net.HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                requestMethod = "GET"
                for ((k, v) in request.requestHeaders) {
                    if (skipHeaders.any { it.equals(k, true) }) continue
                    setRequestProperty(k, v)
                }
                val cookie = CookieManager.getInstance().getCookie(urlStr)
                if (cookie != null) setRequestProperty("Cookie", cookie)
            }
            val code = conn.responseCode
            if (code < 200 || code >= 400) return null
            val contentType = conn.contentType ?: ""
            if (conn.contentLengthLong > 2 * 1024 * 1024) return null
            val bytes = try {
                conn.inputStream.readBytes()
            } catch (e: Exception) {
                return null
            }
            val headers = mutableMapOf<String, String>()
            for ((k, vs) in conn.headerFields) {
                if (k == null) continue
                if (k.equals("Content-Security-Policy", true) ||
                    k.equals("Content-Security-Policy-Report-Only", true) ||
                    k.equals("Content-Length", true)
                ) continue
                headers[k] = vs?.joinToString(", ") ?: ""
            }
            val mime = contentType.substringBefore(';').trim()
                .ifEmpty { "text/html" }
            val charset = if (contentType.contains("charset=")) {
                contentType.substringAfter("charset=")
                    .substringBefore(';').trim()
            } else "UTF-8"
            var body = bytes
            if (mime.contains("text/html")) {
                val html = String(bytes, charset(charset))
                val injected = injectBlobScript(html)
                body = injected.toByteArray(charset(charset))
                logBlobDiag("INJECT: $urlStr (${body.size} B)")
            }
            return android.webkit.WebResourceResponse(
                mime, charset, code, conn.responseMessage ?: "OK",
                headers, java.io.ByteArrayInputStream(body)
            )
        } catch (e: Exception) {
            return null
        }
    }

    /** Tulis byte hasil unduhan ke folder Space ini.
     *  Nama bentrok → tambah (1), (2) — sama seperti pindah file. */
    private fun saveDownloadedBytes(fileName: String, bytes: ByteArray) {
        Thread {
            try {
                val dir = FileStore.folderDir(this, dataDirectorySuffix)
                dir.mkdirs()
                val dest = FileStore.uniqueFile(dir, fileName)
                java.io.FileOutputStream(dest).use { it.write(bytes) }
                logBlobDiag("SIMPAN: ${dest.name} (${dest.length()} B)")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Tersimpan di folder $spaceName: ${dest.name}",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshDownloadCount()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this, "Gagal menyimpan file: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** Multi Live (S1): Back dari Space TIDAK menutup Space. Activity ini
     *  tinggal di back stack (WebView + sesi tetap hidup, thumbnail live
     *  terus di-capture) dan Beranda dimunculkan ke depan. Tap tile di
     *  Beranda membawa Activity ini kembali ke depan tanpa WebView baru
     *  (MainActivity.openSpace memakai FLAG_ACTIVITY_REORDER_TO_FRONT).
     *  Keluar app tetap lewat Back di Beranda seperti biasa. */
    private fun goToDashboard() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        // Perbaikan FC 20:35 WIB: onDestroy lama menghancurkan WebView utama
        // sementara pop-up login masih hidup — pop-up itu pegang referensi ke
        // WebView utama (WebViewTransport) dan destroy() utama lebih dulu
        // bikin crash native. Urutan aman: hancurkan pop-up dulu, lepas
        // keduanya dari parent, baru hancurkan WebView utama.
        activityDestroyed = true
        stopLiveCapture()
        try {
            popupView?.let {
                popupView = null
                rootLayout.removeView(it)
                it.destroy()
            }
        } catch (e: Exception) {
            // Jangan ikut crash saat bersih-bersih.
        }
        if (::webView.isInitialized) {
            try {
                rootLayout.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                // Jangan ikut crash saat bersih-bersih.
            }
        }
        super.onDestroy()
    }
}

/** Space 1 — proses :space1, data directory suffix "space1". */
class Space1Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space1"
}

/** Space 2 — proses :space2, data directory suffix "space2". */
class Space2Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space2"
}

/** Space 3 — proses :space3, data directory suffix "space3". */
class Space3Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space3"
}

/** Space 4 — proses :space4, data directory suffix "space4". */
class Space4Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space4"
}

/** Space 5 — proses :space5, data directory suffix "space5". */
class Space5Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space5"
}

/** Space 6 — proses :space6, data directory suffix "space6". */
class Space6Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space6"
}

/** Space 8 — proses :space8, data directory suffix "space8". */
class Space8Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space8"
}

/** Space 9 — proses :space9, data directory suffix "space9". */
class Space9Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space9"
}

/** Space 10 — proses :space10, data directory suffix "space10". */
class Space10Activity : BrowserActivity() {
    override val dataDirectorySuffix: String = "space10"
}

/** AI Studio — proses :aistudio, data directory suffix "aistudio". */
class AiStudioActivity : BrowserActivity() {
    override val dataDirectorySuffix: String = "aistudio"

    // AI Studio (perbaikan 20:45 WIB): halaman kerja sesudah login bisa
    // ber-URL root "/" — mis. Playground TTS di screenshot HP 20:39
    // (aistudio.google.com/, konten login penuh) — jadi aturan path
    // "/prompts | /app | /library" TIDAK cukup dan chip tetap
    // "Belum login" walau sudah login. Aturan baru: login bila ada cookie
    // sesi Google (SID/SAPISID) di cookie jar WebView ini, ATAU URL kerja.
    // Cookie jar WebView terpisah dari Chrome — SID di sini artinya login
    // lewat WebView ini, bukan tebakan.
    override fun isLoggedIn(url: String): Boolean {
        return try {
            val u = android.net.Uri.parse(url)
            val path = u.path ?: ""
            if (u.host != "aistudio.google.com") return false
            if (path.contains("/prompts") || path.contains("/app") ||
                path.contains("/library")
            ) return true
            val cookie = CookieManager.getInstance()
                .getCookie("https://aistudio.google.com/") ?: ""
            cookie.contains("SID=") || cookie.contains("SAPISID=") ||
                cookie.contains("__Secure-1PSID=")
        } catch (e: Exception) {
            false
        }
    }
}
