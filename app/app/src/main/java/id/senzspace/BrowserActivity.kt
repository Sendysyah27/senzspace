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
    private lateinit var downloadBtn: android.widget.Button
    private val lockLauncher = LockGate.register(this)
    private var popupView: WebView? = null
    private var currentUrl = ""
    private var spaceName = "Space"
    private var homeUrl = "https://claude.ai"
    private var activityDestroyed = false
    private var pendingBlobFileName = ""

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
        fun topBarBtn(label: String): android.widget.Button =
            android.widget.Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#00000000"))
                textSize = 16f
                minimumHeight = 0
                minHeight = 0
            }
        val minimizeBtn = topBarBtn("⌄")
        minimizeBtn.setOnClickListener { goToDashboard() }
        val reloadBtn = topBarBtn("↻")
        reloadBtn.setOnClickListener { if (::webView.isInitialized) webView.reload() }
        val homeBtn = topBarBtn("⌂")
        homeBtn.setOnClickListener { if (::webView.isInitialized) webView.loadUrl(homeUrl) }
        downloadBtn = topBarBtn("⬇")
        downloadBtn.setOnClickListener {
            val intent = android.content.Intent(this, FileManagerActivity::class.java)
            intent.putExtra(FileManagerActivity.EXTRA_FOLDER, dataDirectorySuffix)
            startActivity(intent)
        }
        topBarLayout.addView(topBar)
        topBarLayout.addView(minimizeBtn)
        topBarLayout.addView(reloadBtn)
        topBarLayout.addView(homeBtn)
        topBarLayout.addView(downloadBtn)
        root.addView(topBarLayout)

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
                    if (!isForeground) captureLiveThumbnail()
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
            }
        }
        root.addView(webView)
        // Jembatan JS Multi Live v2 (MutationObserver): halaman hanya boleh
        // memanggil SenzLive.onChanged() tanpa argumen — tidak ada data
        // halaman yang keluar, cuma sinyal "halaman berubah".
        webView.addJavascriptInterface(LiveBridge(), "SenzLive")
        // Jembatan unduh blob (v0.1.11): isi file yang diunduh halaman
        // (mis. lampiran Claude) dikirim halaman sebagai base64.
        webView.addJavascriptInterface(BlobDownloadBridge(), "SenzBlob")

        // Unduhan per Space (PRD F6, slice File Manager): file unduhan
        // dari WebView masuk ke folder Space ini di FileStore. Cookie sesi
        // + User-Agent diteruskan ke DownloadManager — tanpa cookie,
        // unduhan dari situs yang butuh login (mis. lampiran Claude)
        // ditolak server (403).
        // AKAR GAGAL 23:08: Claude menyajikan lampiran sebagai URL
        // blob: (blob:https://claude.ai/...) dan DownloadManager TIDAK
        // BISA menangani skema blob: ("Can not handle uri: blob:…").
        // Jadi blob: diambil isinya lewat JS (XHR + FileReader → base64)
        // lalu ditulis langsung ke folder Space; data: didekode langsung.
        webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            val fileName = android.webkit.URLUtil.guessFileName(
                dlUrl, contentDisposition, mimeType
            )
            when {
                dlUrl.startsWith("blob:") -> downloadBlob(dlUrl, fileName)
                dlUrl.startsWith("data:") -> downloadDataUrl(dlUrl, fileName)
                else -> downloadViaManager(
                    dlUrl, userAgent, contentDisposition, mimeType, fileName
                )
            }
        }

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

    override fun onResume() {
        super.onResume()
        isForeground = true
        LockGate.check(this, lockLauncher)
        refreshDownloadCount()
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

    /** Badge ⬇ di top bar = jumlah file di folder Space ini. */
    private fun refreshDownloadCount() {
        if (!::downloadBtn.isInitialized) return
        val n = try {
            FileStore.count(this, dataDirectorySuffix)
        } catch (e: Exception) {
            0
        }
        downloadBtn.text = if (n > 0) "⬇ $n" else "⬇"
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

    private fun downloadViaManager(
        dlUrl: String, userAgent: String, contentDisposition: String,
        mimeType: String, fileName: String
    ) {
        try {
            val req = android.app.DownloadManager.Request(
                android.net.Uri.parse(dlUrl)
            )
            req.setMimeType(mimeType)
            req.addRequestHeader("User-Agent", userAgent)
            val cookie = CookieManager.getInstance().getCookie(dlUrl)
            if (cookie != null) req.addRequestHeader("Cookie", cookie)
            if (currentUrl.isNotEmpty()) req.addRequestHeader("Referer", currentUrl)
            req.setTitle(fileName)
            req.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            req.setDestinationInExternalFilesDir(
                this, FileStore.ROOT, "$dataDirectorySuffix/$fileName"
            )
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(req)
            Toast.makeText(
                this,
                "Mengunduh $fileName ke folder $spaceName",
                Toast.LENGTH_SHORT
            ).show()
            logEvent("UNDUH: $fileName")
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
            logEvent("UNDUH GAGAL: $fileName")
        }
    }

    /** Ambil isi blob: URL lewat JS halaman (XHR responseType blob →
     *  FileReader → base64). Hanya SATU unduhan blob dalam satu waktu;
     *  unduhan kedua yang datang selagi yang pertama jalan akan
     *  menimpa nama pending-nya (jarang terjadi di Claude). */
    private fun downloadBlob(dlUrl: String, fileName: String) {
        if (!::webView.isInitialized) return
        pendingBlobFileName = fileName
        val escaped = dlUrl.replace("\\", "\\\\").replace("'", "\\'")
        val js = "(function(){try{" +
            "var xhr=new XMLHttpRequest();" +
            "xhr.open('GET','$escaped',true);" +
            "xhr.responseType='blob';" +
            "xhr.onload=function(){var r=new FileReader();" +
            "r.onloadend=function(){var s=r.result||'';var i=s.indexOf(',');" +
            "window.SenzBlob.onBlobData(i>=0?s.substring(i+1):s);};" +
            "r.onerror=function(){window.SenzBlob.onBlobError();};" +
            "r.readAsDataURL(xhr.response);};" +
            "xhr.onerror=function(){window.SenzBlob.onBlobError();};" +
            "xhr.send();}catch(e){window.SenzBlob.onBlobError();}})();"
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

    /** Tulis byte hasil unduhan (blob/data) ke folder Space ini.
     *  Nama bentrok → tambah (1), (2) — sama seperti pindah file. */
    private fun saveDownloadedBytes(fileName: String, bytes: ByteArray) {
        Thread {
            try {
                val dir = FileStore.folderDir(this, dataDirectorySuffix)
                dir.mkdirs()
                val dest = FileStore.uniqueFile(dir, fileName)
                java.io.FileOutputStream(dest).use { it.write(bytes) }
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

    /** Jembatan unduh blob: halaman mengirim isi file sebagai base64.
     *  Dipanggil dari thread binder — decode + tulis di thread pekerja. */
    private inner class BlobDownloadBridge {
        @android.webkit.JavascriptInterface
        fun onBlobData(base64: String) {
            val fileName = pendingBlobFileName.ifEmpty { "download" }
            Thread {
                try {
                    val bytes = android.util.Base64.decode(
                        base64, android.util.Base64.DEFAULT
                    )
                    saveDownloadedBytes(fileName, bytes)
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

        @android.webkit.JavascriptInterface
        fun onBlobError() {
            runOnUiThread {
                Toast.makeText(
                    this@BrowserActivity,
                    "Gagal mengunduh file (blob tidak bisa dibaca)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onBackPressed() {
        // Pop-up login terbuka: Back menutup pop-up dulu.
        if (popupView != null) {
            closePopupWindow()
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else goToDashboard()
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
