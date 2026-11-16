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
    private val debugLines = ArrayDeque<String>()

    /** Log diagnostik S0.2: tampil di layar (bawah) supaya Sendy bisa
     *  screenshot penyebab blank — logcat HP tidak bisa ditarik (adbd mati). */
    private fun logEvent(msg: String) {
        runOnUiThread {
            debugLines.addLast(msg)
            while (debugLines.size > 8) debugLines.removeFirst()
            if (::debugView.isInitialized) {
                debugView.text = debugLines.joinToString("\n")
            }
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
        val color = intent.getStringExtra(MainActivity.EXTRA_SPACE_COLOR) ?: "#5B5BD6"
        val url = intent.getStringExtra(MainActivity.EXTRA_URL) ?: "https://claude.ai"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Aturan anti-salah-akun (PRD F4): nama + warna Space aktif
        // tampil permanen di top bar.
        val topBar = TextView(this).apply {
            text = "● $name  —  SenzSpace S0"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor(color))
            setTextColor(Color.WHITE)
        }
        root.addView(topBar)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            // S0.1 — perbaikan blank putih setelah login (temuan HP 16 Nov 18:27):
            // 1) Login Claude pakai pop-up (khususnya "Continue with Google").
            //    WebView polos tanpa onCreateWindow tidak bisa membuka pop-up
            //    itu, jadi alur login mentok / halaman sesudah login blank.
            //    Sekarang pop-up dialihkan ke WebView utama yang sama.
            // 2) Cookie pihak ketiga ditolak diam-diam oleh WebView polos,
            //    padahal sesi login Claude/OAuth butuh cookie itu.
            // 3) User-Agent bawaan WebView mengandung "; wv" — sebagian situs
            //    melayani halaman kosong / versi rusak untuk UA itu.
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
                    logEvent("MULAI: $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    logEvent("SELESAI: $url")
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        logEvent("HTTP ${errorResponse.statusCode}: ${request.url}")
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
                    // Pop-up (mis. login Google) dimuat di WebView utama,
                    // supaya sesi login mendarat di Space ini, bukan hilang.
                    // S0.2: forward lewat onPageStarted — shouldOverrideUrlLoading
                    // TIDAK terpanggil untuk load awal pop-up, jadi URL login
                    // sempat nyangkut di pop-up tersembunyi.
                    val popup = WebView(view.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                v: WebView,
                                url: String,
                                favicon: android.graphics.Bitmap?
                            ) {
                                if (url != "about:blank") {
                                    webView.loadUrl(url)
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                v: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                webView.loadUrl(request.url.toString())
                                return true
                            }
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
        root.addView(webView)

        // Panel log diagnostik S0.2 (sementara, sampai gerbang login lulus).
        debugView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxLines = 6
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.parseColor("#FFD54F"))
            text = "LOG: menunggu muat..."
        }
        root.addView(debugView)
        setContentView(root)

        if (savedInstanceState == null) {
            webView.loadUrl(url)
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
