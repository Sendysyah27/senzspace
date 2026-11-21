package id.senzspace

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

// Dashboard — Beranda Multi Live (Figma "01 — Beranda · Multi Live").
// Mode "Multi Live" (default): grid 2 kolom, tiap tile menampilkan
// tangkapan layar web Space itu yang sedang berjalan (file
// _live/<suffix>.png, ditulis BrowserActivity tiap 5 detik selama Space
// di belakang). Tap tile = buka Space (instance yang sudah jalan dipakai
// lagi, bukan WebView baru). Mode "Daftar": kartu seperti S1.
// Chip status dibaca dari file space_<suffix>.txt yang ditulis
// BrowserActivity tiap halaman selesai dimuat (1 = login, 0 = belum).
// (SharedPreferences TIDAK dipakai untuk status: tiap Space jalan di proses
// berbeda dan cache prefs antar proses bisa basi; file dibaca segar.)
// Aturan maks 10 Space (D1): sumber kebenaran = Rust profile-core.
// PERBAIKAN PRESISI (mockup v2):
// ukuran px hardcode lama (mis. padding 32, radius 36f) diganti dp() yang
// benar — di HP density ~2.6, 32px cuma ~12dp padahal mockup minta 16dp.
class MainActivity : AppCompatActivity() {
    companion object {
        const val MAX_SPACES = 10
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CLEAR_DATA = "extra_clear_data"
        // v0.1.31: suffix target clear (dicek BrowserActivity sebelum
        // menghapus — pengaman lintas-Space) + slot split-screen.
        const val EXTRA_CLEAR_SUFFIX = "extra_clear_suffix"
        const val EXTRA_SPLIT_WITH = "extra_split_with"
        // v0.1.38: payload prompt API - teks + daftar file _inbox +
        // URL tujuan (mis. halaman Project Claude). Dikirim lewat
        // intent extra (bukan static lintas-proses, lihat UX 31).
        const val EXTRA_API_PROMPT_TEXT = "extra_api_prompt_text"
        const val EXTRA_API_PROMPT_FILES = "extra_api_prompt_files"
        const val EXTRA_API_PROMPT_URL = "extra_api_prompt_url"
        // v0.1.39: payload TTS API - naskah + format suara + model
        // + URL halaman TTS (mis. Playground TTS AI Studio).
        const val EXTRA_API_TTS_SCRIPT = "extra_api_tts_script"
        const val EXTRA_API_TTS_VOICE = "extra_api_tts_voice"
        const val EXTRA_API_TTS_MODEL = "extra_api_tts_model"
        const val EXTRA_API_TTS_URL = "extra_api_tts_url"
        // v0.1.41: nama voice TTS (mis. "Sadaltager", Favorites).
        const val EXTRA_API_TTS_SPEAKER = "extra_api_tts_speaker"
        // v0.1.52: URL yang dimuat di Space (POST /navigate) — host
        // HARUS sama dengan situs Space itu (dicek ApiServer).
        const val EXTRA_API_NAVIGATE_URL = "extra_api_navigate_url"
        // v0.1.40: nama Project Claude tujuan prompt (mis. "SSR CONTENT STUDIO").
        const val EXTRA_API_PROMPT_PROJECT = "extra_api_prompt_project"
        // v0.1.60: payload eksekutor YouTube Studio - aksi + file _inbox +
        // judul + deskripsi + jadwal ("YYYY-MM-DD HH:MM" atau "now").
        const val EXTRA_API_STUDIO_ACTION = "extra_api_studio_action"
        const val EXTRA_API_STUDIO_FILE = "extra_api_studio_file"
        const val EXTRA_API_STUDIO_TITLE = "extra_api_studio_title"
        const val EXTRA_API_STUDIO_DESCRIPTION = "extra_api_studio_description"
        const val EXTRA_API_STUDIO_SCHEDULE = "extra_api_studio_schedule"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_COLOR = "extra_space_color"
        private const val PREFS_UI = "senzspace_ui"
        private const val KEY_MODE = "dashboard_mode"
        private const val MODE_LIVE = "live"
        private const val MODE_LIST = "list"
        private const val LIVE_REFRESH_MS = 2000L
        private const val LIVE_FRESH_SEC = 60L
        private const val KEY_INTERVAL = "live_interval_ms"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** v0.1.42: minta izin SYSTEM_ALERT_WINDOW ("Tampil di atas
     *  aplikasi lain") SEKALI. Izin ini membebaskan launch Space
     *  dari background (BAL): tanpa izin, tugas API bisa mentok
     *  "menunggu Space tampil" bila activity Space sudah
     *  dihancurkan Android saat RAM menipis (insiden VO Charger
     *  00:28-00:41). Ditanya sekali saja (pref overlay_asked);
     *  penolakan tidak mengganggu app — tugas tetap coba jalan
     *  dan gagal jujur dalam 60 detik. */
    private fun maybeAskOverlayPermission() {
        if (android.provider.Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("senzspace_ui", MODE_PRIVATE)
        if (prefs.getBoolean("overlay_asked", false)) return
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.roundedBg("#16233A", 18, this@MainActivity)
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Buka Space tanpa ketuk"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
        })
        root.addView(TextView(this).apply {
            text = "Supaya API bisa membuka Space dari background saat layarnya mati (Android sering menghancurkan layar Space saat RAM menipis), SenzSpace butuh izin sekali: \"Tampil di atas aplikasi lain\". Tanpa izin ini, tugas API bisa mentok di \"menunggu Space tampil\"."
            textSize = 13f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, dp(12), 0, dp(12))
        })
        val grantBtn = Button(this).apply {
            text = "Beri izin"
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundResource(R.drawable.btn_primary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        grantBtn.setOnClickListener {
            prefs.edit().putBoolean("overlay_asked", true).apply()
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
            dialog.dismiss()
        }
        root.addView(grantBtn)
        root.addView(TextView(this).apply {
            text = "Nanti"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
            gravity = Gravity.END
            setPadding(0, dp(12), dp(4), dp(8))
            setOnClickListener {
                prefs.edit().putBoolean("overlay_asked", true).apply()
                dialog.dismiss()
            }
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        dialog.show()
    }

    /** Interval refresh Multi Live (Pengaturan, default 2 detik) —
     *  dipakai Beranda dan tiap Space supaya seirama. */
    private fun liveIntervalMs(): Long {
        return try {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .getLong(KEY_INTERVAL, 2000L).coerceIn(1000L, 10000L)
        } catch (e: Exception) {
            2000L
        }
    }

    // Status JSON untuk kontrol/monitor dari luar app (kontrol API via ADB):
    // ditulis ke SenzSpace/_status/senzspace-status.json tiap ada
    // perubahan (throttle 10 detik). Isinya: per Space (login, limit +
    // jam reset, mtime thumbnail terakhir, jumlah file). TIDAK berisi
    // sesi/cookie/token — hanya status yang memang tampil di Beranda.
    private var lastStatusJson: String? = null
    private var lastStatusWriteMs = 0L

    private fun writeStatusJson(spaces: List<SpaceRegistry.SpaceEntry>) {
        val now = System.currentTimeMillis()
        if (now - lastStatusWriteMs < 10000L && lastStatusJson != null) return
        try {
            val sb = StringBuilder()
            sb.append("{\"updated\":").append(now).append(",\"spaces\":[")
            var first = true
            for (entry in spaces) {
                val suffix = SpaceRegistry.suffixForSlot(entry.slot)
                val loggedIn = SpaceStatus.readLogin(this, suffix)
                val limit = SpaceStatus.readLimit(this, suffix)
                val frozen = LiveFreeze.isFrozen(this, suffix)
                val thumb = FileStore.liveThumbFile(this, suffix)
                val mod = if (thumb.exists()) thumb.lastModified() else 0L
                val files = try { FileStore.count(this, suffix) } catch (e: Exception) { 0 }
                if (!first) sb.append(",")
                first = false
                sb.append("{\"slot\":").append(entry.slot)
                    .append(",\"name\":\"").append(jsonEscape(entry.name)).append("\"")
                    .append(",\"suffix\":\"").append(suffix).append("\"")
                    .append(",\"loggedIn\":").append(loggedIn)
                    .append(",\"limit\":")
                    .append(if (limit == null) "null" else "\"" + jsonEscape(limit) + "\"")
                    .append(",\"liveThumbMod\":").append(mod)
                    .append(",\"files\":").append(files).append(",\"liveFrozen\":").append(frozen).append("}")
            }
            sb.append("]}")
            val json = sb.toString()
            if (json == lastStatusJson) return
            lastStatusJson = json
            lastStatusWriteMs = now
            val dir = java.io.File(FileStore.rootDir(this), "_status")
            dir.mkdirs()
            java.io.File(dir, "senzspace-status.json").writeText(json)
        } catch (e: Exception) {
            // Status JSON boleh gagal; Beranda tetap jalan.
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")

    private lateinit var spaceList: LinearLayout
    private lateinit var liveGrid: GridLayout
    private lateinit var toggleLive: TextView
    private lateinit var toggleList: TextView
    private lateinit var tvSectionLabel: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvSubtitle: TextView
    private val statusViews = mutableMapOf<Int, TextView>()
    private val liveTiles = mutableMapOf<Int, LiveTile>()
    private val lockLauncher = LockGate.register(this)

    private data class LiveTile(
        val preview: ImageView,
        val placeholder: View,
        val badge: TextView,
        val status: TextView,
        val sub: TextView,
        var lastMod: Long = 0L,
        var lastSub: String = "",
        var lastStatus: String = ""
    )

    private val liveHandler = Handler(Looper.getMainLooper())
    private var liveTicking = false
    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveTicking) return
            if (mode() == MODE_LIVE) refreshLiveTiles()
            liveHandler.postDelayed(this, liveIntervalMs())
        }
    }

    private fun mode(): String =
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getString(KEY_MODE, MODE_LIVE) ?: MODE_LIVE

    private fun setMode(m: String) {
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .edit().putString(KEY_MODE, m).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.1.31: ikuti sensor putar HP (berlaku walau auto-rotate
        // sistem mati) — app sebelumnya tidak bisa horizontal.
        // v0.1.33: rotasi MENGIKUTI setelan sistem (auto-rotate HP) —
        // SENSOR (v0.1.31) memutar app dari sensor walau auto-rotate
        // sistem mati; UNSPECIFIED = perilaku default Android.
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        CrashLog.install(this)
        setContentView(R.layout.activity_main)
        FileStore.ensure(this)
        // v0.1.36: nyalakan server API lokal bila
        // saklarnya hidup (default hidup).
        if (ApiToken.isEnabled(this)) ApiService.start(this)
        spaceList = findViewById(R.id.spaceList)
        liveGrid = findViewById(R.id.liveGrid)
        toggleLive = findViewById(R.id.toggleLive)
        toggleList = findViewById(R.id.toggleList)
        tvSectionLabel = findViewById(R.id.tvSectionLabel)
        tvCount = findViewById(R.id.tvCount)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        toggleLive.setOnClickListener { setMode(MODE_LIVE); renderDashboard() }
        toggleList.setOnClickListener { setMode(MODE_LIST); renderDashboard() }
        findViewById<View>(R.id.navFiles).setOnClickListener {
            startActivity(Intent(this, FileManagerActivity::class.java))
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // v0.1.42: minta izin "Tampil di atas aplikasi lain" SEKALI
        // (kecuali bila kunci app aktif — dialog jangan menumpuk di
        // atas layar kunci; jalurnya tetap ada di Pengaturan).
        if (!LockStore.isEnabled(this)) maybeAskOverlayPermission()
        // Guard anti-kill butuh izin notifikasi (Android 13+): tanpa ini
        // service tetap jalan tapi tidak bisa foreground — guard melemah.
        // Minta sekali di awal; kalau ditolak, app tetap jalan normal.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }

    override fun onResume() {
        super.onResume()
        LockGate.check(this, lockLauncher)
        renderDashboard()
        if (mode() == MODE_LIVE && !liveTicking) {
            liveTicking = true
            liveHandler.postDelayed(liveTick, liveIntervalMs())
        }
    }

    override fun onPause() {
        super.onPause()
        liveTicking = false
        liveHandler.removeCallbacks(liveTick)
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

    private fun renderDashboard() {
        val spaces = SpaceRegistry.load(this)
        val live = mode() == MODE_LIVE
        toggleLive.apply {
            setBackgroundResource(if (live) R.drawable.toggle_active_bg else 0)
            setTextColor(Color.parseColor(if (live) "#FFFFFF" else "#8A94A6"))
            typeface = if (live) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        toggleList.apply {
            setBackgroundResource(if (!live) R.drawable.toggle_active_bg else 0)
            setTextColor(Color.parseColor(if (!live) "#FFFFFF" else "#8A94A6"))
            typeface = if (!live) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        tvCount.text = "${spaces.size} / 10"
        tvSubtitle.text = "${spaces.size} Space • " +
            if (live) "Semua live" else "Ruang kerja"
        tvSectionLabel.text = if (live) "SEMUA SPACE • LIVE" else "SPACE AKTIF"
        if (live) {
            spaceList.visibility = View.GONE
            liveGrid.visibility = View.VISIBLE
            buildLiveTiles(spaces)
            refreshLiveTiles()
        } else {
            liveGrid.visibility = View.GONE
            spaceList.visibility = View.VISIBLE
            renderSpaces()
            refreshStatusChips()
        }
    }

    // ---------- Mode Daftar (kartu S1, ukuran sudah dp() benar) ----------

    private fun renderSpaces() {
        spaceList.removeAllViews()
        statusViews.clear()
        val spaces = SpaceRegistry.load(this)
        for (entry in spaces) {
            spaceList.addView(spaceCard(entry))
        }
        if (spaces.size < MAX_SPACES) {
            spaceList.addView(addSpaceCard())
        }
    }

    private fun card(): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(Color.parseColor("#24344D"))
            setCardBackgroundColor(Color.parseColor("#16233A"))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
        }
    }

    private fun spaceCard(entry: SpaceRegistry.SpaceEntry): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(Color.parseColor(entry.color))
        }
        val name = TextView(this).apply {
            text = entry.name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(this).apply {
            text = "Belum login"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        statusViews[entry.slot] = status
        top.addView(dot)
        top.addView(name)
        top.addView(status)
        val site = TextView(this).apply {
            text = entry.url.removePrefix("https://").removePrefix("http://")
            textSize = 13f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, dp(8), 0, 0)
        }
        val meta = TextView(this).apply {
            text = "Terakhir aktif: —  •  0 file baru"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, dp(4), 0, 0)
        }
        inner.addView(top)
        inner.addView(site)
        inner.addView(meta)
        val c = card()
        c.addView(inner)
        c.setOnClickListener { openSpace(entry) }
        c.setOnLongClickListener {
            showSpaceOptions(entry)
            true
        }
        return c
    }

    private fun addSpaceCard(): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // v0.1.32: tanda plus diganti ikon SVG minimalis (tanpa emoji/simbol).
        val plus = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#5B5BD6")
            )
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val label = TextView(this).apply {
            text = "  Space baru"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hint = TextView(this).apply {
            text = "${SpaceRegistry.load(this@MainActivity).size}/10"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
        }
        inner.addView(plus)
        inner.addView(label)
        inner.addView(hint)
        val c = card()
        c.addView(inner)
        c.setOnClickListener {
            startActivity(Intent(this, AddSpaceActivity::class.java))
        }
        return c
    }

    // ---------- Mode Multi Live (grid tile, Figma 01) ----------

    private fun buildLiveTiles(spaces: List<SpaceRegistry.SpaceEntry>) {
        liveGrid.removeAllViews()
        liveTiles.clear()
        for (entry in spaces) {
            liveGrid.addView(liveTile(entry))
        }
        if (spaces.size < MAX_SPACES) {
            liveGrid.addView(addLiveTile())
        }
    }

    private fun tileLayoutParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
    }

    private fun roundedBg(color: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun liveTile(entry: SpaceRegistry.SpaceEntry): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = tileLayoutParams()
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(Color.parseColor("#24344D"))
            setCardBackgroundColor(Color.parseColor("#16233A"))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // Preview web VERTIKAL 159×212 (3:4, Figma v2 portrait) — sesuai
        // layar HP; thumbnail JPEG 240×320 dari WebView dipotong atas.
        val preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(212)
            ).apply { setMargins(dp(5), dp(5), dp(5), 0) }
            background = roundedBg("#1B2A44", 10)
        }
        val placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for (dotColor in listOf("#F87171", "#FBBF24", "#34D399")) {
            chrome.addView(TextView(this).apply {
                text = "●"
                textSize = 8f
                setTextColor(Color.parseColor(dotColor))
                setPadding(0, 0, dp(3), 0)
            })
        }
        chrome.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(9)).apply {
                leftMargin = dp(4)
            }
            background = roundedBg("#24344D", 4)
        })
        placeholder.addView(chrome)
        for (w in listOf(90, 120, 60)) {
            placeholder.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(7)).apply {
                    topMargin = dp(6)
                }
                background = resources.getDrawable(R.drawable.bar_bg, theme)
            })
        }
        placeholder.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(12)).apply {
                topMargin = dp(6)
            }
            background = roundedBg(entry.color, 3)
        })
        val thumb = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val badge = TextView(this).apply {
            text = "LIVE"
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.live_pill_bg)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.TOP
                setMargins(0, dp(4), dp(4), 0)
            }
            visibility = View.GONE
        }
        preview.addView(placeholder)
        preview.addView(thumb)
        preview.addView(badge)
        inner.addView(preview)

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setPadding(dp(10), 0, dp(10), 0)
        }
        nameRow.addView(TextView(this).apply {
            text = "●"
            textSize = 12f
            setTextColor(Color.parseColor(entry.color))
        })
        nameRow.addView(TextView(this).apply {
            text = entry.name
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(6), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val status = TextView(this).apply {
            text = "Belum login"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8A94A6"))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        nameRow.addView(status)
        inner.addView(nameRow)

        val sub = TextView(this).apply {
            text = entry.url.removePrefix("https://").removePrefix("http://") +
                " • 0 file baru"
            textSize = 9f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }
        inner.addView(sub)

        card.addView(inner)
        card.setOnClickListener { openSpace(entry) }
        card.setOnLongClickListener {
            showSpaceOptions(entry)
            true
        }
        liveTiles[entry.slot] = LiveTile(thumb, placeholder, badge, status, sub)
        return card
    }

    private fun addLiveTile(): LinearLayout {
        val tile = LinearLayout(this).apply {
            layoutParams = tileLayoutParams()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(262)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00000000"))
                setStroke(dp(1), Color.parseColor("#24344D"), dp(6).toFloat(), dp(4).toFloat())
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
        }
        tile.addView(TextView(this).apply {
            text = "+ Space baru"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
        })
        tile.addView(TextView(this).apply {
            text = "Maks 10 Space"
            textSize = 9f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        tile.setOnClickListener {
            startActivity(Intent(this, AddSpaceActivity::class.java))
        }
        return tile
    }

    private fun refreshLiveTiles() {
        val spaces = SpaceRegistry.load(this)
        val now = System.currentTimeMillis()
        var liveCount = 0
        var loggedInCount = 0
        for (entry in spaces) {
            val tile = liveTiles[entry.slot] ?: continue
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            val loggedIn = SpaceStatus.readLogin(this, suffix)
            if (loggedIn) loggedInCount++
            val limit = SpaceStatus.readLimit(this, suffix)
            val frozen = LiveFreeze.isFrozen(this, suffix)
            // Chip status: Limit (amber) mengalahkan Aktif — akun Claude
            // yang kena limit 5 jam harus kelihatan tanpa buka Space-nya.
            val statusText = when {
                limit != null -> if (limit == "LIMIT") "Limit" else "Limit • $limit"
                frozen -> "Beku"
                loggedIn -> "Aktif"
                else -> "Belum login"
            }
            if (statusText != tile.lastStatus) {
                tile.lastStatus = statusText
                tile.status.apply {
                    text = statusText
                    when {
                        limit != null -> {
                            setTextColor(Color.parseColor("#FBBF24"))
                            setBackgroundResource(R.drawable.chip_bg)
                        }
                        frozen -> {
                            setTextColor(Color.parseColor("#8A94A6"))
                            setBackgroundResource(R.drawable.chip_bg)
                        }
                        loggedIn -> {
                            setTextColor(Color.parseColor("#34D399"))
                            setBackgroundResource(R.drawable.chip_live_bg)
                        }
                        else -> {
                            setTextColor(Color.parseColor("#8A94A6"))
                            setBackgroundResource(R.drawable.chip_bg)
                        }
                    }
                }
            }
            val site = entry.url.removePrefix("https://").removePrefix("http://")
            val fileCount = try { FileStore.count(this, suffix) } catch (e: Exception) { 0 }
            val thumbFile = FileStore.liveThumbFile(this, suffix)
            val mod = if (thumbFile.exists()) thumbFile.lastModified() else 0L
            if (mod > 0 && mod != tile.lastMod) {
                tile.lastMod = mod
                // Stabilitas Beranda: decode JPEG di thread latar supaya
                // tick 2 detik tidak nge-jank UI; pasang hanya bila file
                // belum berubah lagi selama decode (anti kedip/flicker).
                val path = thumbFile.absolutePath
                val expectMod = mod
                Thread {
                    val bmp = try {
                        BitmapFactory.decodeFile(path)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp != null) {
                        liveHandler.post {
                            if (tile.lastMod == expectMod) {
                                tile.preview.setImageBitmap(bmp)
                                tile.preview.visibility = View.VISIBLE
                                tile.placeholder.visibility = View.GONE
                            } else {
                                bmp.recycle()
                            }
                        }
                    } else {
                        // v0.1.35: thumbnail korup (tulis terpotong saat
                        // proses mati — tile mentok skeleton selamanya,
                        // senzwh 19 Nov 09:43). Hapus supaya Space yang
                        // hidup bisa menangkap ulang dengan bersih.
                        try {
                            thumbFile.delete()
                        } catch (e: Exception) {
                        }
                        liveHandler.post { tile.lastMod = 0L }
                    }
                }.start()
            }
            // v0.1.35: status live dari proses Space (file
            // _live/<suffix>-status.txt: alive|dom|pixels). Tanpa file
            // = Space belum pernah jalan di build ini — perlakukan
            // seperti dulu (berdasarkan thumbnail saja).
            val liveStatus = try {
                val f = FileStore.liveStatusFile(this, suffix)
                if (!f.exists()) null else {
                    val p = f.readText().trim().split("|")
                    if (p.size == 3) Triple(
                        p[0].toLong(), p[1].toLong(), p[2].toLong()
                    ) else null
                }
            } catch (e: Exception) {
                null
            }
            // Proses mati: tidak ada detak 30 dtk terakhir.
            val processAlive = liveStatus == null ||
                (now - liveStatus.first < 30_000)
            // Gambar basi: DOM baru berubah (<60 dtk) tapi piksel tidak
            // berubah >2 menit — renderer beku, draw() mengulang frame
            // lama (eneng127: banner limit 1:50 AM padahal kuota reset).
            val pixelStale = liveStatus != null &&
                liveStatus.second > 0 && liveStatus.third > 0 &&
                (now - liveStatus.second < 60_000) &&
                (now - liveStatus.third > 120_000)
            val ageSec = if (mod > 0) (now - mod) / 1000 else -1
            val fresh = ageSec in 0 until LIVE_FRESH_SEC && !frozen &&
                processAlive && !pixelStale
            if (fresh) liveCount++
            tile.badge.visibility = if (fresh) View.VISIBLE else View.GONE
            // Thumbnail Space mati diredupkan — jangan tampil seolah live.
            tile.preview.alpha = if (!processAlive) 0.4f else 1f
            val subText = when {
                frozen -> "$site • live dibekukan"
                limit != null -> "$site • limit, reset ${if (limit == "LIMIT") "segera" else limit}"
                !processAlive -> "$site • Space tidak berjalan, tap untuk membuka"
                pixelStale -> "$site • gambar tidak update, tap untuk membuka"
                mod > 0 -> "$site • live ${ageText(ageSec)}"
                else -> "$site • $fileCount file baru"
            }
            // Teks sub hanya ditulis bila berubah — kalau tidak, tiap tick
            // 2 detik memicu layout ulang semua tile (kedip + boros).
            if (subText != tile.lastSub) {
                tile.lastSub = subText
                tile.sub.text = subText
            }
        }
        // v0.1.35: subtitle jujur — dulu selalu "Semua live" walau
        // ada tile skeleton / gambar basi (temuan 19 Nov 09:43).
        tvSubtitle.text = "${spaces.size} Space • " + when {
            loggedInCount == 0 -> "belum ada yang live"
            liveCount >= loggedInCount -> "Semua live"
            else -> "$liveCount dari $loggedInCount live"
        }
        // Ringkasan notifikasi grup + status JSON untuk kontrol luar.
        KeepAliveService.refreshSummary(this, liveCount)
        writeStatusJson(spaces)
    }

    private fun ageText(ageSec: Long): String = when {
        ageSec < 5 -> "baru saja"
        ageSec < 60 -> "$ageSec dtk lalu"
        else -> "${ageSec / 60} mnt lalu"
    }

    // ---------- Opsi Space (rename / beku / split / hapus, dipakai kedua mode) ----------

    // v0.1.32: menu tahan lama Space didesain ulang — bukan lagi
    // AlertDialog polos, tapi kartu #16233A sudut 18dp: header (titik
    // warna + nama + chip status + situs + jumlah file), tiap baris
    // punya ikon SVG + label + hint kecil, Hapus Space merah.
    private fun showSpaceOptions(entry: SpaceRegistry.SpaceEntry) {
        // v0.1.20: toggle Live per Space — tahan lama kartu live di
        // Beranda, pilih Matikan/Nyalakan Live. Yang berhenti hanya
        // update gambar live; browser + sesi Space tetap jalan.
        val suffix = SpaceRegistry.suffixForSlot(entry.slot)
        val frozen = LiveFreeze.isFrozen(this, suffix)
        val (statusText, statusColor) = SpacePickerDialog.statusOf(this, entry)
        val files = try {
            FileStore.count(this, suffix)
        } catch (e: Exception) {
            0
        }
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.roundedBg("#16233A", 18, this@MainActivity)
            setPadding(dp(20), dp(20), dp(20), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // Header: titik warna + nama + chip status
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            background = UiKit.dotBg(entry.color)
        })
        header.addView(TextView(this).apply {
            text = entry.name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = statusText
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(statusColor))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        root.addView(header)
        // Detail kecil: situs + jumlah file
        root.addView(TextView(this).apply {
            text = entry.url.removePrefix("https://").removePrefix("http://") +
                "  •  $files file"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#24344D"))
        })
        // Aksi toggle Live (dipakai baris Matikan/Nyalakan Live).
        val toggleFreeze = {
            LiveFreeze.setFrozen(this, suffix, !frozen)
            if (frozen) {
                // v0.1.31: "Nyalakan Live" MEMBUKA Space-nya —
                // timer tangkap thumbnail hidup di activity
                // Space; bila activity itu sempat mati selagi
                // dibekukan, hanya membuka Space yang
                // menghidupkannya lagi (temuan HP: live tidak
                // aktif lagi setelah dibekukan). Saat kembali
                // ke Beranda, onPause menangkap thumbnail
                // segar → tile live lagi.
                Toast.makeText(
                    this,
                    "\"${entry.name}\": Live dinyalakan — membuka Space",
                    Toast.LENGTH_SHORT
                ).show()
                openSpace(entry)
            } else {
                Toast.makeText(
                    this,
                    "\"${entry.name}\": Live dibekukan — browser tetap jalan",
                    Toast.LENGTH_SHORT
                ).show()
                renderDashboard()
            }
        }
        // Baris menu: (ikon, label, hint, warna, aksi)
        val rows = listOf(
            MenuRow(
                R.drawable.ic_edit, "Ubah nama",
                "Ganti nama Space ini", "#8A94A6"
            ) { showRenameDialog(entry) },
            MenuRow(
                if (frozen) R.drawable.ic_play else R.drawable.ic_pause,
                if (frozen) "Nyalakan Live" else "Matikan Live",
                if (frozen) "Hidupkan lagi gambar live di Beranda"
                else "Berhentikan gambar live, browser tetap jalan",
                "#8A94A6"
            ) { toggleFreeze() },
            MenuRow(
                R.drawable.ic_columns, "Buka berdampingan",
                "Layar terbagi atas-bawah dengan Space lain", "#8A94A6"
            ) { showSplitPicker(entry) },
            MenuRow(
                R.drawable.ic_database, "Bersihkan data Space",
                "Hapus cookie + login, file unduhan aman", "#8A94A6"
            ) { showClearDataDialog(entry) },
            MenuRow(
                R.drawable.ic_trash, "Hapus Space",
                "Space + sesi login dihapus", "#F87171"
            ) { showDeleteDialog(entry) }
        )
        var firstRow = true
        for (row in rows) {
            if (!firstRow) {
                root.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(Color.parseColor("#24344D"))
                })
            }
            firstRow = false
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
            }
            rowView.addView(android.widget.ImageView(this).apply {
                setImageResource(row.iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(row.tint)
                )
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = dp(12) }
            }
            texts.addView(TextView(this).apply {
                text = row.label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(row.tint))
            })
            texts.addView(TextView(this).apply {
                text = row.hint
                textSize = 12f
                setTextColor(Color.parseColor("#8A94A6"))
            })
            rowView.addView(texts)
            rowView.setOnClickListener { dialog.dismiss(); row.action() }
            root.addView(rowView)
        }
        root.addView(TextView(this).apply {
            text = "Batal"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
            gravity = Gravity.END
            setPadding(0, dp(12), dp(4), dp(8))
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        dialog.show()
    }

    private data class MenuRow(
        val iconRes: Int,
        val label: String,
        val hint: String,
        val tint: String,
        val action: () -> Unit
    )

    /** Split-screen (v0.1.21): pilih Space AKTIF lain untuk dibuka
     *  berdampingan dengan Space ini. Hanya yang sudah login yang
     *  ditawarkan — sama seperti aturan geser pindah Space. */
    private fun showSplitPicker(entry: SpaceRegistry.SpaceEntry) {
        val others = try {
            SpaceRegistry.load(this).filter {
                it.slot != entry.slot &&
                    SpaceStatus.readLogin(this, SpaceRegistry.suffixForSlot(it.slot))
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (others.isEmpty()) {
            Toast.makeText(
                this,
                "Tidak ada Space aktif lain untuk berdampingan",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // v0.1.32: dialog kartu dengan chip status per Space
        // (Aktif / Beku / Limit / Belum login).
        // v0.1.33: tampilkan Space yang LIVE saja (fallback ke aktif
        // bila tidak ada yang live) — sama seperti Pindah Space.
        val shown = SpacePickerDialog.liveOnly(
            this, others, SpaceRegistry.suffixForSlot(entry.slot)
        )
        SpacePickerDialog.show(
            this,
            "Berdampingan dengan \"${entry.name}\"",
            shown,
            SpaceRegistry.suffixForSlot(entry.slot)
        ) { picked ->
            // v0.1.31: slot split dikirim sebagai intent extra
            // (EXTRA_SPLIT_WITH) — static lintas-proses tidak jalan.
            openSpace(entry, splitWith = picked.slot)
        }
    }

    private fun showRenameDialog(entry: SpaceRegistry.SpaceEntry) {
        val input = EditText(this).apply {
            setText(entry.name)
            setSelection(entry.name.length)
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
        }
        AlertDialog.Builder(this)
            .setTitle("Ubah nama Space")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    SpaceRegistry.rename(this, entry.slot, name)
                    renderDashboard()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Bersihkan data Space (v0.1.30): cookie + storage + cache proses
     *  Space ini dihapus, lalu halaman awal dimuat ulang. Obat untuk
     *  cookie rusak (temuan HP 17 Nov 11:36: Google 400 terus di Space 7
     *  meski Space sudah dihapus-buat lagi — penghapusan folder data saat
     *  proses :spaceN masih hidup tidak selalu berhasil, cookie rusak
     *  bertahan). File unduhan TIDAK dihapus. */
    private fun showClearDataDialog(entry: SpaceRegistry.SpaceEntry) {
        AlertDialog.Builder(this)
            .setTitle("Bersihkan data \"${entry.name}\"?")
            .setMessage(
                "Cookie + data login Space ini dihapus, lalu halaman awal " +
                    "dimuat ulang. Kamu harus login lagi di Space ini. " +
                    "File unduhan di folder Space ini TETAP ada."
            )
            .setPositiveButton("Bersihkan") { _, _ ->
                openSpace(entry, clearData = true)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showDeleteDialog(entry: SpaceRegistry.SpaceEntry) {
        AlertDialog.Builder(this)
            .setTitle("Hapus \"${entry.name}\"?")
            .setMessage(
                "Sesi login Space ini ikut dihapus. File unduhan di folder " +
                    "Space ini TETAP ada di File Manager."
            )
            .setPositiveButton("Hapus") { _, _ ->
                SpaceRegistry.remove(this, entry.slot)
                LiveFreeze.setFrozen(this, SpaceRegistry.suffixForSlot(entry.slot), false)
                // Matikan guard anti-kill Space ini (prosesnya boleh mati).
                KeepAlive.stop(this, SpaceRegistry.suffixForSlot(entry.slot))
                Toast.makeText(this, "\"${entry.name}\" dihapus", Toast.LENGTH_SHORT).show()
                renderDashboard()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshStatusChips() {
        val spaces = SpaceRegistry.load(this)
        for (entry in spaces) {
            val view = statusViews[entry.slot] ?: continue
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            val loggedIn = SpaceStatus.readLogin(this, suffix)
            val limit = SpaceStatus.readLimit(this, suffix)
            val frozen = LiveFreeze.isFrozen(this, suffix)
            view.apply {
                text = when {
                    limit != null -> if (limit == "LIMIT") "Limit" else "Limit • $limit"
                    frozen -> "Beku"
                    loggedIn -> "Aktif"
                    else -> "Belum login"
                }
                setTextColor(Color.parseColor(when {
                    limit != null -> "#FBBF24"
                    loggedIn -> "#4ADE80"
                    else -> "#8A94A6"
                }))
            }
        }
    }

    private fun openSpace(
        entry: SpaceRegistry.SpaceEntry,
        clearData: Boolean = false,
        splitWith: Int? = null
    ) {
        // Multi Live: pakai instance Space yang sudah jalan (REORDER_TO_FRONT)
        // — jangan bikin WebView kedua untuk Space yang sama.
        // v0.1.31: CLEAR_TOP DIHAPUS — flag itu menutup SEMUA activity di
        // atas target di task yang sama (termasuk activity Space LAIN +
        // Beranda). Itu tersangka utama insiden clear Space 8 ikut
        // menghapus Space 7 (17 Nov ~15:xx): activity Space 7 ditutup
        // paksa selagi clear berjalan. Pengganti: REORDER_TO_FRONT +
        // onNewIntent di BrowserActivity (extra tetap sampai, activity
        // lain tidak disentuh). Clear juga membawa suffix target yang
        // dicek ulang di BrowserActivity sebelum menghapus apa pun.
        // v0.1.36: Intent dibangun SpaceLauncher (dipakai Beranda +
        // API supaya perilakunya identik).
        startActivity(SpaceLauncher.intentFor(this, entry, clearData, splitWith))
    }
}
