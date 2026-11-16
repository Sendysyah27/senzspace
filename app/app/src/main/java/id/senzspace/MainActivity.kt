package id.senzspace

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

// Dashboard (CRUD S1): kartu Space dirender dari SpaceRegistry —
// tambah via AddSpaceActivity, tahan lama di kartu untuk Ubah nama / Hapus.
// Chip status dibaca dari file space_<suffix>.txt yang ditulis
// BrowserActivity tiap halaman selesai dimuat (1 = login, 0 = belum),
// dibaca ulang di onResume, jadi chip selalu jujur.
// (SharedPreferences TIDAK dipakai untuk status: tiap Space jalan di proses
// berbeda dan cache prefs antar proses bisa basi; file dibaca segar.)
// Aturan maks 10 Space (D1): sumber kebenaran = Rust profile-core.
class MainActivity : AppCompatActivity() {
    companion object {
        const val MAX_SPACES = 10
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_COLOR = "extra_space_color"
    }

    private lateinit var spaceList: LinearLayout
    private val statusViews = mutableMapOf<Int, TextView>()
    private val lockLauncher = LockGate.register(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.activity_main)
        FileStore.ensure(this)
        spaceList = findViewById(R.id.spaceList)
        findViewById<View>(R.id.btnFileManager).setOnClickListener {
            startActivity(Intent(this, FileManagerActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        LockGate.check(this, lockLauncher)
        renderSpaces()
        refreshStatusChips()
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

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
            ).apply { topMargin = 24 }
            radius = 36f // 18dp
            strokeWidth = 2
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
            setPadding(32, 32, 32, 32)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
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
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(this).apply {
            text = "Belum login"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(20, 8, 20, 8)
        }
        statusViews[entry.slot] = status
        top.addView(dot)
        top.addView(name)
        top.addView(status)
        val site = TextView(this).apply {
            text = entry.url.removePrefix("https://").removePrefix("http://")
            textSize = 13f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 16, 0, 0)
        }
        val meta = TextView(this).apply {
            text = "Terakhir aktif: —  •  0 file baru"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 8, 0, 0)
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
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
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
                    renderSpaces()
                    refreshStatusChips()
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
                renderSpaces()
                refreshStatusChips()
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
        val intent = Intent(this, SpaceRegistry.activityForSlot(entry.slot)).apply {
            putExtra(EXTRA_SPACE_NAME, entry.name)
            putExtra(EXTRA_SPACE_COLOR, entry.color)
            putExtra(EXTRA_URL, entry.url)
        }
        startActivity(intent)
    }
}
