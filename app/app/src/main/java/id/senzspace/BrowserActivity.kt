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
                ViewGroup.LayoutParams.MATCH_PARENT
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
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Toast.makeText(
                            this@BrowserActivity,
                            "Gagal muat: ${error.description}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message
                ): Boolean {
                    // Pop-up (mis. login Google) dimuat di WebView utama,
                    // supaya sesi login mendarat di Space ini, bukan hilang.
                    val popup = WebView(view.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
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
