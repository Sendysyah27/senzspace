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
// PERBAIKAN PRESISI (temuan Sendy 21:34 "UI ngga presisi kayak mockup"):
// ukuran px hardcode lama (mis. padding 32, radius 36f) diganti dp() yang
// benar — di HP density ~2.6, 32px cuma ~12dp padahal mockup minta 16dp.
class MainActivity : AppCompatActivity() {
    companion object {
        const val MAX_SPACES = 10
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_COLOR = "extra_space_color"
        private const val PREFS_UI = "senzspace_ui"
        private const val KEY_MODE = "dashboard_mode"
        private const val MODE_LIVE = "live"
        private const val MODE_LIST = "list"
        private const val LIVE_REFRESH_MS = 5000L
        private const val LIVE_FRESH_SEC = 60L
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        var lastMod: Long = 0L
    )

    private val liveHandler = Handler(Looper.getMainLooper())
    private var liveTicking = false
    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveTicking) return
            if (mode() == MODE_LIVE) refreshLiveTiles()
            liveHandler.postDelayed(this, LIVE_REFRESH_MS)
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
        CrashLog.install(this)
        setContentView(R.layout.activity_main)
        FileStore.ensure(this)
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
    }

    override fun onResume() {
        super.onResume()
        LockGate.check(this, lockLauncher)
        renderDashboard()
        if (mode() == MODE_LIVE && !liveTicking) {
            liveTicking = true
            liveHandler.postDelayed(liveTick, LIVE_REFRESH_MS)
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
        val plus = TextView(this).apply {
            text = "＋"
            textSize = 22f
            setTextColor(Color.parseColor("#5B5BD6"))
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
        // Preview web 149×80 (Figma): placeholder chrome di bawah,
        // thumbnail live di atasnya bila sudah ada file-nya.
        val preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(80)
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
            minimumHeight = dp(134)
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
        for (entry in spaces) {
            val tile = liveTiles[entry.slot] ?: continue
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            val loggedIn = try {
                openFileInput("space_$suffix.txt").bufferedReader().readText().trim() == "1"
            } catch (e: Exception) {
                false
            }
            tile.status.apply {
                text = if (loggedIn) "Aktif" else "Belum login"
                setTextColor(Color.parseColor(if (loggedIn) "#34D399" else "#8A94A6"))
                setBackgroundResource(
                    if (loggedIn) R.drawable.chip_live_bg else R.drawable.chip_bg
                )
            }
            val site = entry.url.removePrefix("https://").removePrefix("http://")
            val fileCount = try { FileStore.count(this, suffix) } catch (e: Exception) { 0 }
            val thumbFile = FileStore.liveThumbFile(this, suffix)
            val mod = if (thumbFile.exists()) thumbFile.lastModified() else 0L
            if (mod > 0 && mod != tile.lastMod) {
                tile.lastMod = mod
                try {
                    val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                    if (bmp != null) {
                        tile.preview.setImageBitmap(bmp)
                        tile.preview.visibility = View.VISIBLE
                        tile.placeholder.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    // Thumbnail rusak — placeholder tetap tampil.
                }
            }
            val ageSec = if (mod > 0) (now - mod) / 1000 else -1
            val fresh = ageSec in 0 until LIVE_FRESH_SEC
            tile.badge.visibility = if (fresh) View.VISIBLE else View.GONE
            tile.sub.text = if (mod > 0) {
                "$site • live ${ageText(ageSec)}"
            } else {
                "$site • $fileCount file baru"
            }
        }
    }

    private fun ageText(ageSec: Long): String = when {
        ageSec < 5 -> "baru saja"
        ageSec < 60 -> "$ageSec dtk lalu"
        else -> "${ageSec / 60} mnt lalu"
    }

    // ---------- Opsi Space (rename / hapus, dipakai kedua mode) ----------

    private fun showSpaceOptions(entry: SpaceRegistry.SpaceEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("Ubah nama", "Hapus Space")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(entry)
                    1 -> showDeleteDialog(entry)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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

    private fun showDeleteDialog(entry: SpaceRegistry.SpaceEntry) {
        AlertDialog.Builder(this)
            .setTitle("Hapus \"${entry.name}\"?")
            .setMessage(
                "Sesi login Space ini ikut dihapus. File unduhan di folder " +
                    "Space ini TETAP ada di File Manager."
            )
            .setPositiveButton("Hapus") { _, _ ->
                SpaceRegistry.remove(this, entry.slot)
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
            val loggedIn = try {
                openFileInput("space_$suffix.txt").bufferedReader().readText().trim() == "1"
            } catch (e: Exception) {
                false
            }
            view.apply {
                text = if (loggedIn) "Aktif" else "Belum login"
                setTextColor(Color.parseColor(if (loggedIn) "#4ADE80" else "#8A94A6"))
            }
        }
    }

    private fun openSpace(entry: SpaceRegistry.SpaceEntry) {
        // Multi Live: pakai instance Space yang sudah jalan (REORDER_TO_FRONT)
        // — jangan bikin WebView kedua untuk Space yang sama.
        val intent = Intent(this, SpaceRegistry.activityForSlot(entry.slot)).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_SPACE_NAME, entry.name)
            putExtra(EXTRA_SPACE_COLOR, entry.color)
            putExtra(EXTRA_URL, entry.url)
        }
        startActivity(intent)
    }
}
