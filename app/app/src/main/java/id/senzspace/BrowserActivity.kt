package id.senzspace

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
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

    private lateinit var webView: WebView
    private lateinit var debugView: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var downloadBtn: android.widget.Button
    private val lockLauncher = LockGate.register(this)
    private var popupView: WebView? = null
    private var currentUrl = ""
    private var spaceName = "Space"
    private val debugLines = ArrayDeque<String>()

    /** Log diagnostik S0.2: tampil di layar (bawah) supaya Sendy bisa
     *  screenshot penyebab blank — logcat HP tidak bisa ditarik (adbd mati).
     *  Baris pertama SELALU URL halaman utama saat ini (tidak pernah
     *  kegeser log JS yang panjang). */
    private fun logEvent(msg: String) {
        runOnUiThread {
            debugLines.addLast(msg)
            while (debugLines.size > 6) debugLines.removeFirst()
            if (::debugView.isInitialized) {
                debugView.text = ("URL: $currentUrl\n" +
                    debugLines.joinToString("\n"))
            }
        }
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
            rootLayout.removeView(popup)
            popup.destroy()
            webView.visibility = android.view.View.VISIBLE
            logEvent("POPUP ditutup — kembali ke $currentUrl")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // WAJIB sebelum WebView pertama dibuat di proses ini.
        WebView.setDataDirectorySuffix(dataDirectorySuffix)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        super.onCreate(savedInstanceState)

        val name = intent.getStringExtra(MainActivity.EXTRA_SPACE_NAME) ?: "Space"
        spaceName = name
        val color = intent.getStringExtra(MainActivity.EXTRA_SPACE_COLOR) ?: "#5B5BD6"
        val url = intent.getStringExtra(MainActivity.EXTRA_URL) ?: "https://claude.ai"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout = root

        // Aturan anti-salah-akun (PRD F4): nama + warna Space aktif
        // tampil permanen di top bar, plus tombol ⬇ (mockup v2 layar 02):
        // badge jumlah file di folder Space ini; tap → File Manager.
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        downloadBtn = android.widget.Button(this).apply {
            text = "⬇"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00000000"))
            textSize = 16f
            minimumHeight = 0
            minHeight = 0
        }
        downloadBtn.setOnClickListener {
            val intent = android.content.Intent(this, FileManagerActivity::class.java)
            intent.putExtra(FileManagerActivity.EXTRA_FOLDER, dataDirectorySuffix)
            startActivity(intent)
        }
        topBarLayout.addView(topBar)
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

        // Unduhan per Space (PRD F6, slice File Manager): file unduhan
        // dari WebView masuk ke folder Space ini di FileStore. Cookie sesi
        // + User-Agent diteruskan ke DownloadManager — tanpa cookie,
        // unduhan dari situs yang butuh login (mis. lampiran Claude)
        // ditolak server (403).
        webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            val fileName = android.webkit.URLUtil.guessFileName(
                dlUrl, contentDisposition, mimeType
            )
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

        // Panel log diagnostik S0.2 (sementara, sampai gerbang login lulus).
        debugView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxLines = 8
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.parseColor("#FFD54F"))
            text = "LOG: menunggu muat..."
        }
        root.addView(debugView)
        setContentView(root)

        // S0.1 — perbaikan blank putih setelah login (temuan HP 16 Nov 18:27):
        // cookie pihak ketiga + UA dibersihkan. S0.2: panel log. S0.3: pop-up
        // login jadi WebView popup asli (window.opener utuh) — lihat
        // openPopupWindow; pengalihan URL pop-up ke WebView utama (S0.1/S0.2)
        // TERBUKTI bikin callback OAuth kehilangan opener → blank putih.

        if (savedInstanceState == null) {
            webView.loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
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
        // S0.4: tulis cookie sesi ke disk tiap app ditinggalkan, supaya sesi
        // tetap ada walau proses mati mendadak setelahnya.
        if (::webView.isInitialized) {
            CookieManager.getInstance().flush()
        }
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

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
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

/** AI Studio — proses :aistudio, data directory suffix "aistudio". */
class AiStudioActivity : BrowserActivity() {
    override val dataDirectorySuffix: String = "aistudio"

    // AI Studio: login ditandai URL kerja (/prompts, /app); halaman "/" saat
    // logout tampil sebagai landing — itu BUKAN login.
    override fun isLoggedIn(url: String): Boolean {
        return try {
            val u = android.net.Uri.parse(url)
            val path = u.path ?: ""
            u.host == "aistudio.google.com" &&
                (path.contains("/prompts") || path.contains("/app") ||
                    path.contains("/library"))
        } catch (e: Exception) {
            false
        }
    }
}
