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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
        // v0.1.31: requestSplitWith DIHAPUS — static companion hidup
        // PER PROSES Android. MainActivity (proses utama) mengisi
        // static ini, tapi BrowserActivity yang membacanya jalan di
        // proses :spaceN yang berbeda — nilainya selalu null di sana,
        // jadi "Buka berdampingan" tidak pernah jalan (temuan HP
        // 17 Nov). Pengganti: intent extra EXTRA_SPLIT_WITH +
        // onNewIntent (lihat bawah).
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

    /** v0.1.31: geser top bar DIHAPUS (perintah Sendy 17 Nov) —
     *  diganti tombol ⇌ di top bar (showSwitchSpacePicker).
     *  Slot split yang diminta lewat intent (EXTRA_SPLIT_WITH)
     *  ditampung di field instance ini, dikonsumsi di onResume. */
    private var pendingSplitSlot: Int? = null
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

    /** Host login Google — shouldInterceptRequest TIDAK mengambilnya.
     *  Alasan (temuan HP 17 Nov 09:14): halaman Sign-in Google
     *  (accounts.google.com) gagal dengan dialog "Something went
     *  wrong" di WebView utama, padahal di Chrome aman dan login
     *  Google pernah LULUS di gate S0 (16 Nov, sebelum trap blob
     *  ada). Penyebab paling mungkin: interceptDocument mengambil
     *  ulang halaman itu lewat HttpURLConnection + menyisipi skrip
     *  trap (yang membungkus appendChild/iframe srcdoc) — halaman
     *  login Google yang penuh iframe dinamis tidak tahan
     *  dimodifikasi. Halaman auth tidak butuh penangkap blob
     *  (tidak ada unduhan di sana), jadi WebView memuatnya sendiri
     *  apa adanya, persis seperti Chrome. */
    private val NO_INJECT_HOSTS = setOf(
        "accounts.google.com", "myaccount.google.com"
    )

    /** v0.1.31 — DAFTAR PUTIH trap blob: skrip penangkap blob HANYA
     *  diinjeksikan ke host Claude + AI Studio (satu-satunya yang
     *  butuh unduhan blob). Sebelumnya trap diinjeksikan ke SEMUA situs
     *  kecuali host Google — halaman login situs lain (mis. ChatGPT /
     *  auth.openai.com) ikut dimodifikasi DOM-nya sehingga login Google
     *  macet di tengah isi akun (temuan HP 17 Nov ~15:xx, Space 8).
     *  Situs lain kini dimuat apa adanya seperti Chrome; unduhan https
     *  lewat fetch WebView tetap jalan di mana saja. */
    private val TRAP_HOSTS = setOf(
        "claude.ai", "aistudio.google.com"
    )

    private fun isTrapHost(host: String): Boolean {
        if (host in TRAP_HOSTS) return true
        return TRAP_HOSTS.any { host.endsWith(".$it") }
    }

    /** v0.1.38 - payload prompt API yang sedang dieksekusi di Space
     *  ini (datang lewat intent extra, lihat consumeApiPrompt).
     *  pendingPromptFiles = file _inbox yang disodorkan ke chooser
     *  saat JS mengklik tombol attach; promptChooserServed =
     *  chooser sudah dipanggil (dipakai tunggu attach). */
    private var pendingPromptText: String? = null
    private var pendingPromptFiles: List<java.io.File>? = null
    private var pendingPromptUrl: String? = null
    /** v0.1.40 - nama Project Claude tujuan (mis. "SSR CONTENT STUDIO"). */
    private var pendingPromptProject: String? = null
    private var promptProjectDone: Boolean = false
    private var promptRunning: Boolean = false
    private var promptChooserServed: Boolean = false
    private val promptHandler = Handler(Looper.getMainLooper())
    /** v0.1.39 - payload TTS API (AI Studio Space 7). */
    private var pendingTtsScript: String? = null
    private var pendingTtsVoice: String? = null
    private var pendingTtsModel: String? = null
    private var pendingTtsUrl: String? = null
    private var pendingTtsSpeaker: String? = null
    private var ttsActiveModel: String = ""
    private var ttsRunning: Boolean = false
    private var ttsFilesBefore: Set<String> = emptySet()
    private var ttsDownloadInfo: String = ""
    private var ttsLastTapInfo: String = ""
    private val ttsHandler = Handler(Looper.getMainLooper())

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

    // v0.1.35: status live per Space (dibaca Beranda) — detak proses,
    // perubahan DOM, perubahan piksel thumbnail. Dipakai Beranda untuk
    // membedakan "Space mati" vs "gambar basi" vs "live beneran".
    private var lastAliveMs = 0L
    private var lastDomMs = 0L
    private var lastPixelMs = 0L
    private var lastStatusWriteMs = 0L
    private var lastThumbBmp: android.graphics.Bitmap? = null

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
            // v0.1.35: detak proses — Beranda tahu Space ini masih jalan
            // atau sudah mati dibunuh sistem (RAM menipis).
            lastAliveMs = System.currentTimeMillis()
            writeLiveStatus()
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
        lastThumbBmp?.recycle()
        lastThumbBmp = null
    }

    /** Status live (v0.1.35) — satu file kecil di _live, ditulis proses
     *  Space, dibaca Beranda: detak proses | DOM berubah | gambar berubah.
     *  Dibatasi 1 tulis / 5 dtk supaya tidak membebani storage. */
    private fun writeLiveStatus() {
        val now = System.currentTimeMillis()
        if (now - lastStatusWriteMs < 5000) return
        lastStatusWriteMs = now
        try {
            val f = FileStore.liveStatusFile(this, dataDirectorySuffix)
            f.parentFile?.mkdirs()
            f.writeText("$lastAliveMs|$lastDomMs|$lastPixelMs")
        } catch (e: Exception) {
        }
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
            // v0.1.35: deteksi renderer beku — draw() pada WebView latar
            // yang renderernya mati mengembalikan frame lama yang sama
            // terus-menerus (tile jadi "live baru saja" padahal gambar
            // basi berjam-jam — eneng127, 19 Nov 09:43). Bandingkan
            // piksel dengan tangkapan sebelumnya.
            val changed = try {
                lastThumbBmp?.let { !it.sameAs(bmp) } ?: true
            } catch (e: Exception) {
                true
            }
            if (changed) {
                lastPixelMs = now
                lastThumbBmp?.recycle()
                lastThumbBmp = try {
                    android.graphics.Bitmap.createBitmap(bmp)
                } catch (e: Exception) {
                    null
                }
            }
            val file = FileStore.liveThumbFile(this, dataDirectorySuffix)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bmp.recycle()
            writeLiveStatus()
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
                // v0.1.35: catat DOM berubah — Beranda pakai ini untuk
                // membedakan "halaman diam (idle)" vs "renderer beku".
                lastDomMs = System.currentTimeMillis()
                writeLiveStatus()
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
        // v0.1.31: ikuti sensor putar HP (berlaku walau auto-rotate
        // sistem mati) — app sebelumnya tidak bisa horizontal.
        // v0.1.33: rotasi MENGIKUTI setelan sistem (auto-rotate HP) —
        // SENSOR (v0.1.31) memutar app dari sensor walau auto-rotate
        // sistem mati; UNSPECIFIED = perilaku default Android.
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

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
        // Unduhan (badge jumlah file; tap → File Manager folder ini).
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
        // Lucide (bukan lagi huruf yang tampilannya beda-beda
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
        // v0.1.31: tombol pindah Space (ganti geser top bar) — buka
        // dialog pilih Space AKTIF (sudah login), aturan Sendy 06:45.
        val switchBtn = topBarIcon(R.drawable.ic_switch, "Pindah Space")
        switchBtn.setOnClickListener { showSwitchSpacePicker() }
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
        topBarLayout.addView(switchBtn)
        topBarLayout.addView(minimizeBtn)
        topBarLayout.addView(reloadBtn)
        topBarLayout.addView(homeBtn)
        topBarLayout.addView(downloadWrap)
        // v0.1.31: animasi sentuh ringan (mengecil 0,88x saat ditekan)
        // untuk semua tombol top bar.
        addTouchAnimation(switchBtn)
        addTouchAnimation(minimizeBtn)
        addTouchAnimation(reloadBtn)
        addTouchAnimation(homeBtn)
        addTouchAnimation(downloadWrap)
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
                    // v0.1.38: halaman selesai - jalankan prompt API
                    // bila ada payload menunggu.
                    maybeRunApiPrompt()
                    // v0.1.39: sama untuk TTS API.
                    maybeRunApiTts()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    // v0.1.35: renderer mati (biasanya dibunuh sistem saat
                    // RAM menipis — POVA 5, 8 Space, free ~412MB) bikin
                    // WebView HITAM total sampai dimuat ulang (temuan 19
                    // Nov 09:42: SENDY09 hitam 60-90 dtk; refresh tidak
                    // langsung memunculkan konten, home baru memunculkan).
                    // Tanpa handler ini WebView diam hitam selamanya — dan
                    // draw() untuk tile ikut mengembalikan frame basi
                    // berjam-jam. Muat ulang di sini juga membersihkan
                    // timer Claude yang nyangkut (sisi Claude).
                    try {
                        val f = java.io.File(
                            FileStore.folderDir(
                                this@BrowserActivity, "_log-crash"
                            ),
                            "renderer-gone.txt"
                        )
                        f.parentFile?.mkdirs()
                        val ts = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                        ).format(java.util.Date())
                        f.appendText(
                            "$ts [${dataDirectorySuffix}] " +
                                "crash=${detail.didCrash()}\n"
                        )
                    } catch (e: Exception) {
                    }
                    Toast.makeText(
                        this@BrowserActivity,
                        "Renderer Space mati — memuat ulang",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        view.reload()
                    } catch (e: Exception) {
                        try {
                            view.loadUrl(homeUrl)
                        } catch (_: Exception) {
                        }
                    }
                    return true
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
                    // v0.1.38: prompt API sedang melampirkan file - JANGAN
                    // buka pemilih file, sodorkan langsung URI file
                    // _inbox lewat FileProvider (otoritas
                    // ${applicationId}.fileprovider, lihat file_paths).
                    if (pendingPromptFiles != null) {
                        val staged = pendingPromptFiles
                        pendingPromptFiles = null
                        try {
                            val uris = staged!!.map { f ->
                                val uri = androidx.core.content.FileProvider
                                    .getUriForFile(
                                        this@BrowserActivity,
                                        "${packageName}.fileprovider", f
                                    )
                                grantUriPermission(
                                    packageName, uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                                uri
                            }.toTypedArray()
                            promptChooserServed = true
                            filePathCallback.onReceiveValue(uris)
                        } catch (e: Exception) {
                            filePathCallback.onReceiveValue(null)
                        }
                        return true
                    }
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
            if (intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_DATA, false)) {
                clearSpaceDataAndLoad(
                    url,
                    intent.getStringExtra(MainActivity.EXTRA_CLEAR_SUFFIX)
                )
            } else {
                webView.loadUrl(url)
            }
            intent.getIntExtra(MainActivity.EXTRA_SPLIT_WITH, -1)
                .takeIf { it > 0 }?.let { pendingSplitSlot = it }
        }
        startLiveCapture()
        // v0.1.38: payload prompt API dari intent (bila ada).
        consumeApiPrompt(intent)
        // v0.1.39: payload TTS API dari intent (bila ada).
        consumeApiTts(intent)
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


    // ================= PROMPT API (v0.1.38) =================
    // Eksekusi prompt + lampiran ke chat Claude di WebView Space ini.
    // Alur: consumeApiPrompt (intent extra) -> maybeRunApiPrompt
    // (dipanggil tiap onPageFinished) -> diagnose -> attach (bila ada
    // file) -> isi teks -> kirim. Status tiap langkah ditulis ke file
    // api-prompt-<suffix>.json (filesDir internal, dibaca ApiServer
    // untuk /prompt-status). Selektor DOM Claude (ProseMirror +
    // aria-label) dicoba defensif: diagnose mengembalikan daftar
    // aria-label tombol di form composer, jadi log status memberi tahu
    // nama tombol attach yang sebenarnya bila selektor meleset.
    // HANYA atas perintah eksplisit per kirim (POST /prompt) - tidak
    // ada auto-kirim.

    private fun promptStatusFile(): java.io.File =
        java.io.File(filesDir, "api-prompt-$dataDirectorySuffix.json")

    private fun writePromptStatus(status: String, detail: String) {
        try {
            val obj = org.json.JSONObject().apply {
                put("status", status)
                put("detail", detail)
                put("updated", System.currentTimeMillis())
            }
            promptStatusFile().writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    private fun failPrompt(detail: String) {
        promptRunning = false
        pendingPromptText = null
        pendingPromptFiles = null
        writePromptStatus("failed", detail)
    }

    private fun consumeApiPrompt(intent: Intent) {
        val text = intent.getStringExtra(MainActivity.EXTRA_API_PROMPT_TEXT)
            ?: return
        intent.removeExtra(MainActivity.EXTRA_API_PROMPT_TEXT)
        val names = intent.getStringArrayListExtra(
            MainActivity.EXTRA_API_PROMPT_FILES
        )
        intent.removeExtra(MainActivity.EXTRA_API_PROMPT_FILES)
        val url = intent.getStringExtra(MainActivity.EXTRA_API_PROMPT_URL)
        intent.removeExtra(MainActivity.EXTRA_API_PROMPT_URL)
        val project = intent.getStringExtra(MainActivity.EXTRA_API_PROMPT_PROJECT)
        intent.removeExtra(MainActivity.EXTRA_API_PROMPT_PROJECT)
        val files = names?.mapNotNull { n ->
            val f = java.io.File(FileStore.folderDir(this, "_inbox"), n)
            if (f.isFile) f else null
        } ?: emptyList()
        pendingPromptText = text
        pendingPromptFiles = files
        pendingPromptUrl = url
        pendingPromptProject = project
        promptProjectDone = false
        promptRunning = false
        promptChooserServed = false
        writePromptStatus("queued", "payload diterima Space $dataDirectorySuffix")
        if (url != null && currentUrl != url) {
            // Masuk ke halaman tujuan dulu (mis. Project Claude);
            // eksekusi jalan dari onPageFinished setelah halaman muat.
            if (::webView.isInitialized) webView.loadUrl(url)
        } else {
            maybeRunApiPrompt()
        }
    }

    private fun maybeRunApiPrompt() {
        val text = pendingPromptText ?: return
        if (promptRunning) return
        if (!::webView.isInitialized) return
        val host = try {
            android.net.Uri.parse(currentUrl).host
        } catch (_: Exception) {
            null
        }
        if (host == null) return
        if (host != "claude.ai") {
            failPrompt("bukan halaman Claude: $currentUrl")
            return
        }
        promptRunning = true
        // v0.1.40: prompt masuk Project - navigasi sendiri dari tombol
        // kiri atas (sidebar) ke Project, baru buka chat baru di sana.
        if (pendingPromptProject != null && !promptProjectDone) {
            writePromptStatus("running", "menuju Project ${pendingPromptProject}")
            runPromptProject()
        } else {
            writePromptStatus("running", "mencari kolom chat Claude")
            runPromptDiagnose(5)
        }
    }

    /** v0.1.40 - navigasi ke Project Claude dari dalam Space.
     *  Langkah: klik tombol kiri atas (sidebar) -> klik nama Project
     *  -> tunggu URL /project/ -> klik "New chat" -> diagnose composer.
     *  Semua jalan di background (WebView tetap mengeksekusi meski
     *  activity tidak tampil - terbukti diagnose TTS 22:24). */
    private fun runPromptProject() {
        if (pendingPromptText == null) return
        val js = """(function(){var labels=['Toggle sidebar','Open sidebar','Close sidebar','Open navigation menu','Menu'];var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<labels.length;i++){for(var j=0;j<bs.length;j++){var l=(bs[j].getAttribute('aria-label')||'').trim();if(l===labels[i]){bs[j].click();return '1';}}}for(var k=0;k<bs.length;k++){var al=(bs[k].getAttribute('aria-label')||'').toLowerCase();if(al.indexOf('sidebar')>=0||al.indexOf('menu')>=0){bs[k].click();return '1';}}return '0';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                promptHandler.postDelayed({ runPromptPickProject() }, 1500)
            } else {
                failPrompt("tombol sidebar (kiri atas) tidak ketemu - tidak bisa masuk Project")
            }
        }
    }

    private fun runPromptPickProject() {
        val want = pendingPromptProject ?: return runPromptDiagnose(5)
        val quoted = org.json.JSONObject.quote(want)
        val js = """(function(){var want=$quoted.toLowerCase();var els=[...document.querySelectorAll('a,button,[role="link"],[role="button"],[role="menuitem"],li')].filter(function(e){return e.offsetParent!==null;});for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t.toLowerCase()===want){els[i].click();return t;}}for(var j=0;j<els.length;j++){var u=(els[j].textContent||'').trim();if(u.toLowerCase().indexOf(want)>=0&&u.length<80){els[j].click();return u;}}return '';})()"""
        webView.evaluateJavascript(js) { raw ->
            val picked = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (picked.isBlank()) {
                failPrompt("Project '$want' tidak ketemu di sidebar (cek nama Project-nya)")
            } else {
                writePromptStatus("running", "Project dipilih: $picked")
                waitPromptProjectUrl(5)
            }
        }
    }

    private fun waitPromptProjectUrl(triesLeft: Int) {
        if (pendingPromptText == null) return
        if (currentUrl.contains("/project/")) {
            runPromptNewChat()
        } else if (triesLeft <= 0) {
            failPrompt("halaman Project tidak kebuka (URL sekarang: $currentUrl)")
        } else {
            promptHandler.postDelayed({ waitPromptProjectUrl(triesLeft - 1) }, 2000)
        }
    }

    private fun runPromptNewChat() {
        val js = """(function(){var bs=[...document.querySelectorAll('button,a')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(/new chat/i.test(l)||/new chat/i.test(tx)){bs[i].click();return '1';}}return '0';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                promptProjectDone = true
                writePromptStatus("running", "chat baru di Project dibuka - mencari kolom chat")
                promptHandler.postDelayed({ runPromptDiagnose(5) }, 2000)
            } else {
                failPrompt("tombol 'New chat' di Project tidak ketemu")
            }
        }
    }

    /** Langkah 1: kolom chat (ProseMirror) ada? Balik "1|1|label,label"
     *  = composer ada | tombol kirim ada | aria-label tombol form. */
    private fun runPromptDiagnose(triesLeft: Int) {
        if (pendingPromptText == null) return
        val js = """(function(){var c=document.querySelector('div.ProseMirror[contenteditable="true"]')||document.querySelector('div[contenteditable="true"]');var s=document.querySelector('button[aria-label="Send message"]');var btns=[];var root=c?c.closest('form'):null;if(root){var bs=root.querySelectorAll('button');for(var i=0;i<bs.length;i++){var l=bs[i].getAttribute('aria-label');if(l)btns.push(l);}}return (c?'1':'0')+'|'+(s?'1':'0')+'|'+btns.join(',');})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            if (parts.size >= 2 && parts[0] == "1") {
                writePromptStatus(
                    "running",
                    "kolom chat ketemu; tombol: ${parts.getOrElse(2) { "" }}"
                )
                val files = pendingPromptFiles
                if (!files.isNullOrEmpty()) runPromptAttach() else runPromptFill()
            } else if (triesLeft > 1) {
                promptHandler.postDelayed({ runPromptDiagnose(triesLeft - 1) }, 2000)
            } else {
                failPrompt(
                    "kolom chat Claude tidak ketemu (tombol: ${parts.getOrElse(2) { "" }} - mungkin belum login)"
                )
            }
        }
    }

    /** Langkah 2 (bila ada file): klik tombol attach, tunggu chooser
     *  disodori file, tunggu 3 dtk, cek chip lampiran muncul. */
    private fun runPromptAttach() {
        val files = pendingPromptFiles ?: emptyList()
        val js = """(function(){var labels=['Add files','Attach files','Upload files','Add content','Attach'];for(var k=0;k<labels.length;k++){var b=document.querySelector('button[aria-label="'+labels[k]+'"]');if(b){b.click();return labels[k];}}var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){var l=bs[i].getAttribute('aria-label')||'';if(/attach|add file|upload/i.test(l)){bs[i].click();return l;}}return '';})()"""
        promptChooserServed = false
        writePromptStatus("running", "mengklik tombol attach (${files.size} file)")
        webView.evaluateJavascript(js) { raw ->
            val label = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (label.isBlank()) {
                failPrompt("tombol attach Claude tidak ketemu - cek /prompt-status tombol")
                return@evaluateJavascript
            }
            writePromptStatus("running", "tombol attach diklik: $label")
            waitPromptChooser(20)
        }
    }

    private fun waitPromptChooser(triesLeft: Int) {
        if (promptChooserServed) {
            promptHandler.postDelayed({ runPromptCheckAttached() }, 3000)
            return
        }
        if (triesLeft <= 0) {
            failPrompt("tombol attach diklik tapi pemilih file tidak muncul")
            return
        }
        promptHandler.postDelayed({ waitPromptChooser(triesLeft - 1) }, 500)
    }

    private fun runPromptCheckAttached() {
        val files = pendingPromptFiles ?: emptyList()
        val namesJson = org.json.JSONArray(files.map { it.name }).toString()
        val js = """(function(){var root=document.querySelector('div.ProseMirror[contenteditable="true"]');var area=root?root.closest('form'):document.body;var t=(area.innerText||'');var names=$namesJson;var found=[];for(var i=0;i<names.length;i++){if(t.indexOf(names[i])>=0)found.push(names[i]);}return found.join(',');})()"""
        webView.evaluateJavascript(js) { raw ->
            val found = raw?.trim()?.removeSurrounding("\"") ?: ""
            val n = if (found.isBlank()) 0 else found.split(",").size
            if (n >= files.size) {
                writePromptStatus("running", "lampiran muncul: $found")
                runPromptFill()
            } else {
                failPrompt("lampiran tidak muncul semua ($found dari ${files.size})")
            }
        }
    }

    /** Langkah 3: isi teks prompt ke kolom chat (insertText). */
    private fun runPromptFill() {
        val text = pendingPromptText ?: return
        val quoted = org.json.JSONObject.quote(text)
        val js = """(function(){var c=document.querySelector('div.ProseMirror[contenteditable="true"]')||document.querySelector('div[contenteditable="true"]');if(!c)return '0|0';c.focus();var ok=document.execCommand('insertText',false,$quoted);return String((c.innerText||'').length)+'|'+(ok?'1':'0');})()"""
        writePromptStatus("running", "mengisi teks prompt")
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            val len = parts.getOrNull(0)?.toIntOrNull() ?: 0
            if (len > 0) {
                runPromptSend()
            } else {
                failPrompt("teks prompt tidak masuk ke kolom chat")
            }
        }
    }

    /** Langkah 4: klik tombol kirim. */
    private fun runPromptSend() {
        val js = """(function(){var b=document.querySelector('button[aria-label="Send message"]');if(!b)return '0';if(b.disabled)return 'disabled';b.click();return '1';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                promptRunning = false
                pendingPromptText = null
                pendingPromptFiles = null
                writePromptStatus("sent", "terkirim - Claude sedang menjawab")
            } else if (clean == "disabled") {
                failPrompt("tombol kirim nonaktif (teks/lampiran belum diterima Claude)")
            } else {
                failPrompt("tombol kirim tidak ketemu")
            }
        }
    }
    // ================= AKHIR PROMPT API =================


    // ================= TTS API (v0.1.39) =================
    // Generate VO di AI Studio (Space 7) dari naskah: pilih model
    // starred (bila diminta) -> isi format suara -> isi naskah ->
    // Run -> tunggu selesai -> klik unduh (blob trap menyimpan wav
    // ke folder aistudio). Alur sama seperti PROMPT API: payload
    // lewat intent extra, status ke api-tts-<suffix>.json.
    // Halaman TTS AI Studio bisa ber-URL root "/" (lihat
    // AiStudioActivity) — jadi eksekutor TIDAK mensyaratkan path
    // tertentu, hanya host aistudio.google.com + ada kolom naskah.
    // Selektor DOM AI Studio defensif; diagnose mengembalikan daftar
    // textarea + tombol supaya log status memberi tahu nama kontrol
    // yang sebenarnya bila selektor meleset. HANYA atas perintah
    // eksplisit per generate (POST /tts) - tidak ada auto-generate.

    private fun ttsStatusFile(): java.io.File =
        java.io.File(filesDir, "api-tts-$dataDirectorySuffix.json")

    private fun writeTtsStatus(status: String, detail: String) {
        try {
            val obj = org.json.JSONObject().apply {
                put("status", status)
                put("detail", detail)
                put("updated", System.currentTimeMillis())
            }
            ttsStatusFile().writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    private fun failTts(detail: String) {
        ttsRunning = false
        pendingTtsScript = null
        pendingTtsSpeaker = null
        writeTtsStatus("failed", detail)
    }

    private fun consumeApiTts(intent: Intent) {
        val script = intent.getStringExtra(MainActivity.EXTRA_API_TTS_SCRIPT)
            ?: return
        intent.removeExtra(MainActivity.EXTRA_API_TTS_SCRIPT)
        val voice = intent.getStringExtra(MainActivity.EXTRA_API_TTS_VOICE)
        intent.removeExtra(MainActivity.EXTRA_API_TTS_VOICE)
        val model = intent.getStringExtra(MainActivity.EXTRA_API_TTS_MODEL)
        intent.removeExtra(MainActivity.EXTRA_API_TTS_MODEL)
        val url = intent.getStringExtra(MainActivity.EXTRA_API_TTS_URL)
        intent.removeExtra(MainActivity.EXTRA_API_TTS_URL)
        val speaker = intent.getStringExtra(MainActivity.EXTRA_API_TTS_SPEAKER)
        intent.removeExtra(MainActivity.EXTRA_API_TTS_SPEAKER)
        pendingTtsScript = script
        pendingTtsVoice = voice
        pendingTtsModel = model
        pendingTtsUrl = url
        pendingTtsSpeaker = speaker
        ttsRunning = false
        writeTtsStatus("queued", "payload TTS diterima Space $dataDirectorySuffix")
        if (url != null && currentUrl != url) {
            if (::webView.isInitialized) webView.loadUrl(url)
        } else {
            maybeRunApiTts()
        }
    }

    private fun maybeRunApiTts() {
        if (pendingTtsScript == null) return
        if (ttsRunning) return
        if (!::webView.isInitialized) return
        val host = try {
            android.net.Uri.parse(currentUrl).host
        } catch (_: Exception) {
            null
        }
        if (host == null) return
        if (host != "aistudio.google.com") {
            failTts("bukan halaman AI Studio: $currentUrl")
            return
        }
        ttsRunning = true
        writeTtsStatus("running", "mencari halaman TTS AI Studio")
        runTtsDiagnose(5)
    }

    /** Langkah 1 (v0.1.41): kenali halaman AI Studio.
     *  Balik "ta|run|dl|model|speaker|create|taLabels|btnLabels":
     *  ta = kolom isian terlihat, speaker = ada dropdown
     *  "Speaker 1" (editor dialog TTS), create = ada tombol
     *  "Create new dialog" (halaman utama TTS).
     *  Keputusan: editor -> Style; ada Create -> klik itu;
     *  selain itu -> alur pilih model (Run settings). */
    private fun runTtsDiagnose(triesLeft: Int) {
        if (pendingTtsScript == null) return
        val js = """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});var taLabels=tas.map(function(t){return (t.getAttribute('aria-label')||t.getAttribute('placeholder')||'textarea').replace(/[|,]/g,' ');});var run=0,dl=0,model='';var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});var btnLabels=[];for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l)btnLabels.push(l.replace(/[|,]/g,' '));else if(tx&&tx.length<40)btnLabels.push(tx.replace(/[|,]/g,' '));if(!run&&(l==='Run'||tx==='Run'))run=1;if(!dl&&(l==='Download'||tx==='Download'))dl=1;if(!model&&/gemini/i.test(tx))model=tx.replace(/[|,]/g,' ').slice(0,60);}var bodyTxt=document.body?document.body.innerText||'':'';var speaker=bodyTxt.indexOf('Speaker 1')>=0?'1':'0';var create=bodyTxt.indexOf('Create new dialog')>=0?'1':'0';return tas.length+'|'+run+'|'+dl+'|'+model+'|'+speaker+'|'+create+'|'+taLabels.join(',')+'|'+btnLabels.join(',');})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            val taCount = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val modelText = parts.getOrNull(3) ?: ""
            val speakerFlag = parts.getOrNull(4) ?: "0"
            val createFlag = parts.getOrNull(5) ?: "0"
            if (modelText.isNotBlank()) ttsActiveModel = modelText
            if (speakerFlag == "1" && taCount > 0) {
                writeTtsStatus(
                    "running",
                    "editor dialog TTS ketemu (kolom: ${parts.getOrElse(6) { "" }}; model aktif: $modelText)"
                )
                runTtsStyle()
            } else if (createFlag == "1") {
                writeTtsStatus("running", "halaman TTS ketemu - membuka dialog baru")
                runTtsCreateDialog()
            } else if (triesLeft > 1) {
                ttsHandler.postDelayed({ runTtsDiagnose(triesLeft - 1) }, 2000)
            } else {
                // Bukan editor / bukan halaman utama TTS (mis. halaman
                // preview suara 22:24, atau Playground chat). Alur
                // ASLI (screenshot Sendy 23:28-23:32): Run settings
                // -> kartu model -> Starred -> model TTS -> dialog.
                writeTtsStatus("running", "menuju pemilihan model TTS (Run settings)")
                runTtsModelFlow()
            }
        }
    }

    /** v0.1.41 - ALUR MODEL ASLI (screenshot Sendy 23:28-23:32):
     *  1) klik tombol Run settings (ikon slider, kanan atas) ->
     *  2) klik kartu model aktif -> 3) tab Starred -> klik entri
     *  model TTS -> 4) klik "Create new dialog" -> diagnose ulang.
     *  Hamburger kiri ("Open navigation menu") TIDAK dipakai:
     *  menunya tidak berisi entri TTS (penyebab failed 23:21). */
    private fun runTtsModelFlow() {
        if (pendingTtsScript == null) return
        val js = """(function(){var b=document.querySelector('button[aria-label="Toggle run settings panel"]');if(!b){var bs=[...document.querySelectorAll('button')];for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').toLowerCase();if(l.indexOf('run settings')>=0){b=bs[i];break;}}}if(!b)return '0';b.click();return '1';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                ttsHandler.postDelayed({ runTtsPickModelCard() }, 1500)
            } else {
                runTtsDumpInventory("tombol Run settings tidak ketemu")
            }
        }
    }

    /** Langkah 2: klik kartu model aktif di panel Run settings.
     *  Kartu berisi nama model (mis. "Gemini 4 Hydrogen") + id
     *  (mis. "gemini-4-hydrogen") - klik elemen terdalam yang
     *  teksnya mengandung nama/id itu. */
    private fun runTtsPickModelCard() {
        if (pendingTtsScript == null) return
        val activeQuoted = org.json.JSONObject.quote(ttsActiveModel)
        val js = """(function(){var want=$activeQuoted;var els=[...document.querySelectorAll('*')].filter(function(e){return e.offsetParent!==null&&e.children.length===0;});var best=null,bestLen=1e9;for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(!t)continue;var hit=(want&&t.toLowerCase().indexOf(want.toLowerCase())>=0)||/gemini-[0-9]/i.test(t);if(hit&&t.length<bestLen){best=els[i];bestLen=t.length;}}if(!best)return '0';best.click();return best.textContent.trim().slice(0,60);})()"""
        webView.evaluateJavascript(js) { raw ->
            val picked = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (picked.isBlank() || picked == "0") {
                runTtsDumpInventory("kartu model tidak ketemu di panel Run settings")
            } else {
                writeTtsStatus("running", "kartu model dibuka: $picked - memilih model TTS")
                ttsHandler.postDelayed({ runTtsPickModelEntry() }, 2000)
            }
        }
    }

    /** Langkah 3: di Model selection, tab Starred dulu, lalu klik
     *  entri model TTS (default "Gemini 3.8 Flash Lite TTS"). */
    private fun runTtsPickModelEntry() {
        if (pendingTtsScript == null) return
        val want = pendingTtsModel ?: "Gemini 3.8 Flash Lite TTS"
        val quoted = org.json.JSONObject.quote(want)
        val js = """(function(){var want=$quoted.toLowerCase();var tabs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<tabs.length;i++){if((tabs[i].textContent||'').trim()==='Starred'){tabs[i].click();break;}}var els=[...document.querySelectorAll('*')].filter(function(e){return e.offsetParent!==null&&e.children.length===0;});var best=null,bestLen=1e9;for(var j=0;j<els.length;j++){var t=(els[j].textContent||'').trim();if(t.toLowerCase().indexOf(want)>=0&&t.length<bestLen){best=els[j];bestLen=t.length;}}if(!best)return '0';best.click();return best.textContent.trim().slice(0,60);})()"""
        // Beri waktu tab Starred tampil sebelum klik entri.
        ttsHandler.postDelayed({
            webView.evaluateJavascript(js) { raw ->
                val picked = raw?.trim()?.removeSurrounding("\"") ?: ""
                if (picked.isBlank() || picked == "0") {
                    runTtsDumpMenu(want)
                } else {
                    writeTtsStatus("running", "model TTS dipilih: $picked - membuka dialog baru")
                    ttsHandler.postDelayed({ runTtsCreateDialog() }, 2000)
                }
            }
        }, 1000)
    }

    /** Langkah 4: klik "Create new dialog", lalu diagnose ulang
     *  (editor dialog harus muncul: ada "Speaker 1" + kolom). */
    private fun runTtsCreateDialog() {
        if (pendingTtsScript == null) return
        val js = """(function(){var els=[...document.querySelectorAll('button,[role="button"],a,div')].filter(function(e){return e.offsetParent!==null;});for(var i=0;i<els.length;i++){if((els[i].textContent||'').trim()==='Create new dialog'){els[i].click();return '1';}}return '0';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                ttsHandler.postDelayed({ runTtsDiagnose(5) }, 2000)
            } else {
                runTtsDumpInventory("tombol 'Create new dialog' tidak ketemu setelah pilih model")
            }
        }
    }

    /** v0.1.41 - dump inventaris halaman ke status (diagnostik):
     *  semua elemen isian (tag + type + label + terlihat/tidak) +
     *  semua tombol + URL. Dipakai saat kolom isian tetap tidak
     *  ketemu, supaya perbaikan berikut pakai selektor sebenarnya,
     *  bukan tebakan. */
    private fun runTtsDumpInventory(prefix: String) {
        val js = """(function(){var out=[];var els=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')];for(var i=0;i<els.length;i++){var e=els[i];out.push(e.tagName+'|'+(e.getAttribute('type')||'')+'|'+(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('role')||'')+'|'+(e.offsetParent!==null?'vis':'hid'));}var btns=[];var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var j=0;j<bs.length;j++){var l=(bs[j].getAttribute('aria-label')||'').trim();var tx=(bs[j].textContent||'').trim();if(l)btns.push(l);else if(tx&&tx.length<40)btns.push(tx);}return out.join(';')+' ##TOMBOL## '+btns.join(',')+' ##URL## '+location.href;})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            failTts("$prefix. Inventaris halaman: $clean")
        }
    }

    private fun runTtsDumpMenu(want: String) {
        val js = """(function(){var items=[...document.querySelectorAll('[role="menuitem"],[role="option"],a,button,li')].filter(function(e){return e.offsetParent!==null;});var names=[];for(var i=0;i<items.length;i++){var t=(items[i].textContent||'').trim();if(t&&t.length<60)names.push(t);}return names.join(',');})()"""
        webView.evaluateJavascript(js) { raw ->
            val names = raw?.trim()?.removeSurrounding("\"") ?: ""
            failTts("entri TTS / model '$want' tidak ketemu di menu navigasi. Isi menu: $names")
        }
    }

    /** Langkah 5 (v0.1.41): isi STYLE suara (format Riko).
     *  Klik tombol "Style" di editor -> isi kotak "Describe the
     *  voice style" -> tutup lagi menunya -> lanjut ke Speaker.
     *  Format Riko BUKAN nama voice - jangan dicari di daftar voice. */
    /** Langkah 5b: isi STYLE (format suara Riko) — v0.1.46.
     *  Pelajaran 20 Nov 15:21: klik Style SEKALI langsung gagal
     *  ("tombol tidak ketemu") padahal screenshot membuktikan
     *  tombolnya ADA — halaman masih loading/transisi, atau kontrol
     *  di dialog baru bukan <button>. Karena itu kliknya DIULANG
     *  (poll 10x1 dtk) + selektor diperluas (button -> role=button
     *  -> elemen daun teks "Style"). */
    private fun runTtsStyle() {
        val voice = pendingTtsVoice ?: return runTtsSpeaker()
        val probe = voice.take(11) // "Very bright" — penanda isi menempel
        // v0.1.49: style mungkin SUDAH menempel (diisi manual / run
        // sebelumnya) — tombol Style menampilkan formatnya, bukan
        // kata "Style". Bila label sudah berisi probe, lewati isi.
        webView.evaluateJavascript(ttsStyleButtonLabelJs(probe)) { raw ->
            val label = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (label.contains(probe)) {
                writeTtsStatus("running", "format suara sudah menempel (tombol Style menampilkan format) - lanjut pilih speaker")
                runTtsSpeaker()
                return@evaluateJavascript
            }
            writeTtsStatus("running", "membuka pengaturan Style")
            waitTtsStyleButton(10, voice, 1)
        }
    }

    /** Tunggu tombol Style muncul (poll 10x1 dtk), lalu klik.
     *  v0.1.50: nomor percobaan dialirkan (dulu hardcode 1 di
     *  pemanggilan waitTtsStyleField — percobaan ulang selalu
     *  dilaporkan sebagai percobaan 1). */
    private fun waitTtsStyleButton(triesLeft: Int, voice: String, attempt: Int) {
        val probe = voice.take(11) // "Very bright" — penanda isi menempel
        webView.evaluateJavascript(ttsClickStyleJs(probe)) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "0") {
                if (triesLeft <= 0) {
                    runTtsDumpButtons()
                } else {
                    ttsHandler.postDelayed({ waitTtsStyleButton(triesLeft - 1, voice, attempt) }, 1000)
                }
                return@evaluateJavascript
            }
            writeTtsStatus("running", "tombol Style ketemu (via $clean) - mengisi format suara")
            waitTtsStyleField(10, voice, probe, attempt)
        }
    }

    /** JS klik tombol "Style" (buka/tutup menu) — v0.1.46: tiga
     *  lapis selektor (button, [role=button], elemen daun), hasil
     *  'btn'|'role'|'leaf'|'0'. v0.1.49: tombol BERGANTI label jadi
     *  teks style sesudah menempel ("Very bright, high-p…", bukti
     *  17:07) — pencocokan terima label "Style" ATAU label yang
     *  berisi probe. */
    private fun ttsClickStyleJs(probe: String): String {
        val q = org.json.JSONObject.quote(probe)
        return """(function(){var p=$q;var vis=function(e){return e.offsetParent!==null;};var txt=function(e){return (e.textContent||'').trim();};var ok=function(t){return t==='Style'||t.indexOf('Style')===0||t.indexOf(p)>=0;};var bs=[...document.querySelectorAll('button')].filter(vis);for(var i=0;i<bs.length;i++){var t=txt(bs[i]);if(ok(t)){bs[i].click();return 'btn';}}var rs=[...document.querySelectorAll('[role="button"]')].filter(vis);for(var j=0;j<rs.length;j++){var u=txt(rs[j]);if(ok(u)){rs[j].click();return 'role';}}var els=[...document.querySelectorAll('*')].filter(function(e){return vis(e)&&e.children.length===0;});for(var k=0;k<els.length;k++){if(txt(els[k])==='Style'){els[k].click();return 'leaf';}}return '0';})()"""
    }

    /** JS: label tombol Style saat ini — teks tombolnya, atau '0'
     *  bila tidak ketemu. Sesudah style menempel, labelnya adalah
     *  teks style itu sendiri (bukan lagi kata "Style"). */
    private fun ttsStyleButtonLabelJs(probe: String): String {
        val q = org.json.JSONObject.quote(probe)
        return """(function(){var p=$q;var els=[...document.querySelectorAll('button,[role="button"]')].filter(function(e){return e.offsetParent!==null;});for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t==='Style'||t.indexOf('Style')===0||t.indexOf(p)>=0){return t;}}var leaf=[...document.querySelectorAll('*')].filter(function(e){return e.children.length===0&&e.offsetParent!==null&&(e.textContent||'').trim()==='Style';});if(leaf.length>0){return 'Style';}return '0';})()"""
    }

    /** Gagal cari tombol Style: dump label tombol + path URL (bukti). */
    private fun runTtsDumpButtons() {
        val js = """(function(){var out=[];var bs=[...document.querySelectorAll('button,[role="button"]')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length&&i<15;i++){var t=(bs[i].textContent||'').trim().replace(/\s+/g,' ').slice(0,30);out.push(bs[i].tagName+':'+t);}return location.origin+location.pathname+' || '+out.join(' ;; ');})()"""
        webView.evaluateJavascript(js) { raw ->
            val dump = raw?.trim()?.removeSurrounding("\"") ?: ""
            failTts("tombol Style tidak ketemu di editor dialog setelah 10 dtk. Dump tombol: $dump")
        }
    }

    /** JS baca isi kotak "Describe the voice style" ('' bila tak ada). */
    private fun ttsStyleValueJs(): String {
        return """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0)return (tas[i].value||tas[i].textContent||'');}return '';})()"""
    }

    /** Tunggu kolom Style muncul, isi, lalu VERIFIKASI baca-balik.
     *  v0.1.44: percobaan 1 = isi lewat JS (ttsFillJs, kini dengan
     *  InputEvent ber-data + setter dipaksa untuk voice); percobaan 2
     *  = KETIK BENERAN lewat jalur IME (InputConnection.commitText)
     *  — event input-nya trusted seperti ketikan pengguna.
     *  v0.1.45: percobaan 3 = KETIK LEWAT TOMBOL KEYBOARD
     *  (dispatchKeyEvent per karakter, tanpa IME) — commitText
     *  terbukti tidak sampai (dump v0.1.44: len=0 sesudah dua cara). */
    private fun waitTtsStyleField(triesLeft: Int, voice: String, probe: String, attempt: Int) {
        val findJs = """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0)return '1';}return '0';})()"""
        webView.evaluateJavascript(findJs) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean != "1") {
                if (triesLeft <= 0) {
                    failTts("kolom 'Describe the voice style' tidak muncul setelah Style dibuka (percobaan $attempt)")
                } else {
                    ttsHandler.postDelayed({ waitTtsStyleField(triesLeft - 1, voice, probe, attempt) }, 1000)
                }
                return@evaluateJavascript
            }
            if (attempt == 1) {
                webView.evaluateJavascript(ttsFillJs("voice", voice)) { raw2 ->
                    val len = raw2?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                    if (len <= 0) {
                        failTts("kolom 'Describe the voice style' tidak terisi (percobaan $attempt)")
                        return@evaluateJavascript
                    }
                    // v0.1.47: isi ulang tertunda jalan di 300 ms — beri
                    // waktu sebelum baca-balik pertama.
                    ttsHandler.postDelayed({ verifyTtsStyleClosed(voice, probe, attempt) }, 600)
                }
            } else if (attempt == 2) {
                // Percobaan 2: KLIK kolom (bukan cuma focus), lalu
                // ketik lewat IME. v0.1.46: focus() saja tidak
                // memindahkan fokus (active=BUTTON).
                webView.evaluateJavascript(ttsClickStyleFieldJs()) { rawFocus ->
                    val focus = rawFocus?.trim()?.removeSurrounding("\"") ?: ""
                    ttsHandler.postDelayed({
                        val typed = ttsTypeReal(voice)
                        writeTtsStatus("running", "mengetik format suara lewat keyboard (percobaan 2, fokus=$focus, commitText=$typed)")
                        ttsHandler.postDelayed({ verifyTtsStyleClosed(voice, probe, attempt) }, 800)
                    }, 400)
                }
            } else if (attempt == 3) {
                // Percobaan 3: KLIK kolom, lalu ketik lewat KeyEvent.
                webView.evaluateJavascript(ttsClickStyleFieldJs()) { rawFocus ->
                    val focus = rawFocus?.trim()?.removeSurrounding("\"") ?: ""
                    ttsHandler.postDelayed({
                        ttsTypeKeys(voice, ", fokus=$focus") { verifyTtsStyleClosed(voice, probe, attempt) }
                    }, 400)
                }
            } else {
                // Percobaan 4: tap beneran + IME hidup.
                ttsTypeLiveIme(voice) { verifyTtsStyleClosed(voice, probe, attempt) }
            }
        }
    }

    /** Ketik lewat KeyEvent hardware (percobaan 3) — tanpa IME, tanpa
     *  koneksi input: tiap karakter dikirim sebagai DOWN+UP langsung
     *  ke WebView, seperti keyboard fisik. onDone dipanggil 800 ms
     *  sesudah karakter terakhir. */
    private fun ttsTypeKeys(text: String, note: String = "", onDone: () -> Unit) {
        val events = ArrayList<KeyEvent>()
        var skipped = 0
        for (ch in text) {
            val code = keyCodeFor(ch)
            if (code == -1) { skipped++; continue }
            val meta = if (ch.isUpperCase()) KeyEvent.META_SHIFT_ON else 0
            val now = SystemClock.uptimeMillis()
            events.add(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            events.add(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        writeTtsStatus("running", "mengetik format suara lewat tombol keyboard (percobaan 3, ${events.size / 2} karakter, $skipped dilewati$note)")
        Thread {
            for (ev in events) {
                ttsHandler.post { webView.dispatchKeyEvent(ev) }
                Thread.sleep(25)
            }
            ttsHandler.postDelayed({ onDone() }, 800)
        }.start()
    }

    /** Petakan satu karakter ke KeyEvent (huruf, angka, spasi, koma,
     *  titik, strip). -1 = karakter tidak didukung, dilewati. */
    private fun keyCodeFor(ch: Char): Int {
        return when {
            ch in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
            ch in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
            ch in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
            ch == ' ' -> KeyEvent.KEYCODE_SPACE
            ch == ',' -> KeyEvent.KEYCODE_COMMA
            ch == '.' -> KeyEvent.KEYCODE_PERIOD
            ch == '-' -> KeyEvent.KEYCODE_MINUS
            else -> -1
        }
    }

    /** Verifikasi 1 (kotak, sebelum tutup) + verifikasi 2 (label
     *  tombol yang tertutup) isi Style — v0.1.49. Pelajaran 20 Nov
     *  17:07: sesudah style menempel, tombol Style BERGANTI label
     *  jadi teks style, jadi buka-ulang + baca kotak gagal walau
     *  nilainya menempel. Label tombol yang tertutup adalah bukti
     *  yang terlihat di layar — tidak perlu buka menu lagi. */
    private fun verifyTtsStyleClosed(voice: String, probe: String, attempt: Int) {
        // Verifikasi 1: baca balik SEBELUM menu ditutup.
        webView.evaluateJavascript(ttsStyleValueJs()) { raw3 ->
            val v1 = raw3?.trim()?.removeSurrounding("\"") ?: ""
            if (!v1.contains(probe)) {
                retryTtsStyle(voice, probe, attempt)
                return@evaluateJavascript
            }
            // Tutup menu (klik tombol Style — pencocokan sudah
            // terima label ber-probe), lalu verifikasi 2 dari LABEL
            // tombol yang tertutup.
            webView.evaluateJavascript(ttsClickStyleJs(probe)) { _ ->
                ttsHandler.postDelayed({
                    webView.evaluateJavascript(ttsStyleButtonLabelJs(probe)) { raw4 ->
                        val label = raw4?.trim()?.removeSurrounding("\"") ?: ""
                        if (!label.contains(probe)) {
                            retryTtsStyle(voice, probe, attempt)
                        } else {
                            writeTtsStatus("running", "format suara menempel (tombol Style menampilkan format) - lanjut pilih speaker")
                            runTtsSpeaker()
                        }
                    }
                }, 1000)
            }
        }
    }

    /** JS KLIK kolom Style (bukan cuma focus) + blok semua isinya —
     *  v0.1.47. Pelajaran 20 Nov 15:43 (dump v0.1.46): focus()
     *  programmatic TIDAK memindahkan fokus (active=BUTTON), jadi
     *  commitText + KeyEvent tidak sampai. Klik beneran menaruh
     *  karet di dalam kotak. Hasil: 'focus' (fokus nempel di kotak),
     *  'nofocus' (kotak ketemu tapi fokus tidak pindah), '0' (kotak
     *  tidak ketemu). */
    private fun ttsClickStyleFieldJs(): String {
        return """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0){tas[i].click();tas[i].focus();try{tas[i].setSelectionRange(0,tas[i].value.length);}catch(e){}try{tas[i].select();}catch(e){}return document.activeElement===tas[i]?'focus':'nofocus';}}return '0';})()"""
    }

    /** JS: posisi tengah kotak Style di layar (CSS px) — 'x|y'
     *  atau '0' bila kotaknya tidak ketemu. */
    private fun ttsStyleFieldRectJs(): String {
        return """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0){var r=tas[i].getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2);}}return '0';})()"""
    }

    /** Tap beneran ke satu titik di WebView (sentuhan DOWN+UP) —
     *  v0.1.48. Tap ini dikirim aplikasi ke WebView-nya sendiri
     *  (bukan ketuk layar HP oleh Jhon), koordinat dihitung dari
     *  posisi elemen di halaman. */
    private fun ttsTapAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()
        ttsHandler.postDelayed({
            val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 120)
    }

    /** JS: keadaan kotak Style sesudah tap — 'field|active=TAG|ph'
     *  bila kotak masih ada, 'nofield|active=…' bila menu ketutup. */
    private fun ttsStyleFieldStateJs(): String {
        return """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});var found='nofield';for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0){found='field';break;}}var ae=document.activeElement;var a=ae?(ae.tagName+'|'+((ae.getAttribute('placeholder')||'').slice(0,20))):'none';return found+'|active='+a;})()"""
    }

    /** Percobaan 4: tap beneran + IME hidup — v0.1.48.
     *  Pelajaran dari video manual Sendy (20 Nov 16:07): nilai
     *  cuma menempel lewat sesi IME beneran — tap beneran ke kotak
     *  bikin keyboard kebuka + koneksi IME hidup, lalu paste/
     *  commitText lewat koneksi itu. Tap -> tunggu 1200 ms
     *  (keyboard naik) -> commitText lewat onCreateInputConnection
     *  (yang kini koneksi HIDUP, bukan manual) -> verifikasi.
     *  v0.1.49: jeda 500 ms sebelum baca rect (menu selesai
     *  animasi); sesudah tap dicek kotak masih ada + fokusnya;
     *  bila menu ketutup (tap meleset), buka lagi + tap ulang
     *  sekali; rect+scale+titik+state dicatat ke ttsLastTapInfo
     *  (muncul di dump gagal). onDone(true/false) = commitText
     *  diterima atau tidak. */
    private fun ttsTypeLiveIme(voice: String, onDone: (Boolean) -> Unit) {
        ttsHandler.postDelayed({ ttsTypeLiveImeOnce(voice, onDone, false) }, 500)
    }

    private fun ttsTypeLiveImeOnce(voice: String, onDone: (Boolean) -> Unit, retried: Boolean) {
        webView.evaluateJavascript(ttsStyleFieldRectJs()) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            if (parts.size < 2) {
                writeTtsStatus("running", "kotak Style tidak ketemu buat ditap (percobaan 4)")
                onDone(false)
                return@evaluateJavascript
            }
            val scale = webView.scale
            val cx = (parts[0].toFloatOrNull() ?: 0f) * scale
            val cy = (parts[1].toFloatOrNull() ?: 0f) * scale
            ttsLastTapInfo = "tap rect=${parts[0]}x${parts[1]} scale=$scale titik=${cx.toInt()}x${cy.toInt()}"
            writeTtsStatus("running", "mengetuk kotak Style (percobaan 4, $ttsLastTapInfo) - menunggu keyboard")
            ttsTapAt(cx, cy)
            ttsHandler.postDelayed({
                webView.evaluateJavascript(ttsStyleFieldStateJs()) { rawState ->
                    val state = rawState?.trim()?.removeSurrounding("\"") ?: ""
                    ttsLastTapInfo += " state=$state"
                    if (!state.startsWith("field") && !retried) {
                        // Tap meleset / menu ketutup — buka lagi + tap ulang sekali.
                        writeTtsStatus("running", "tap pertama menutup menu (kotak hilang) - buka lagi + tap ulang (percobaan 4)")
                        webView.evaluateJavascript(ttsClickStyleJs(voice.take(11))) { _ ->
                            ttsHandler.postDelayed({ ttsTypeLiveImeOnce(voice, onDone, true) }, 1000)
                        }
                        return@evaluateJavascript
                    }
                    val typed = ttsTypeReal(voice)
                    writeTtsStatus("running", "mengetik format suara lewat IME hidup (percobaan 4, $state, commitText=$typed)")
                    ttsHandler.postDelayed({ onDone(typed) }, 800)
                }
            }, 1200)
        }
    }

    /** Ketik beneran lewat jalur IME (InputConnection.commitText) —
     *  event input-nya trusted seperti ketikan pengguna, bukan set
     *  nilai lewat kode. Mengembalikan true bila commitText diterima. */
    private fun ttsTypeReal(text: String): Boolean {
        return try {
            val ic: InputConnection? = webView.onCreateInputConnection(EditorInfo())
            if (ic == null) {
                writeTtsStatus("running", "InputConnection tidak tersedia (percobaan 2)")
                false
            } else {
                ic.commitText(text, 1)
            }
        } catch (e: Exception) {
            writeTtsStatus("running", "commitText error: ${e.message}")
            false
        }
    }

    /** v0.1.50: pastikan menu Style KEBUKA sebelum percobaan isi.
     *  Bug Run 12 (v0.1.49): sesudah verifikasi #1 lolos, menu
     *  ditutup oleh klik tutup; verifikasi #2 (label) gagal ->
     *  retry langsung cari kolom di menu yang KETUTUP -> 10 dtk
     *  tidak ketemu -> failed, padahal menunya tinggal dibuka
     *  lagi. Kolom ada -> langsung onReady (tanpa klik, jangan
     *  sampai klik malah menutup menu yang sudah kebuka); kolom
     *  tidak ada -> klik tombol Style, tunggu 1000 ms, cek lagi;
     *  masih tidak ada -> gagal jujur dengan nomor percobaan. */
    private fun ensureStyleMenuOpen(voice: String, probe: String, attempt: Int, onReady: () -> Unit) {
        webView.evaluateJavascript(ttsStyleFieldStateJs()) { raw ->
            val state = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (state.startsWith("field")) {
                onReady()
                return@evaluateJavascript
            }
            writeTtsStatus("running", "menu Style ketutup (percobaan $attempt) - membuka lagi")
            webView.evaluateJavascript(ttsClickStyleJs(probe)) { _ ->
                ttsHandler.postDelayed({
                    webView.evaluateJavascript(ttsStyleFieldStateJs()) { raw2 ->
                        val state2 = raw2?.trim()?.removeSurrounding("\"") ?: ""
                        if (state2.startsWith("field")) {
                            onReady()
                        } else {
                            failTts("kolom 'Describe the voice style' tidak muncul setelah Style dibuka (percobaan $attempt)")
                        }
                    }
                }, 1000)
            }
        }
    }

    /** Isi Style gagal verifikasi: percobaan 1 -> ketik IME
     *  (percobaan 2); percobaan 2 -> ketik KeyEvent (percobaan 3);
     *  percobaan 3 -> tap beneran + IME hidup (percobaan 4);
     *  percobaan 4 gagal -> dump bukti + gagal jujur.
     *  v0.1.50: percobaan ulang lewat ensureStyleMenuOpen dulu
     *  (menu bisa ketutup sesudah verifikasi #2). */
    private fun retryTtsStyle(voice: String, probe: String, attempt: Int) {
        if (attempt < 4) {
            val next = when (attempt) {
                1 -> "coba ketik lewat keyboard"
                2 -> "coba ketik lewat tombol keyboard"
                else -> "coba tap beneran + IME hidup"
            }
            writeTtsStatus("running", "format suara belum menempel (percobaan $attempt) - $next")
            ttsHandler.postDelayed({ ensureStyleMenuOpen(voice, probe, attempt + 1) { waitTtsStyleField(10, voice, probe, attempt + 1) } }, 1000)
        } else {
            runTtsDumpStyle()
        }
    }

    /** Gagal total isi Style: dump anatomi kolom + probe satu-tugas
     *  ke detail gagal (bukti). Probe: set nilai 'PROBE123' via
     *  setter lalu baca balik DALAM JS call yang sama — eq=true
     *  berarti setter jalan dan nilai dihapus framework secara async;
     *  eq=false berarti setter dicegat langsung. */
    private fun runTtsDumpStyle() {
        val js = """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});var out=[];var ae=document.activeElement;out.push('active='+(ae?(ae.tagName+'|'+((ae.getAttribute('placeholder')||'').slice(0,30))):'none'));for(var i=0;i<tas.length&&i<8;i++){var t=tas[i];var ph=(t.getAttribute('placeholder')||'').slice(0,40);var al=(t.getAttribute('aria-label')||'').slice(0,40);var v=(t.value||t.textContent||'');out.push(t.tagName+'|ph='+ph+'|al='+al+'|len='+v.length+'|hasProbe='+(v.indexOf('Very bright')>=0)+'|dis='+t.disabled+'|ro='+t.readOnly);}var st=null;for(var j=0;j<tas.length;j++){var p=(tas[j].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0){st=tas[j];break;}}if(st){var setter=null;try{setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;}catch(e){}try{if(setter){setter.call(st,'PROBE123');}else{st.value='PROBE123';}}catch(e2){}var back=(st.value||st.textContent||'');out.push('probe=set8-read'+back.length+'|eq='+(back==='PROBE123'));try{if(setter){setter.call(st,'');}else{st.value='';}}catch(e3){}}else{out.push('probe=no-field');}return out.join(' ;; ');})()"""
        webView.evaluateJavascript(js) { raw ->
            val dump = raw?.trim()?.removeSurrounding("\"") ?: ""
            val tap = if (ttsLastTapInfo.isNotEmpty()) " | $ttsLastTapInfo" else ""
            failTts("format suara tidak menempel di kotak Style setelah 4 percobaan (JS + ketik keyboard + tombol keyboard + tap beneran + IME hidup) - VO dibatalkan. Dump kolom: $dump$tap")
        }
    }

    /** Langkah 6: pilih VOICE (nama) - klik dropdown "Speaker 1". */
    private fun runTtsSpeaker() {
        writeTtsStatus("running", "membuka pilihan Speaker")
        val js = """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){if((bs[i].textContent||'').indexOf('Speaker 1')>=0){bs[i].click();return '1';}}return '0';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                ttsHandler.postDelayed({ runTtsPickSpeaker() }, 1500)
            } else {
                failTts("dropdown Speaker 1 tidak ketemu di editor dialog")
            }
        }
    }

    /** Langkah 6b: klik nama voice (default "Sadaltager", Favorites). */
    private fun runTtsPickSpeaker() {
        val want = pendingTtsSpeaker ?: "Sadaltager"
        val quoted = org.json.JSONObject.quote(want)
        val js = """(function(){var want=$quoted.toLowerCase();var els=[...document.querySelectorAll('*')].filter(function(e){return e.offsetParent!==null&&e.children.length===0;});for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t.toLowerCase()===want){els[i].click();return t;}}for(var j=0;j<els.length;j++){var u=(els[j].textContent||'').trim();if(u.toLowerCase().indexOf(want)>=0&&u.length<60){els[j].click();return u;}}return '';})()"""
        webView.evaluateJavascript(js) { raw ->
            val picked = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (picked.isBlank()) {
                runTtsDumpMenu(want)
            } else {
                writeTtsStatus("running", "voice dipilih: $picked - mengisi naskah")
                ttsHandler.postDelayed({ runTtsScript() }, 1500)
            }
        }
    }

    /** JS isi satu kolom (voice = gaya suara, script = naskah):
     *  cari textarea yang cocok, isi lewat insertText (fallback ke
     *  native setter + event input untuk framework Angular/React).
     *  v0.1.44: untuk voice, setter + event SELALU dijalankan walau
     *  execCommand mengaku sukses (nilainya terbukti hilang), dan
     *  event pertama adalah InputEvent ber-data (inputType
     *  insertText, data = teks) — sebagian framework menghitung
     *  nilai baru dari event.data, dan Event polos (data kosong)
     *  bisa mengosongkan modelnya.
     *  v0.1.47: untuk voice, ISI ULANG TERTUNDA — 300 ms sesudah isi
     *  pertama, setTimeout mencari ULANG kotaknya (query segar) lalu
     *  set nilai lagi via setter + event input. Pelajaran 20 Nov
     *  15:43 (probe eq=true): setter jalan, framework menghapus
     *  nilai sesudah JS call selesai — isi ulang sesudah tick reset
     *  itu (atau pada elemen pengganti) bisa menempel. */
    private fun ttsFillJs(role: String, text: String): String {
        val quoted = org.json.JSONObject.quote(text)
        val js = """(function(role,text){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});var pick=null;if(role==='voice'){pick=tas.find(function(t){return (t.getAttribute('placeholder')||'').toLowerCase().indexOf('describe the voice style')>=0;});if(!pick){pick=tas.find(function(t){var s=((t.getAttribute('aria-label')||'')+' '+(t.getAttribute('placeholder')||'')).toLowerCase();return s.indexOf('style')>=0||s.indexOf('voice')>=0||s.indexOf('describe')>=0||s.indexOf('tone')>=0;});}}else{pick=tas.find(function(t){var s=((t.getAttribute('aria-label')||'')+' '+(t.getAttribute('placeholder')||'')).toLowerCase();return s.indexOf('text')>=0||s.indexOf('script')>=0||s.indexOf('enter')>=0||s.indexOf('speech')>=0||s.indexOf('tags')>=0;});if(!pick&&tas.length>0){pick=tas.reduce(function(a,b){return (b.offsetWidth*b.offsetHeight)>(a.offsetWidth*a.offsetHeight)?b:a;});}}if(!pick)return '0';pick.focus();try{pick.setSelectionRange(0,pick.value.length);}catch(e){}var ok=false;try{ok=document.execCommand('insertText',false,text);}catch(e){}if(role==='voice'||!ok||(pick.value||pick.textContent||'').length===0){if(pick.tagName==='TEXTAREA'){var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;setter.call(pick,text);}else if(pick.tagName==='INPUT'){var setter2=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;setter2.call(pick,text);}else{pick.textContent=text;}try{pick.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}pick.dispatchEvent(new Event('input',{bubbles:true}));try{pick.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}if(role==='voice'){try{setTimeout(function(){var tas2=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas2.length;i++){var p=(tas2[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0){var s2=tas2[i];try{var setter2=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;setter2.call(s2,text);}catch(e){try{s2.value=text;}catch(e2){}}try{s2.dispatchEvent(new Event('input',{bubbles:true}));}catch(e3){}break;}}},300);}catch(e){}}}return String((pick.value||pick.textContent||'').length);})('$role',$quoted)"""
        return js
    }

    /** Langkah 4: isi naskah. */
    private fun runTtsScript() {
        val script = pendingTtsScript ?: return
        writeTtsStatus("running", "mengisi naskah")
        webView.evaluateJavascript(ttsFillJs("script", script)) { raw ->
            val len = raw?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
            if (len > 0) {
                runTtsRun()
            } else {
                failTts("kolom naskah tidak terisi")
            }
        }
    }

    /** Langkah 5: klik Run, catat isi folder aistudio sebelum generate. */
    private fun runTtsRun() {
        val js = """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l==='Run'||tx==='Run'){if(bs[i].disabled)return 'disabled';bs[i].click();return '1';}}return '0';})()"""
        writeTtsStatus("running", "menekan Run")
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                ttsFilesBefore = try {
                    FileStore.listFiles(this, "aistudio").map { it.name }.toSet()
                } catch (_: Exception) {
                    emptySet()
                }
                writeTtsStatus("running", "VO sedang digenerate - tunggu sampai selesai")
                waitTtsDone(60)
            } else if (clean == "disabled") {
                failTts("tombol Run nonaktif (naskah/format suara belum diterima)")
            } else {
                failTts("tombol Run tidak ketemu")
            }
        }
    }

    /** Langkah 6: tunggu VO SELESAI digenerate (10 mnt) — v0.1.43.
     *  Pelajaran 20 Nov 14:13: picu lama (tombol Download ADA atau
     *  <audio> ADA) menembak terlalu cepat — audio 1:25 baru selesai
     *  ~2 mnt, tapi eksekutor sudah klik unduh di +30 dtk (tombol
     *  dialog LAMA / belum siap) dan klik itu tidak memicu blob sama
     *  sekali. Sekarang syaratnya KETAT: ada tombol Download DAN ada
     *  <audio> dengan readyState 4 + duration > 0 (audio benar-benar
     *  siap diputar). */
    private fun waitTtsDone(triesLeft: Int) {
        if (pendingTtsScript == null) return
        val js = """(function(){var dl=0,ready=0;var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l==='Download'||tx==='Download'){dl=1;break;}}var aus=document.querySelectorAll('audio');for(var j=0;j<aus.length;j++){var a=aus[j];if((a.src||a.querySelector('source'))&&a.readyState===4&&a.duration>0){ready=1;break;}}return dl+'|'+ready;})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            if (parts.getOrNull(0) == "1" && parts.getOrNull(1) == "1") {
                runTtsDownload()
            } else if (triesLeft <= 0) {
                failTts("VO tidak selesai dalam 10 menit (tombol unduh / audio siap tidak muncul)")
            } else {
                ttsHandler.postDelayed({ waitTtsDone(triesLeft - 1) }, 10000)
            }
        }
    }

    /** Langkah 7: klik unduh dialog TERBARU, lalu tunggu file VO — v0.1.43.
     *  Pelajaran 20 Nov 14:13: klik tombol Download PERTAMA di DOM
     *  bisa kena dialog LAMA (19 Nov) — kliknya tidak memicu blob.
     *  Sekarang klik tombol TERAKHIR (dialog terbaru = paling bawah). */
    private fun runTtsDownload() {
        val js = """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});var found=[];for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l==='Download'||tx==='Download'){found.push(bs[i]);}}if(found.length===0)return '0|0';found[found.length-1].click();return '1|'+found.length;})()"""
        writeTtsStatus("running", "mengunduh VO")
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            if (parts.getOrNull(0) == "1") {
                ttsDownloadInfo = "tombol unduh terbaru diklik (1 dari ${parts.getOrNull(1) ?: "?"})"
                waitTtsFile(6)
            } else {
                failTts("tombol unduh tidak ketemu")
            }
        }
    }

    private fun waitTtsFile(triesLeft: Int) {
        if (pendingTtsScript == null) return
        val now = try {
            FileStore.listFiles(this, "aistudio")
        } catch (_: Exception) {
            emptyList()
        }
        val fresh = now.firstOrNull { f ->
            f.isFile && f.name !in ttsFilesBefore &&
                (f.name.endsWith(".wav", ignoreCase = true) ||
                    f.name.endsWith(".mp3", ignoreCase = true) ||
                    f.name.endsWith(".ogg", ignoreCase = true))
        }
        if (fresh != null) {
            ttsRunning = false
            pendingTtsScript = null
            writeTtsStatus("done", "VO tersimpan: ${fresh.name} (${fresh.length()} byte)")
        } else if (triesLeft <= 0) {
            failTts("${ttsDownloadInfo.ifBlank { "tombol unduh diklik" }} tapi file VO tidak muncul di folder aistudio (cek _log-blob/blob-debug.txt)")
        } else {
            ttsHandler.postDelayed({ waitTtsFile(triesLeft - 1) }, 5000)
        }
    }
    // ================= AKHIR TTS API =================

    /** Bersihkan data Space ini (v0.1.30) lalu muat halaman awal.
     *  Dijalankan DI DALAM proses Space (:spaceN) — CookieManager tiap
     *  proses punya toko cookie sendiri (dataDirectorySuffix), jadi
     *  MainActivity (proses utama) TIDAK BISA membersihkannya; hanya
     *  Space ini sendiri yang bisa. Menghapus: semua cookie (termasuk
     *  cookie .google.com yang rusak → Google 400), WebStorage
     *  (localStorage/IndexedDB), cache WebView, plus file status login
     *  + limit Space ini supaya chip Beranda jujur lagi. File unduhan
     *  TIDAK disentuh. removeAllCookies bersifat async — halaman awal
     *  dimuat di callback-nya; pengaman 3 detik bila callback telat. */
    private fun clearSpaceDataAndLoad(home: String, expectedSuffix: String? = null) {
        // v0.1.31 — pengaman lintas-Space: clear HANYA jalan bila suffix
        // yang diminta pengirim sama dengan suffix proses ini. Kalau
        // tidak cocok (intent nyasar / slot salah), BATALKAN — jangan
        // pernah menghapus data Space lain. Temuan HP: clear Space 8
        // ikut menghapus Space 7 (17 Nov ~15:xx).
        if (expectedSuffix != null && expectedSuffix != dataDirectorySuffix) {
            android.widget.Toast.makeText(
                this, "Batal: Space tidak cocok — data TIDAK dihapus",
                android.widget.Toast.LENGTH_LONG
            ).show()
            if (::webView.isInitialized) webView.loadUrl(home)
            return
        }
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies {
                try {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                } catch (_: Exception) {
                }
                try {
                    if (::webView.isInitialized) webView.clearCache(true)
                } catch (_: Exception) {
                }
                try {
                    java.io.File(filesDir, "space_${dataDirectorySuffix}.txt").delete()
                    java.io.File(filesDir, "space_${dataDirectorySuffix}_limit.txt").delete()
                } catch (_: Exception) {
                }
                try {
                    android.webkit.CookieManager.getInstance().flush()
                } catch (_: Exception) {
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Data Space dibersihkan — memuat ulang",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (::webView.isInitialized) webView.loadUrl(home)
                }
            }
        } catch (_: Exception) {
            if (::webView.isInitialized) webView.loadUrl(home)
        }
        rootLayout.postDelayed({
            if (::webView.isInitialized && webView.url == null) {
                webView.loadUrl(home)
            }
        }, 3000)
    }

    /** v0.1.31: animasi sentuh ringan — tombol mengecil ke 0,88x saat
     *  ditekan, kembali saat dilepas. Mengembalikan false supaya klik
     *  tetap jalan normal. */
    private fun addTouchAnimation(v: android.view.View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.88f).scaleY(0.88f)
                        .setDuration(80).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(120).start()
            }
            false
        }
    }

    /** Pindah Space lewat tombol ⇌ di top bar (ganti geser, v0.1.31).
     *  Daftar = Space AKTIF (sudah login) selain Space ini — aturan
     *  Sendy 06:45. Bila tidak ada Space aktif lain, beri tahu.
     *  v0.1.32: dialog kartu SpacePickerDialog — tiap baris ada chip
     *  status (Aktif / Beku / Limit / Belum login), jadi kelihatan
     *  mana Space yang aktif dan mana yang belum login. */
    private fun showSwitchSpacePicker() {
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
        val pool = if (active.size >= 2) active else spaces
        val others = pool.filter {
            SpaceRegistry.suffixForSlot(it.slot) != dataDirectorySuffix
        }
        if (others.isEmpty()) {
            android.widget.Toast.makeText(
                this, "Tidak ada Space aktif lain", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // v0.1.33: tampilkan Space yang LIVE saja (bukan semua yang
        // Aktif) — liveOnly fallback ke daftar aktif bila tidak ada
        // yang live, supaya dialog tidak kosong.
        val shown = SpacePickerDialog.liveOnly(this, others, dataDirectorySuffix)
        SpacePickerDialog.show(
            this, "Pindah Space", shown, dataDirectorySuffix
        ) { next ->
            android.widget.Toast.makeText(this, "→ ${next.name}", android.widget.Toast.LENGTH_SHORT).show()
            val intent = android.content.Intent(this, SpaceRegistry.activityForSlot(next.slot)).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_SPACE_NAME, next.name)
                putExtra(MainActivity.EXTRA_SPACE_COLOR, next.color)
                putExtra(MainActivity.EXTRA_URL, next.url)
            }
            startActivity(intent)
        }
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
        android.widget.Toast.makeText(
            this, "Membuka ${entry.name} berdampingan…", android.widget.Toast.LENGTH_SHORT
        ).show()
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
        // v0.1.31: hidupkan lagi timer tangkap live (idempotent) — bila
        // activity sempat dihancurkan sistem selagi Space dibekukan,
        // timer mati dan "Nyalakan Live" saja tidak menghidupkannya lagi
        // (temuan HP: live tidak aktif lagi setelah dibekukan).
        startLiveCapture()
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

    /** v0.1.31: terima intent susulan untuk activity yang sudah jalan
     *  (REORDER_TO_FRONT tidak memanggil onCreate). Menangani Bersihkan
     *  data (dengan cek suffix) + permintaan split-screen. Tanpa ini,
     *  extra di intent yang dibawa REORDER_TO_FRONT diabaikan. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_DATA, false)) {
            clearSpaceDataAndLoad(
                homeUrl,
                intent.getStringExtra(MainActivity.EXTRA_CLEAR_SUFFIX)
            )
        }
        intent.getIntExtra(MainActivity.EXTRA_SPLIT_WITH, -1)
            .takeIf { it > 0 }?.let { pendingSplitSlot = it }
        // v0.1.38: payload prompt API susulan (REORDER_TO_FRONT).
        consumeApiPrompt(intent)
        // v0.1.39: payload TTS API susulan.
        consumeApiTts(intent)
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

    /** Badge unduhan di top bar = jumlah file di folder Space ini. */
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
            "if(m){nm=decodeURIComponent(m[1].replace(/^utf-8''/i,'').replace(/\"/g,''));}" +
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
            "}catch(e){try{window.SenzHttp.onError('js '+e.message);}catch(_){}}})();"
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
    private fun looksLikeText(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        val n = minOf(b.size, 512)
        var printable = 0
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            if (v == 9 || v == 10 || v == 13 || (v in 32..126) || v >= 128) printable++
        }
        return printable * 100 / n >= 95
    }

    private fun fixExtension(name: String, bytes: ByteArray): String {
        val sniffed = sniffExtension(bytes)
            ?: if (looksLikeText(bytes)) "txt" else return name
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
            "var fs2=document.querySelectorAll('iframe');" +
            "for(var i2=0;i2<fs2.length;i2++){" +
            "try{fs2[i2].contentWindow.postMessage({type:'senz-get-blob',url:url},'*');}catch(e){}}" +
            "}catch(e){}},3000);" +
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
            "xhr.send();}catch(e){window.SenzBlob.onBlobError('js-exception '+e.message);}},15000);" +
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
            "window.__senzPendingReqs={};" +
            "window.__senzReplyBlob=function(u){" +
            "var d=window.__senzBlobGet(u)||'';if(!d)return;" +
            "var nm=window.__senzBlobName(u)||'';" +
            "if(!nm){try{var as=document.querySelectorAll('a[download]');for(var ai=0;ai<as.length;ai++){if(as[ai].href===u&&as[ai].getAttribute('download')){nm=as[ai].getAttribute('download');break;}}}catch(e){}}" +
            "try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: balas '+u.slice(-8));}catch(e){}" +
            "var rp={type:'senz-blob-data',url:u,data:d,name:nm};" +
            "if(window.parent&&window.parent!==window){window.parent.postMessage(rp,'*');}" +
            "else{try{window.__senzBlobDone=true;}catch(e){}if(window.SenzBlob)window.SenzBlob.onBlobData(d,nm);}};" +
            "window.__senzFlushPending=function(u){" +
            "if(!window.__senzPendingReqs[u])return;" +
            "delete window.__senzPendingReqs[u];" +
            "window.__senzReplyBlob(u);};" +
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
            "window.__senzBlobCache[u]=i>=0?s.substring(i+1):s;" +
            "try{window.__senzFlushPending&&window.__senzFlushPending(u);}catch(e){}};" +
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
            "try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: req '+m.url.slice(-8)+' cache='+(window.__senzBlobGet(m.url)?'ada':'kosong'));}catch(e){}" +
            "if(window.__senzBlobGet(m.url)){window.__senzReplyBlob(m.url);}" +
            "else{window.__senzPendingReqs[m.url]=true;" +
            "setTimeout(function(){try{delete window.__senzPendingReqs[m.url];}catch(e){}},20000);}" +
            "try{var fs=document.querySelectorAll('iframe');" +
            "for(var i=0;i<fs.length;i++){try{fs[i].contentWindow.postMessage(m,'*');}catch(e){}}}catch(e){}" +
            "}else if(m&&m.type==='senz-blob-data'){" +
            "if(window.parent===window){" +
            "if(m.url===window.__senzPendingBlobUrl&&m.data&&!window.__senzBlobDone){" +
            "var nm=m.name||'';" +
            "try{nm=nm||window.__senzBlobName(m.url)||'';}catch(e){}" +
            "window.__senzBlobDone=true;window.SenzBlob.onBlobData(m.data,nm);}}" +
            "else if(m.data){try{window.parent.postMessage(m,'*');}catch(e){}}" +
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
            "try{trapNode(document.documentElement||document);}catch(e){}" +
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
            val host = request.url.host ?: ""
            // v0.1.31: hanya host Claude / AI Studio yang diambil
            // (daftar putih TRAP_HOSTS). Host lain — termasuk halaman
            // login situs lain — dimuat WebView apa adanya.
            if (!isTrapHost(host)) return null
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
