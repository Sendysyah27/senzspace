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
        private const val TAG = "SenzSpace"

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

        /** v0.1.78 — state WebView yang disimpan saat shed (MemoryGuard),
         *  keyed by suffix. Dipulihkan onCreate berikut (restoreState)
         *  bila state sistem tidak cukup; dihapus sesudah dipakai.
         *  Hidup per proses (tiap :spaceN punya map sendiri) — bila
         *  proses mati, map hilang dan fallback URL snapshot (v0.1.77)
         *  yang berlaku. */
        private val shedStateBySuffix = mutableMapOf<String, Bundle>()
    }

    private lateinit var webView: WebView
    private lateinit var rootLayout: LinearLayout
    private var topBarView: android.view.View? = null

    /** v0.1.31: geser top bar DIHAPUS —
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
    /** v0.1.78 — WebView sudah di-destroy MemoryGuard (shed): jangan
     *  sentuh webView lagi (url/draw melempar) sampai activity
     *  dibuat ulang (onResume → recreate()). */
    private var shedWebViewDestroyed = false
    private var shedReceiver: android.content.BroadcastReceiver? = null
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
     *  Google jalan di prototipe S0 (16 Nov, sebelum trap blob
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

    /** v0.1.75 (audit #1): gerbang origin jembatan JS. Jembatan
     *  (SenzLive/SenzBlob/SenzHttp) HANYA boleh dipanggil halaman
     *  yang URL-nya sekarang di host home Space ini, host trap
     *  (Claude/AI Studio), atau host login Google (OAuth) — atau
     *  subdomainnya. Halaman lain (tautan/redirect/iframe asing)
     *  tidak bisa menulis file lewat jembatan. URL kosong = tolak. */
    private fun isBridgeOriginAllowed(): Boolean {
        val host = try {
            android.net.Uri.parse(currentUrl).host
        } catch (_: Exception) {
            null
        } ?: return false
        if (isTrapHost(host)) return true
        if (host in NO_INJECT_HOSTS) return true
        if (NO_INJECT_HOSTS.any { host.endsWith(".$it") }) return true
        val homeHost = try {
            android.net.Uri.parse(homeUrl).host
        } catch (_: Exception) {
            null
        }
        if (homeHost != null && (host == homeHost || host.endsWith(".$homeHost"))) return true
        return false
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
    // v0.1.69: dump kandidat tombol unduh (label@x,y, pisah ' ;; ')
    // dari ttsDownloadButtonRectJs — dibawa ke detail gagal.
    private var ttsDownloadCandidates: String = ""
    private var ttsLastTapInfo: String = ""
    // v0.1.51: percobaan Style yang akhirnya lolos (0 = sudah menempel
    // sebelum percobaan 1), + apakah tap unduh sudah diulang sekali.
    private var ttsStyleAttempt: Int = 0
    private var ttsDownloadRetried: Boolean = false
    // v0.1.81: watchdog anti-stall TTS + hitung percobaan kartu dialog
    // + tenggat fallback rekam-ulang audio.
    private var ttsStartMs: Long = 0
    private var ttsEditorFound: Boolean = false
    private var ttsCreateAttempts: Int = 0
    private var ttsWatchdogFired: Boolean = false
    private var ttsFallbackDeadlineMs: Long = 0
    // v0.1.83: hitung siklus diagnose anti-/docs (A1) + rekam langsung
    // tanpa event unduh (B1) + endpoint rekam manual (C1).
    private var ttsDirectRecordStarted: Boolean = false
    private var ttsDiagnoseCycles: Int = 0
    private var ttsRecordManual: Boolean = false
    private var ttsRecordFileName: String = ""
    private var ttsRecordPendingFile: String? = null
    // v0.1.84: verdict gate rekam langsung — diisi bridge
    // onBlobRecordDone (async, dari onstop JS). File HANYA boleh
    // "done" bila verdict "ok"; "empty"/"short" -> gagal jujur.
    // Di-reset tiap startDirectRecord supaya verdict basi tidak
    // memberkati file rekam berikutnya.
    private var ttsRecordGateVerdict: String = ""
    private var ttsRecordGateDetail: String = ""
    private var ttsRecordGateCapturedSec: Double = 0.0
    private var ttsRecordGateDurationSec: Double = 0.0
    private var ttsRecordGateChunks: Int = 0
    private var ttsRecordGateBytes: Long = 0
    // v0.1.81 (B3): penghitung diagnostik unduh blob — dibawa ke
    // pesan gagal supaya ketahuan di mana rantai putus.
    private var blobDlIframes: Int = 0
    private var blobDlCoreReqs: Int = 0
    private var blobDlFetchTried: Boolean = false
    private var blobDlFetchOk: Boolean = false
    private var lastBlobError: String = ""
    private val ttsWatchdogRunnable = Runnable { checkTtsWatchdogTick() }

    /** v0.1.81 watchdog anti-stall (A3): anggaran 3 menit untuk
     *  mencapai editor, 10 menit total sesudah editor ketemu;
     *  ditangguhkan selama fallback rekam-ulang audio berjalan.
     *  Bila kedaluwarsa -> fail via dump inventaris (status ditulis
     *  dulu secara sinkron supaya beku langsung kelihatan walau
     *  callback dump tidak pernah kembali). Kembalikan true bila
     *  sudah fail (atau sudah pernah fail karena watchdog). */
    private fun checkTtsWatchdog(): Boolean {
        if (pendingTtsScript == null || !ttsRunning) return false
        if (ttsWatchdogFired) return true
        val now = System.currentTimeMillis()
        if (ttsFallbackDeadlineMs > 0 && now < ttsFallbackDeadlineMs) return false
        if (ttsStartMs <= 0) return false
        val budgetMs = if (ttsEditorFound) 10 * 60 * 1000L else 3 * 60 * 1000L
        if (now - ttsStartMs > budgetMs) {
            ttsWatchdogFired = true
            writeTtsStatus(
                "running",
                "TTS timeout ${if (ttsEditorFound) 10 else 3} menit di halaman $currentUrl - mengumpulkan inventaris halaman"
            )
            runTtsDumpInventory("TTS timeout ${if (ttsEditorFound) 10 else 3} menit di halaman $currentUrl")
            return true
        }
        return false
    }

    /** Tick watchdog berkala (A3): penangkap beku bila callback
     *  evaluateJavascript berhenti kembali sama sekali — rantai
     *  langkah tidak bisa memeriksa dirinya sendiri. Berhenti
     *  sendiri bila run sudah selesai/gagal. */
    private fun checkTtsWatchdogTick() {
        if (pendingTtsScript == null || !ttsRunning) return
        if (checkTtsWatchdog()) return
        ttsHandler.postDelayed(ttsWatchdogRunnable, 60000)
    }
    private val ttsHandler = Handler(Looper.getMainLooper())
    /** v0.1.60 - payload eksekutor YouTube Studio (POST /studio).
     *  Aksi: dashboard (ringkasan channel), videos (daftar Content),
     *  upload (file _inbox + judul + deskripsi + jadwal). Aturan host:
     *  hanya studio.youtube.com (dicek ApiServer + maybeRunApiStudio).
     *  pendingStudioFile = file _inbox yang disodorkan ke chooser
     *  saat eksekutor mengetuk "Select files" di dialog upload;
     *  studioChooserServed =
     *  chooser sudah dipanggil (dipakai tunggu upload);
     *  studioUploadFileName = nama file (tetap ada sesudah
     *  pendingStudioFile dikosongkan chooser — dipakai verifikasi
     *  nama file di dialog upload); studioSelectFilesClicked =
     *  tap "Select files" sudah dilakukan sekali di run ini
     *  (v0.1.63 — jangan tap berulang, chooser kedua jatuh ke
     *  pemilih file di layar karena file _inbox sudah terpakai). */
    private var pendingStudioAction: String? = null
    private var pendingStudioFile: java.io.File? = null
    private var pendingStudioTitle: String? = null
    private var pendingStudioDescription: String? = null
    private var pendingStudioSchedule: String? = null
    private var studioRunning: Boolean = false
    private var studioChooserServed: Boolean = false
    private var studioSelectFilesClicked: Boolean = false
    private var studioDashboardEmptyRetried: Boolean = false
    private var studioUploadFileName: String? = null
    private var studioVideoId: String? = null
    private var studioChannelId: String? = null
    private var studioUiLang: String = "en"
    private val studioHandler = Handler(Looper.getMainLooper())

    // v0.1.52 - snapshot halaman (API bridge, GET /page): pengaman
    // jalan ganda (OPT-15: latch + nomor urut untuk reset darurat
    // bila callback tidak pernah menyala). v0.1.68: cadence snapshot
    // TIDAK LAGI ikut tick live (OPT-17) — timer wall-clock sendiri
    // tiap 20 dtk (snapshotRunnable di bawah).
    private var pageSnapRunning: Boolean = false
    private var pageSnapSeq: Int = 0

    /** Callback unduhan file-chooser yang sedang menunggu pilihan user.
     *  WAJIB dipanggil tepat sekali (dengan array URI atau null). */
    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /** Pemilih file untuk <input type="file"> di WebView (v0.1.56,
     *  Opsi A — AKAR 7): File Manager in-app dalam mode pilih.
     *  FM menyalin file pilihan ke _inbox lalu mengembalikan path
     *  HASIL (extra EXTRA_PICK_RESULT); hasilnya diubah jadi URI
     *  FileProvider persis seperti jalur _inbox API (v0.1.38).
     *  Didaftar di sini (bukan di onCreate) supaya siap sebelum
     *  WebView pertama kali dipakai; dipanggil dari onShowFileChooser
     *  (utama + pop-up). System picker HP tetap ada sebagai jalan
     *  kedua — lewat tombol "Pilih dari HP" di dalam FM. */
    private val fmPickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileChooserCallback
        fileChooserCallback = null
        if (cb == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val stagedPath = result.data!!.getStringExtra(FileManagerActivity.EXTRA_PICK_RESULT)
        val staged = stagedPath?.let { java.io.File(it) }
        if (staged == null || !staged.isFile) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", staged
            )
            grantUriPermission(
                packageName, uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            cb.onReceiveValue(arrayOf(uri))
        } catch (e: Exception) {
            cb.onReceiveValue(null)
        }
    }

    /** v0.1.56: buka FM mode pilih untuk chooser WebView. Folder awal
     *  = folder Space ini (dataDirectorySuffix = "spaceN"/"aistudio"). */
    private fun launchFmPicker() {
        val intent = android.content.Intent(this, FileManagerActivity::class.java).apply {
            putExtra(FileManagerActivity.EXTRA_FOLDER, dataDirectorySuffix)
            putExtra(FileManagerActivity.EXTRA_PICK_MODE, true)
        }
        fmPickLauncher.launch(intent)
    }

    // Multi Live v2 (S1): tile Beranda harus menampilkan halaman web
    // yang sedang
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
    // v0.1.68: SNAPSHOT_INTERVAL_MS (OPT-17) — snapshot wall-clock
    // tiap 20 dtk, lepas dari live interval user (1–10 dtk).
    // PAGE_SNAP_TIMEOUT_MS (OPT-15) — bila callback
    // evaluateJavascript snapshot tidak menyala dalam 10 dtk,
    // latch pageSnapRunning direset paksa (renderer hang) + ditandai
    // file supaya monitor bisa melihatnya.
    private val SNAPSHOT_INTERVAL_MS: Long = 20_000L
    private val PAGE_SNAP_TIMEOUT_MS: Long = 10_000L
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
    // v0.1.68 (OPT-16): hasil snapshot terakhir — Claude sedang
    // generating atau tidak. Dipakai interval adaptif tangkapan
    // (bersama lastDomMs): saat streaming, tangkap paling 1×/5 dtk.
    private var lastSnapGenerating: Boolean = false
    // v0.1.68 (OPT-16): kompresi JPEG + tulis file thumbnail jalan
    // di thread ini (satu antrean, berurutan) — draw WebView tetap
    // di UI thread, itu keharusan API. Tidak pernah menyentuh
    // lastThumbBmp (itu tetap milik UI thread).
    private val thumbBg = java.util.concurrent.Executors.newSingleThreadExecutor()

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

    // v0.1.68 (OPT-17): snapshot halaman jalan di WALL-CLOCK tiap
    // 20 dtk (timer terpisah), TIDAK ikut tick live. Dulu snapshot
    // diambil tiap 10 tick — pada live interval 10 dtk itu berarti
    // ~100 dtk, nyaris menyentuh ambang basi monitor (120 dtk); satu
    // tick terlewat (throttling) langsung membuat Space tampak
    // UNKNOWN. Live interval tetap mengatur frekuensi thumbnail saja.
    // Ambang basi 120 dtk + field ageSec/stale di API tidak berubah;
    // isi snapshot juga tidak berubah, hanya seberapa sering ditulis.
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            if (!liveRunning) return
            capturePageSnapshot()
            liveHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    private fun startLiveCapture() {
        if (liveRunning) return
        liveRunning = true
        liveHandler.postDelayed(liveRunnable, liveIntervalMs())
        liveHandler.postDelayed(snapshotRunnable, SNAPSHOT_INTERVAL_MS)
    }

    private fun stopLiveCapture() {
        liveRunning = false
        liveHandler.removeCallbacks(liveRunnable)
        liveHandler.removeCallbacks(snapshotRunnable)
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
        // v0.1.78: WebView sudah di-destroy (shed) — jangan draw.
        if (shedWebViewDestroyed) return
        // v0.1.20: Live dibekukan dari Beranda (tahan lama tile →
        // Matikan Live) — thumbnail berhenti update, browser + sesi
        // tetap jalan. Satu titik cek ini menutup semua pemicu tangkap
        // (timer 2 dtk, MutationObserver, onPageFinished).
        if (LiveFreeze.isFrozen(this, dataDirectorySuffix)) return
        val now = System.currentTimeMillis()
        // v0.1.68 (OPT-16): interval ADAPTIF. Saat Claude streaming
        // (DOM bermutasi terus — lastDomMs baru — atau snapshot
        // terakhir bilang generating), tangkapan dibatasi 1×/5 dtk,
        // bukan ~1×/dtk: kurangi rebutan main thread tepat saat
        // eksekutor paling butuh thread untuk polling verifikasi.
        // Saat halaman tenang, batas lama 1 dtk tetap berlaku.
        val streaming = lastSnapGenerating || (now - lastDomMs < 15_000L)
        val minIntervalMs = if (streaming) 5_000L else 1_000L
        if (now - lastCaptureMs < minIntervalMs) return
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
                // v0.1.68 (OPT-8): file HANYA ditulis bila piksel
                // berubah — mtime lalu jujur mencerminkan perubahan
                // piksel terakhir, dan thumb_age_sec di /spaces
                // menjadi sinyal kesegaran yang benar (dulu file
                // ditulis ulang tiap capture walau piksel identik,
                // jadi thumb_age_sec = 0 padahal frame beku berjam-jam).
                // v0.1.68 (OPT-16): kompresi JPEG + tulis file jalan
                // di thread background (satu antrean, berurutan) —
                // draw WebView tetap di UI thread, itu keharusan API.
                // Bitmap diserahkan ke thread itu dan didaur ulang
                // di sana sesudah tulis; thread ini tidak pernah
                // menyentuh lastThumbBmp.
                val file = FileStore.liveThumbFile(this, dataDirectorySuffix)
                thumbBg.execute {
                    try {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        // Bersihkan file PNG lama (format v0.1.8) supaya
                        // tidak menumpuk.
                        val oldPng = java.io.File(file.parentFile, "$dataDirectorySuffix.png")
                        if (oldPng.exists()) oldPng.delete()
                    } catch (_: Exception) {
                        // Thumbnail boleh gagal; jangan ganggu browsing.
                    } finally {
                        bmp.recycle()
                    }
                }
            } else {
                // Piksel sama: jangan tulis apa pun — file + mtime
                // dibiarkan apa adanya (OPT-8). Bitmap dibuang di sini.
                bmp.recycle()
            }
            writeLiveStatus()
        } catch (e: Exception) {
            // Thumbnail boleh gagal; jangan ganggu browsing.
        }
    }

    /** v0.1.52 - baca keadaan halaman (API bridge, GET /page):
     *  URL (tanpa query) § judul § lagi ngetik jawaban § kena limit
     *  § jam reset limit § teks awal § teks akhir. Dipisah "§", teks
     *  dibersihkan dulu (tanpa " \ § |, baris baru jadi spasi) supaya
     *  balasan evaluateJavascript (string JSON-quoted) gampang dipecah
     *  di Kotlin. Limit: EN "5-hour message limit" / "out of free
     *  messages", ID "kehabisan pesan gratis" / "Penggunaan gratis".
     *  Reset: EN "resets at 10:50 PM" / halaman upsell "returns at
     *  5:00 PM" / "out of free messages until 5:00 PM", ID
     *  "pukul 00.20". Generating
     *  (best effort): tombol Stop ada di halaman.
     *  v0.1.53 - field ke-8: daftar link chat sidebar (maks 20,
     *  "judul^url" dipisah "~"): semua <a href="/chat/<id>"> di DOM.
     *  v0.1.55 - DWI-BAHASA (audit API 20 Nov): label tombol sidebar
     *  kini EN + ID, dicocokkan tanpa peduli huruf besar-kecil
     *  ("buka/tutup sidebar", "buka/tutup bilah sisi", "buka menu
     *  navigasi"); label "Menu" DIHAPUS dari daftar — di UI Indonesia
     *  ia malah kena tombol titik-tiga chat dan membuka menu konteks
     *  (bukti Space 4, 21:58). Fallback substring cuma "sidebar" /
     *  "bilah" (buang "menu"). Bila tidak ada tombol yang cocok,
     *  __senzSbTried TIDAK diset (coba lagi tick berikut — dulu
     *  diset walau tidak ada yang diklik). Field ke-10 = sbClicked
     *  (label yang diklik, atau "fallback:<label>"), field ke-11 =
     *  uiLang ("id" bila teks halaman berisi "Chat baru"/"Kirim
     *  pesan"/"kehabisan pesan gratis", kalau tidak "en"). Flag
     *  generating kini juga mengenali "Berhenti". */
    private fun pageSnapshotJs(): String = """
        (function(){try{
        var txt=document.body?document.body.innerText:'';
        var clean=function(s){return (s||'').replace(/["\\§|]/g,' ').replace(/\s+/g,' ').trim();};
        var full=clean(txt);
        var head=full.substring(0,4000);
        var tail=full.substring(Math.max(0,full.length-1500));
        var stop=!!(document.querySelector('button[aria-label*="Stop"]')||document.querySelector('button[aria-label*="Berhenti"]'));
        var low=txt.toLowerCase();
        var lim=txt.indexOf('5-hour message limit')>=0||low.indexOf('out of free messages')>=0||low.indexOf('kehabisan pesan gratis')>=0||low.indexOf('penggunaan gratis')>=0;
        var m=txt.match(/resets at ([0-9:]+ [AP]M)/)||txt.match(/returns? at ([0-9:]+ [AP]M)/)||txt.match(/until ([0-9:]+ [AP]M)/)||txt.match(/pukul ([0-9]{1,2}\.[0-9]{2})/);
        var reset=m?m[1]:'';
        var u='';try{u=location.origin+location.pathname;}catch(e){}
        var raw=document.querySelectorAll('a[href*="/chat/"]');
        var seenCount=raw.length;
        var links=[];var seen={};
        for(var i=0;i<raw.length&&links.length<20;i++){
        var h=raw[i].getAttribute('href')||'';
        var mm=h.match(/\/chat\/([A-Za-z0-9-]+)/);
        if(!mm) continue;
        if(seen[mm[1]]) continue;seen[mm[1]]=1;
        var t=clean(raw[i].innerText||'').replace(/[\^~]/g,' ');
        if(!t) continue;
        links.push(t+'^'+location.origin+'/chat/'+mm[1]);
        }
        var sbBtns=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});
        var sbClicked='';
        var sbMatch=function(want){for(var a=0;a<want.length;a++){for(var b=0;b<sbBtns.length;b++){var l=(sbBtns[b].getAttribute('aria-label')||'').trim().toLowerCase();if(l===want[a]){sbBtns[b].click();return want[a];}}}return '';};
        var sbOpen=['toggle sidebar','open sidebar','buka sidebar','alih sidebar','buka bilah sisi','open navigation menu','buka menu navigasi'];
        var sbClose=['close sidebar','tutup sidebar','tutup bilah sisi'];
        if(links.length===0&&!window.__senzSbDone&&!window.__senzSbTried){
        sbClicked=sbMatch(sbOpen);
        if(!sbClicked){for(var k=0;k<sbBtns.length;k++){var al=(sbBtns[k].getAttribute('aria-label')||'').toLowerCase();if(al.indexOf('sidebar')>=0||al.indexOf('bilah')>=0){sbBtns[k].click();sbClicked='fallback:'+al;break;}}}
        if(sbClicked)window.__senzSbTried=true;
        }else if(links.length>0&&window.__senzSbTried){
        sbMatch(sbClose);window.__senzSbTried=false;
        }
        if(links.length>0){window.__senzSbChats=links.join('~');window.__senzSbDone=true;}
        var linksField=links.length>0?links.join('~'):(window.__senzSbChats||'');
        var uiLang=(txt.indexOf('Chat baru')>=0||txt.indexOf('Kirim pesan')>=0||low.indexOf('kehabisan pesan gratis')>=0)?'id':'en';
        return [u,clean(document.title),stop?'1':'0',lim?'1':'0',reset,head,tail,linksField,String(seenCount),sbClicked,uiLang].join('§');
        }catch(e){return '§§0§0§§§§§§§';}})()
    """.trimIndent()

    /** v0.1.52 - tulis snapshot halaman ke api-page-<suffix>.json
     *  (filesDir internal, dibaca ApiServer untuk GET /page).
     *  Dijalankan tiap halaman selesai + tiap 20 dtk wall-clock
     *  (timer terpisah, OPT-17 — dulu ikut tick live).
     *  Bila limit ketemu, tulis juga file limit SpaceStatus supaya
     *  field `limit` di /spaces jujur (sebelumnya null walau modal
     *  limit tampil — bukti 20 Nov 20:30). TIDAK PERNAH menghapus
     *  file limit dari sini: halaman home tidak menampilkan modal
     *  limit, jadi "tidak ketemu" belum tentu "sudah bebas". */
    private fun pageStatusFile(): java.io.File =
        java.io.File(filesDir, "api-page-$dataDirectorySuffix.json")

    /** v0.1.77 — WebView dianggap blank bila tidak punya URL (null /
     *  kosong) atau masih di about:blank. WebView segar (activity
     *  dibuat ulang sistem) melapor null; about:blank tidak pernah
     *  halaman Space yang sah. */
    private fun isWebViewBlank(): Boolean {
        if (!::webView.isInitialized) return true
        val u = webView.url
        return u.isNullOrEmpty() || u == "about:blank"
    }

    /** v0.1.77 — URL terakhir Space ini dari snapshot halaman
     *  (api-page-<suffix>.json, ditulis capturePageSnapshot) atau
     *  homeUrl bila snapshot tidak ada / isinya bukan URL http(s).
     *  Snapshot bertahan di filesDir walau proses mati — currentUrl
     *  (memori) tidak. */
    private fun lastUrlOrHome(): String {
        try {
            val f = pageStatusFile()
            if (f.isFile) {
                val u = org.json.JSONObject(f.readText()).optString("url", "")
                if (u.startsWith("http://") || u.startsWith("https://")) return u
            }
        } catch (_: Exception) {
        }
        return homeUrl
    }

    /** v0.1.77 — muat URL terakhir Space ini (atau homeUrl).
     *  reload() adalah no-op saat WebView tanpa URL, jadi pemulihan
     *  blank HARUS loadUrl eksplisit. currentUrl disetel sesuai URL
     *  yang dimuat. */
    private fun loadLastUrlOrHome() {
        val u = lastUrlOrHome()
        currentUrl = u
        try {
            webView.loadUrl(u)
        } catch (_: Exception) {
        }
    }

    private fun capturePageSnapshot() {
        if (!::webView.isInitialized) return
        // v0.1.78: WebView sudah di-destroy (shed) — jangan evaluate.
        if (shedWebViewDestroyed) return
        if (pageSnapRunning) return
        pageSnapRunning = true
        pageSnapSeq++
        val seq = pageSnapSeq
        // v0.1.68 (OPT-15): latch TANPA timeout = snapshot mati
        // selamanya bila callback evaluateJavascript tidak pernah
        // menyala (renderer hang) — tanpa log, tanpa pemulihan.
        // Reset darurat: bila latch masih terkunci sesudah 10 dtk,
        // bersihkan supaya tangkapan berikut dicoba lagi, dan tandai
        // file marker supaya monitor bisa melihatnya. Nomor urut
        // mencegah reset ini membersihkan latch tangkapan BARU yang
        // dimulai sesudah callback normal menyala.
        liveHandler.postDelayed({
            if (pageSnapRunning && pageSnapSeq == seq) {
                pageSnapRunning = false
                try {
                    val f = FileStore.snapStuckFile(this, dataDirectorySuffix)
                    f.parentFile?.mkdirs()
                    f.writeText(System.currentTimeMillis().toString())
                } catch (_: Exception) {
                }
            }
        }, PAGE_SNAP_TIMEOUT_MS)
        try {
            webView.evaluateJavascript(pageSnapshotJs()) { raw ->
                pageSnapRunning = false
                try {
                    val s = raw?.removeSurrounding("\"") ?: return@evaluateJavascript
                    val p = s.split("§")
                    if (p.size < 11) return@evaluateJavascript
                    val limited = p[3] == "1"
                    val reset = p[4]
                    // v0.1.68 (OPT-16): catat hasil snapshot terakhir
                    // untuk interval adaptif tangkapan thumbnail.
                    lastSnapGenerating = p[2] == "1"
                    if (limited) {
                        SpaceStatus.writeLimit(this, dataDirectorySuffix, reset)
                    }
                    // v0.1.53: field ke-8 = link chat sidebar
                    // ("judul^url" dipisah "~", maks 20).
                    val chats = org.json.JSONArray()
                    if (p[7].isNotEmpty()) {
                        for (pair in p[7].split("~")) {
                            val kv = pair.split("^", limit = 2)
                            if (kv.size == 2 && kv[1].isNotEmpty()) {
                                chats.put(org.json.JSONObject().apply {
                                    put("title", kv[0])
                                    put("url", kv[1])
                                })
                            }
                        }
                    }
                    val obj = org.json.JSONObject().apply {
                        put("name", spaceName)
                        put("url", p[0])
                        put("title", p[1])
                        put("generating", p[2] == "1")
                        put("limit", limited)
                        put("limitReset", reset)
                        put("text", p[5])
                        put("tail", p[6])
                        put("chats", chats)
                        put("chatLinksSeen", p[8].toIntOrNull() ?: 0)
                        put("sbClicked", p[9])
                        put("uiLang", p[10])
                        put("updated", System.currentTimeMillis())
                    }
                    pageStatusFile().writeText(obj.toString())
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            pageSnapRunning = false
        }
    }

    /** v0.1.52 - muat URL di Space ini (POST /navigate). Host URL
     *  HARUS sama dengan situs Space (claude.ai / aistudio) —
     *  dicek ApiServer sebelum intent dikirim; di sini cuma muat. */
    private fun consumeApiNavigate(intent: android.content.Intent) {
        val url = intent.getStringExtra(MainActivity.EXTRA_API_NAVIGATE_URL)
            ?: return
        intent.removeExtra(MainActivity.EXTRA_API_NAVIGATE_URL)
        if (!::webView.isInitialized) return
        if (currentUrl != url) webView.loadUrl(url)
    }

    /** Jembatan JS untuk Multi Live v2: halaman memanggil onChanged()
     *  tiap DOM berubah, onLimit/onLimitCleared tiap status limit Claude
     *  berubah. Dipanggil dari thread binder — lempar ke UI. Tidak ada
     *  isi halaman yang keluar: onChanged tanpa argumen, onLimit hanya
     *  membawa jam reset ("10:30 PM") untuk chip Beranda. */
    private inner class LiveBridge {
        @android.webkit.JavascriptInterface
        fun onChanged() {
            if (!isBridgeOriginAllowed()) return
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
            if (!isBridgeOriginAllowed()) return
            liveHandler.post {
                SpaceStatus.writeLimit(this@BrowserActivity, dataDirectorySuffix, reset)
            }
        }

        @android.webkit.JavascriptInterface
        fun onLimitCleared() {
            if (!isBridgeOriginAllowed()) return
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
                    "var m=txt.match(/resets at ([0-9:]+ [AP]M)/)||txt.match(/returns? at ([0-9:]+ [AP]M)/)||txt.match(/until ([0-9:]+ [AP]M)/);" +
                    "var reset=m?m[1]:'';var st=hit?('LIMIT:'+reset):'OK';" +
                    "if(window.__senzLimitState!==st){window.__senzLimitState=st;" +
                    "if(hit){window.SenzLive.onLimit(reset);}else{window.SenzLive.onLimitCleared();}}" +
                    "}catch(e){}};checkLimit();setInterval(checkLimit,5000);})();",
                null
            )
        } catch (e: Exception) {
        }
    }

    // Panel log kuning S0.2 SUDAH DILEPAS.
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
            // v0.1.75 (audit #2): konten campuran diblokir di popup
            // juga — sama seperti WebView utama.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
                        // v0.1.56: File Manager in-app mode pilih (Opsi A)
                        // — bukan lagi system picker langsung. File di
                        // luar SenzSpace tetap bisa dipilih lewat tombol
                        // "Pilih dari HP" di dalam FM.
                        launchFmPicker()
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
        // v0.1.71: buka Space langsung (tanpa Beranda) tetap memastikan
        // API hidup + watchdog terdaftar. ApiService jalan di proses
        // utama; startService aman bila service sudah jalan.
        if (ApiToken.isEnabled(this)) {
            ApiService.start(this)
            WatchdogWorker.enqueue(this)
            // v0.1.72: guardian watchdog (:watchdog) — proses sendiri
            // yang menjaga ApiService tetap hidup; idempotent.
            WatchdogGuardService.start(this)
        }
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
        // v0.1.77: reload() no-op saat WebView tanpa URL — bila blank,
        // muat URL terakhir/homeUrl, bukan reload buta.
        reloadBtn.setOnClickListener {
            if (::webView.isInitialized) {
                if (isWebViewBlank()) loadLastUrlOrHome() else webView.reload()
            }
        }
        val homeBtn = topBarIcon(R.drawable.ic_home, "Beranda Space")
        homeBtn.setOnClickListener { if (::webView.isInitialized) webView.loadUrl(homeUrl) }
        // v0.1.31: tombol pindah Space (ganti geser top bar) — buka
        // dialog pilih Space AKTIF (sudah login).
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
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
                    // v0.1.82 (A2): suntik core+trap blob via
                    // evaluateJavascript (tidak tunduk CSP) — SEBELUM
                    // executor bekerja di bawah.
                    ensureBlobCoreInjected(url)
                    // v0.1.16: penangkap blob (cache/inject) DIHAPUS — blob
                    // null-origin tidak bisa ditangkap dari frame utama.
                    if (!isForeground) captureLiveThumbnail()
                    // v0.1.38: halaman selesai - jalankan prompt API
                    // bila ada payload menunggu.
                    maybeRunApiPrompt()
                    // v0.1.39: sama untuk TTS API.
                    maybeRunApiTts()
                    // v0.1.83: sama untuk rekam manual (/tts-record).
                    maybeStartTtsRecord()
                    // v0.1.60: sama untuk eksekutor Studio.
                    maybeRunApiStudio()
                    // v0.1.52: halaman selesai - baca keadaan halaman
                    // untuk GET /page.
                    capturePageSnapshot()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    // v0.1.35: renderer mati (biasanya dibunuh sistem saat
                    // RAM menipis — POVA 5, 8 Space, free ~412MB) bikin
                    // WebView HITAM total sampai dimuat ulang (temuan 19
                    // Nov 09:42: Space 1 hitam 60-90 dtk; refresh tidak
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
                    // v0.1.77: reload() no-op bila renderer mati sebelum
                    // halaman commit (URL kosong) — muat URL terakhir
                    // Space ini; bila ada URL, reload seperti biasa.
                    // Fallback loadUrl(homeUrl) dipertahankan.
                    try {
                        if (view.url.isNullOrEmpty() || view.url == "about:blank") {
                            view.loadUrl(lastUrlOrHome())
                        } else {
                            view.reload()
                        }
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
                    // v0.1.75 (audit #3): skrip penangkap blob dilayani
                    // sebagai file same-origin (bukan inline) bila
                    // halaman tidak memakai nonce CSP.
                    if (request.method == "GET" &&
                        request.url.path == TRAP_SCRIPT_PATH &&
                        isTrapHost(request.url.host ?: "")
                    ) {
                        return trapScriptResponse()
                    }
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
                            // v0.1.80 (D1): JANGAN telan diam-diam — bila
                            // serving gagal, status prompt harus bilang
                            // apa yang gagal (nama file + pesan), bukan
                            // "pemilih file tidak muncul" (menyesatkan).
                            // Status "failed" tetap ditulis
                            // waitPromptChooser sesudah timeout; ini
                            // hanya jejak diagnosa.
                            val names = staged?.map { it.name }?.joinToString(", ")
                            writePromptStatus(
                                "running",
                                "gagal sodorkan file _inbox ($names): ${e.message}"
                            )
                            filePathCallback.onReceiveValue(null)
                        }
                        return true
                    }
                    // v0.1.60: eksekutor Studio sedang mengunggah video -
                    // sodorkan file _inbox yang sama lewat FileProvider
                    // (pola prompt API di atas). Sesudah disodorkan,
                    // chooser berikutnya kembali ke FM (field dikosongkan).
                    if (pendingStudioFile != null) {
                        val staged = pendingStudioFile
                        pendingStudioFile = null
                        try {
                            val uri = androidx.core.content.FileProvider
                                .getUriForFile(
                                    this@BrowserActivity,
                                    "${packageName}.fileprovider", staged!!
                                )
                            grantUriPermission(
                                packageName, uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            studioChooserServed = true
                            filePathCallback.onReceiveValue(arrayOf(uri))
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
                        // v0.1.56: File Manager in-app mode pilih (Opsi A)
                        // — bukan lagi system picker langsung. File di
                        // luar SenzSpace tetap bisa dipilih lewat tombol
                        // "Pilih dari HP" di dalam FM.
                        launchFmPicker()
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
        // bikin callback OAuth kehilangan opener → blank putih.

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
        } else {
            // v0.1.77: activity dibuat ulang sistem (tekanan RAM —
            // LMK) — WebView segar tanpa URL bila state tidak
            // dipulihkan: blok di atas dilewati karena
            // savedInstanceState != null. Pulihkan state WebView
            // (disimpan onSaveInstanceState); bila pulih gagal atau
            // URL tetap kosong, muat URL terakhir Space ini.
            // Bersihkan data tetap didahulukan bila intent memintanya
            // (jangan pulihkan state yang baru dibersihkan).
            if (intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_DATA, false)) {
                clearSpaceDataAndLoad(
                    url,
                    intent.getStringExtra(MainActivity.EXTRA_CLEAR_SUFFIX)
                )
            } else {
                // State disalin ke val non-null eksplisit supaya
                // restoreState tidak bergantung pada smart-cast
                // Bundle? di dalam try-expression; restored diisi
                // lewat statement agar tipenya pasti Boolean.
                val state: Bundle = savedInstanceState
                var restored = false
                try {
                    restored = webView.restoreState(state) != null
                } catch (_: Exception) {
                    restored = false
                }
                if (!restored || isWebViewBlank()) {
                    // v0.1.78: state yang disimpan saat shed
                    // (MemoryGuard) — coba pulihkan dulu sebelum
                    // fallback URL terakhir.
                    val shed = shedStateBySuffix.remove(dataDirectorySuffix)
                    if (shed != null) {
                        try {
                            restored = webView.restoreState(shed) != null
                        } catch (_: Exception) {
                            restored = false
                        }
                    }
                }
                if (!restored || isWebViewBlank()) {
                    loadLastUrlOrHome()
                } else {
                    currentUrl = webView.url ?: ""
                }
            }
        }
        startLiveCapture()
        // v0.1.38: payload prompt API dari intent (bila ada).
        consumeApiPrompt(intent)
        // v0.1.39: payload TTS API dari intent (bila ada).
        consumeApiTts(intent)
        // v0.1.83: perintah rekam manual /tts-record dari intent.
        consumeApiTtsRecord(intent)
        // v0.1.60: payload eksekutor Studio dari intent (bila ada).
        consumeApiStudio(intent)
        // v0.1.52: URL navigasi API dari intent (bila ada).
        consumeApiNavigate(intent)
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
        // v0.1.78: receiver shed terkendali (MemoryGuard). Broadcast
        // dalam-app saja (pengirim setPackage + flag NOT_EXPORTED di
        // sini) — tidak ada receiver di manifest, tidak ada aksi
        // sistem. Didaftar di akhir onCreate (webView sudah ada).
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context?,
                intent: android.content.Intent?
            ) {
                if (intent?.action == WatchdogGuardService.ACTION_SHED_SPACE) {
                    onShedBroadcast(intent)
                }
            }
        }
        shedReceiver = receiver
        try {
            val filter =
                android.content.IntentFilter(WatchdogGuardService.ACTION_SHED_SPACE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    receiver, filter,
                    android.content.Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {
            shedReceiver = null
        }
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
        // v0.1.56: JANGAN buang diam-diam nama file yang hilang di
        // tengah jalan (audit §6.3) — bila ada yang hilang antara
        // validasi POST /prompt dan eksekusi, gagal jujur dengan nama
        // file yang hilang; JANGAN kirim dengan lampiran lebih sedikit.
        val missing = names?.filter { n ->
            !java.io.File(FileStore.folderDir(this, "_inbox"), n).isFile
        } ?: emptyList()
        if (missing.isNotEmpty()) {
            writePromptStatus(
                "failed",
                "file lampiran tidak ketemu di _inbox: ${missing.joinToString(", ")}"
            )
            return
        }
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
        // kiri atas (sidebar) ke Project.
        // v0.1.70: sesudah halaman Project kebuka, LANGSUNG diagnose
        // composer halaman Project (mengetik di situ memulai chat baru
        // DI DALAM project); tanpa klik "New chat" (tombol itu tidak
        // ada di halaman Project).
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
     *  (by NAMA lewat sidebar — Project itu per-akun, tiap akun punya
     *  ID project sendiri, JANGAN hardcode URL project) -> tunggu URL
     *  /project/ (baca LIVE via JS, v0.1.70) -> diagnose composer
     *  halaman Project langsung.
     *  v0.1.80 (D2): cek dulu sidebar SUDAH terbuka (nama Project
     *  sudah kelihatan) — bila ya, langsung pilih Project tanpa klik
     *  toggle (toggle saat sidebar terbuka bisa menutupnya, dan
     *  tombolnya sering tidak ketemu); pencarian toggle diperluas
     *  ke button,[role="button"].
     *  Semua jalan di background (WebView tetap mengeksekusi meski
     *  activity tidak tampil). */
    private fun runPromptProject() {
        if (pendingPromptText == null) return
        val want = pendingPromptProject ?: return runPromptDiagnose(5)
        val quoted = org.json.JSONObject.quote(want)
        val js = """(function(){var want=$quoted.toLowerCase();function vis(e){if(e.offsetParent===null)return false;var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=window.innerHeight||r.left>=window.innerWidth)return false;var cs=getComputedStyle(e);return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0;}var els=[...document.querySelectorAll('a,button,[role="link"],[role="button"],[role="menuitem"],li')].filter(function(e){return vis(e);});for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim().toLowerCase();if(t===want||t==='projects'||t==='proyek'){return 'open';}}var open=['toggle sidebar','open sidebar','buka sidebar','alih sidebar','buka bilah sisi','open navigation menu','buka menu navigasi'];var bs=[...document.querySelectorAll('button,[role="button"]')].filter(function(b){return vis(b);});for(var j=0;j<open.length;j++){for(var k=0;k<bs.length;k++){var l=(bs[k].getAttribute('aria-label')||'').trim().toLowerCase();if(l===open[j]){bs[k].click();return '1';}}}for(var m=0;m<bs.length;m++){var al=(bs[m].getAttribute('aria-label')||'').toLowerCase();if(al.indexOf('sidebar')>=0||al.indexOf('bilah')>=0){bs[m].click();return '1';}}return '0';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            when (clean) {
                "open" -> runPromptPickProject()
                "1" -> promptHandler.postDelayed({ runPromptPickProject() }, 1500)
                else -> failPrompt("tombol sidebar (kiri atas) tidak ketemu - tidak bisa masuk Project")
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
        // v0.1.70 - baca URL LIVE via JS: currentUrl hanya update di
        // onPageStarted/onPageFinished, sedangkan navigasi sidebar
        // Claude itu SPA (pushState, tanpa full load) -> cache basi
        // dan wait ini tidak pernah melihat "/project/".
        val js = """(function(){return location.href;})()"""
        webView.evaluateJavascript(js) { raw ->
            if (pendingPromptText == null) return@evaluateJavascript
            val liveUrl = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (liveUrl.contains("/project/")) {
                // Composer halaman Project SUDAH ADA; mengetik di situ
                // memulai chat baru DI DALAM project — tidak perlu klik
                // "New chat" (tombol itu tidak ada di halaman Project).
                // Tandai selesai supaya onPageFinished berikutnya tidak
                // mengulang flow Project (penjaga maybeRunApiPrompt).
                promptProjectDone = true
                runPromptDiagnose(5)
            } else if (triesLeft <= 0) {
                failPrompt("halaman Project tidak kebuka (URL sekarang: $liveUrl)")
            } else {
                promptHandler.postDelayed({ waitPromptProjectUrl(triesLeft - 1) }, 2000)
            }
        }
    }

    /** Langkah 1: kolom chat (ProseMirror) ada? Balik "1|1|label,label"
     *  = composer ada | tombol kirim ada | aria-label tombol form. */
    private fun runPromptDiagnose(triesLeft: Int) {
        if (pendingPromptText == null) return
        val js = """(function(){var c=document.querySelector('div.ProseMirror[contenteditable="true"]')||document.querySelector('div[contenteditable="true"]');var s=document.querySelector('button[aria-label="Send message"]')||document.querySelector('button[aria-label="Kirim pesan"]');var btns=[];var root=c?c.closest('form'):null;if(root){var bs=root.querySelectorAll('button');for(var i=0;i<bs.length;i++){var l=bs[i].getAttribute('aria-label');if(l)btns.push(l);}}return (c?'1':'0')+'|'+(s?'1':'0')+'|'+btns.join(',');})()"""
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

    /** Langkah 2 (bila ada file): ketuk tombol attach, tunggu chooser
     *  disodori file, tunggu 3 dtk, cek chip lampiran muncul.
     *  v0.1.70 - MENU ATTACH: di composer halaman Project, klik attach
     *  membuka MENU ("Upload from device" / "Unggah dari perangkat"),
     *  bukan chooser langsung. Menu bisa juga sudah terbuka dari
     *  percobaan sebelumnya -> cek item menu DULU sebelum ketuk attach
     *  (ketuk attach saat menu terbuka malah menutup menu). Pencarian
     *  item menu by teks + visibility, BUKAN koordinat (posisi menu
     *  mengikuti tinggi composer).
     *  v0.1.80 - TAP BENERAN (bukan klik JS): klik JS .click() TIDAK
     *  memicu <input type=file> Claude — akar "pemilih file tidak
     *  muncul" (lihat clickAttachMenuItem / clickAttachButton). */
    private fun runPromptAttach() {
        val files = pendingPromptFiles ?: emptyList()
        promptChooserServed = false
        writePromptStatus("running", "mencari item menu unduh (${files.size} file)")
        clickAttachMenuItem { clicked ->
            if (pendingPromptText == null) return@clickAttachMenuItem
            if (clicked) {
                waitPromptChooser(20)
            } else {
                clickAttachButton()
            }
        }
    }

    /** v0.1.70 - cari item menu "Upload from device" / "Unggah dari
     *  perangkat" yang TERLIHAT lalu klik; callback true = diklik.
     *  Bila beberapa elemen cocok (induk + anak), ambil yang
     *  TERAKHIR di urutan DOM = yang paling dalam/spesifik.
     *  v0.1.80 - TAP BENERAN: JS hanya mengembalikan pusat rect
     *  elemen terlihat (vis() gaya Studio: offsetParent + rect di
     *  viewport + computed visibility/display/opacity — offsetParent
     *  saja meloloskan elemen tersembunyi/duplikat), Kotlin mengetuk
     *  titik itu via studioTapAt (dispatchTouchEvent DOWN+UP, pola
     *  ttsTapAt/studioTapAt). Label exact didahulukan atas contains;
     *  di antara yang sejenis, yang TERDALAM (paling akhir di urutan
     *  DOM) menang. Callback true = diketuk (rect ketemu). */
    private fun clickAttachMenuItem(onDone: (Boolean) -> Unit) {
        val js = """(function(){function vis(b){if(b.offsetParent===null)return false;var r=b.getBoundingClientRect();if(r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=window.innerHeight||r.left>=window.innerWidth)return false;var cs=getComputedStyle(b);return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0;}var labels=['upload from device','unggah dari perangkat'];var els=[...document.querySelectorAll('button,[role="menuitem"],[role="button"],li')].filter(function(e){return !e.disabled;});var hit=null,hitLabel='',hitExact=false;for(var i=0;i<els.length;i++){if(!vis(els[i]))continue;var t=(els[i].textContent||'').trim().toLowerCase();for(var k=0;k<labels.length;k++){var exact=(t===labels[k]);var cont=!exact&&(t.indexOf(labels[k])>=0&&t.length<60);if(exact||cont){if(!hit||exact||!hitExact){hit=els[i];hitLabel=labels[k];hitExact=exact;}}}}if(hit){var r=hit.getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2)+'|'+hit.tagName+'|'+hitLabel;}return '';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            val x = parts.getOrNull(0)?.toFloatOrNull()
            val y = parts.getOrNull(1)?.toFloatOrNull()
            if (parts.size < 4 || x == null || y == null) {
                onDone(false)
                return@evaluateJavascript
            }
            val tag = parts[2]
            val label = parts[3]
            writePromptStatus("running", "item menu unduh diketuk: $label ($tag) @$x,$y")
            studioTapAt(x, y)
            onDone(true)
        }
    }

    /** Ketuk tombol attach (perilaku lama), lalu tunggu ~1,5 dtk dan
     *  coba lagi item menu — bila menu muncul, ketuk; bila tidak ada
     *  menu (chooser langsung terbuka, seperti di chat biasa),
     *  langsung tunggu chooser.
     *  v0.1.80 - TAP BENERAN: JS hanya mengembalikan pusat rect
     *  tombol attach yang terlihat (vis() gaya Studio), Kotlin
     *  mengetuk via studioTapAt. Klik JS .click() tidak memicu menu
     *  attach Claude dengan andal (pelajaran v0.1.76). */
    private fun clickAttachButton() {
        val js = """(function(){function vis(b){if(b.offsetParent===null)return false;var r=b.getBoundingClientRect();if(r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=window.innerHeight||r.left>=window.innerWidth)return false;var cs=getComputedStyle(b);return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0;}var labels=['Add files','Attach files','Upload files','Add content','Attach','Lampirkan','Tambah file','Lampirkan file','Unggah file'];for(var k=0;k<labels.length;k++){var b=document.querySelector('button[aria-label="'+labels[k]+'"]');if(b&&vis(b)){var r=b.getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2)+'|'+labels[k];}}var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){var l=bs[i].getAttribute('aria-label')||'';if(/attach|add file|upload|lampir|unggah/i.test(l)&&vis(bs[i])){var r2=bs[i].getBoundingClientRect();return (r2.left+r2.width/2)+'|'+(r2.top+r2.height/2)+'|'+l;}}return '';})()"""
        writePromptStatus("running", "mencari tombol attach (${pendingPromptFiles?.size ?: 0} file)")
        webView.evaluateJavascript(js) { raw ->
            if (pendingPromptText == null) return@evaluateJavascript
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            val x = parts.getOrNull(0)?.toFloatOrNull()
            val y = parts.getOrNull(1)?.toFloatOrNull()
            val label = if (parts.size > 2) parts.drop(2).joinToString("|") else ""
            if (x == null || y == null || label.isBlank()) {
                failPrompt("tombol attach Claude tidak ketemu - cek /prompt-status tombol")
                return@evaluateJavascript
            }
            writePromptStatus("running", "tombol attach diketuk: $label @$x,$y")
            studioTapAt(x, y)
            promptHandler.postDelayed({
                clickAttachMenuItem {
                    if (pendingPromptText == null) return@clickAttachMenuItem
                    waitPromptChooser(20)
                }
            }, 1500)
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

    /** Langkah 3: isi teks prompt ke kolom chat (insertText).
     *  v0.1.61 - KOLOM DIBERSIHKAN DULU: isi lama dibaca sebelum isi
     *  baru. Kosong -> langsung isi. Sama persis dengan teks pending
     *  (sisa percobaan sebelumnya) -> hapus dulu (select-all +
     *  delete, fallback textContent='' + event input) lalu isi.
     *  Berisi teks LAIN -> gagal jujur "kolom chat berisi teks lain"
     *  + 80 karakter pertama — teks itu TIDAK disentuh dan TIDAK
     *  dikirim (insertText tanpa clear sebelumnya menempelkan teks
     *  baru ke sisa lama).
     *  v0.1.67 - ISI DIBERITAHUKAN KE FRAMEWORK (OPT-1):
     *  execCommand telanjang tidak memberitahu model ProseMirror
     *  Claude bahwa teks berubah — DOM berisi teks tapi tombol Send
     *  tetap nonaktif ("tombol kirim nonaktif"). Pola ttsFillJs:
     *  execCommand dulu; bila isi sesudahnya != teks yang diminta,
     *  fallback textContent + InputEvent('input',
     *  inputType:'insertText') + Event('input') + Event('change'),
     *  lalu isi ulang tertunda 300 ms bila framework menghapus
     *  nilainya. Verifikasi sesudah clear: clear yang gagal (isi
     *  lama masih ada) -> gagal jujur, jangan lanjut isi. Verifikasi
     *  akhir (runPromptFillVerify, 500 ms sesudah isi): innerText
     *  (normalisasi) HARUS == teks yang diminta DAN tombol Send ada
     *  dan tidak disabled — bila tidak, gagal jujur dengan bukti
     *  (isi kolom, 80 karakter pertama). */
    private fun runPromptFill() {
        val text = pendingPromptText ?: return
        val quoted = org.json.JSONObject.quote(text)
        val js = """(function(){var c=document.querySelector('div.ProseMirror[contenteditable="true"]')||document.querySelector('div[contenteditable="true"]');if(!c)return '0|nocomposer';var want=$quoted;var norm=function(s){return (s||'').replace(/\s+/g,' ').trim();};var cur=(c.innerText||'');if(norm(cur).length>0&&norm(cur)!==norm(want))return '0|other|'+norm(cur).replace(/\|/g,' ').slice(0,80);c.focus();if(norm(cur).length>0){try{document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);}catch(e){}if(norm(c.innerText||'').length>0){c.textContent='';try{c.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'deleteContent'}));}catch(e2){}c.dispatchEvent(new Event('input',{bubbles:true}));}if(norm(c.innerText||'').length>0)return '0|clearfail|'+norm(c.innerText||'').replace(/\|/g,' ').slice(0,80);}var ok=false;try{ok=document.execCommand('insertText',false,want);}catch(e){}if(!ok||norm(c.innerText||'')!==norm(want)){c.textContent=want;try{c.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'insertText',data:want}));}catch(e2){}c.dispatchEvent(new Event('input',{bubbles:true}));try{c.dispatchEvent(new Event('change',{bubbles:true}));}catch(e3){}try{setTimeout(function(){if(norm(c.innerText||'')!==norm(want)){c.textContent=want;try{c.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'insertText',data:want}));}catch(e4){}c.dispatchEvent(new Event('input',{bubbles:true}));}},300);}catch(e5){}}return '1|filled';})()"""
        writePromptStatus("running", "mengisi teks prompt")
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            when (parts.getOrNull(1)) {
                "other" -> failPrompt("kolom chat berisi teks lain: ${parts.drop(2).joinToString("|")}")
                "nocomposer" -> failPrompt("kolom chat tidak ketemu")
                "clearfail" -> failPrompt("teks lama di kolom chat tidak bisa dihapus: ${parts.drop(2).joinToString("|")}")
                else -> promptHandler.postDelayed({ runPromptFillVerify() }, 500)
            }
        }
    }

    /** Langkah 3b (v0.1.67, OPT-1): verifikasi akhir isi kolom
     *  sesudah jeda isi-ulang 300 ms — innerText (normalisasi)
     *  HARUS sama persis dengan teks pending DAN tombol Send ada
     *  dan tidak disabled. Gagal -> failPrompt jujur dengan bukti
     *  (isi kolom 80 karakter pertama / keadaan tombol), JANGAN
     *  lanjut ke runPromptSend. */
    private fun runPromptFillVerify() {
        val text = pendingPromptText ?: return
        val quoted = org.json.JSONObject.quote(text)
        val js = """(function(){var c=document.querySelector('div.ProseMirror[contenteditable="true"]')||document.querySelector('div[contenteditable="true"]');if(!c)return '0|nocomposer';var want=$quoted;var norm=function(s){return (s||'').replace(/\s+/g,' ').trim();};var cur=norm(c.innerText||'');if(cur!==norm(want))return '0|mismatch|'+cur.replace(/\|/g,' ').slice(0,80);var b=document.querySelector('button[aria-label="Send message"]')||document.querySelector('button[aria-label="Kirim pesan"]');if(!b)return '0|nosend';if(b.disabled)return '0|senddisabled';return '1|ok';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            when (parts.getOrNull(1)) {
                "ok" -> runPromptSend()
                "nocomposer" -> failPrompt("kolom chat tidak ketemu (sesudah isi teks)")
                "mismatch" -> failPrompt("teks tidak terdaftar di composer (isi kolom: ${parts.drop(2).joinToString("|")})")
                "nosend" -> failPrompt("tombol kirim tidak ketemu sesudah isi teks")
                "senddisabled" -> failPrompt("tombol kirim nonaktif sesudah isi teks (teks sudah terdaftar di composer)")
                else -> failPrompt("teks prompt tidak terverifikasi di kolom chat")
            }
        }
    }

    /** Langkah 4: klik tombol kirim. v0.1.61 - klik SAJA bukan bukti
     *  terkirim: sesudah klik, verifikasi lewat runPromptVerifySend
     *  (~10 dtk) sebelum menulis status "sent". Tombol kirim tidak
     *  ada karena Claude sedang menjawab (tombol berubah jadi Stop)
     *  -> gagal jujur "Claude sedang menjawab" (teks tetap di kolom
     *  chat — percobaan berikut akan menolaknya lewat aturan
     *  runPromptFill, bukan menimpanya diam-diam).
     *  v0.1.67 - SIDIK JARI SEBELUM KLIK (OPT-2): sebelum klik,
     *  hitung kemunculan teks prompt di body + jumlah elemen
     *  user-turn ([data-testid="user-message"]) — dikirim ke
     *  runPromptVerifySend sebagai baseline; verifikasi mewajibkan
     *  angka itu BERTAMBAH, supaya teks basi di pesan Claude
     *  sendiri ("tulis 'continue last checkpoint' lagi") tidak
     *  lagi lolos sebagai "sent" (false positive). */
    private fun runPromptSend() {
        val text = pendingPromptText ?: return
        val quoted = org.json.JSONObject.quote(text)
        val js = """(function(){var b=document.querySelector('button[aria-label="Send message"]')||document.querySelector('button[aria-label="Kirim pesan"]');if(!b){var s=document.querySelector('button[aria-label*="Stop"]')||document.querySelector('button[aria-label*="Berhenti"]');return s?'responding':'0';}if(b.disabled)return 'disabled';var body=document.body?document.body.innerText:'';var occ=body.split($quoted).length-1;var turns=document.querySelectorAll('[data-testid="user-message"]').length;b.click();return '1|'+occ+'|'+turns;})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            if (parts.getOrNull(0) == "1") {
                val occBefore = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val turnsBefore = parts.getOrNull(2)?.toIntOrNull() ?: 0
                writePromptStatus("running", "tombol kirim diklik - memverifikasi pesan terkirim")
                runPromptVerifySend(5, occBefore, turnsBefore)
            } else if (clean == "disabled") {
                failPrompt("tombol kirim nonaktif (teks/lampiran belum diterima Claude)")
            } else if (clean == "responding") {
                failPrompt("Claude sedang menjawab (pesan masih di kolom chat)")
            } else {
                failPrompt("tombol kirim tidak ketemu")
            }
        }
    }

    /** Langkah 4b: verifikasi kirim beneran (v0.1.61) — pesan
     *  dianggap terkirim hanya bila kolom chat sudah kosong DAN
     *  (teks tampil sebagai pesan user ATAU Claude mulai menjawab
     *  — tombol Stop ada). Poll 5x2 dtk (~10 dtk). Kolom masih berisi
     *  teks sesudah 10 dtk -> gagal "pesan tidak terkirim (masih di
     *  kolom chat)"; kolom kosong tapi tidak terverifikasi -> gagal
     *  "pesan tidak terverifikasi terkirim". Status "sent" TIDAK
     *  PERNAH ditulis sebelum verifikasi ini lolos.
     *  v0.1.67 - SIDIK JARI HARUS BERTAMBAH (OPT-2): selain gerbang
     *  di atas, jumlah kemunculan teks prompt di body ATAU jumlah
     *  elemen user-turn HARUS lebih besar dari baseline yang
     *  ditangkap runPromptSend SEBELUM klik — teks prompt yang
     *  sudah ada di halaman (mis. frasa "continue last checkpoint"
     *  di pesan Claude sendiri) tidak lagi cukup sebagai bukti. */
    private fun runPromptVerifySend(triesLeft: Int, occBefore: Int = 0, turnsBefore: Int = 0) {
        val text = pendingPromptText ?: return
        val quoted = org.json.JSONObject.quote(text)
        val js = """(function(){var c=document.querySelector('div.ProseMirror[contenteditable="true"]')||document.querySelector('div[contenteditable="true"]');var empty=!c||(c.innerText||'').trim()==='';if(!empty)return '0|incomposer';var stop=!!(document.querySelector('button[aria-label*="Stop"]')||document.querySelector('button[aria-label*="Berhenti"]'));var body=document.body?document.body.innerText:'';var occ=body.split($quoted).length-1;var turns=document.querySelectorAll('[data-testid="user-message"]').length;var grew=(occ>$occBefore)||(turns>$turnsBefore);var seen=occ>0;return (grew&&(stop||seen))?'1|ok':'0|unverified';})()"""
        webView.evaluateJavascript(js) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            if (parts.getOrNull(0) == "1") {
                promptRunning = false
                pendingPromptText = null
                pendingPromptFiles = null
                writePromptStatus("sent", "terkirim - Claude sedang menjawab")
            } else if (triesLeft > 1) {
                promptHandler.postDelayed({ runPromptVerifySend(triesLeft - 1, occBefore, turnsBefore) }, 2000)
            } else {
                if (parts.getOrNull(1) == "incomposer") {
                    failPrompt("pesan tidak terkirim (masih di kolom chat)")
                } else {
                    failPrompt("pesan tidak terverifikasi terkirim sesudah klik Send")
                }
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
        // v0.1.81: matikan watchdog + tenggat fallback saat run berakhir.
        ttsFallbackDeadlineMs = 0
        ttsHandler.removeCallbacks(ttsWatchdogRunnable)
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
        // v0.1.81 (A3): mulai anggaran watchdog anti-stall.
        ttsStartMs = System.currentTimeMillis()
        ttsEditorFound = false
        ttsCreateAttempts = 0
        ttsWatchdogFired = false
        ttsFallbackDeadlineMs = 0
        // v0.1.83: reset hitung siklus diagnose + flag rekam langsung.
        ttsDirectRecordStarted = false
        ttsDiagnoseCycles = 0
        ttsHandler.removeCallbacks(ttsWatchdogRunnable)
        ttsHandler.postDelayed(ttsWatchdogRunnable, 60000)
        writeTtsStatus("running", "mencari halaman TTS AI Studio")
        runTtsDiagnose(5)
    }

    /** Langkah 1 (v0.1.41): kenali halaman AI Studio.
     *  Balik "ta|run|dl|model|speaker|create|taLabels|btnLabels|path|hrs":
     *  ta = kolom isian terlihat, speaker = ada dropdown
     *  "Speaker 1" (editor dialog TTS), create = ada tombol
     *  "Create new dialog" (halaman utama TTS), path =
     *  location.pathname (bukti halaman /docs), hrs = tombol Run
     *  settings TERLIHAT (bukti halaman Playground lama).
     *  Keputusan: editor -> Style; ada Create -> klik itu;
     *  selain itu -> v0.1.83 (A1/A2): alur model HANYA bila hrs=1
     *  (Playground lama terbukti); bila di /docs atau di luar
     *  halaman TTS -> navigasi pulang ke beranda lalu diagnose
     *  ulang; bila di generate-speech tapi kartu belum ke-render
     *  (SPA lambat) -> retry terus sampai watchdog habis. */
    private fun runTtsDiagnose(triesLeft: Int) {
        if (pendingTtsScript == null) return
        if (checkTtsWatchdog()) return
        val js = """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});var taLabels=tas.map(function(t){return (t.getAttribute('aria-label')||t.getAttribute('placeholder')||'textarea').replace(/[|,]/g,' ');});var run=0,dl=0,model='';var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});var btnLabels=[];for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l)btnLabels.push(l.replace(/[|,]/g,' '));else if(tx&&tx.length<40)btnLabels.push(tx.replace(/[|,]/g,' '));if(!run&&(l==='Run'||l==='Jalankan'||tx==='Run'||tx==='Jalankan'))run=1;if(!dl&&(l==='Download'||l==='Unduh'||tx==='Download'||tx==='Unduh'))dl=1;if(!model&&/gemini/i.test(tx))model=tx.replace(/[|,]/g,' ').slice(0,60);}var bodyTxt=document.body?document.body.innerText||'':'';var speaker=bodyTxt.indexOf('Speaker 1')>=0?'1':'0';var createEl=0;var cands=[...document.querySelectorAll('button,[role="button"],a,div')].filter(function(e){return e.offsetParent!==null;});for(var ci=0;ci<cands.length;ci++){var ct=(cands[ci].textContent||'').trim();if(ct==='Create new dialog'||ct==='Buat dialog baru'){createEl=1;break;}}var create=(bodyTxt.indexOf('Create new dialog')>=0||bodyTxt.indexOf('Buat dialog baru')>=0||createEl===1)?'1':'0';var hrs=0;try{var rb=document.querySelector('button[aria-label="Toggle run settings panel"]');if(rb&&rb.offsetParent!==null)hrs=1;else{for(var k=0;k<bs.length;k++){var al=(bs[k].getAttribute('aria-label')||'').toLowerCase();if(al.indexOf('run settings')>=0||al.indexOf('pengaturan run')>=0){hrs=1;break;}}}}catch(e){}return tas.length+'|'+run+'|'+dl+'|'+model+'|'+speaker+'|'+create+'|'+taLabels.join(',')+'|'+btnLabels.join(',')+'|'+location.pathname+'|'+hrs;})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            val taCount = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val modelText = parts.getOrNull(3) ?: ""
            val speakerFlag = parts.getOrNull(4) ?: "0"
            val createFlag = parts.getOrNull(5) ?: "0"
            // v0.1.83 (A1): path halaman + bukti Playground lama.
            val urlPath = parts.getOrNull(8) ?: ""
            val hasRunSettings = parts.getOrNull(9) == "1"
            if (modelText.isNotBlank()) ttsActiveModel = modelText
            if (speakerFlag == "1" && taCount > 0) {
                // v0.1.81: editor ketemu — anggaran watchdog jadi 10 mnt.
                ttsEditorFound = true
                ttsCreateAttempts = 0
                writeTtsStatus(
                    "running",
                    "editor dialog TTS ketemu (kolom: ${parts.getOrElse(6) { "" }}; model aktif: $modelText)"
                )
                runTtsStyle()
            } else if (createFlag == "1") {
                writeTtsStatus("running", "halaman TTS ketemu - membuka dialog baru")
                runTtsCreateDialog()
            } else if (triesLeft > 1) {
                // v0.1.81 (A3): tulis status tiap percobaan supaya
                // beku langsung kelihatan (dulu retry ini diam).
                writeTtsStatus("running", "mencari halaman TTS AI Studio (percobaan ${6 - triesLeft}/5)")
                ttsHandler.postDelayed({ runTtsDiagnose(triesLeft - 1) }, 2000)
            } else {
                // v0.1.83 (A1/A2): 5 retry habis tanpa editor/kartu.
                // Alur model (v0.1.41, UI Playground lama) HANYA bila
                // tombol Run settings terbukti ada — di UI baru alur
                // itu jalan buntu (bukti live 24 Nov: 4x gagal di
                // /docs, "tombol Run settings tidak ketemu").
                if (hasRunSettings) {
                    writeTtsStatus("running", "menuju pemilihan model TTS (Run settings)")
                    runTtsModelFlow()
                    return@evaluateJavascript
                }
                ttsDiagnoseCycles++
                val onDocs = urlPath.contains("/docs")
                val onTtsPage = urlPath == "/" || urlPath == "/generate-speech" ||
                    urlPath.startsWith("/generate-speech")
                if (onDocs || !onTtsPage) {
                    // Halaman mental ke /docs (atau ke halaman AI
                    // Studio lain yang bukan TTS) — navigasi pulang
                    // ke beranda lalu diagnose ulang. Anggaran
                    // watchdog TIDAK di-reset: checkTtsWatchdog di
                    // atas yang menghentikan siklus bila 3 mnt habis.
                    val where = if (onDocs) "halaman /docs" else "halaman bukan TTS ($urlPath)"
                    writeTtsStatus("running", "$where — kembali ke beranda TTS (siklus $ttsDiagnoseCycles)")
                    if (::webView.isInitialized) webView.loadUrl("https://aistudio.google.com/")
                    ttsHandler.postDelayed({ runTtsDiagnose(5) }, 4000)
                } else {
                    // generate-speech tapi kartu belum ke-render (SPA
                    // lambat, 1-6 K/s) — retry terus sampai watchdog
                    // habis, JANGAN pindah ke alur model.
                    writeTtsStatus("running", "menunggu render halaman TTS (siklus $ttsDiagnoseCycles)")
                    ttsHandler.postDelayed({ runTtsDiagnose(5) }, 2000)
                }
            }
        }
    }

    /** v0.1.41 - ALUR MODEL ASLI:
     *  1) klik tombol Run settings (ikon slider, kanan atas) ->
     *  2) klik kartu model aktif -> 3) tab Starred -> klik entri
     *  model TTS -> 4) klik "Create new dialog" -> diagnose ulang.
     *  Hamburger kiri ("Open navigation menu") TIDAK dipakai:
     *  menunya tidak berisi entri TTS.
     *  v0.1.83 (A1): HANYA dipanggil bila runTtsDiagnose sudah
     *  membuktikan tombol Run settings TERLIHAT (hrs=1) — alur
     *  ini khusus Playground lama, bukan jalan buntu UI baru. */
    private fun runTtsModelFlow() {
        if (pendingTtsScript == null) return
        if (checkTtsWatchdog()) return
        val js = """(function(){var b=document.querySelector('button[aria-label="Toggle run settings panel"]');if(!b){var bs=[...document.querySelectorAll('button')];for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').toLowerCase();if(l.indexOf('run settings')>=0||l.indexOf('pengaturan run')>=0){b=bs[i];break;}}}if(!b)return '0';b.click();return '1';})()"""
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
        if (checkTtsWatchdog()) return
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
        if (checkTtsWatchdog()) return
        val want = pendingTtsModel ?: "Gemini 3.8 Flash Lite TTS"
        val quoted = org.json.JSONObject.quote(want)
        val js = """(function(){var want=$quoted.toLowerCase();var tabs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<tabs.length;i++){var tt=(tabs[i].textContent||'').trim();if(tt==='Starred'||tt==='Berbintang'){tabs[i].click();break;}}var els=[...document.querySelectorAll('*')].filter(function(e){return e.offsetParent!==null&&e.children.length===0;});var best=null,bestLen=1e9;for(var j=0;j<els.length;j++){var t=(els[j].textContent||'').trim();if(t.toLowerCase().indexOf(want)>=0&&t.length<bestLen){best=els[j];bestLen=t.length;}}if(!best)return '0';best.click();return best.textContent.trim().slice(0,60);})()"""
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

    /** Langkah 4 (v0.1.81): KETUK BENERAN kartu "Create new dialog",
     *  lalu diagnose ulang (editor dialog harus muncul: ada
     *  "Speaker 1" + kolom). Klik JS .click() TIDAK menavigasi kartu
     *  ini (kelas yang sama dengan attach prompt v0.1.80 — tap ADB
     *  manual 24 Nov membuktikan tap beneran yang membuka dialog).
     *  JS hanya mengembalikan pusat rect elemen TERLIHAT (vis() gaya
     *  Studio); Kotlin mengetuk via ttsTapAt yang sudah ada
     *  (dispatchTouchEvent DOWN+UP 120 ms, kali webView.scale seperti
     *  runTtsDownload). Maks 3 ketuk; sesudah itu fail dengan dump. */
    private fun runTtsCreateDialog() {
        if (pendingTtsScript == null) return
        if (checkTtsWatchdog()) return
        ttsCreateAttempts++
        if (ttsCreateAttempts > 3) {
            runTtsDumpInventory("kartu 'Create new dialog' sudah diketuk 3x tapi dialog tidak terbuka")
            return
        }
        val js = """(function(){var vis=function(e){var r=e.getBoundingClientRect();if(e.offsetParent===null)return false;if(r.width<=0||r.height<=0)return false;if(r.bottom<0||r.top>window.innerHeight||r.right<0||r.left>window.innerWidth)return false;var cs=getComputedStyle(e);return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity||'1')>0;};var els=[...document.querySelectorAll('button,[role="button"],a,div')].filter(vis);var best=null,bestDepth=-1;for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t==='Create new dialog'||t==='Buat dialog baru'){var d=0;var p=els[i];while(p){d++;p=p.parentElement;}if(d>bestDepth){best=els[i];bestDepth=d;}}}if(best){var r=best.getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2)+'|'+(best.textContent||'').trim().slice(0,40);}var els2=els.filter(function(e){var t=(e.textContent||'').trim();return t.length<60&&(t.toLowerCase().indexOf('create new dialog')>=0||t.toLowerCase().indexOf('buat dialog baru')>=0);});if(els2.length===0)return '0';var b2=els2[els2.length-1];var r2=b2.getBoundingClientRect();return (r2.left+r2.width/2)+'|'+(r2.top+r2.height/2)+'|'+(b2.textContent||'').trim().slice(0,40);})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            val parts = clean.split("|")
            if (parts.size < 3 || parts[0] == "0") {
                runTtsDumpInventory("tombol 'Create new dialog' tidak ketemu setelah pilih model")
                return@evaluateJavascript
            }
            val scale = webView.scale
            val cx = (parts[0].toFloatOrNull() ?: 0f) * scale
            val cy = (parts[1].toFloatOrNull() ?: 0f) * scale
            val label = parts.getOrNull(2) ?: ""
            writeTtsStatus("running", "kartu dialog baru diketuk: $label @${cx.toInt()},${cy.toInt()} (percobaan $ttsCreateAttempts/3)")
            ttsTapAt(cx, cy)
            ttsHandler.postDelayed({ runTtsDiagnose(5) }, 2000)
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
        if (checkTtsWatchdog()) return
        val voice = pendingTtsVoice ?: return runTtsSpeaker()
        val probe = voice.take(11) // "Very bright" — penanda isi menempel
        ttsStyleAttempt = 0
        // v0.1.49: style mungkin SUDAH menempel (diisi manual / run
        // sebelumnya) — tombol Style menampilkan formatnya, bukan
        // kata "Style". Bila label sudah berisi probe, lewati isi.
        webView.evaluateJavascript(ttsStyleButtonLabelJs(probe)) { raw ->
            val label = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (label.contains(probe)) {
                writeTtsStatus("running", "format suara sudah menempel (sebelum percobaan 1) - lanjut pilih speaker")
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
        return """(function(){var p=$q;var vis=function(e){return e.offsetParent!==null;};var txt=function(e){return (e.textContent||'').trim();};var ok=function(t){return t==='Style'||t==='Gaya'||t.indexOf('Style')===0||t.indexOf('Gaya')===0||t.indexOf(p)>=0;};var bs=[...document.querySelectorAll('button')].filter(vis);for(var i=0;i<bs.length;i++){var t=txt(bs[i]);if(ok(t)){bs[i].click();return 'btn';}}var rs=[...document.querySelectorAll('[role="button"]')].filter(vis);for(var j=0;j<rs.length;j++){var u=txt(rs[j]);if(ok(u)){rs[j].click();return 'role';}}var els=[...document.querySelectorAll('*')].filter(function(e){return vis(e)&&e.children.length===0;});for(var k=0;k<els.length;k++){if(txt(els[k])==='Style'||txt(els[k])==='Gaya'){els[k].click();return 'leaf';}}return '0';})()"""
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
     *  tidak sampai (dump v0.1.44: len=0 sesudah dua cara). */
    private fun waitTtsStyleField(triesLeft: Int, voice: String, probe: String, attempt: Int) {
        val findJs = """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0||p.indexOf('gaya suara')>=0)return '1';}return '0';})()"""
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
                            ttsStyleAttempt = attempt
                            writeTtsStatus("running", "format suara menempel (percobaan $attempt, tombol Style menampilkan format) - lanjut pilih speaker")
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
        return """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0||p.indexOf('gaya suara')>=0){tas[i].click();tas[i].focus();try{tas[i].setSelectionRange(0,tas[i].value.length);}catch(e){}try{tas[i].select();}catch(e){}return document.activeElement===tas[i]?'focus':'nofocus';}}return '0';})()"""
    }

    /** JS: posisi tengah kotak Style di layar (CSS px) — 'x|y'
     *  atau '0' bila kotaknya tidak ketemu. */
    private fun ttsStyleFieldRectJs(): String {
        return """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var p=(tas[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0){var r=tas[i].getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2);}}return '0';})()"""
    }

    /** Tap beneran ke satu titik di WebView (sentuhan DOWN+UP) —
     *  v0.1.48. Tap ini dikirim aplikasi ke WebView-nya sendiri
     *  (bukan ketuk layar HP oleh pengguna), koordinat dihitung dari
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
     *  Nilai hanya menempel lewat sesi IME hidup — tap ke kotak
     *  membuka keyboard + koneksi IME hidup, lalu paste/commitText
     *  lewat koneksi itu. Tap -> tunggu 1200 ms
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

    /** JS: klik tombol tutup sheet Speaker — v0.1.51. Sesudah pilih
     *  Sadaltager, sheet-nya TERTINGGAL kebuka (Gboard naik) dan
     *  eksekutor tidak pernah menutupnya — ditutup manual via X.
     *  Cari tombol tutup: aria-label berisi close/tutup, atau teks
     *  '×'/'✕'/'Close'. Hasil '1' (diklik) / '0' (tidak ketemu —
     *  lanjut saja, sheet kebuka tidak menghalangi isi naskah). */
    private fun ttsCloseSpeakerSheetJs(): String {
        return """(function(){var bs=[...document.querySelectorAll('button,[role="button"]')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var al=(bs[i].getAttribute('aria-label')||'').toLowerCase();if(al.indexOf('close')>=0||al.indexOf('tutup')>=0){bs[i].click();return '1';}}for(var j=0;j<bs.length;j++){var t=(bs[j].textContent||'').trim();if(t==='×'||t==='✕'||t==='Close'){bs[j].click();return '1';}}return '0';})()"""
    }

    /** JS: posisi tengah tombol unduh — v0.1.69.
     *  Dulu (v0.1.51): <button> berlabel/teks PERSIS 'Download'/'Unduh',
     *  ambil yang TERAKHIR di DOM. Tap 2x ke tombol itu tidak
     *  menghasilkan file (trap nol aktivitas).
     *  Kini: cocok juga aria-label yang MENGANDUNG 'download'/'unduh'
     *  (mis. 'Download audio') + elemen [role="button"], dan utamakan
     *  kandidat yang paling dekat dengan bar player — jarak vertikal
     *  ke tombol putar/jeda, fallback ke <audio> bila tingginya > 0;
     *  bila tidak ada jangkar, tetap ambil yang terakhir di DOM.
     *  Hasil 'x|y|count|dump' — dump = label@x,y tiap kandidat
     *  (maks 6, pisah ' ;; ') — atau '0|0' bila tidak ketemu. */
    private fun ttsDownloadButtonRectJs(): String {
        return """(function(){var els=[...document.querySelectorAll('button,[role="button"]')].filter(function(b){return b.offsetParent!==null;});var found=[];for(var i=0;i<els.length;i++){var l=(els[i].getAttribute('aria-label')||'').trim().toLowerCase();var tx=(els[i].textContent||'').trim().toLowerCase();if(l.indexOf('download')>=0||l.indexOf('unduh')>=0||(tx.length<=30&&(tx.indexOf('download')>=0||tx.indexOf('unduh')>=0))){found.push(els[i]);}}if(found.length===0)return '0|0';var anchor=null;for(var a=0;a<els.length;a++){var al=(els[a].getAttribute('aria-label')||'').toLowerCase();var at=(els[a].textContent||'').toLowerCase();if(al.indexOf('play')>=0||al.indexOf('pause')>=0||al.indexOf('putar')>=0||al.indexOf('jeda')>=0||at.indexOf('play_arrow')>=0||at.indexOf('pause_circle')>=0){anchor=els[a];break;}}var acy=null;if(anchor){var ra=anchor.getBoundingClientRect();acy=ra.top+ra.height/2;}else{var au=document.querySelector('audio');if(au){var ru=au.getBoundingClientRect();if(ru.height>0){acy=ru.top+ru.height/2;}}}var pick=found[found.length-1];if(acy!==null){var best=1e9;for(var j=0;j<found.length;j++){var rj=found[j].getBoundingClientRect();var d=Math.abs((rj.top+rj.height/2)-acy);if(d<best){best=d;pick=found[j];}}}var dump=[];for(var k=0;k<found.length&&k<6;k++){var rk=found[k].getBoundingClientRect();var lb=((found[k].getAttribute('aria-label')||'')||(found[k].textContent||'').trim()).replace(/\s+/g,' ').replace(/\|/g,'').slice(0,20);if(lb===''){lb=found[k].tagName;}dump.push(lb+'@'+Math.round(rk.left+rk.width/2)+','+Math.round(rk.top+rk.height/2));}var pr=pick.getBoundingClientRect();return (pr.left+pr.width/2)+'|'+(pr.top+pr.height/2)+'|'+found.length+'|'+dump.join(' ;; ');})()"""
    }

    /** Tutup sheet Speaker (best effort) lalu onDone(clicked) —
     *  v0.1.51. Gagal ketemu tombol tutup BUKAN gagal run: lanjut
     *  saja, naskah tetap bisa diisi dengan sheet kebuka. */
    private fun closeSpeakerSheet(onDone: (Boolean) -> Unit) {
        webView.evaluateJavascript(ttsCloseSpeakerSheetJs()) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            onDone(clean == "1")
        }
    }

    /** Langkah 6: pilih VOICE (nama) - klik dropdown "Speaker 1". */
    private fun runTtsSpeaker() {
        if (checkTtsWatchdog()) return
        writeTtsStatus("running", "membuka pilihan Speaker")
        val js = """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var sp=(bs[i].textContent||'');if(sp.indexOf('Speaker 1')>=0||sp.indexOf('Pembicara 1')>=0){bs[i].click();return '1';}}return '0';})()"""
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
                writeTtsStatus("running", "voice dipilih: $picked - menutup sheet Speaker lalu mengisi naskah")
                closeSpeakerSheet { closed ->
                    writeTtsStatus("running", "voice dipilih: $picked - sheet Speaker ${if (closed) "ditutup" else "tidak ketemu tombol tutupnya, lanjut"} - mengisi naskah")
                    ttsHandler.postDelayed({ runTtsScript() }, 800)
                }
            }
        }
    }

    /** JS isi satu kolom (voice = gaya suara, script = naskah):
     *  cari textarea yang cocok, isi lewat insertText (fallback ke
     *  native setter + event input untuk framework Angular/React).
     *  v0.1.44: untuk voice, setter + event SELALU dijalankan walau
     *  execCommand mengaku sukses (nilainya hilang), dan
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
        val js = """(function(role,text){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});var pick=null;if(role==='voice'){pick=tas.find(function(t){return (t.getAttribute('placeholder')||'').toLowerCase().indexOf('describe the voice style')>=0||(t.getAttribute('placeholder')||'').toLowerCase().indexOf('gaya suara')>=0;});if(!pick){pick=tas.find(function(t){var s=((t.getAttribute('aria-label')||'')+' '+(t.getAttribute('placeholder')||'')).toLowerCase();return s.indexOf('style')>=0||s.indexOf('voice')>=0||s.indexOf('describe')>=0||s.indexOf('tone')>=0||s.indexOf('gaya')>=0||s.indexOf('suara')>=0||s.indexOf('nada')>=0;});}}else{pick=tas.find(function(t){var s=((t.getAttribute('aria-label')||'')+' '+(t.getAttribute('placeholder')||'')).toLowerCase();return s.indexOf('text')>=0||s.indexOf('script')>=0||s.indexOf('enter')>=0||s.indexOf('speech')>=0||s.indexOf('tags')>=0;});if(!pick&&tas.length>0){pick=tas.reduce(function(a,b){return (b.offsetWidth*b.offsetHeight)>(a.offsetWidth*a.offsetHeight)?b:a;});}}if(!pick)return '0';pick.focus();try{pick.setSelectionRange(0,pick.value.length);}catch(e){}var ok=false;try{ok=document.execCommand('insertText',false,text);}catch(e){}if(role==='voice'||!ok||(pick.value||pick.textContent||'').length===0){if(pick.tagName==='TEXTAREA'){var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;setter.call(pick,text);}else if(pick.tagName==='INPUT'){var setter2=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;setter2.call(pick,text);}else{pick.textContent=text;}try{pick.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}pick.dispatchEvent(new Event('input',{bubbles:true}));try{pick.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}if(role==='voice'){try{setTimeout(function(){var tas2=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas2.length;i++){var p=(tas2[i].getAttribute('placeholder')||'').toLowerCase();if(p.indexOf('describe the voice style')>=0||p.indexOf('gaya suara')>=0){var s2=tas2[i];try{var setter2=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;setter2.call(s2,text);}catch(e){try{s2.value=text;}catch(e2){}}try{s2.dispatchEvent(new Event('input',{bubbles:true}));}catch(e3){}break;}}},300);}catch(e){}}}return String((pick.value||pick.textContent||'').length);})('$role',$quoted)"""
        return js
    }

    /** Langkah 4: isi naskah. */
    private fun runTtsScript() {
        if (checkTtsWatchdog()) return
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
        if (checkTtsWatchdog()) return
        val js = """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l==='Run'||l==='Jalankan'||tx==='Run'||tx==='Jalankan'){if(bs[i].disabled)return 'disabled';bs[i].click();return '1';}}return '0';})()"""
        writeTtsStatus("running", "menekan Run")
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "1") {
                ttsFilesBefore = try {
                    FileStore.listFiles(this, "aistudio").map { it.name }.toSet()
                } catch (_: Exception) {
                    emptySet()
                }
                ttsDownloadRetried = false
                ttsDownloadInfo = ""
                ttsDownloadCandidates = ""
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
        if (checkTtsWatchdog()) return
        val js = """(function(){var dl=0,ready=0;var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim();var tx=(bs[i].textContent||'').trim();if(l==='Download'||l==='Unduh'||tx==='Download'||tx==='Unduh'){dl=1;break;}}var aus=document.querySelectorAll('audio');for(var j=0;j<aus.length;j++){var a=aus[j];if((a.src||a.querySelector('source'))&&a.readyState===4&&a.duration>0){ready=1;break;}}return dl+'|'+ready;})()"""
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

    /** Langkah 7: TAP BENERAN tombol unduh (utamakan ikon di bar
     *  player), lalu tunggu file VO — v0.1.51 + v0.1.69. JS .click()
     *  pada tombol Download TIDAK PERNAH memicu blob — blob-debug.txt
     *  NOL aktivitas sesudah klik (INJECT ada, TRAP data / core: req
     *  tidak ada). Yang jalan adalah TAP BENERAN (tap jari di ikon
     *  unduh player, dan tap+IME di kotak Style v0.1.48).
     *  Jadi eksekutor kini: tutup sheet Speaker dulu (best effort,
     *  supaya tap tidak jatuh ke sheet) -> baca rect tengah tombol
     *  Download (v0.1.69: yang paling dekat bar player, bukan lagi
     *  TERAKHIR di DOM) -> dispatchTouchEvent DOWN+UP di titik itu
     *  (ttsTapAt, pola yang sama dengan tap kotak Style) -> tunggu
     *  file. RISIKO JUJUR: tap sintetis dari dalam app mungkin
     *  tetap tidak memicu handler yang sama dengan jari beneran —
     *  bila gagal, bukti ada di blob-debug.txt + ttsDownloadInfo
     *  + dump kandidat (ttsDownloadCandidates). */
    private fun runTtsDownload() {
        if (checkTtsWatchdog()) return
        closeSpeakerSheet { _ ->
            webView.evaluateJavascript(ttsDownloadButtonRectJs()) { raw ->
                val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
                val parts = clean.split("|")
                if (parts.size < 3 || parts[0] == "0") {
                    failTts("tombol unduh tidak ketemu")
                    return@evaluateJavascript
                }
                val scale = webView.scale
                val cx = (parts[0].toFloatOrNull() ?: 0f) * scale
                val cy = (parts[1].toFloatOrNull() ?: 0f) * scale
                // v0.1.69: parts[3] = dump kandidat (label@x,y, pisah ' ;; ')
                ttsDownloadCandidates = parts.getOrNull(3) ?: ""
                ttsDownloadInfo = "tombol unduh ditap beneran (1 dari ${parts[2]}, titik=${cx.toInt()}x${cy.toInt()})"
                writeTtsStatus("running", "mengunduh VO ($ttsDownloadInfo)")
                ttsTapAt(cx, cy)
                ttsHandler.postDelayed({ waitTtsFile(6) }, 1000)
            }
        }
    }

    private fun waitTtsFile(triesLeft: Int) {
        if (pendingTtsScript == null) return
        if (checkTtsWatchdog()) return
        val now = try {
            FileStore.listFiles(this, "aistudio")
        } catch (_: Exception) {
            emptyList()
        }
        val fresh = now.firstOrNull { f ->
            f.isFile && f.name !in ttsFilesBefore &&
                (f.name.endsWith(".wav", ignoreCase = true) ||
                    f.name.endsWith(".mp3", ignoreCase = true) ||
                    f.name.endsWith(".ogg", ignoreCase = true) ||
                    f.name.endsWith(".webm", ignoreCase = true))
        }
        if (fresh != null) {
            ttsRunning = false
            pendingTtsScript = null
            // v0.1.81: matikan watchdog + tenggat fallback saat done.
            ttsFallbackDeadlineMs = 0
            ttsHandler.removeCallbacks(ttsWatchdogRunnable)
            val styleNote = if (ttsStyleAttempt == 0) "Style sudah menempel" else "Style percobaan $ttsStyleAttempt"
            // v0.1.84 (C): file .webm HANYA datang dari rekam
            // langsung — unduhan AI Studio asli .wav. File rekam
            // HANYA boleh "done" bila gate lolos; 0-byte / verdict
            // gagal / verdict tidak diterima -> gagal jujur, jangan
            // pernah "done" palsu (bukti live v0.1.83: file 0-byte
            // dilaporkan done).
            val fromRecorder = fresh.name.endsWith(".webm", ignoreCase = true)
            if (fromRecorder && fresh.length() == 0L) {
                failTts("fallback: rekaman kosong (0 byte) — file VO tidak tersimpan")
            } else if (fromRecorder && ttsRecordGateVerdict != "ok") {
                val detail = if (ttsRecordGateVerdict.isNotEmpty()) ttsRecordGateDetail else "rekaman tidak lengkap (verdict tidak diterima)"
                failTts("fallback: $detail — file VO tidak tersimpan")
            } else {
                val gateNote = if (fromRecorder) " (${ttsRecordGateDetail})" else ""
                writeTtsStatus("done", "VO tersimpan: ${fresh.name} (${fresh.length()} byte)$gateNote ($styleNote)")
            }
        } else if (ttsFallbackDeadlineMs > 0 && System.currentTimeMillis() < ttsFallbackDeadlineMs) {
            // v0.1.81 (B4): fallback rekam-ulang audio sedang berjalan
            // (real-time, durasi audio) — terus tunggu tanpa
            // menghabiskan triesLeft.
            ttsHandler.postDelayed({ waitTtsFile(triesLeft) }, 5000)
        } else if (triesLeft <= 3 && !ttsDirectRecordStarted) {
            // v0.1.83 (B1): 15 dtk berlalu tanpa file baru SETELAH
            // tap unduh — di UI baru tombol unduh player MATI
            // (tidak memicu event unduh; downloadBlob tidak pernah
            // dipanggil; bukti live 24 Nov). Jangan tunggu event
            // yang tidak datang: rekam langsung dari player.
            ttsDirectRecordStarted = true
            writeTtsStatus("running", "tombol unduh tidak memicu unduhan — merekam ulang audio dari player (langsung)")
            startDirectRecord(ttsDirectRecordFileName(), manual = false)
            ttsHandler.postDelayed({ waitTtsFile(triesLeft - 1) }, 5000)
        } else if (triesLeft <= 0) {
            if (!ttsDownloadRetried) {
                // v0.1.51: tap pertama belum memunculkan file — tap
                // sekali lagi (bisa saja tap pertama jatuh ke sheet
                // yang belum ketutup / halaman masih animasi).
                ttsDownloadRetried = true
                writeTtsStatus("running", "file VO belum muncul - tap tombol unduh sekali lagi")
                runTtsDownload()
            } else {
                // v0.1.69: detail gagal membawa dump kandidat tombol
                // unduh supaya ketahuan tombol mana yang ditap.
                // v0.1.81 (B3): + diagnosa blob terakhir.
                val dumpNote = if (ttsDownloadCandidates.isNotBlank()) " Kandidat unduh: $ttsDownloadCandidates" else ""
                val blobNote = if (lastBlobError.isNotBlank()) " Diagnosa blob: $lastBlobError" else ""
                failTts("${ttsDownloadInfo.ifBlank { "tombol unduh ditap" }} tapi file VO tidak muncul di folder aistudio (cek _log-blob/blob-debug.txt).$dumpNote$blobNote")
            }
        } else {
            ttsHandler.postDelayed({ waitTtsFile(triesLeft - 1) }, 5000)
        }
    }
    // ============ REKAM LANGSUNG + ENDPOINT /tts-record (v0.1.83) ============

    private fun ttsRecordStatusFile(): java.io.File =
        java.io.File(filesDir, "api-ttsrecord-$dataDirectorySuffix.json")

    private fun writeTtsRecordStatus(state: String, message: String, file: String = "", bytes: Long = 0) {
        try {
            val json = org.json.JSONObject().apply {
                // Skema SAMA dengan api-tts-*.json (status/detail/
                // updated) supaya /tts-record-status dibaca pembaca
                // yang sama; file + bytes tambahan bila ada.
                put("status", state)
                put("detail", message)
                put("updated", System.currentTimeMillis())
                if (file.isNotEmpty()) put("file", file)
                if (bytes > 0) put("bytes", bytes)
            }
            ttsRecordStatusFile().writeText(json.toString())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "gagal tulis status tts-record: ${e.message}")
        }
    }

    /** Nama standar jalur unduh TTS (dipakai juga rekam langsung
     *  di tengah run TTS): "Generated Audio <M d, yyyy - h:mma>"
     *  + .webm. */
    private fun ttsDirectRecordFileName(): String {
        val fmt = java.text.SimpleDateFormat("MMMM d, yyyy - h:mma", java.util.Locale.US)
        return "Generated Audio ${fmt.format(java.util.Date())}.webm"
    }

    /** JS rekam langsung (v0.1.84, tulis ulang dari v0.1.83):
     *  ASINKRON — mengembalikan 'pending' (bukan 'started'):
     *  rantai metadata -> play -> rekam berjalan lewat
     *  setTimeout/setInterval dan melaporkan hasil via bridge
     *  onBlobFallbackStart (rekam mulai) / onBlobRecordFail (gagal)
     *  / onBlobRecordDone (verdict gate saat stop). 'no-audio' /
     *  'no-src' tetap sinkron (elemen tidak ketemu / tanpa src).
     *  (A) STABLE ELEMENT: findAudio rekursif (document + semua
     *  shadow root, v0.1.82) menemukan elemen SPA, lalu dibuat
     *  elemen <audio> BARU di main frame (document.body, hidden,
     *  DI LUAR tree React SPA) dengan src = blob URL yang sama —
     *  SPA tidak bisa mengganti / me-pause elemen ini (bukti live
     *  v0.1.83: rekam dari elemen SPA cuma menangkap 10,3 dtk dari
     *  68 dtk). Bila stable-element gagal load (error event, mis.
     *  blob cross-origin iframe sandbox) -> fallback ke elemen SPA
     *  dengan monitor yang sama.
     *  (D) TUNGGU METADATA: polling readyState>=1 + durasi finite
     *  >0 tiap 250 ms, timeout 10 dtk -> 'metadata-timeout'
     *  (bukti live v0.1.83: rekam in-run baca durasi 1 dtk karena
     *  metadata belum ke-load -> file 0-byte). play() diverifikasi
     *  (tidak paused + currentTime maju dalam 3 dtk) -> bila
     *  ditolak / tidak main -> 'play-denied' / 'not-playing'.
     *  (B) MONITOR tiap 1 dtk ke blob-debug
     *  ('record-monitor: t=.. ct=.. paused=.. ended=..
     *  attached=.. chunks=.. bytes=..'); pause yang belum ended ->
     *  play() lagi ('re-play'); elemen SPA ter-detach ->
     *  'spa-detached' (gagal jujur, jangan simpan pendek diam-diam).
     *  (C) GATE di onstop: verdict 'ok' hanya bila bytes>0 DAN
     *  (ended ATAU currentTime>=durasi-1 ATAU wall>=durasi);
     *  'empty' (0 byte) / 'short' -> onBlobRecordDone saja, TANPA
     *  onBlobData (tidak ada file yang boleh jadi "done").
     *  window.__senzBlobDone di-reset false saat rekam mulai
     *  (unduhan sukses sebelumnya di sesi yang sama bisa
     *  meninggalkan flag true dan membuang rekaman);
     *  perlindungan ganda-simpan tetap jalan (yang selesai
     *  duluan menang). __NAME_JSON__ diganti
     *  org.json.JSONObject.quote(fileName). */
    private fun directRecordJs(fileName: String): String {
        val nameJson = org.json.JSONObject.quote(fileName)
        return """(function(){
            function diag(s){try{window.SenzBlob.onBlobDiag(s);}catch(e){}}
            function findAudio(root){
                if(!root)return null;
                if(root.tagName==='AUDIO')return root;
                var a=root.querySelector?root.querySelector('audio'):null;
                if(a)return a;
                var els=root.querySelectorAll?root.querySelectorAll('*'):null;
                if(els){for(var i=0;i<els.length;i++){var el=els[i];if(el.shadowRoot){var f=findAudio(el.shadowRoot);if(f)return f;}}}
                if(root.shadowRoot)return findAudio(root.shadowRoot);
                return null;
            }
            var spa=findAudio(document);
            if(!spa){return 'no-audio';}
            var spaSrc='';
            try{spaSrc=spa.currentSrc||spa.src||'';}catch(e){}
            var spaDur=0;
            try{spaDur=spa.duration||0;}catch(e){}
            var spaHost='';
            try{spaHost=spaSrc.split('/')[2]||'';}catch(e){}
            diag('record: elemen SPA host='+spaHost+' dur='+(isFinite(spaDur)?spaDur.toFixed(1):'NaN'));
            if(!spaSrc){return 'no-src';}
            var au=null;var stable=false;var rec=null;var dead=false;var stopped=false;var started=false;
            var dur=0;var chunks=[];var chunkBytes=0;var startWall=0;var monTimer=null;var recStarted=false;
            function failAsync(reason){
                if(dead)return;dead=true;
                if(monTimer){clearInterval(monTimer);monTimer=null;}
                try{if(rec&&rec.state!=='inactive'){rec.stop();}}catch(e){}
                try{window.SenzBlob.onBlobRecordFail(reason);}catch(e){}
            }
            function startRec(d){
                if(dead||started)return;started=true;dur=d;recStarted=true;
                diag('record: metadata ok dur='+d.toFixed(1)+' stable='+(stable?'ya':'tidak'));
                window.__senzBlobDone=false;
                var stream=null;
                try{stream=au.captureStream?au.captureStream():(au.mozCaptureStream?au.mozCaptureStream():null);}catch(e){}
                if(!stream){failAsync('no-stream');return;}
                var mime='';
                try{if(window.MediaRecorder&&MediaRecorder.isTypeSupported('audio/webm;codecs=opus'))mime='audio/webm;codecs=opus';}catch(e){}
                try{rec=mime?new MediaRecorder(stream,{mimeType:mime}):new MediaRecorder(stream);}catch(e){failAsync('no-recorder');return;}
                startWall=Date.now();
                rec.ondataavailable=function(e){if(e.data&&e.data.size){chunks.push(e.data);chunkBytes+=e.data.size;}};
                rec.onerror=function(){failAsync('recorder-error');};
                rec.onstop=function(){
                    if(dead)return;
                    try{
                        var ct=0;try{ct=au.currentTime||0;}catch(e){}
                        var ended=false;try{ended=!!au.ended;}catch(e){}
                        var b=new Blob(chunks,{type:rec.mimeType||'audio/webm'});
                        var ok=b.size>0&&(ended||ct>=dur-1||(Date.now()-startWall)/1000>=dur);
                        var verdict=ok?'ok':(b.size===0?'empty':'short');
                        try{window.SenzBlob.onBlobRecordDone(String(Math.round(ct*10)/10),String(Math.round(dur*10)/10),String(chunks.length),String(b.size),verdict);}catch(e){}
                        if(!ok){return;}
                        var r=new FileReader();
                        r.onloadend=function(){if(dead)return;if(window.__senzBlobDone)return;window.__senzBlobDone=true;var s=r.result||'';var i=s.indexOf(',');window.SenzBlob.onBlobData(i>=0?s.substring(i+1):s,__NAME_JSON__);};
                        r.onerror=function(){try{window.SenzBlob.onBlobError('fallback: reader-error (langsung)');}catch(e){}};
                        r.readAsDataURL(b);
                    }catch(e){try{window.SenzBlob.onBlobError('fallback: '+(e.message||e)+' (langsung)');}catch(e2){}}
                };
                var maxMs=Math.min(300000,(dur+15)*1000);
                var stopOnce=function(){if(stopped)return;stopped=true;try{rec.stop();}catch(e){}try{au.pause();}catch(e){}};
                try{au.onended=function(){stopOnce();};}catch(e){}
                setTimeout(stopOnce,maxMs);
                monTimer=setInterval(function(){
                    if(dead||stopped){if(monTimer){clearInterval(monTimer);monTimer=null;}return;}
                    var ct=0,paused=true,ended=false,attached=false;
                    try{ct=au.currentTime||0;}catch(e){}
                    try{paused=!!au.paused;}catch(e){}
                    try{ended=!!au.ended;}catch(e){}
                    try{attached=document.contains(au);}catch(e){}
                    diag('record-monitor: t='+Math.round((Date.now()-startWall)/1000)+'s ct='+ct.toFixed(1)+' paused='+paused+' ended='+ended+' attached='+attached+' chunks='+chunks.length+' bytes='+chunkBytes);
                    if(!attached&&!stable){failAsync('spa-detached');return;}
                    if(paused&&!ended){try{var pp=au.play();if(pp&&pp.catch){pp.catch(function(){});}}catch(e){}diag('record: re-play ct='+ct.toFixed(1));}
                    if(ended){stopOnce();}
                },1000);
                try{au.currentTime=0;}catch(e){}
                var p=null;
                try{p=au.play();}catch(e){failAsync('play-denied');return;}
                if(p&&p.catch){p.catch(function(e){failAsync('play-denied');});}
                setTimeout(function(){
                    if(dead||stopped)return;
                    var ct=0,paused=true,ended=false;
                    try{ct=au.currentTime||0;}catch(e){}
                    try{paused=!!au.paused;}catch(e){}
                    try{ended=!!au.ended;}catch(e){}
                    if(ended)return;
                    if(paused||ct<=0){failAsync('not-playing');return;}
                    diag('record: main terverifikasi ct='+ct.toFixed(1));
                },3000);
                try{rec.start(1000);}catch(e){failAsync('rec-start-fail');return;}
                diag('fallback: rekam langsung durasi='+Math.ceil(dur)+' dtk');
                try{window.SenzBlob.onBlobFallbackStart(String(Math.ceil(dur)));}catch(e){}
            }
            var waited=0;
            function metaPoll(){
                if(dead||started)return;
                var d=0,rs=0;
                try{d=au.duration||0;}catch(e){}
                try{rs=au.readyState||0;}catch(e){}
                if(isFinite(d)&&d>0&&rs>=1){startRec(d);return;}
                waited+=250;
                if(waited>=10000){failAsync('metadata-timeout');return;}
                setTimeout(metaPoll,250);
            }
            try{
                var old=document.getElementById('senz-stable-audio');
                if(old){try{old.pause();}catch(e){}try{old.remove();}catch(e){}}
                var el=document.createElement('audio');
                el.id='senz-stable-audio';
                el.style.cssText='display:none;position:absolute;left:-9999px;top:0;width:1px;height:1px;';
                el.preload='auto';
                el.src=spaSrc;
                el.addEventListener('error',function(){
                    if(dead||recStarted||!stable)return;
                    diag('fallback: stable-element error, pakai elemen SPA');
                    try{el.remove();}catch(e){}
                    au=spa;stable=false;waited=0;metaPoll();
                });
                if(document.body){document.body.appendChild(el);}else{document.documentElement.appendChild(el);}
                au=el;stable=true;
                diag('record: stable-element dibuat');
            }catch(e){
                diag('fallback: rekam dari elemen SPA (stable-element gagal: '+(e.message||e)+')');
                au=spa;stable=false;
            }
            metaPoll();
            return 'pending';
        })()""".replace("__NAME_JSON__", nameJson)
    }

    /** Pemicu rekam langsung. manual=true: dari endpoint
     *  /tts-record (boleh tanpa pendingTtsScript); manual=false:
     *  dari waitTtsFile di tengah run TTS. */
    private fun startDirectRecord(fileName: String, manual: Boolean) {
        ttsRecordManual = manual
        ttsRecordFileName = fileName
        ttsRecordPendingFile = fileName
        // v0.1.84: reset verdict gate tiap rekam — verdict basi
        // tidak boleh memberkati file rekam berikutnya.
        ttsRecordGateVerdict = ""
        ttsRecordGateDetail = ""
        ttsRecordGateCapturedSec = 0.0
        ttsRecordGateDurationSec = 0.0
        ttsRecordGateChunks = 0
        ttsRecordGateBytes = 0
        if (manual) writeTtsRecordStatus("running", "merekam ulang audio dari player", fileName)
        webView.evaluateJavascript(directRecordJs(fileName)) { raw ->
            val r = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (r == "started" || r == "pending") {
                // v0.1.84: 'pending' = rantai async (metadata ->
                // play -> rekam) berjalan; hasil dilaporkan via
                // bridge onBlobFallbackStart (mulai) /
                // onBlobRecordFail (gagal) / onBlobRecordDone
                // (verdict gate). Tidak ada yang dilakukan di sini.
                return@evaluateJavascript
            }
            val reason = when (r) {
                "no-audio" -> "tidak ada <audio> di halaman"
                "no-src" -> "elemen audio tanpa src"
                "no-stream" -> "captureStream tidak tersedia"
                "no-recorder" -> "MediaRecorder tidak bisa dibuat"
                "play-denied" -> "play audio ditolak halaman"
                "rec-start-fail" -> "rec.start gagal"
                else -> "rekam langsung gagal ($r)"
            }
            val msg = "fallback: $reason (langsung)"
            logBlobDiag(msg)
            if (manual) {
                ttsRecordManual = false
                writeTtsRecordStatus("failed", reason)
            } else {
                // Run TTS: gagal jujur SEKARANG (menunggu percobaan
                // waitTtsFile sia-sia — perekam sudah pasti tidak
                // jalan). failTts membersihkan pendingTtsScript
                // sehingga waitTtsFile yang sudah terjadwal langsung
                // kembali tanpa aksi.
                failTts("$msg — file VO tidak tersimpan")
            }
        }
    }

    /** Ambil perintah rekam manual dari intent (MainActivity
     *  EXTRA_API_TTS_RECORD_FILE). Dipanggil di onCreate +
     *  onNewIntent — TANPA payload TTS, TANPA pendingTtsScript.
     *  Bila halaman AI Studio SUDAH termuat (onNewIntent ke Space
     *  yang sedang aktif, progress 100) -> rekam sekarang.
     *  Bila halaman belum termuat (activity baru / restore) ->
     *  biarkan tertunda; onPageFinished memanggil
     *  maybeStartTtsRecord(). */
    private fun consumeApiTtsRecord(intent: Intent?) {
        val recFile = intent?.getStringExtra(MainActivity.EXTRA_API_TTS_RECORD_FILE) ?: return
        intent.removeExtra(MainActivity.EXTRA_API_TTS_RECORD_FILE)
        // Hanya Space AI Studio (suffix "aistudio"); slot lain sudah
        // ditolak 400 di ApiServer — ini pertahanan lapis kedua.
        if (dataDirectorySuffix != "aistudio") {
            writeTtsRecordStatus("failed", "Space $dataDirectorySuffix bukan AI Studio")
            return
        }
        ttsRecordPendingFile = recFile
        if (pendingTtsScript == null && ::webView.isInitialized) {
            val cur = try { webView.url ?: "" } catch (e: Exception) { "" }
            if (cur.contains("aistudio.google.com") && webView.progress >= 100) {
                maybeStartTtsRecord()
            }
        }
    }

    /** Jalankan rekam manual bila ada perintah tertunda DAN halaman
     *  AI Studio sudah termuat. Dipanggil dari consumeApiTtsRecord
     *  dan onPageFinished (di samping maybeRunApiTts). Jeda 3 dtk
     *  memberi waktu render SPA (player/audio belum tentu ada
     *  saat onPageFinished). */
    private fun maybeStartTtsRecord() {
        val recFile = ttsRecordPendingFile ?: return
        if (!::webView.isInitialized) return
        // Hanya Space AI Studio (suffix "aistudio"); host dicek di
        // ApiServer juga.
        if (dataDirectorySuffix != "aistudio") return
        val cur = try { webView.url ?: "" } catch (e: Exception) { "" }
        if (!cur.contains("aistudio.google.com")) return
        ttsRecordPendingFile = null
        ttsHandler.postDelayed({ startDirectRecord(recFile, manual = true) }, 3000)
    }

    // ================= AKHIR TTS API =================


    // ================= STUDIO API (v0.1.60) =================
    // Eksekutor YouTube Studio di WebView Space ini (POST /studio).
    // Alur sama seperti PROMPT API: consumeApiStudio (intent extra)
    // -> maybeRunApiStudio (dipanggil tiap onPageFinished) -> rantai
    // langkah per aksi -> status ke api-studio-<suffix>.json (filesDir
    // internal, dibaca ApiServer untuk /studio-status). Aturan host:
    // hanya studio.youtube.com (dicek ApiServer + maybeRunApiStudio);
    // Space yang belum login (halaman sign-in Google) gagal jujur.
    // Aksi: dashboard (ringkasan channel), videos (daftar Content),
    // upload (file _inbox + judul + deskripsi + jadwal "now" atau
    // "YYYY-MM-DD HH:MM" zona waktu akun Studio). Upload: diagnose
    // -> Create -> Upload videos -> chooser sodorkan file _inbox
    // (cabang onShowFileChooser yang sama seperti prompt) -> isi judul
    // + deskripsi -> audience -> tunggu pemrosesan -> Next x3 ->
    // Visibility (Schedule/Public) -> Schedule/Publish -> VERIFIKASI
    // video muncul di daftar Content (cocok judul persis) baru status
    // done + videoUrl. Klik tombol saja BUKAN bukti (pelajaran "sent"
    // palsu prompt API). Selektor dwi-bahasa EN+ID (pola v0.1.55).
    // HANYA atas perintah eksplisit per tugas (POST /studio) - tidak
    // ada auto-upload. JS di bawah TIDAK PERNAH diuji live dari VM —
    // tiap langkah verifikasi keadaan berikut + gagal jujur dengan
    // nama langkah + dump kontrol bila kontrol tidak ketemu.

    private fun studioStatusFile(): java.io.File =
        java.io.File(filesDir, "api-studio-$dataDirectorySuffix.json")

    private fun writeStudioStatus(status: String, detail: String, data: org.json.JSONObject? = null) {
        try {
            val obj = org.json.JSONObject().apply {
                put("status", status)
                put("detail", detail)
                put("updated", System.currentTimeMillis())
                put("action", pendingStudioAction ?: "")
                if (data != null) {
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, data.get(k))
                    }
                }
            }
            studioStatusFile().writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    private fun failStudio(detail: String) {
        studioRunning = false
        pendingStudioAction = null
        pendingStudioFile = null
        pendingStudioTitle = null
        pendingStudioDescription = null
        pendingStudioSchedule = null
        studioUploadFileName = null
        writeStudioStatus("failed", detail)
    }

    private fun consumeApiStudio(intent: Intent) {
        val action = intent.getStringExtra(MainActivity.EXTRA_API_STUDIO_ACTION)
            ?: return
        intent.removeExtra(MainActivity.EXTRA_API_STUDIO_ACTION)
        val fileName = intent.getStringExtra(MainActivity.EXTRA_API_STUDIO_FILE)
        intent.removeExtra(MainActivity.EXTRA_API_STUDIO_FILE)
        val title = intent.getStringExtra(MainActivity.EXTRA_API_STUDIO_TITLE)
        intent.removeExtra(MainActivity.EXTRA_API_STUDIO_TITLE)
        val description = intent.getStringExtra(MainActivity.EXTRA_API_STUDIO_DESCRIPTION)
        intent.removeExtra(MainActivity.EXTRA_API_STUDIO_DESCRIPTION)
        val schedule = intent.getStringExtra(MainActivity.EXTRA_API_STUDIO_SCHEDULE)
        intent.removeExtra(MainActivity.EXTRA_API_STUDIO_SCHEDULE)
        if (action != "dashboard" && action != "videos" && action != "upload") {
            writeStudioStatus("failed", "aksi tidak dikenal: $action")
            return
        }
        // upload: file harus masih ada di _inbox (validasi ulang —
        // pola v0.1.56); judul wajib (maks 100 karakter, dicek ApiServer).
        var file: java.io.File? = null
        if (action == "upload") {
            if (fileName.isNullOrBlank()) {
                writeStudioStatus("failed", "upload butuh nama file di _inbox")
                return
            }
            val f = java.io.File(FileStore.folderDir(this, "_inbox"), fileName)
            if (!f.isFile) {
                writeStudioStatus("failed", "file upload tidak ketemu di _inbox: $fileName")
                return
            }
            file = f
            if (title.isNullOrBlank()) {
                writeStudioStatus("failed", "upload butuh judul video")
                return
            }
        }
        pendingStudioAction = action
        pendingStudioFile = file
        pendingStudioTitle = title
        pendingStudioDescription = description
        pendingStudioSchedule = schedule ?: "now"
        // Nama file disimpan terpisah: pendingStudioFile dikosongkan
        // cabang chooser sesudah disodorkan, sementara tunggu dialog
        // masih butuh nama file untuk verifikasi kemunculannya.
        studioUploadFileName = fileName
        studioRunning = false
        studioChooserServed = false
        studioSelectFilesClicked = false
        studioDashboardEmptyRetried = false
        studioVideoId = null
        studioChannelId = null
        writeStudioStatus("queued", "payload Studio diterima Space $dataDirectorySuffix")
        // dashboard/videos: mulai dari dasbor Studio; upload: pakai
        // halaman Studio yang sedang dibuka (tombol Create ada di
        // semua halaman Studio).
        if (action != "upload") {
            val home = "https://studio.youtube.com/"
            if (::webView.isInitialized && currentUrl != home) webView.loadUrl(home)
            else maybeRunApiStudio()
        } else {
            maybeRunApiStudio()
        }
    }

    private fun maybeRunApiStudio() {
        val action = pendingStudioAction ?: return
        if (studioRunning) return
        if (!::webView.isInitialized) return
        val host = try {
            android.net.Uri.parse(currentUrl).host
        } catch (_: Exception) {
            null
        }
        if (host == null) return
        if (host != "studio.youtube.com") {
            failStudio("bukan halaman YouTube Studio: $currentUrl (mungkin belum login di Space ini)")
            return
        }
        studioRunning = true
        when (action) {
            "dashboard" -> {
                writeStudioStatus("running", "memeriksa interstitial Studio")
                runStudioDismissInterstitial({
                    writeStudioStatus("running", "membaca dasbor channel")
                    runStudioDashboard(2)
                })
            }
            "videos" -> {
                writeStudioStatus("running", "memeriksa interstitial Studio")
                runStudioDismissInterstitial({
                    writeStudioStatus("running", "membuka halaman Content")
                    runStudioGoContent({ runStudioVideos(5) })
                })
            }
            "upload" -> {
                writeStudioStatus("running", "memeriksa interstitial Studio")
                runStudioDismissInterstitial({
                    writeStudioStatus("running", "mencari tombol Create di Studio")
                    runStudioUploadDiagnose(5)
                })
            }
        }
    }

    /** Tap beneran di koordinat WebView (pola ttsTapAt v0.1.48):
     *  dispatchTouchEvent DOWN + UP (120 ms). Dipakai fallback klik
     *  tombol Schedule/Publish bila JS .click() diam, dan sejak
     *  v0.1.65 juga tombol Create (menu tidak kebuka sesudah klik
     *  JS). Tap fallback selalu SEKALI per pemanggilan (tombol
     *  satu-kali; klik ganda menu/Next bisa menutup menu atau
     *  melompati langkah). v0.1.66: sebelum tap, keadaan
     *  diverifikasi sekali lagi di studioClickThenVerify — tap tidak
     *  jalan bila menu sudah kebuka (klik JS lambat). */
    private fun studioTapAt(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()
        studioHandler.postDelayed({
            val up = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 120)
    }

    /** JS: cari tombol terlihat dari daftar label (aria-label persis
     *  ATAU teks persis, tanpa peduli huruf besar-kecil — pola tiga
     *  lapis v0.1.46). v0.1.66: pilih kandidat yang BENAR-BENAR
     *  terlihat — offsetParent non-null saja tidak cukup (elemen di
     *  drawer/menu tersembunyi atau di luar viewport tetap lolos,
     *  jadi klik JS + tap beneran bisa mendarat di tombol yang tidak
     *  pernah dilihat pengguna). Syarat terlihat: offsetParent
     *  non-null DAN rect di dalam viewport (lebar/tinggi > 0, tidak
     *  di luar layar) DAN computed style visibility/display DAN
     *  opacity > 0. Bila ada beberapa kandidat, yang terlihat
     *  didahulukan (urutan DOM di antara yang terlihat). Bila tidak
     *  ada yang terlihat: "0|". Mode "click": klik dan balik
     *  "1|label"; mode "rect": balik "x|y|label" titik tengah
     *  (CSS px) — rect yang dipakai tap adalah rect elemen terlihat
     *  yang sama. Tombol yang nonaktif dilewati (kliknya tidak
     *  berbuat apa-apa). dialogScope=true mencari di dalam dialog
     *  upload (ytcp-uploads-dialog) supaya tidak kena tombol halaman
     *  di belakang modal. */
    private fun studioButtonJs(mode: String, labelsJson: String, dialogScope: Boolean): String {
        val root = if (dialogScope) "var root=document.querySelector('ytcp-uploads-dialog')||document;" else "var root=document;"
        return """(function(mode,labels){$root function vis(b){if(b.offsetParent===null)return false;var r=b.getBoundingClientRect();if(r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=window.innerHeight||r.left>=window.innerWidth)return false;var cs=getComputedStyle(b);return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0;}var bs=[...root.querySelectorAll('button,a,[role="button"],[role="menuitem"],[role="option"],[role="link"],li')].filter(function(b){return !b.disabled;});for(var k=0;k<labels.length;k++){for(var i=0;i<bs.length;i++){if(!vis(bs[i]))continue;var l=(bs[i].getAttribute('aria-label')||'').trim().toLowerCase();var tx=(bs[i].textContent||'').trim().toLowerCase();if(l===labels[k]||tx===labels[k]){if(mode==='rect'){var r=bs[i].getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2)+'|'+labels[k];}bs[i].click();return '1|'+labels[k];}}}return '0|';})('$mode',$labelsJson)"""
    }

    /** JS: klik radio dari daftar label (teks baris mengandung label).
     *  Cari [role=radio]/input[type=radio]/tp-yt-paper-radio-button
     *  dulu, lalu baris label yang mengandung radio di dalamnya.
     *  Balik "1|label" bila radio kini tercentang, "clicked|label"
     *  bila diklik tapi status tidak bisa dibaca, "0|" bila tidak
     *  ketemu. Dipakai audience + visibilitas (dialog upload). */
    private fun studioRadioJs(labelsJson: String, dialogScope: Boolean): String {
        val root = if (dialogScope) "var root=document.querySelector('ytcp-uploads-dialog')||document;" else "var root=document;"
        return """(function(labels){$root var els=[...root.querySelectorAll('[role="radio"],input[type="radio"],tp-yt-paper-radio-button')];for(var k=0;k<labels.length;k++){for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').toLowerCase();var al=(els[i].getAttribute('aria-label')||'').toLowerCase();if(t.indexOf(labels[k])>=0||al.indexOf(labels[k])>=0){els[i].click();var on=els[i].getAttribute('aria-checked')==='true'||els[i].checked===true;return (on?'1|':'clicked|')+labels[k];}}}var rows=[...root.querySelectorAll('label,div,span')].filter(function(e){return e.offsetParent!==null;});for(var k2=0;k2<labels.length;k2++){for(var j=0;j<rows.length;j++){var tx=(rows[j].textContent||'').trim().toLowerCase();if(tx===labels[k2]||tx.indexOf(labels[k2])===0){var r=rows[j].querySelector('[role="radio"],input[type="radio"],tp-yt-paper-radio-button');if(r){r.click();var on2=r.getAttribute('aria-checked')==='true'||r.checked===true;return (on2?'1|':'clicked|')+labels[k2];}rows[j].click();return 'clicked|'+labels[k2];}}}return '0|';})($labelsJson)"""
    }

    /** JS: isi kolom Studio dari peran (title/description/date/time).
     *  Cari dari aria-label/placeholder (EN+ID), lalu isi: focus +
     *  select-all + insertText, fallback setter + event input/change
     *  (pola ttsFillJs). Balik "len|120 karakter pertama isi"
     *  (baca-balik untuk verifikasi). */
    private fun studioFillJs(role: String, text: String): String {
        val quoted = org.json.JSONObject.quote(text)
        return """(function(role,text){var fields=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});function sig(t){return ((t.getAttribute('aria-label')||'')+' '+(t.getAttribute('placeholder')||'')+' '+(t.getAttribute('name')||'')).toLowerCase();}var pick=null;for(var i=0;i<fields.length;i++){var s=sig(fields[i]);if(role==='title'&&(s.indexOf('title')>=0||s.indexOf('judul')>=0)){pick=fields[i];break;}if(role==='description'&&(s.indexOf('description')>=0||s.indexOf('deskripsi')>=0||s.indexOf('tell viewers')>=0||s.indexOf('ceritakan tentang video')>=0)){pick=fields[i];break;}if(role==='date'&&(s.indexOf('date')>=0||s.indexOf('tanggal')>=0)){pick=fields[i];break;}if(role==='time'&&(s.indexOf('time')>=0||s.indexOf('waktu')>=0)){pick=fields[i];break;}}if(!pick)return '0|';pick.focus();try{pick.setSelectionRange(0,pick.value.length);}catch(e){}try{document.execCommand('selectAll',false,null);}catch(e2){}var ok=false;try{ok=document.execCommand('insertText',false,text);}catch(e3){}var cur=pick.value||pick.textContent||'';if(!ok||cur.length===0||cur.indexOf(text.slice(0,20))<0){if(pick.tagName==='TEXTAREA'){var st=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;st.call(pick,text);}else if(pick.tagName==='INPUT'){var st2=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;st2.call(pick,text);}else{pick.textContent=text;}try{pick.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e4){}pick.dispatchEvent(new Event('input',{bubbles:true}));try{pick.dispatchEvent(new Event('change',{bubbles:true}));}catch(e5){}}var v=pick.value||pick.textContent||'';return String(v.length)+'|'+v.slice(0,120);})('$role',$quoted)"""
    }

    /** JS: dump kontrol terlihat (maks 15 tombol + 8 kolom isian)
     *  untuk detail gagal — pola runTtsDumpButtons/runTtsDumpStyle. */
    private fun studioDumpJs(): String {
        return """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;}).slice(0,15);var bl=bs.map(function(b){return (b.getAttribute('aria-label')||(b.textContent||'').trim()).replace(/[|,]/g,' ').slice(0,40);});var fs=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;}).slice(0,8);var fl=fs.map(function(t){return (t.tagName+'|'+(t.getAttribute('aria-label')||t.getAttribute('placeholder')||'')).replace(/[|,]/g,' ').slice(0,50);});return 'tombol: '+bl.join(',')+' || kolom: '+fl.join(',');})()"""
    }

    /** Poll verifikasi keadaan (JS balik "1") — maks triesLeft kali,
     *  intervalMs sekali (default 1 dtk). Dipakai sesudah tiap klik
     *  (menu/dialog butuh waktu animasi; jangan tembak sekali lalu
     *  gagal). */
    private fun studioVerifyLoop(verifyJs: String, triesLeft: Int, onOk: () -> Unit, onFail: () -> Unit, intervalMs: Long = 1000) {
        webView.evaluateJavascript(verifyJs) { raw ->
            val ok = (raw?.trim()?.removeSurrounding("\"") ?: "") == "1"
            if (ok) {
                onOk()
            } else if (triesLeft > 1) {
                studioHandler.postDelayed({ studioVerifyLoop(verifyJs, triesLeft - 1, onOk, onFail, intervalMs) }, intervalMs)
            } else {
                onFail()
            }
        }
    }

    /** Klik tombol dari daftar label lalu verifikasi keadaan berikut.
     *  Urutan: klik JS -> tunggu 1200 ms -> verifikasi (3x, interval
     *  verifyIntervalMs) -> bila gagal dan allowTap: baca rect tombol
     *  -> verifikasi sekali lagi SEBELUM tap (menu bisa kebuka lambat
     *  sesudah klik JS — tap di atas menu yang sudah kebuka malah
     *  menutupnya lagi, race v0.1.66) -> bila masih tertutup, tap
     *  beneran sekali di rect tombol lalu verifikasi lagi (2x); bila
     *  tombol sudah tidak ada (aksi berhasil tapi verifikasi lambat),
     *  verifikasi sekali lagi sebelum gagal. onFail membawa alasan +
     *  jejak percobaan: "noclick" (tombol tidak ketemu / tidak ada
     *  yang terlihat) atau "noverify" (keadaan tidak berubah),
     *  diikuti hasil tiap langkah dalam kurung — dipakai detail
     *  gagal langkah Create. */
    private fun studioClickThenVerify(
        labelsJson: String,
        verifyJs: String,
        dialogScope: Boolean,
        allowTap: Boolean,
        onOk: () -> Unit,
        onFail: (String) -> Unit,
        verifyIntervalMs: Long = 1000
    ) {
        webView.evaluateJavascript(studioButtonJs("click", labelsJson, dialogScope)) { raw ->
            val clicked = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|").getOrNull(0) == "1"
            if (!clicked) {
                onFail("noclick (tombol tidak ketemu / tidak ada kandidat terlihat)")
                return@evaluateJavascript
            }
            studioHandler.postDelayed({
                studioVerifyLoop(verifyJs, 3, onOk, {
                    if (!allowTap) {
                        onFail("noverify (klik JS ok; menu=0 sesudah verifikasi 3x @${verifyIntervalMs}ms)")
                    } else {
                        webView.evaluateJavascript(studioButtonJs("rect", labelsJson, dialogScope)) { raw3 ->
                            val rp = (raw3?.trim()?.removeSurrounding("\"") ?: "").split("|")
                            val rx = rp.getOrNull(0)?.toFloatOrNull()
                            val ry = rp.getOrNull(1)?.toFloatOrNull()
                            if (rx == null || ry == null) {
                                // Tombol hilang — mungkin aksi berhasil
                                // tapi verifikasi lambat: verifikasi lagi.
                                studioVerifyLoop(verifyJs, 2, onOk, { onFail("noverify (klik JS ok; tombol hilang; menu=0 sesudah verifikasi 2x)") })
                                return@evaluateJavascript
                            }
                            // v0.1.66: verifikasi sekali lagi SEBELUM tap —
                            // menu yang kebuka lambat sesudah klik JS
                            // tidak boleh ditutup lagi oleh tap fallback.
                            webView.evaluateJavascript(verifyJs) { rawV ->
                                val openNow = (rawV?.trim()?.removeSurrounding("\"") ?: "") == "1"
                                if (openNow) {
                                    onOk()
                                    return@evaluateJavascript
                                }
                                val scale = webView.scale
                                studioTapAt(rx * scale, ry * scale)
                                studioHandler.postDelayed({
                                    studioVerifyLoop(verifyJs, 2, onOk, { onFail("noverify (klik JS ok; menu=0 sesudah verifikasi 3x @${verifyIntervalMs}ms; tap beneran rect=${rx}x${ry}; menu=0 sesudah tap + verifikasi 2x)") })
                                }, 1200)
                            }
                        }
                    }
                }, verifyIntervalMs)
            }, 1200)
        }
    }

    /** JS dasbor: urai ringkasan channel dari innerText. Bentuk teks
     *  sudah dibuktikan snapshot live: "Your channel DInfokan",
     *  "Current subscribers 68", "Summary Last 28 days Views 66.6K —
     *  Watch time (hours) 324.2", seksi "Top content" (judul + views
     *  per baris). Field yang tidak terurai = "" (bukan gagal) — teks
     *  mentah tetap ada di snapshot /page. Balik '§'-delimited
     *  (pola pageSnapshotJs): channel§subs§views28§watch28§top5
     *  (top5 = judul^views, dipisah '~'). */
    private fun studioDashboardJs(): String {
        return """(function(){try{var txt=document.body?document.body.innerText:'';function clean(s){return (s||'').replace(/["\\§|^~]/g,' ').replace(/\s+/g,' ').trim();}var channel='',subs='',views='',watch='',top='';var m=txt.match(/Your channel\s*\n([^\n]+)/)||txt.match(/Channel Anda\s*\n([^\n]+)/);if(!m){var m2=txt.match(/Your channel\s+([^\\n]+?)(?:\s+Dashboard|\s+Dasbor|\s+Content|\s+Konten|$)/)||txt.match(/Channel Anda\s+([^\\n]+?)(?:\s+Dashboard|\s+Dasbor|\s+Content|\s+Konten|$)/);if(m2)channel=clean(m2[1]);}else channel=clean(m[1]);m=txt.match(/Current subscribers\s*\n?\s*([\d.,]+)/)||txt.match(/Subscriber saat ini\s*\n?\s*([\d.,]+)/);if(m)subs=m[1];m=txt.match(/Views\s+([\d.,]+[KM]?)\s*[—–-]\s*Watch time \(hours\)\s+([\d.,]+)/)||txt.match(/Penayangan\s+([\d.,]+[KM]?)\s*[—–-]\s*Waktu tonton \(jam\)\s+([\d.,]+)/);if(m){views=m[1];watch=m[2];}var i1=txt.indexOf('Top content');if(i1<0)i1=txt.indexOf('Konten teratas');var ends=['View realtime data','Lihat data realtime','Recent subscribers','Subscriber terbaru','Create your first post','Buat postingan pertama'];var i2=-1;for(var e=0;e<ends.length;e++){var j=txt.indexOf(ends[e],i1);if(j>=0&&(i2<0||j<i2))i2=j;}var sec=i1>=0?txt.substring(i1,i2>=0?i2:txt.length):'';var lines=sec.split('\n').map(function(s){return clean(s);}).filter(function(s){return s.length>0;});var items=[];var cur='';for(var i=0;i<lines.length;i++){var L=lines[i];if(/^(Top content|Konten teratas|Last 48 hours|48 jam terakhir|Views|Penayangan)$/.test(L))continue;var mm=L.match(/^(.*?)\s+([\d.,]+\s*[KM]?)$/);if(mm&&mm[1].length>0&&!/^(Views|Penayangan|Ranking|First|Go to|Lihat)/.test(mm[1])){items.push(clean(mm[1])+'^'+mm[2].trim());cur='';if(items.length>=5)break;}else if(/^[\d.,]+\s*[KM]?$/.test(L)){if(cur){items.push(cur+'^'+L);cur='';if(items.length>=5)break;}}else{cur=cur?cur+' '+L:L;}}top=items.join('~');return [channel,subs,views,watch,top].join('§');}catch(e){return '§§§§';}})()"""
    }

    /** JS siap-dasbor (v0.1.61): dasbor baru dianggap siap diurai
     *  bila teks khasnya sudah ada ("Current subscribers" / "Channel
     *  dashboard" + angka) — angka analitik dimuat belakangan, jadi
     *  urai tanpa tunggu = semua field kosong. */
    private fun studioDashboardReadyJs(): String {
        return """(function(){var t=document.body?document.body.innerText:'';var hasSubs=t.indexOf('Current subscribers')>=0||t.indexOf('Subscriber saat ini')>=0;var hasDash=t.indexOf('Channel dashboard')>=0||t.indexOf('Dasbor channel')>=0;var hasNum=/\d/.test(t);return ((hasSubs||hasDash)&&hasNum)?'1':'0';})()"""
    }

    /** JS interstitial Studio (v0.1.62): halaman "Try out the YouTube
     *  Studio app" muncul sesudah install — dasbor tidak tampil,
     *  tombol Create tidak ada. Balik 'clean' (tidak ada interstitial),
     *  'clicked' (elemen "CONTINUE TO STUDIO" diklik), atau 'notfound'
     *  (teks interstitial ada tapi elemen kliknya tidak ketemu).
     *  Elemen dicari di button/a/div/span/[role]/[onclick] — bukan
     *  cuma <button> (diagnose menemukan daftar tombol KOSONG di
     *  halaman ini, jadi elemennya bukan <button>). Cocok teks
     *  persis (tanpa peduli huruf) dan pilih yang paling dalam,
     *  supaya tidak kena wadah yang teksnya cuma mengandung label
     *  itu. "GET THE APP" tidak pernah diklik (membuka Play Store). */
    private fun studioInterstitialJs(): String {
        return """(function(){var t=(document.body?document.body.innerText:'')||'';var low=t.toLowerCase();var inter=low.indexOf('try out the youtube studio app')>=0||low.indexOf('continue to studio')>=0||low.indexOf('lanjutkan ke studio')>=0||low.indexOf('coba aplikasi youtube studio')>=0;if(!inter)return 'clean';var labels=['continue to studio','lanjutkan ke studio'];var els=[...document.querySelectorAll('button,a,div,span,[role="button"],[role="link"],[onclick]')].filter(function(e){return e.offsetParent!==null;});for(var k=0;k<labels.length;k++){var best=null,bestDepth=-1;for(var i=0;i<els.length;i++){var tx=(els[i].textContent||'').trim().toLowerCase();if(tx===labels[k]){var d=0,p=els[i].parentElement;while(p){d++;p=p.parentElement;}if(d>bestDepth){bestDepth=d;best=els[i];}}}if(best){best.click();return 'clicked';}}return 'notfound';})()"""
    }

    /** JS dasbor muncul (v0.1.62): tombol Create ada ATAU teks khas
     *  dasbor ada. Balik '1'/'0'. Dipakai poll sesudah klik
     *  "CONTINUE TO STUDIO" — halaman interstitial tidak punya
     *  keduanya, jadi '1' berarti interstitial sudah pergi. */
    private fun studioDashboardAppearedJs(): String {
        return """(function(){var t=(document.body?document.body.innerText:'')||'';if(t.indexOf('Channel dashboard')>=0||t.indexOf('Dasbor channel')>=0)return '1';var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim().toLowerCase();var tx=(bs[i].textContent||'').trim().toLowerCase();if(l==='create'||l==='buat'||tx==='create'||tx==='buat')return '1';}return '0';})()"""
    }

    /** Gagal jujur dengan bukti (v0.1.62): 300 karakter pertama teks
     *  halaman + dump kontrol — pola runStudioWaitDialog. Param
     *  extra (v0.1.66) ditempel sesudah prefix bila tidak kosong. */
    private fun failStudioWithEvidence(prefix: String, extra: String = "") {
        val diagJs = """(function(){var t=(document.body?document.body.innerText:'')||'';return t.replace(/\s+/g,' ').trim().slice(0,300);})()"""
        webView.evaluateJavascript(diagJs) { rawDiag ->
            val diag = (rawDiag?.trim()?.removeSurrounding("\"") ?: "").take(300)
            webView.evaluateJavascript(studioDumpJs()) { rawDump ->
                val dump = (rawDump?.trim()?.removeSurrounding("\"") ?: "").take(300)
                failStudio("$prefix${if (extra.isNotBlank()) " ($extra)" else ""} (teks halaman: $diag; $dump)")
            }
        }
    }

    /** JS probe tombol Create (v0.1.66): hitung kandidat berlabel
     *  Create/Buat, berapa yang terlihat (syarat yang sama dengan
     *  studioButtonJs), tag + rect + dalam-viewport kandidat
     *  terpilih, dan apakah menu ("Upload videos") sudah kebuka.
     *  Balik 'ditemukan|terlihat|tag|x,y,w,h|dalamViewport|menu'. */
    private fun studioCreateProbeJs(labelsJson: String): String {
        return """(function(labels){function vis(b){if(b.offsetParent===null)return false;var r=b.getBoundingClientRect();if(r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=window.innerHeight||r.left>=window.innerWidth)return false;var cs=getComputedStyle(b);return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0;}var bs=[...document.querySelectorAll('button,a,[role="button"],[role="menuitem"],[role="option"],[role="link"],li')].filter(function(b){return !b.disabled;});var found=0,seen=0,pick=null;for(var k=0;k<labels.length;k++){for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim().toLowerCase();var tx=(bs[i].textContent||'').trim().toLowerCase();if(l===labels[k]||tx===labels[k]){found++;if(vis(bs[i])){seen++;if(!pick)pick=bs[i];}}}}var tag='none',rect='none',inVp='0';if(pick){tag=pick.tagName;var r=pick.getBoundingClientRect();rect=Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+','+Math.round(r.height);inVp=(r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth)?'1':'0';}var t=document.body?document.body.innerText:'';var menu=(t.indexOf('Upload videos')>=0||t.indexOf('Upload video')>=0||t.indexOf('Unggah video')>=0)?'1':'0';return found+'|'+seen+'|'+tag+'|'+rect+'|'+inVp+'|'+menu;})($labelsJson)"""
    }

    /** Gagal jujur langkah Create dengan bukti (v0.1.66): alasan +
     *  jejak percobaan dari studioClickThenVerify (klik JS, hasil
     *  verifikasi, rect tap), probe tombol Create (ditemukan /
     *  terlihat / tag / rect / dalam-viewport / menu), 300 karakter
     *  pertama teks halaman + dump kontrol — pola
     *  failStudioWithEvidence. */
    private fun failStudioCreateMenu(reason: String) {
        val labels = org.json.JSONArray(listOf("create", "buat")).toString()
        webView.evaluateJavascript(studioCreateProbeJs(labels)) { rawProbe ->
            val p = (rawProbe?.trim()?.removeSurrounding("\"") ?: "").split("|")
            val probe = "Create: ditemukan=${p.getOrNull(0) ?: "?"} terlihat=${p.getOrNull(1) ?: "?"} tag=${p.getOrNull(2) ?: "?"} rect=${p.getOrNull(3) ?: "?"} dalamViewport=${p.getOrNull(4) ?: "?"} menu=${p.getOrNull(5) ?: "?"}"
            failStudioWithEvidence("menu Create tidak kebuka sesudah klik tombol Create ($reason)", probe)
        }
    }

    /** Singkirkan interstitial Studio sebelum alur apa pun (v0.1.62).
     *  Tanpa interstitial -> onOk langsung. Sesudah klik
     *  "CONTINUE TO STUDIO", poll ~10 dtk sampai dasbor muncul;
     *  bila interstitial tetap ada, gagal jujur dengan bukti teks
     *  halaman. Dipakai upload + dashboard + videos — ketiganya
     *  mentok di langkah pertama bila interstitial muncul. */
    private fun runStudioDismissInterstitial(onOk: () -> Unit) {
        if (pendingStudioAction == null) return
        webView.evaluateJavascript(studioInterstitialJs()) { raw ->
            val res = (raw?.trim()?.removeSurrounding("\"") ?: "")
            when (res) {
                "clean" -> onOk()
                "notfound" -> failStudioWithEvidence("interstitial Studio tidak bisa disingkirkan — elemen 'CONTINUE TO STUDIO' tidak ketemu")
                else -> studioVerifyLoop(studioDashboardAppearedJs(), 10, onOk, {
                    failStudioWithEvidence("interstitial Studio masih ada sesudah klik 'CONTINUE TO STUDIO'")
                })
            }
        }
    }

    /** Aksi dashboard: baca ringkasan channel dari dasbor Studio.
     *  v0.1.61 - TUNGGU SIAP DULU: periksa teks khas dasbor sebelum
     *  mengurai; bila hasil urai pertama KOSONG semua, ulangi sekali
     *  sesudah ~10 dtk. Bila tetap kosong, status tetap "done"
     *  (field kosong bukan gagal) TAPI detail menyatakan eksplisit
     *  bacaannya kosong dan halaman mungkin belum lengkap — bukan
     *  field kosong diam-diam. */
    private fun runStudioDashboard(triesLeft: Int) {
        if (pendingStudioAction == null) return
        webView.evaluateJavascript(studioDashboardReadyJs()) { rawReady ->
            val ready = (rawReady?.trim()?.removeSurrounding("\"") ?: "") == "1"
            if (!ready) {
                if (triesLeft > 1) {
                    studioHandler.postDelayed({ runStudioDashboard(triesLeft - 1) }, 10000)
                } else {
                    finishStudioDashboardEmpty("dasbor belum siap sesudah menunggu")
                }
                return@evaluateJavascript
            }
            webView.evaluateJavascript(studioDashboardJs()) { raw ->
                val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
                val parts = clean.split("§")
                if (parts.size < 5) {
                    if (triesLeft > 1) {
                        studioHandler.postDelayed({ runStudioDashboard(triesLeft - 1) }, 2000)
                    } else {
                        finishStudioDashboardEmpty("dasbor tidak terbaca")
                    }
                    return@evaluateJavascript
                }
                val topArr = org.json.JSONArray()
                val topRaw = parts[4]
                if (topRaw.isNotBlank()) {
                    for (row in topRaw.split("~")) {
                        val p = row.split("^")
                        if (p.size == 2) {
                            topArr.put(org.json.JSONObject().apply {
                                put("title", p[0])
                                put("views", p[1])
                            })
                        }
                    }
                }
                val allEmpty = parts[0].isBlank() && parts[1].isBlank() &&
                    parts[2].isBlank() && parts[3].isBlank() && topArr.length() == 0
                if (allEmpty && !studioDashboardEmptyRetried) {
                    studioDashboardEmptyRetried = true
                    studioHandler.postDelayed({ runStudioDashboard(1) }, 10000)
                    return@evaluateJavascript
                }
                val data = org.json.JSONObject().apply {
                    put("channel", parts[0])
                    put("subscribers", parts[1])
                    put("views28d", parts[2])
                    put("watchHours28d", parts[3])
                    put("topVideos", topArr)
                }
                val n = topArr.length()
                studioRunning = false
                pendingStudioAction = null
                if (allEmpty) {
                    writeStudioStatus("done", "dasbor dibaca KOSONG — halaman mungkin belum lengkap (channel: ?, subscriber: ?, 0 video teratas)", data)
                } else {
                    writeStudioStatus("done", "dasbor dibaca (channel: ${parts[0].ifBlank { "?" }}, subscriber: ${parts[1].ifBlank { "?" }}, $n video teratas)", data)
                }
            }
        }
    }

    /** Dasbor selesai dibaca tapi kosong: tetap "done" (field kosong
     *  bukan gagal), detail menyatakan kosong secara eksplisit. */
    private fun finishStudioDashboardEmpty(reason: String) {
        studioRunning = false
        pendingStudioAction = null
        val data = org.json.JSONObject().apply {
            put("channel", "")
            put("subscribers", "")
            put("views28d", "")
            put("watchHours28d", "")
            put("topVideos", org.json.JSONArray())
        }
        writeStudioStatus("done", "dasbor dibaca KOSONG — $reason; halaman mungkin belum lengkap (channel: ?, subscriber: ?, 0 video teratas)", data)
    }

    /** Buka halaman Content (SPA — klik nav, bukan loadUrl penuh).
     *  Jalur pertama: klik link a[href*="/videos"] (paling andal —
     *  nav Studio pakai anchor). Jalur kedua: label nav "Content" /
     *  "Konten". Verifikasi: pathname mengandung /videos. */
    private fun runStudioGoContent(onOk: () -> Unit) {
        if (pendingStudioAction == null) return
        if (currentUrl.contains("/videos")) {
            onOk()
            return
        }
        val hrefJs = """(function(){var as=[...document.querySelectorAll('a[href*="/videos"]')].filter(function(a){return a.offsetParent!==null;});if(as.length>0){as[0].click();return '1';}return '0';})()"""
        val verifyJs = """(function(){return (location.pathname.indexOf('/videos')>=0)?'1':'0';})()"""
        webView.evaluateJavascript(hrefJs) { raw ->
            val ok = (raw?.trim()?.removeSurrounding("\"") ?: "") == "1"
            if (ok) {
                studioVerifyLoop(verifyJs, 5, onOk, {
                    runStudioGoContentByLabel(onOk)
                })
            } else {
                runStudioGoContentByLabel(onOk)
            }
        }
    }

    private fun runStudioGoContentByLabel(onOk: () -> Unit) {
        val labels = org.json.JSONArray(listOf("content", "konten")).toString()
        val verifyJs = """(function(){return (location.pathname.indexOf('/videos')>=0)?'1':'0';})()"""
        studioClickThenVerify(labels, verifyJs, false, false, onOk, {
            failStudio("menu Content tidak ketemu di dasbor Studio")
        })
    }

    /** JS daftar Content: baris video dari link judul
     *  a[href="/video/<id>/edit"] (maks 10, unik per id). Per baris:
     *  judul^url-edit^visibilitas^tanggal^views (dipisah '~').
     *  Visibilitas dari teks baris (Scheduled/Public/Private/
     *  Unlisted + padanan ID). Tanggal dari pola "Nov 22, 2026"
     *  atau "22 Nov 2026". Views = angka pertama sesudah tanggal
     *  (kolom urutan: visibilitas, tanggal, views, komentar, suka). */
    private fun studioVideosJs(): String {
        return """(function(){try{var rows=[];var seen={};var links=[...document.querySelectorAll('a[href*="/video/"]')].filter(function(a){return a.offsetParent!==null;});for(var i=0;i<links.length&&rows.length<10;i++){var h=links[i].getAttribute('href')||'';if(h.indexOf('/edit')<0)continue;var mm=h.match(/\/video\/([A-Za-z0-9_-]{11})/);if(!mm)continue;if(seen[mm[1]])continue;seen[mm[1]]=1;var t=(links[i].textContent||'').replace(/["\\§|^~]/g,' ').replace(/\s+/g,' ').trim();if(!t||t.length<2)continue;var row=links[i];for(var d=0;d<5&&row.parentElement;d++){row=row.parentElement;if((row.innerText||'').length>80)break;}var rt=(row.innerText||'').replace(/\s+/g,' ');var vis='';if(/Scheduled|Dijadwalkan/i.test(rt))vis='scheduled';else if(/Public|Publik/i.test(rt))vis='public';else if(/Private|Pribadi/i.test(rt))vis='private';else if(/Unlisted|Tidak publik/i.test(rt))vis='unlisted';var dm=rt.match(/(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2},\s+\d{4}/)||rt.match(/(\d{1,2})\s+(Jan|Feb|Mar|Apr|Mei|Jun|Jul|Agu|Sep|Okt|Nov|Des)\s+(\d{4})/);var date=dm?dm[0]:'';var after=dm?rt.substring(rt.indexOf(dm[0])+dm[0].length):rt;var vm=after.match(/([\d.,]+[KM]?)/);var views=vm?vm[1].trim():'';rows.push(t+'^'+location.origin+'/video/'+mm[1]+'/edit'+'^'+vis+'^'+date+'^'+views);}return rows.join('~');}catch(e){return '';}})()"""
    }

    /** Aksi videos: daftar 10 video pertama halaman Content. */
    private fun runStudioVideos(triesLeft: Int) {
        if (pendingStudioAction == null) return
        webView.evaluateJavascript(studioVideosJs()) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean.isBlank()) {
                if (triesLeft > 1) {
                    studioHandler.postDelayed({ runStudioVideos(triesLeft - 1) }, 2000)
                } else {
                    failStudio("daftar video Content kosong/tidak terbaca")
                }
                return@evaluateJavascript
            }
            val arr = org.json.JSONArray()
            for (row in clean.split("~")) {
                val p = row.split("^")
                if (p.size >= 5) {
                    arr.put(org.json.JSONObject().apply {
                        put("title", p[0])
                        put("url", p[1])
                        put("visibility", p[2])
                        put("date", p[3])
                        put("views", p[4])
                    })
                }
            }
            val data = org.json.JSONObject().apply { put("videos", arr) }
            val n = arr.length()
            studioRunning = false
            pendingStudioAction = null
            writeStudioStatus("done", "daftar Content dibaca: $n video", data)
        }
    }

    /** Langkah 1 upload: diagnose halaman Studio. Gagal jujur bila
     *  belum login (halaman sign-in Google) atau tombol Create tidak
     *  ketemu sesudah 5x2 dtk (bawa dump kontrol). Sambil jalan,
     *  tangkap channel id dari URL dasbor (dipakai URL Content saat
     *  verifikasi akhir) + bahasa UI halaman (format tanggal). */
    private fun runStudioUploadDiagnose(triesLeft: Int) {
        if (pendingStudioAction == null) return
        val js = """(function(){var bs=[...document.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});var create=0;for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim().toLowerCase();var tx=(bs[i].textContent||'').trim().toLowerCase();if(l==='create'||l==='buat'||tx==='create'||tx==='buat'){create=1;break;}}var t=document.body?document.body.innerText||'':'';var signedin=t.indexOf('Sign in to continue to YouTube')<0&&t.indexOf('Login untuk melanjutkan ke YouTube')<0;var id=(t.indexOf('Dasbor channel')>=0||t.indexOf('Konten channel')>=0||t.indexOf('Jadwalkan')>=0)?'id':'en';return create+'|'+(signedin?'1':'0')+'|'+id;})()"""
        webView.evaluateJavascript(js) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            val create = parts.getOrNull(0) == "1"
            val signedIn = parts.getOrNull(1) == "1"
            studioUiLang = parts.getOrNull(2) ?: "en"
            if (!signedIn) {
                failStudio("belum login di Space ini — masih di halaman sign-in Google")
                return@evaluateJavascript
            }
            if (!create) {
                if (triesLeft > 1) {
                    studioHandler.postDelayed({ runStudioUploadDiagnose(triesLeft - 1) }, 2000)
                } else {
                    webView.evaluateJavascript(studioDumpJs()) { rawDump ->
                        val dump = (rawDump?.trim()?.removeSurrounding("\"") ?: "").take(300)
                        failStudio("tombol Create tidak ketemu di Studio ($dump)")
                    }
                }
                return@evaluateJavascript
            }
            val m = Regex("/channel/(UC[A-Za-z0-9_-]+)").find(currentUrl)
            if (m != null) studioChannelId = m.groupValues[1]
            writeStudioStatus("running", "tombol Create ketemu — membuka menu upload")
            val labels = org.json.JSONArray(listOf("create", "buat")).toString()
            val verifyJs = """(function(){var t=document.body?document.body.innerText||'':'';return (t.indexOf('Upload videos')>=0||t.indexOf('Upload video')>=0||t.indexOf('Unggah video')>=0)?'1':'0';})()"""
            // v0.1.66: verifikasi 3x @2 dtk (bukan 1 dtk) — menu bisa
            // kebuka lambat sesudah klik JS; tap fallback baru jalan
            // sesudah jendela itu, dan gagal membawa bukti probe.
            studioClickThenVerify(labels, verifyJs, false, true, onOk = { runStudioClickUploadMenu() }, onFail = { reason ->
                failStudioCreateMenu(reason)
            }, verifyIntervalMs = 2000)
        }
    }

    /** Langkah 2 upload: klik "Upload videos" di menu Create. Tanpa
     *  tap ulang (klik ganda membuka chooser 2x — file _inbox sudah
     *  terpakai, chooser kedua jatuh ke pemilih file di layar).
     *  Verifikasi: menu ketutup (item "Upload videos" hilang). */
    private fun runStudioClickUploadMenu() {
        if (pendingStudioAction == null) return
        val labels = org.json.JSONArray(listOf("upload videos", "upload video", "unggah video")).toString()
        val verifyJs = """(function(){var els=[...document.querySelectorAll('button,a,[role="menuitem"]')].filter(function(e){return e.offsetParent!==null;});for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim().toLowerCase();if(t==='upload videos'||t==='upload video'||t==='unggah video')return '0';}return '1';})()"""
        studioClickThenVerify(labels, verifyJs, false, false, { runStudioWaitDialog(30) }, {
            failStudio("menu 'Upload videos' tidak ketemu sesudah menu Create kebuka")
        })
    }

    /** JS posisi tengah "Select files" (v0.1.63, diubah v0.1.64):
     *  cari di dalam dialog upload (ytcp-uploads-dialog) di
     *  button/a/div/span/[role="button"]/[role="link"]/[onclick] —
     *  teks persis tanpa peduli huruf, elemen paling dalam (pola
     *  interstitial v0.1.62 — kontrol Studio bukan selalu
     *  <button>). Elemen di-scroll ke tengah viewport dulu, lalu
     *  balik 'x|y' titik tengahnya (CSS px) atau '0' bila tidak
     *  ketemu. v0.1.64: TIDAK lagi klik JS di sini — klik .click()
     *  tidak memicu chooser (Chrome butuh gestur terpercaya);
     *  pemanggil mengetuk rect ini beneran via studioTapAt
     *  (pola ttsTapAt v0.1.48, dipakai Schedule/Publish v0.1.60). */
    private fun studioSelectFilesJs(labelsJson: String): String {
        return """(function(labels){var root=document.querySelector('ytcp-uploads-dialog')||document;var els=[...root.querySelectorAll('button,a,div,span,[role="button"],[role="link"],[onclick]')].filter(function(e){return e.offsetParent!==null;});for(var k=0;k<labels.length;k++){var best=null,bestDepth=-1;for(var i=0;i<els.length;i++){var tx=(els[i].textContent||'').trim().toLowerCase();if(tx===labels[k]){var d=0,p=els[i].parentElement;while(p){d++;p=p.parentElement;}if(d>bestDepth){bestDepth=d;best=els[i];}}}if(best){try{best.scrollIntoView({block:'center'});}catch(e){}var r=best.getBoundingClientRect();if(r.width>0&&r.height>0){return (r.left+r.width/2)+'|'+(r.top+r.height/2);}return '0';}}return '0';})($labelsJson)"""
    }

    /** Tap beneran "Select files" di dialog upload (v0.1.63,
     *  diubah v0.1.64): picu onShowFileChooser yang menyodorkan
     *  file _inbox pending. v0.1.64: klik JS .click() TIDAK memicu
     *  chooser (Chrome butuh gestur terpercaya — preseden tombol
     *  Download TTS v0.1.51), jadi tombol diketuk beneran:
     *  baca rect dari studioSelectFilesJs lalu dispatchTouchEvent
     *  di titik tengahnya via studioTapAt (CSS px x webView.scale,
     *  pola ttsTapAt v0.1.48 — TANPA koordinat mati, rect dibaca
     *  live dari DOM tiap tap). Sesudah tap, tunggu 2 dtk lalu
     *  lanjutkan poll tunggu dialog (chooser dipanggil async oleh
     *  Chromium). Tombol tidak ketemu -> gagal jujur dengan bukti
     *  teks halaman. */
    private fun runStudioClickSelectFiles(triesLeft: Int) {
        if (pendingStudioAction == null) return
        val labels = org.json.JSONArray(listOf("select files", "pilih file")).toString()
        webView.evaluateJavascript(studioSelectFilesJs(labels)) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            if (clean == "0" || clean.isBlank()) {
                failStudioWithEvidence("tombol 'Select files' tidak ketemu di dialog upload")
                return@evaluateJavascript
            }
            val parts = clean.split("|")
            val rx = parts.getOrNull(0)?.toFloatOrNull()
            val ry = parts.getOrNull(1)?.toFloatOrNull()
            if (rx == null || ry == null) {
                failStudioWithEvidence("tombol 'Select files' tidak ketemu di dialog upload")
                return@evaluateJavascript
            }
            val scale = webView.scale
            val tx = (rx * scale).toInt()
            val ty = (ry * scale).toInt()
            writeStudioStatus("running", "dialog upload kebuka — mengetuk tombol 'Select files' (tap beneran, titik=${tx}x${ty})")
            studioTapAt(rx * scale, ry * scale)
            studioHandler.postDelayed({ runStudioWaitDialog(triesLeft) }, 2000)
        }
    }

    /** Langkah 3 upload (v0.1.61, diubah v0.1.63): tunggu dialog
     *  upload kebuka + file benar tersaji ke chooser (flag
     *  studioChooserServed dari cabang onShowFileChooser). Dialog
     *  diterima bila ada kolom judul ATAU heading "Upload videos" /
     *  "Unggah video" di dalam dialog; nama file yang dipilih dicek
     *  kemunculannya di teks halaman DAN di nilai kolom isian
     *  (judul terisi nama file sesudah dipilih — nilai input tidak
     *  masuk innerText). Poll 30x1 dtk. Bila dialog tidak kebuka,
     *  detail gagal membawa bukti: 300 karakter pertama teks
     *  halaman + label tombol + ada/tidaknya dialog, file-input,
     *  dan flag chooser — supaya penyebabnya bisa dibaca tanpa
     *  menebak. v0.1.63: dialog kebuka TANPA flag chooser BUKAN
     *  lagi gagal langsung — klik menu "Upload videos" membuka
     *  dialog TANPA memicu chooser, jadi eksekutor mengetuk "Select
     *  files" sendiri (sekali saja per run) buat memicunya; berlaku
     *  sama untuk dialog yang baru dibuka run ini maupun dialog
     *  sisa sesi sebelumnya (keduanya dipulihkan dengan cara yang
     *  sama, tanpa dibedakan). v0.1.64: tap-nya tap beneran
     *  (dispatchTouchEvent di rect tombol, pola studioTapAt) —
     *  klik JS .click() tidak memicu chooser. Sesudah tap, poll
     *  sampai nama file
     *  muncul; bila anggaran habis dan chooser sudah tersaji, tetap
     *  lanjut isi judul — nama file bisa tidak tampil sebagai teks
     *  dan isi judul punya verifikasi baca-balik sendiri. Bila
     *  chooser tidak pernah tersaji, gagal jujur dengan bukti. */
    private fun runStudioWaitDialog(triesLeft: Int) {
        if (pendingStudioAction == null) return
        val fileName = studioUploadFileName
        val fileQuoted = org.json.JSONObject.quote(fileName ?: "")
        val js = """(function(){var dlg=document.querySelector('ytcp-uploads-dialog');var scope=dlg||document;var hasTitle=0;var tas=[...scope.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var s=((tas[i].getAttribute('aria-label')||'')+' '+(tas[i].getAttribute('placeholder')||'')).toLowerCase();if(s.indexOf('title')>=0||s.indexOf('judul')>=0){hasTitle=1;break;}}var heading=0;if(dlg){var ht=(dlg.innerText||'').toLowerCase();if(ht.indexOf('upload videos')>=0||ht.indexOf('upload video')>=0||ht.indexOf('unggah video')>=0)heading=1;}var fname=$fileQuoted;var fileSeen=0;if(fname){var t=document.body?document.body.innerText:'';var base=fname.replace(/\.[^.]+$/,'');if(t.indexOf(fname)>=0||t.indexOf(base)>=0)fileSeen=1;else{var ins=[...document.querySelectorAll('input,textarea')];for(var q=0;q<ins.length;q++){var v=ins[q].value||'';if(v&&v.indexOf(base)>=0){fileSeen=1;break;}}}}var hasInput=!!document.querySelector('input[type="file"]');return (hasTitle||heading?'1':'0')+'|'+fileSeen+'|'+(hasInput?'1':'0')+'|'+(dlg?'1':'0');})()"""
        webView.evaluateJavascript(js) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            val dialogOpen = parts.getOrNull(0) == "1"
            val fileSeen = parts.getOrNull(1) == "1"
            if (dialogOpen) {
                if (!studioChooserServed && !studioSelectFilesClicked) {
                    // v0.1.63: dialog kebuka tapi chooser belum tersaji —
                    // tap beneran "Select files" sendiri buat picu
                    // chooser (bukan gagal — klik menu "Upload videos"
                    // membuka dialog TANPA memicu chooser; v0.1.64:
                    // klik JS .click() tidak memicu chooser, jadi tap).
                    studioSelectFilesClicked = true
                    writeStudioStatus("running", "dialog upload kebuka — memilih file (tap 'Select files')")
                    runStudioClickSelectFiles(triesLeft)
                    return@evaluateJavascript
                }
                if (!studioChooserServed) {
                    // Sudah diklik sekali, chooser belum tersaji — tunggu.
                    if (triesLeft <= 0) {
                        failStudioWithEvidence("file tidak tersaji ke chooser sesudah klik 'Select files'")
                    } else {
                        studioHandler.postDelayed({ runStudioWaitDialog(triesLeft - 1) }, 1000)
                    }
                    return@evaluateJavascript
                }
                // Chooser tersaji — tunggu nama file muncul (bukti file
                // benar masuk dialog), maks sisa anggaran. Bila anggaran
                // habis tapi chooser sudah tersaji, tetap lanjut isi
                // judul: nama file bisa tidak tampil sebagai teks dan
                // isi judul punya verifikasi baca-balik sendiri.
                if (fileSeen || triesLeft <= 0) {
                    writeStudioStatus("running", "dialog upload kebuka — mengisi judul (nama file terlihat: ${if (fileSeen) "ya" else "tidak"})")
                    runStudioFillTitle()
                } else {
                    studioHandler.postDelayed({ runStudioWaitDialog(triesLeft - 1) }, 1000)
                }
            } else if (triesLeft <= 0) {
                val diagJs = """(function(){var t=(document.body?document.body.innerText:'')||'';var head=t.replace(/\s+/g,' ').trim().slice(0,300);var hasDlg=!!document.querySelector('ytcp-uploads-dialog');var hasInput=!!document.querySelector('input[type="file"]');return head+' | dialog:'+(hasDlg?'ya':'tidak')+' | file-input:'+(hasInput?'ya':'tidak');})()"""
                webView.evaluateJavascript(diagJs) { rawDiag ->
                    val diag = (rawDiag?.trim()?.removeSurrounding("\"") ?: "").take(400)
                    webView.evaluateJavascript(studioDumpJs()) { rawDump ->
                        val dump = (rawDump?.trim()?.removeSurrounding("\"") ?: "").take(300)
                        failStudio("dialog upload tidak kebuka sesudah file dipilih (chooser tersaji: ${if (studioChooserServed) "ya" else "tidak"}; $diag; $dump)")
                    }
                }
            } else {
                studioHandler.postDelayed({ runStudioWaitDialog(triesLeft - 1) }, 1000)
            }
        }
    }

    /** Langkah 4 upload: isi kolom judul (wajib). Kolom sudah terisi
     *  nama file — select-all + insertText menggantinya. Verifikasi
     *  baca-balik: isi HARUS diawali judul (bila menempel di belakang
     *  nama file, gagal — jangan kirim judul salah). */
    private fun runStudioFillTitle() {
        val title = pendingStudioTitle ?: return
        webView.evaluateJavascript(studioFillJs("title", title)) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            val len = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val head = parts.getOrNull(1) ?: ""
            if (len <= 0 || !head.startsWith(title)) {
                failStudio("judul tidak terisi (terbaca: ${head.take(60)})")
                return@evaluateJavascript
            }
            val desc = pendingStudioDescription
            if (!desc.isNullOrBlank()) {
                writeStudioStatus("running", "judul terisi — mengisi deskripsi")
                runStudioFillDescription()
            } else {
                writeStudioStatus("running", "judul terisi — memilih audience")
                runStudioPickAudience()
            }
        }
    }

    /** Langkah 5 upload: isi kolom deskripsi (opsional — dilewati
     *  bila kosong). Verifikasi baca-balik: isi diawali 40 karakter
     *  pertama deskripsi. */
    private fun runStudioFillDescription() {
        val desc = pendingStudioDescription ?: return
        webView.evaluateJavascript(studioFillJs("description", desc)) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            val len = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val head = parts.getOrNull(1) ?: ""
            if (len <= 0 || !head.startsWith(desc.take(40))) {
                failStudio("deskripsi tidak terisi (terbaca: ${head.take(60)})")
                return@evaluateJavascript
            }
            writeStudioStatus("running", "deskripsi terisi — memilih audience")
            runStudioPickAudience()
        }
    }

    /** Langkah 6 upload: audience "No, it's not made for kids"
     *  (wajib — tanpa ini tombol Next tidak aktif). */
    private fun runStudioPickAudience() {
        if (pendingStudioAction == null) return
        val labels = org.json.JSONArray(listOf("no, it's not made for kids", "tidak, video ini tidak dibuat untuk anak-anak")).toString()
        webView.evaluateJavascript(studioRadioJs(labels, true)) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            if (parts.getOrNull(0) == "1" || parts.getOrNull(0) == "clicked") {
                writeStudioStatus("running", "audience dipilih — menunggu video selesai diproses")
                waitStudioProcessing(180)
            } else {
                failStudio("pilihan audience 'not made for kids' tidak ketemu")
            }
        }
    }

    /** Langkah 7 upload: tunggu pemrosesan video selesai (maks 30
     *  menit: 180x10 dtk). Status progres ditulis tiap 1 menit.
     *  Teks dibaca dari dialog upload saja (bukan seluruh halaman)
     *  supaya angka % tidak ketukar statistik dasbor di belakang. */
    private fun waitStudioProcessing(triesLeft: Int) {
        if (pendingStudioAction == null) return
        val js = """(function(){var dlg=document.querySelector('ytcp-uploads-dialog')||document.body;var t=dlg.innerText||'';var done=/processing complete|pemrosesan selesai/i.test(t)||(/upload complete|upload selesai/i.test(t)&&!/processing|memproses/i.test(t));var pct=(t.match(/(\d+)%/)||[])[1]||'';return (done?'1':'0')+'|'+pct;})()"""
        webView.evaluateJavascript(js) { raw ->
            val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            if (parts.getOrNull(0) == "1") {
                writeStudioStatus("running", "video selesai diproses — lanjut ke Video elements")
                runStudioNext("elements")
            } else if (triesLeft <= 0) {
                failStudio("video belum selesai diproses sesudah 30 menit")
            } else {
                if (triesLeft % 6 == 0) {
                    val pct = parts.getOrNull(1) ?: ""
                    writeStudioStatus("running", "video sedang diproses${if (pct.isNotBlank()) " ($pct%)" else ""} — menunggu")
                }
                studioHandler.postDelayed({ waitStudioProcessing(triesLeft - 1) }, 10000)
            }
        }
    }

    /** Langkah 8 upload: klik Next masuk ke langkah berikut.
     *  Verifikasi pakai isi KHAS langkah tujuan (daftar langkah di
     *  sidebar menampilkan semua nama langkah, jadi nama langkah
     *  saja bukan bukti): elements = tombol "Add an end screen" /
     *  "Add cards"; checks = teks copyright / "No issues found";
     *  visibility = radio/button "Schedule". Tombol Next yang
     *  nonaktif (langkah Checks menunggu pemeriksaan) ditunggu
     *  sampai aktif (maks 3 menit untuk checks, 30 dtk lainnya). */
    private fun runStudioNext(step: String) {
        if (pendingStudioAction == null) return
        val verifyJs = when (step) {
            "elements" -> """(function(){var d=document.querySelector('ytcp-uploads-dialog')||document;var t=d.innerText||'';return (t.indexOf('Add an end screen')>=0||t.indexOf('Tambah layar akhir')>=0||t.indexOf('Add cards')>=0||t.indexOf('Tambah kartu')>=0)?'1':'0';})()"""
            "checks" -> """(function(){var d=document.querySelector('ytcp-uploads-dialog')||document;var t=d.innerText||'';return (/copyright|hak cipta/i.test(t)||t.indexOf('No issues found')>=0||t.indexOf('Tidak ada masalah')>=0)?'1':'0';})()"""
            else -> """(function(){var d=document.querySelector('ytcp-uploads-dialog')||document;var t=d.innerText||'';return (t.indexOf('Schedule')>=0||t.indexOf('Jadwalkan')>=0)?'1':'0';})()"""
        }
        val maxWait = if (step == "checks") 90 else 15
        waitStudioNextEnabled(step, verifyJs, maxWait)
    }

    private fun waitStudioNextEnabled(step: String, verifyJs: String, triesLeft: Int) {
        if (pendingStudioAction == null) return
        val js = """(function(){var root=document.querySelector('ytcp-uploads-dialog')||document;var bs=[...root.querySelectorAll('button')].filter(function(b){return b.offsetParent!==null;});for(var i=0;i<bs.length;i++){var l=(bs[i].getAttribute('aria-label')||'').trim().toLowerCase();var tx=(bs[i].textContent||'').trim().toLowerCase();if(l==='next'||l==='berikutnya'||tx==='next'||tx==='berikutnya'){return bs[i].disabled?'disabled':'1';}}return '0';})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            when (clean) {
                "1" -> {
                    val labels = org.json.JSONArray(listOf("next", "berikutnya")).toString()
                    studioClickThenVerify(labels, verifyJs, true, false, {
                        writeStudioStatus("running", "masuk langkah $step")
                        when (step) {
                            "elements" -> runStudioNext("checks")
                            "checks" -> runStudioNext("visibility")
                            else -> runStudioVisibility()
                        }
                    }, {
                        failStudio("tombol Next tidak membawa ke langkah $step")
                    })
                }
                "disabled" -> {
                    if (triesLeft <= 0) {
                        failStudio("tombol Next tetap nonaktif di langkah $step")
                    } else {
                        studioHandler.postDelayed({ waitStudioNextEnabled(step, verifyJs, triesLeft - 1) }, 2000)
                    }
                }
                else -> failStudio("tombol Next tidak ketemu di langkah $step")
            }
        }
    }

    /** Langkah 9 upload: pilih visibilitas. "now" = radio Public,
     *  lalu tombol Publish. Jadwal = radio Schedule, lalu isi
     *  tanggal + jam. */
    private fun runStudioVisibility() {
        if (pendingStudioAction == null) return
        val schedule = pendingStudioSchedule ?: "now"
        if (schedule == "now") {
            writeStudioStatus("running", "memilih visibilitas Public")
            val labels = org.json.JSONArray(listOf("public", "publik")).toString()
            webView.evaluateJavascript(studioRadioJs(labels, true)) { raw ->
                val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
                if (parts.getOrNull(0) == "1" || parts.getOrNull(0) == "clicked") {
                    runStudioClickPublish()
                } else {
                    failStudio("pilihan visibilitas Public tidak ketemu")
                }
            }
        } else {
            writeStudioStatus("running", "memilih jadwal tayang")
            val labels = org.json.JSONArray(listOf("schedule", "jadwalkan")).toString()
            webView.evaluateJavascript(studioRadioJs(labels, true)) { raw ->
                val parts = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
                if (parts.getOrNull(0) == "1" || parts.getOrNull(0) == "clicked") {
                    runStudioFillSchedule()
                } else {
                    failStudio("pilihan visibilitas Schedule tidak ketemu")
                }
            }
        }
    }

    /** Langkah 10 upload (hanya bila terjadwal): isi tanggal + jam
     *  tayang. Format tanggal mengikuti bahasa UI akun (EN MM/DD/YYYY,
     *  ID DD/MM/YYYY — dicek runStudioUploadDiagnose). Verifikasi
     *  baca-balik longgar: nilai berisi nomor hari + nomor bulan
     *  (atau nama bulan EN/ID). Jam EN dicoba dulu 24-jam, lalu
     *  12-jam ("2:30 PM") bila tidak menempel. */
    private fun runStudioFillSchedule() {
        val schedule = pendingStudioSchedule ?: return
        val parts = schedule.split(" ")
        if (parts.size != 2) {
            failStudio("format jadwal tidak valid: $schedule")
            return
        }
        val dp = parts[0].split("-")
        if (dp.size != 3) {
            failStudio("format tanggal tidak valid: ${parts[0]}")
            return
        }
        val timePart = parts[1]
        val dateText = if (studioUiLang == "id") {
            "${dp[2]}/${dp[1]}/${dp[0]}"
        } else {
            "${dp[1]}/${dp[2]}/${dp[0]}"
        }
        writeStudioStatus("running", "mengisi tanggal + jam tayang ($schedule)")
        webView.evaluateJavascript(studioFillJs("date", dateText)) { raw ->
            val filled = (raw?.trim()?.removeSurrounding("\"") ?: "").split("|")
            val dateVal = filled.getOrNull(1) ?: ""
            val day = dp[2].toIntOrNull() ?: 0
            val month = dp[1].toIntOrNull() ?: 0
            val monthAbbrs = when (month) {
                1 -> listOf("jan")
                2 -> listOf("feb")
                3 -> listOf("mar")
                4 -> listOf("apr")
                5 -> listOf("may", "mei")
                6 -> listOf("jun")
                7 -> listOf("jul")
                8 -> listOf("aug", "agu")
                9 -> listOf("sep")
                10 -> listOf("oct", "okt")
                11 -> listOf("nov")
                12 -> listOf("dec", "des")
                else -> emptyList()
            }
            val low = dateVal.lowercase()
            val dayOk = day > 0 && Regex("\\b$day\\b").containsMatchIn(low)
            val monthOk = month > 0 && (Regex("\\b$month\\b").containsMatchIn(low) || monthAbbrs.any { low.contains(it) })
            if (filled.getOrNull(0)?.toIntOrNull() ?: 0 <= 0 || !(dateVal.contains(dateText) || (dayOk && monthOk))) {
                failStudio("tanggal tayang tidak terisi (terbaca: $dateVal)")
                return@evaluateJavascript
            }
            webView.evaluateJavascript(studioFillJs("time", timePart)) { raw2 ->
                val filled2 = (raw2?.trim()?.removeSurrounding("\"") ?: "").split("|")
                val timeVal = filled2.getOrNull(1) ?: ""
                if (timeVal.contains(timePart)) {
                    runStudioClickPublish()
                } else if (studioUiLang == "en") {
                    val h = timePart.substring(0, 2).toIntOrNull() ?: 0
                    val m = timePart.substring(3, 5)
                    val h12 = if (h % 12 == 0) 12 else h % 12
                    val ampm = if (h < 12) "AM" else "PM"
                    val time12 = "$h12:$m $ampm"
                    webView.evaluateJavascript(studioFillJs("time", time12)) { raw3 ->
                        val filled3 = (raw3?.trim()?.removeSurrounding("\"") ?: "").split("|")
                        val timeVal3 = filled3.getOrNull(1) ?: ""
                        if (timeVal3.contains(time12) || timeVal3.contains("$h12:$m")) {
                            runStudioClickPublish()
                        } else {
                            failStudio("jam tayang tidak terisi (terbaca: $timeVal3)")
                        }
                    }
                } else {
                    failStudio("jam tayang tidak terisi (terbaca: $timeVal)")
                }
            }
        }
    }

    /** Langkah terakhir upload: klik Schedule (terjadwal) atau Publish
     *  (now). Satu-satunya langkah yang memakai tap beneran sebagai
     *  fallback (tombol satu-kali; dialog ketutup sesudah klik).
     *  Verifikasi: dialog upload ketutup (kolom judul hilang). Baru
     *  sesudah itu verifikasi isi Content — status "done" HANYA bila
     *  video muncul di daftar (klik saja bukan bukti). */
    private fun runStudioClickPublish() {
        if (pendingStudioAction == null) return
        val scheduled = (pendingStudioSchedule ?: "now") != "now"
        val labels = org.json.JSONArray(
            if (scheduled) listOf("schedule", "jadwalkan") else listOf("publish", "publikasikan")
        ).toString()
        val verifyJs = """(function(){var tas=[...document.querySelectorAll('textarea,[contenteditable],input,[role="textbox"]')].filter(function(t){return t.offsetParent!==null;});for(var i=0;i<tas.length;i++){var s=((tas[i].getAttribute('aria-label')||'')+' '+(tas[i].getAttribute('placeholder')||'')).toLowerCase();if(s.indexOf('title')>=0||s.indexOf('judul')>=0)return '0';}return '1';})()"""
        writeStudioStatus("running", if (scheduled) "menjadwalkan video" else "menerbitkan video")
        studioClickThenVerify(labels, verifyJs, true, true, {
            writeStudioStatus("running", "video terkirim — memverifikasi di daftar Content")
            runStudioVerifyUpload(6)
        }, {
            failStudio("tombol ${if (scheduled) "Schedule" else "Publish"} tidak menyelesaikan upload")
        })
    }

    /** Verifikasi akhir upload: video HARUS muncul di daftar Content
     *  (cocok judul persis). Buka halaman Content lewat URL langsung
     *  (channel id dari diagnose) atau klik nav bila id tidak ketahuan. */
    private fun runStudioVerifyUpload(triesLeft: Int) {
        if (pendingStudioAction == null) return
        val title = pendingStudioTitle ?: return
        val contentUrl = studioChannelId?.let { "https://studio.youtube.com/channel/$it/videos" }
        if (contentUrl != null && currentUrl != contentUrl) {
            webView.loadUrl(contentUrl)
            studioHandler.postDelayed({ runStudioVerifyUploadCheck(title, triesLeft) }, 5000)
            return
        }
        if (contentUrl == null && !currentUrl.contains("/videos")) {
            runStudioGoContent({ runStudioVerifyUploadCheck(title, triesLeft) })
            return
        }
        runStudioVerifyUploadCheck(title, triesLeft)
    }

    private fun runStudioVerifyUploadCheck(title: String, triesLeft: Int) {
        if (pendingStudioAction == null) return
        if (!currentUrl.contains("/videos")) {
            if (triesLeft <= 0) {
                failStudio("halaman Content tidak kebuka untuk verifikasi")
            } else {
                studioHandler.postDelayed({ runStudioVerifyUploadCheck(title, triesLeft - 1) }, 3000)
            }
            return
        }
        val js = """(function(){var rows=[];var links=[...document.querySelectorAll('a[href*="/video/"]')].filter(function(a){return a.offsetParent!==null;});for(var i=0;i<links.length;i++){var h=links[i].getAttribute('href')||'';if(h.indexOf('/edit')<0)continue;var mm=h.match(/\/video\/([A-Za-z0-9_-]{11})/);if(!mm)continue;var t=(links[i].textContent||'').replace(/\s+/g,' ').trim();if(t)rows.push(t+'^'+mm[1]);}return rows.join('~');})()"""
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"") ?: ""
            var foundId: String? = null
            if (clean.isNotBlank()) {
                for (row in clean.split("~")) {
                    val p = row.split("^")
                    if (p.size == 2 && p[0].equals(title, ignoreCase = true)) {
                        foundId = p[1]
                        break
                    }
                }
            }
            if (foundId != null) {
                val scheduled = (pendingStudioSchedule ?: "now") != "now"
                val data = org.json.JSONObject().apply {
                    put("title", title)
                    put("videoId", foundId)
                    put("videoUrl", "https://www.youtube.com/watch?v=$foundId")
                    put("visibility", if (scheduled) "scheduled" else "public")
                    if (scheduled) put("scheduledAt", pendingStudioSchedule)
                }
                studioRunning = false
                pendingStudioAction = null
                pendingStudioFile = null
                pendingStudioTitle = null
                pendingStudioDescription = null
                pendingStudioSchedule = null
                studioUploadFileName = null
                writeStudioStatus("done", "video ${if (scheduled) "dijadwalkan" else "diterbitkan"}: $title", data)
            } else if (triesLeft <= 0) {
                failStudio("video tidak muncul di daftar Content sesudah ${if ((pendingStudioSchedule ?: "now") != "now") "dijadwalkan" else "diterbitkan"} — kemungkinan masih diproses; cek manual di Studio")
            } else {
                studioHandler.postDelayed({ runStudioVerifyUploadCheck(title, triesLeft - 1) }, 10000)
            }
        }
    }

    // ================= AKHIR STUDIO API =================

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
     *  Daftar = Space AKTIF (sudah login) selain Space ini. Bila tidak
     *  ada Space aktif lain, beri tahu.
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

    /** v0.1.78 — shed terkendali dari MemoryGuard: simpan state WebView
     *  (jalur v0.1.77), lepas renderer + JS heap (destroy), tapi
     *  activity + proses + KeepAlive tetap hidup. Pemulihan saat Space
     *  dibuka lagi: onResume melihat flag → recreate() → onCreate
     *  memulihkan state shed (bila ada) atau URL terakhir dari snapshot
     *  (v0.1.77). Abaikan bila Space ini sedang terlihat (balapan
     *  dengan guard) atau WebView sudah di-shed / belum dibuat.
     *  v0.1.79: badan dipindah ke shedWebViewNow (dipakai juga
     *  onTrimMemory); fungsi ini tinggal memeriksa slot +
     *  foreground. */
    private fun onShedBroadcast(intent: android.content.Intent) {
        val slot = intent.getIntExtra(WatchdogGuardService.EXTRA_SHED_SLOT, -1)
        val mySlot = try {
            SpaceRegistry.load(this).firstOrNull {
                SpaceRegistry.suffixForSlot(it.slot) == dataDirectorySuffix
            }?.slot
        } catch (e: Exception) {
            null
        }
        if (slot < 0 || slot != mySlot) return
        if (isForeground) return
        if (!::webView.isInitialized || shedWebViewDestroyed) return
        shedWebViewNow("broadcast")
    }

    /** v0.1.79 — jalur shed yang dipakai onShedBroadcast (broadcast
     *  guard) dan onTrimMemory (tekanan RAM sistem): simpan state
     *  WebView (jalur v0.1.77), lepas renderer + JS heap (destroy),
     *  lalu tulis marker konfirmasi shed-<suffix>.json untuk tick
     *  guard. Activity + proses + KeepAlive tetap hidup; pemulihan
     *  saat Space dibuka lagi: onResume melihat flag → recreate()
     *  → onCreate memulihkan state shed atau URL terakhir dari
     *  snapshot (v0.1.77). Penelepon WAJIB memeriksa dulu: bukan
     *  foreground, WebView ada + belum di-shed, tidak generating
     *  (trim). */
    private fun shedWebViewNow(reason: String) {
        // Simpan state (v0.1.77) sebelum destroy — onCreate berikut
        // memulihkannya bila snapshot URL tidak cukup.
        try {
            val bundle = Bundle()
            webView.saveState(bundle)
            shedStateBySuffix[dataDirectorySuffix] = bundle
        } catch (_: Exception) {
        }
        stopLiveCapture()
        // Urutan hancur yang aman (komentar onDestroy): pop-up dulu,
        // baru WebView utama — pop-up pegang referensi ke WebView utama.
        try {
            popupView?.let {
                popupView = null
                rootLayout.removeView(it)
                it.destroy()
            }
        } catch (_: Exception) {
        }
        try {
            rootLayout.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {
        }
        shedWebViewDestroyed = true
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        // v0.1.79: marker konfirmasi untuk tick guard — guard
        // menghitung shed_confirmed_count dari file ini (JANGAN
        // dihapus guard; ini bukti terakhir yang dibaca verifikasi
        // live). Atomik tmp+rename, pola api-token v0.1.77.
        val shedMs = System.currentTimeMillis()
        try {
            val mySlot = try {
                SpaceRegistry.load(this).firstOrNull {
                    SpaceRegistry.suffixForSlot(it.slot) == dataDirectorySuffix
                }?.slot
            } catch (e: Exception) {
                null
            } ?: -1
            val obj = org.json.JSONObject().apply {
                put("slot", mySlot)
                put("shed_ms", shedMs)
                put("source", reason)
            }
            val f = java.io.File(filesDir, "shed-$dataDirectorySuffix.json")
            val tmp = java.io.File(filesDir, "shed-$dataDirectorySuffix.json.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) f.writeText(obj.toString())
        } catch (_: Exception) {
        }
        android.util.Log.i(
            "SenzWatchdog",
            "shedWebViewNow($reason): WebView $dataDirectorySuffix di-shed (saveState + destroy + marker)"
        )
    }

    /** v0.1.79 — tekanan RAM sistem: shed mandiri per-Space tanpa
     *  menunggu tick guard 60 dtk. Tiap proses :spaceN punya
     *  instance sendiri, jadi callback ini milik proses itu saja.
     *  Hanya pada RUNNING_CRITICAL / COMPLETE; hanya bila Space
     *  ini bukan foreground dan snapshot terakhirnya tidak
     *  generating. Semua Space background non-generating boleh
     *  shed bersamaan saat kritis — itu respons yang benar. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) return
        if (activityDestroyed) return
        if (isForeground) return
        if (!::webView.isInitialized || shedWebViewDestroyed) return
        if (snapshotGenerating()) return
        shedWebViewNow("trim")
    }

    /** v0.1.79 — snapshot terakhir Space ini bilang generating?
     *  Pola sama seperti WatchdogGuardService.snapshotGenerating. */
    private fun snapshotGenerating(): Boolean {
        return try {
            val f = java.io.File(filesDir, "api-page-$dataDirectorySuffix.json")
            if (!f.isFile) false
            else org.json.JSONObject(f.readText()).optBoolean("generating", false)
        } catch (_: Exception) {
            false
        }
    }

    /** v0.1.77 — simpan state WebView supaya onCreate bisa
     *  memulihkannya bila activity dibuat ulang sistem (tekanan RAM).
     *  Tanpa ini, blok loadUrl di onCreate dilewati (savedInstanceState
     *  != null) dan WebView segar tanpa URL → blank selamanya. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            try {
                webView.saveState(outState)
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // v0.1.78: WebView di-shed MemoryGuard — buat ulang activity
        // (WebView segar + restore v0.1.77). Jangan sentuh WebView
        // yang sudah di-destroy (url/draw melempar).
        if (shedWebViewDestroyed) {
            shedWebViewDestroyed = false
            recreate()
            return
        }
        // v0.1.78: promosikan service guard bila ada yang gagal
        // dipromosikan sebelumnya (flag guard_needs_promotion).
        GuardPromoter.promoteIfNeeded(this)
        // v0.1.31: hidupkan lagi timer tangkap live (idempotent) — bila
        // activity sempat dihancurkan sistem selagi Space dibekukan,
        // timer mati dan "Nyalakan Live" saja tidak menghidupkannya lagi
        // (temuan HP: live tidak aktif lagi setelah dibekukan).
        startLiveCapture()
        // v0.1.77: jaring pengaman — Space menjadi tampak tapi WebView
        // tanpa URL (activity dibuat ulang / renderer mati tanpa
        // handler) → muat URL terakhir. Idempotent: hanya jalan saat
        // URL kosong, tidak pernah memuat ulang halaman yang ada.
        // Bersihkan data dikecualikan: clearSpaceDataAndLoad memuat
        // homeUrl sendiri sesudah menghapus data.
        if (::webView.isInitialized && isWebViewBlank() &&
            !intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_DATA, false)
        ) {
            loadLastUrlOrHome()
        }
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
        // v0.1.83: perintah rekam manual /tts-record susulan.
        consumeApiTtsRecord(intent)
        // v0.1.60: payload eksekutor Studio susulan.
        consumeApiStudio(intent)
        // v0.1.52: URL navigasi API susulan.
        consumeApiNavigate(intent)
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
     *  dibaca lewat bridge + dilihat di File Manager). */
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
            if (!isBridgeOriginAllowed()) return
            logBlobDiag("UNDUH-WEB diag: $msg")
        }

        @android.webkit.JavascriptInterface
        fun onMeta(totalStr: String, cdName: String) {
            if (!isBridgeOriginAllowed()) return
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
            if (!isBridgeOriginAllowed()) return
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
            if (!isBridgeOriginAllowed()) return
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
            if (!isBridgeOriginAllowed()) return
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

    /** Ambil isi blob (v0.1.17, diperbaiki v0.1.81): 1) cache frame
     *  utama (Claude, blob:https://...), 2) tanya semua iframe via
     *  postMessage (AI Studio, blob:null/... — cache hidup di iframe
     *  pembuat, dipasang lewat injeksi dokumen; tiap frame yang
     *  menerima JUGA mencoba fetch(url) di konteksnya sendiri —
     *  frame pemilik blob BISA fetch karena same-origin), 3) fallback
     *  rekam-ulang via MediaRecorder dari <audio> di frame utama,
     *  4) XHR terakhir, lalu gagal. Bendera __senzBlobDone mencegah
     *  simpan ganda bila dua jalur sama-sama berhasil. */
    /** v0.1.82 (A2): suntik core+trap blob via evaluateJavascript saat
     *  halaman selesai dimuat. evaluateJavascript TIDAK tunduk pada CSP
     *  halaman — jadi core+trap PASTI ada di frame utama apapun CSP-nya
     *  (belt-and-braces di samping injeksi jaringan shouldInterceptRequest,
     *  yang buktinya tidak jalan di halaman AI Studio baru: tidak ada
     *  baris 'dl scan'/'TRAP' di log 24 Nov 13:21). Idempoten (guard
     *  __senzBlobCache/__senzTrapInstalled di JS) — aman tiap
     *  onPageFinished. Hanya host trap (Claude/AI Studio). Dipanggil
     *  SEBELUM rantai executor (maybeRunApi*) supaya core sudah ada
     *  saat executor bekerja; evaluateJavascript dieksekusi berurutan,
     *  jadi suntikan ini jalan sebelum evaluateJavascript executor. */
    private fun ensureBlobCoreInjected(url: String) {
        if (!::webView.isInitialized) return
        try {
            val host = android.net.Uri.parse(url).host ?: return
            if (!isTrapHost(host)) return
            webView.evaluateJavascript(blobInjectScript(), null)
        } catch (_: Exception) {
        }
    }

    private fun downloadBlob(dlUrl: String, fileName: String) {
        if (!::webView.isInitialized) return
        pendingBlobFileName = fileName
        // v0.1.81 (B3): penghitung diagnostik baru per unduhan.
        blobDlIframes = 0
        blobDlCoreReqs = 0
        blobDlFetchTried = false
        blobDlFetchOk = false
        lastBlobError = ""
        val escaped = dlUrl.replace("\\", "\\\\").replace("'", "\\'")
        val js = "(function(){try{" +
            "var url='$escaped';" +
            "window.__senzPendingBlobUrl=url;" +
            "window.__senzBlobDone=false;" +
            "window.__senzXhrTried=false;" +
            "window.__senzFallbackErr='';" +
            "var name='';" +
            "try{name=(window.__senzBlobName&&window.__senzBlobName(url))||'';}catch(e){}" +
            "if(!name){try{var as=document.querySelectorAll('a[download]');" +
            "for(var ai=0;ai<as.length;ai++){" +
            "if(as[ai].href===url&&as[ai].getAttribute('download'))" +
            "{name=as[ai].getAttribute('download');break;}}}catch(e){}}" +
            "var cached='';" +
            "try{cached=(window.__senzBlobGet&&window.__senzBlobGet(url))||'';}catch(e){}" +
            "var senzAllIframes=function(){var out=[];var walk=function(root){try{var els=root.querySelectorAll('iframe');for(var i=0;i<els.length;i++)out.push(els[i]);var all=root.querySelectorAll('*');for(var j=0;j<all.length;j++){var sr=null;try{sr=all[j].shadowRoot;}catch(e){}if(sr)walk(sr);}}catch(e){}};walk(document);return out;};" +
            "var fs0=senzAllIframes();" +
            "try{window.SenzBlob.onBlobDiag('dl url='+url.slice(0,60)+',iframes='+fs0.length+',hit='+(cached!==''));}catch(e){}" +
            "try{if(window.__senzTrapRescan){var sc=window.__senzTrapRescan();try{window.SenzBlob.onBlobDiag('dl scan: '+sc);}catch(e){}}}catch(e){}" +
            "if(cached){window.__senzBlobDone=true;window.SenzBlob.onBlobData(cached,name);return;}" +
            "var postAll=function(){var fs=senzAllIframes();for(var i=0;i<fs.length;i++){try{fs[i].contentWindow.postMessage({type:'senz-get-blob',url:url},'*');}catch(e){}}};" +
            "var postSelf=function(){try{window.postMessage({type:'senz-get-blob',url:url},'*');}catch(e){}};" +
            "postAll();postSelf();" +
            "setTimeout(function(){try{if(window.__senzBlobDone)return;postAll();postSelf();}catch(e){}},3000);" +
            "var senzTryXhr=function(){try{" +
            "if(window.__senzBlobDone)return;" +
            "if(window.__senzXhrTried)return;window.__senzXhrTried=true;" +
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
            "xhr.onerror=function(){var fb=window.__senzFallbackErr||'';window.SenzBlob.onBlobError('xhr-error status='+xhr.status+(fb?(' (fallback gagal: '+fb+')'):''));};" +
            "xhr.ontimeout=function(){window.SenzBlob.onBlobError('xhr-timeout');};" +
            "xhr.send();}catch(e){window.SenzBlob.onBlobError('js-exception '+e.message);}};" +
            "var senzTryRecorder=function(){try{" +
            "var findAudio=function(root){try{var aus=root.querySelectorAll('audio');for(var ai2=0;ai2<aus.length;ai2++){if(aus[ai2].src===url)return {el:aus[ai2],sh:(root!==document)};}}catch(e){}try{var all=root.querySelectorAll('*');for(var j=0;j<all.length;j++){var sr=null;try{sr=all[j].shadowRoot;}catch(e){}if(sr){var r=findAudio(sr);if(r)return r;}}}catch(e){}return null;};" +
            "var found=findAudio(document);var a=found?found.el:null;var audioInShadow=found?found.sh:false;" +
            "var shadowFail=function(rs){try{if(audioInShadow&&window.SenzBlob)window.SenzBlob.onBlobDiag('fallback: audio ketemu di shadow DOM tapi '+rs);}catch(e){}};" +
            "if(!a){try{window.SenzBlob.onBlobDiag('fallback: tidak ada <audio> untuk url ini');}catch(e){}senzTryXhr();return;}" +
            "var dur=(a.duration&&isFinite(a.duration))?a.duration:60;" +
            "try{window.SenzBlob.onBlobDiag('fallback: rekam ulang audio durasi='+Math.ceil(dur)+' dtk'+(audioInShadow?' di shadow DOM':''));}catch(e){}" +
            "try{window.SenzBlob.onBlobFallbackStart(String(Math.ceil(dur)));}catch(e){}" +
            "var stream=null;" +
            "try{stream=a.captureStream?a.captureStream():(a.mozCaptureStream?a.mozCaptureStream():null);}catch(e){}" +
            "if(!stream){shadowFail('captureStream tidak tersedia');window.__senzFallbackErr='captureStream tidak tersedia';senzTryXhr();return;}" +
            "var mime='';" +
            "try{if(window.MediaRecorder&&MediaRecorder.isTypeSupported('audio/webm;codecs=opus'))mime='audio/webm;codecs=opus';}catch(e){}" +
            "var rec=null;var dead=false;" +
            "try{rec=mime?new MediaRecorder(stream,{mimeType:mime}):new MediaRecorder(stream);}catch(e){shadowFail('MediaRecorder tidak bisa dibuat');window.__senzFallbackErr='MediaRecorder tidak bisa dibuat ('+(e.message||e)+')';senzTryXhr();return;}" +
            "var chunks=[];var stopped=false;" +
            "rec.ondataavailable=function(e){if(e.data&&e.data.size)chunks.push(e.data);};" +
            "rec.onstop=function(){if(dead)return;try{" +
            "var b=new Blob(chunks,{type:rec.mimeType||'audio/webm'});" +
            "var r=new FileReader();" +
            "r.onloadend=function(){if(dead)return;if(window.__senzBlobDone)return;window.__senzBlobDone=true;var s=r.result||'';var i=s.indexOf(',');window.SenzBlob.onBlobData(i>=0?s.substring(i+1):s,name);};" +
            "r.onerror=function(){window.SenzBlob.onBlobError('fallback: reader-error');};" +
            "r.readAsDataURL(b);}catch(e){window.SenzBlob.onBlobError('fallback: '+(e.message||e));}};" +
            "rec.onerror=function(){if(dead)return;window.__senzFallbackErr='recorder-error';senzTryXhr();};" +
            "var maxMs=Math.min(300000,(dur+15)*1000);" +
            "var stopOnce=function(){if(stopped)return;stopped=true;try{rec.stop();}catch(e){}try{a.pause();}catch(e){}};" +
            "try{a.onended=function(){stopOnce();};}catch(e){}" +
            "setTimeout(stopOnce,maxMs);" +
            "try{a.currentTime=0;}catch(e){}" +
            "var p=null;try{p=a.play();}catch(e){dead=true;shadowFail('play ditolak');window.__senzFallbackErr='play ditolak ('+(e.message||e)+')';senzTryXhr();return;}" +
            "if(p&&p.catch){p.catch(function(e){if(dead)return;dead=true;try{rec.stop();}catch(e){}shadowFail('play ditolak');window.__senzFallbackErr='play ditolak ('+(e.message||e)+')';senzTryXhr();});}" +
            "try{rec.start(1000);}catch(e){if(!dead){shadowFail('rec.start gagal');window.__senzFallbackErr='rec.start gagal ('+(e.message||e)+')';senzTryXhr();}}" +
            "}catch(e){window.SenzBlob.onBlobError('fallback: js '+(e.message||e));}};" +
            "setTimeout(function(){try{if(window.__senzBlobDone)return;senzTryRecorder();}catch(e){window.SenzBlob.onBlobError('js-exception '+e.message);}},15000);" +
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
            if (!isBridgeOriginAllowed()) return
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
            if (!isBridgeOriginAllowed()) return
            // v0.1.81 (B3): hitung jawaban frame untuk diagnosa gagal.
            try {
                if (info.startsWith("dl url=")) {
                    blobDlIframes = Regex("iframes=(\\d+)").find(info)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    blobDlCoreReqs = 0
                    blobDlFetchTried = false
                    blobDlFetchOk = false
                } else if (info.startsWith("core: req ")) {
                    blobDlCoreReqs++
                } else if (info.startsWith("core: fetch-try")) {
                    blobDlFetchTried = true
                } else if (info.startsWith("core: fetch-ok")) {
                    blobDlFetchOk = true
                }
            } catch (_: Exception) {
            }
            logBlobDiag(info)
        }

        /** v0.1.81 (B4): fallback rekam-ulang mulai — perpanjang
         *  tunggu file TTS selama durasi audio + margin (maks 5 mnt)
         *  supaya waitTtsFile tidak fail di tengah rekaman. Hanya
         *  berlaku saat run TTS aktif.
         *  v0.1.83 (C1): rekam manual (/tts-record) juga memakai
         *  jalur ini — tenggat ditulis + status rekam diperbarui,
         *  walau pendingTtsScript null. */
        @android.webkit.JavascriptInterface
        fun onBlobFallbackStart(durationSec: String) {
            if (!isBridgeOriginAllowed()) return
            val dur = durationSec.toIntOrNull() ?: 60
            val waitMs = minOf(300000L, (dur + 30L) * 1000L)
            ttsFallbackDeadlineMs = System.currentTimeMillis() + waitMs
            if (ttsRecordManual) {
                writeTtsRecordStatus("running", "merekam ulang audio dari player (durasi ${dur} dtk)", ttsRecordFileName)
                return
            }
            if (pendingTtsScript == null) return
            writeTtsStatus("running", "mengunduh via rekam ulang audio (fallback, ~${dur} dtk)")
        }

        /** v0.1.84: rekam langsung gagal (async — metadata / play /
         *  recorder / monitor, dari failAsync di directRecordJs).
         *  manual=true: status rekam failed; manual=false: failTts
         *  (failTts membersihkan pendingTtsScript sehingga
         *  waitTtsFile yang sudah terjadwal langsung kembali). */
        @android.webkit.JavascriptInterface
        fun onBlobRecordFail(reason: String) {
            if (!isBridgeOriginAllowed()) return
            val msg = when (reason) {
                "metadata-timeout" -> "metadata audio tidak ke-load (10 dtk)"
                "play-denied" -> "play audio ditolak halaman"
                "not-playing" -> "audio tidak kunjung main (play tidak jalan)"
                "no-stream" -> "captureStream tidak tersedia"
                "no-recorder" -> "MediaRecorder tidak bisa dibuat"
                "rec-start-fail" -> "rec.start gagal"
                "recorder-error" -> "MediaRecorder error saat rekam"
                "spa-detached" -> "elemen audio SPA diganti saat rekam"
                else -> "rekam langsung gagal ($reason)"
            }
            logBlobDiag("record: gagal — $msg")
            if (ttsRecordManual) {
                ttsRecordManual = false
                ttsFallbackDeadlineMs = 0
                writeTtsRecordStatus("failed", msg)
            } else if (pendingTtsScript != null) {
                failTts("fallback: $msg — file VO tidak tersimpan")
            }
        }

        /** v0.1.84: verdict gate rekam langsung (async, dari onstop
         *  JS — dipanggil SEBELUM onBlobData, jadi verdict selalu
         *  tersedia saat file ditulis). 'ok' -> file boleh "done";
         *  'empty'/'short' -> gagal jujur SEKARANG (JS tidak
         *  mengirim onBlobData, jadi tidak ada file; fail di sini
         *  supaya status tidak menggantung "running"). Detail
         *  selalu mencantumkan detik tertangkap vs durasi audio. */
        @android.webkit.JavascriptInterface
        fun onBlobRecordDone(capturedSec: String, durationSec: String, chunks: String, bytes: String, verdict: String) {
            if (!isBridgeOriginAllowed()) return
            ttsRecordGateCapturedSec = capturedSec.toDoubleOrNull() ?: 0.0
            ttsRecordGateDurationSec = durationSec.toDoubleOrNull() ?: 0.0
            ttsRecordGateChunks = chunks.toIntOrNull() ?: 0
            ttsRecordGateBytes = bytes.toLongOrNull() ?: 0
            ttsRecordGateVerdict = verdict
            val cap = "%.1f".format(java.util.Locale.US, ttsRecordGateCapturedSec)
            val dur = "%.1f".format(java.util.Locale.US, ttsRecordGateDurationSec)
            ttsRecordGateDetail = when (verdict) {
                "ok" -> "tertangkap $cap dtk dari $dur dtk (${ttsRecordGateChunks} chunk, ${ttsRecordGateBytes} byte)"
                "empty" -> "rekaman kosong (0 byte)"
                else -> "rekaman tidak lengkap: tertangkap $cap dtk dari $dur dtk (currentTime terakhir $cap dtk, ${ttsRecordGateChunks} chunk)"
            }
            logBlobDiag("record-gate: $verdict — ${ttsRecordGateDetail}")
            if (verdict == "ok") return
            if (ttsRecordManual) {
                ttsRecordManual = false
                ttsFallbackDeadlineMs = 0
                writeTtsRecordStatus("failed", ttsRecordGateDetail)
            } else if (pendingTtsScript != null) {
                failTts("fallback: ${ttsRecordGateDetail} — file VO tidak tersimpan")
            }
        }

        @android.webkit.JavascriptInterface
        fun onBlobError(reason: String) {
            if (!isBridgeOriginAllowed()) return
            // v0.1.81 (B3): kegagalan membawa rantai diagnosa —
            // berapa iframe ditanya, berapa menjawab, apakah ada
            // frame yang mencoba fetch di konteksnya sendiri.
            val diag = "iframe ditanya: $blobDlIframes, menjawab core: req: $blobDlCoreReqs, fetch-try: ${if (blobDlFetchTried) "ya" else "tidak"}${if (blobDlFetchOk) " (fetch-ok)" else ""}"
            lastBlobError = "$reason ($diag)"
            logBlobDiag("GAGAL: $reason ($diag)")
            // v0.1.84: bila error datang dari rekam manual (mis.
            // FileReader gagal sesudah gate ok), jangan biarkan
            // status menggantung "running" — gagal jujur.
            if (ttsRecordManual) {
                ttsRecordManual = false
                ttsFallbackDeadlineMs = 0
                writeTtsRecordStatus("failed", reason)
            }
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
     *  dalamnya.
     *  v0.1.81: perangkap jalan di SETIAP frame (iframe bersarang),
     *  hook attachShadow (shadow DOM), insertAdjacentHTML/innerHTML/
     *  Document.write, + __senzTrapRescan (pindai ulang saat unduh).
     *  Core: bila cache miss, frame mencoba fetch(url) di konteksnya
     *  sendiri (frame pemilik blob bisa — same-origin) + fallback
     *  MediaRecorder dari <audio> di frame utama.
     *  v0.1.82: heartbeat 'core: installed'/'trap: installed' saat
     *  terpasang (diagnosis pasti bila injeksi tidak mengeksekusi);
     *  trapNode + rescan menembus shadow DOM REKURSIF (walkRoots),
     *  rescan melaporkan shadowRoots; downloadBlob mencoba juga
     *  fetch di konteks frame utama (postSelf); fallback MediaRecorder
     *  mencari <audio> di shadow root (rekursif) + diag bila ketemu
     *  tapi captureStream/play ditolak. Suntikan utama kini JUGA via
     *  evaluateJavascript di onPageFinished (ensureBlobCoreInjected,
     *  tidak tunduk CSP) — injeksi jaringan dipertahankan. */
    private fun blobInjectScript(): String {
        val core = "(function(){try{" +
            "if(window.__senzBlobCache)return;" +
            "window.__senzBlobCache={};" +
            "window.__senzBlobNames={};" +
            "window.__senzBlobDone=false;" +
            "window.__senzPendingReqs={};" +
            "window.__senzFetchTried={};" +
            "try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: installed '+location.href);}catch(e){}" +
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
            "setTimeout(function(){try{delete window.__senzPendingReqs[m.url];}catch(e){}},20000);" +
            "try{" +
            "if(!window.__senzFetchTried[m.url]){window.__senzFetchTried[m.url]=true;" +
            "try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: fetch-try '+m.url.slice(-8));}catch(e){}" +
            "fetch(m.url).then(function(r){" +
            "if(!r.ok){try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: fetch-gagal http '+r.status);}catch(e){}return null;}" +
            "return r.blob();}).then(function(b){" +
            "if(!b)return;" +
            "var rr=new FileReader();" +
            "rr.onloadend=function(){var s=rr.result||'';var i=s.indexOf(',');var d=i>=0?s.substring(i+1):s;" +
            "try{window.__senzBlobCache[m.url]=d;}catch(e){}" +
            "try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: fetch-ok '+m.url.slice(-8));}catch(e){}" +
            "window.__senzReplyBlob(m.url);};" +
            "rr.onerror=function(){try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: fetch-gagal reader');}catch(e){}};" +
            "rr.readAsDataURL(b);}).catch(function(e){" +
            "try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: fetch-gagal '+(e&&e.message?e.message:e));}catch(e){}" +
            "});}}catch(e){try{if(window.SenzBlob)window.SenzBlob.onBlobDiag('core: fetch-gagal js '+e.message);}catch(e){}}" +
            "}" +
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
        // Perangkap iframe (v0.1.18, diperluas v0.1.81) — jalan di
        // SETIAP frame (bukan hanya frame utama, supaya iframe
        // bersarang tertangkap frame induknya sendiri). Menangkap
        // iframe srcdoc / data:text/html saat dimasukkan ke DOM atau
        // saat srcdoc/src di-set, lalu menyisipkan CORE yang sama ke
        // dalamnya. Tulis '<scr'+'ipt>' (bukan literal '</script>')
        // supaya tidak memutus tag <script> injeksi ini sendiri.
        val trap = "(function(){try{" +
            "if(window.__senzTrapInstalled)return;" +
            "window.__senzTrapInstalled=true;" +
            "var CORE=" + org.json.JSONObject.quote(core) + ";" +
            "var wrap=function(h){try{" +
            "if(!h||h.indexOf('__senzBlobCache')!==-1)return h;" +
            "return h+'<scr'+'ipt>'+CORE+'</scr'+'ipt>';}catch(e){return h;}};" +
            "var diag=function(m){try{if(window.SenzBlob)window.SenzBlob.onBlobDiag(m);}catch(e){}};" +
            "try{diag('trap: installed '+location.href+' frames='+(document.querySelectorAll?document.querySelectorAll('iframe').length:0));}catch(e){}" +
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
            "var walkRoots=function(root,fn){try{fn(root);}catch(e){}try{var all=root.querySelectorAll('*');for(var j=0;j<all.length;j++){var sr=null;try{sr=all[j].shadowRoot;}catch(e){}if(sr)walkRoots(sr,fn);}}catch(e){}};" +
            "var trapNode=function(n){try{" +
            "if(!n)return;" +
            "var trapIn=function(root){try{" +
            "if(root.tagName==='IFRAME')trapOne(root);" +
            "if(root.querySelectorAll){var fs=root.querySelectorAll('iframe');" +
            "for(var i=0;i<fs.length;i++)trapOne(fs[i]);}" +
            "}catch(e){}};" +
            "walkRoots(n,trapIn);" +
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
            "var observeRoot=function(root){try{" +
            "var _o=new MutationObserver(function(muts){" +
            "for(var i=0;i<muts.length;i++){var m=muts[i];" +
            "for(var j=0;j<m.addedNodes.length;j++)trapNode(m.addedNodes[j]);}});" +
            "_o.observe(root,{childList:true,subtree:true});}catch(e){}};" +
            "try{observeRoot(document.documentElement||document);}catch(e){}" +
            "try{if(Element.prototype.attachShadow){var _as=Element.prototype.attachShadow;" +
            "Element.prototype.attachShadow=function(init){var sr=_as.call(this,init);try{observeRoot(sr);}catch(e){}try{trapNode(sr);}catch(e){}return sr;};}}catch(e){}" +
            "try{var _de=document.documentElement||document;" +
            "walkRoots(_de,function(rt){try{if(rt!==_de)observeRoot(rt);}catch(e){}try{trapNode(rt);}catch(e){}});}catch(e){}" +
            "try{var _iah=Element.prototype.insertAdjacentHTML;" +
            "Element.prototype.insertAdjacentHTML=function(pos,html){var r=_iah.call(this,pos,html);try{trapNode(this);}catch(e){}return r;};}catch(e){}" +
            "try{var _ihD=Object.getOwnPropertyDescriptor(Element.prototype,'innerHTML');" +
            "if(_ihD&&_ihD.set){var _ihS=_ihD.set;" +
            "Object.defineProperty(Element.prototype,'innerHTML',{configurable:true,enumerable:_ihD.enumerable,get:_ihD.get,set:function(v){var r=_ihS.call(this,v);try{trapNode(this);}catch(e){}return r;}});}}catch(e){}" +
            "try{var _dw=Document.prototype.write;Document.prototype.write=function(){var r=_dw.apply(this,arguments);try{trapNode(document);}catch(e){}return r;};" +
            "var _dwl=Document.prototype.writeln;Document.prototype.writeln=function(){var r=_dwl.apply(this,arguments);try{trapNode(document);}catch(e){}return r;};}catch(e){}" +
            "window.__senzTrapRescan=function(){try{" +
            "var n=0;var m=0;var k=0;" +
            "var scan=function(root){" +
            "try{var fs=root.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){n++;var b=fs[i].__senzTrapped;try{trapOne(fs[i]);}catch(e){}if(fs[i].__senzTrapped&&!b)m++;}}catch(e){}" +
            "try{var all=root.querySelectorAll('*');for(var j=0;j<all.length;j++){var sr=null;try{sr=all[j].shadowRoot;}catch(e){}if(sr){k++;scan(sr);}}}catch(e){}" +
            "};" +
            "try{trapNode(document.documentElement||document);}catch(e){}" +
            "scan(document);" +
            "return 'iframes='+n+' trapped='+m+' shadowRoots='+k;" +
            "}catch(e){return 'iframes=? trapped=? shadowRoots=?';}};" +
            "try{trapNode(document.documentElement||document);}catch(e){}" +
            "}catch(e){}})();"
        return core + trap
    }

    /** v0.1.75 (audit #3): path semu skrip penangkap blob — tidak
     *  ada di server asli; shouldInterceptRequest melayaninya dari
     *  memori supaya CSP 'self' mengizinkannya tanpa strip. */
    private val TRAP_SCRIPT_PATH = "/__senzspace_blob_trap_v1.js"

    /** Layani skrip penangkap blob sebagai file JavaScript
     *  same-origin (dipakai bila halaman tidak memakai nonce CSP). */
    private fun trapScriptResponse(): android.webkit.WebResourceResponse {
        val bytes = blobInjectScript().toByteArray(Charsets.UTF_8)
        return android.webkit.WebResourceResponse(
            "application/javascript", "UTF-8", 200, "OK",
            emptyMap(), java.io.ByteArrayInputStream(bytes)
        )
    }

    /** Cari nilai nonce CSP ("nonce-...") di nilai-nilai header
     *  atau teks tag <meta> CSP. */
    private fun extractCspNonce(values: Collection<String>): String? {
        val re = Regex("nonce-([A-Za-z0-9+/=]+)")
        for (v in values) {
            val m = re.find(v)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** Sisipkan skrip penangkap blob ke HTML: sebelum </body> (fallback:
     *  tempel di akhir).
     *  v0.1.75 (audit #3): CSP TIDAK LAGI di-strip — meta CSP dibiarkan
     *  utuh. Bila halaman memakai nonce CSP, skrip inline diberi nonce
     *  itu (diizinkan kebijakan); bila tidak, skrip dimuat sebagai file
     *  same-origin (diizinkan 'self', dilayani dari memori). */
    private fun injectBlobScript(html: String, nonce: String?, host: String): String {
        var out = html
        try {
            val scriptTag = if (nonce != null) {
                "<script nonce=\"" + nonce + "\">" + blobInjectScript() + "</script>"
            } else {
                "<script src=\"https://" + host + TRAP_SCRIPT_PATH + "\"></script>"
            }
            val idx = out.lastIndexOf("</body>", ignoreCase = true)
            out = if (idx >= 0) {
                out.substring(0, idx) + scriptTag + out.substring(idx)
            } else {
                out + scriptTag
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Inti v0.1.17: ambil dokumen HTML (frame utama + iframe), sisipkan
     *  skrip penangkap blob, kembalikan dengan header CSP UTUH
     *  (v0.1.75, audit #3 — CSP tidak lagi di-strip; skrip diberi
     *  nonce halaman bila ada, atau dimuat sebagai file same-origin).
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
                // v0.1.75 (audit #3): header CSP + Report-Only
                // DITERUSKAN utuh (dulu di-strip); hanya
                // Content-Length yang dibuang (panjang body berubah
                // sesudah injeksi).
                if (k.equals("Content-Length", true)) continue
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
                // v0.1.75 (audit #3): nonce CSP dari header; bila
                // tidak ada, cari di <meta> CSP di HTML.
                var nonce = extractCspNonce(conn.headerFields.values.flatten())
                if (nonce == null) {
                    val metaRe = Regex(
                        "<meta[^>]*http-equiv\\s*=\\s*[\"'']?Content-Security-Policy[\"'']?[^>]*>",
                        RegexOption.IGNORE_CASE
                    )
                    val meta = metaRe.find(html)
                    if (meta != null) nonce = extractCspNonce(listOf(meta.value))
                }
                val injected = injectBlobScript(html, nonce, host)
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
                // v0.1.83 (C1): rekam manual selesai — tulis status
                // done (nama + ukuran) supaya GET /tts-record-status
                // bisa menjawab sesudah SIMPAN. Mode manual hanya
                // dimasuki startDirectRecord(manual=true), jadi
                // simpanan apa pun di jendela ini ADALAH rekamannya
                // (uniqueFile boleh menambah suffix pada nama).
                // v0.1.84 (C): "done" HANYA bila gate lolos
                // (verdict "ok" dari onBlobRecordDone, selalu
                // diterima sebelum onBlobData). Verdict gagal /
                // tidak diterima -> "failed" (jangan pernah "done"
                // palsu seperti file 0-byte v0.1.83).
                if (ttsRecordManual) {
                    ttsRecordManual = false
                    ttsFallbackDeadlineMs = 0
                    if (ttsRecordGateVerdict == "ok") {
                        writeTtsRecordStatus("done", "rekaman tersimpan: ${dest.name} (${ttsRecordGateDetail})", dest.name, dest.length())
                    } else {
                        val detail = if (ttsRecordGateVerdict.isNotEmpty()) ttsRecordGateDetail else "rekaman tidak lengkap (verdict tidak diterima)"
                        writeTtsRecordStatus("failed", detail, dest.name, dest.length())
                    }
                }
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
        // v0.1.78: lepas receiver shed.
        try {
            shedReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        shedReceiver = null
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
