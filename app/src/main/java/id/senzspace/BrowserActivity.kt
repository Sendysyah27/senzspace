package id.senzspace

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
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
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(false)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
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
