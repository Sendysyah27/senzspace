package id.senzspace

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

// File Manager native v2 (S1, PRD F7): tampil dan fungsi seperti
// file manager Android pada umumnya.
// Pola app file manager Android pada umumnya:
// - Baris ringkas 56dp (ikon + nama + meta + tombol menu), bukan kartu besar
//   dengan 3 tombol per file.
// - Navigasi drill-down dengan breadcrumb + tombol Back naik satu level
//   (folder bisa berisi subfolder, mis. per episode di _Arsip).
// - Tap file = Buka (viewer sistem via FileProvider); tahan = pilih banyak
//   (action bar bawah: Pindah / Salin / Ekspor / Bagikan / Hapus).
// - Menu per file: Buka, Bagikan, Pindah, Salin, Ganti nama, Detail,
//   Ekspor, Hapus.
// - Cari (filter nama), Urut (Nama / Terbaru / Terlama / Terbesar —
//   tersimpan), Buat folder, Detail file (ukuran, tanggal, path).
// Folder tujuan Pindah/Salin tetap folder tingkat atas (model F7).
class FileManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "extra_folder"
        // v0.1.56 (Opsi A, AKAR 7-8): mode pilih — FM dipakai sebagai
        // pemilih file untuk lampiran WebView. Ketuk file → salin ke
        // _inbox → kembalikan path HASIL lewat EXTRA_PICK_RESULT.
        // JANGAN PERNAH kembalikan path asli folder Space (jejak
        // staging hilang, lampiran Space A bisa ke Space B tanpa jejak).
        const val EXTRA_PICK_MODE = "extra_pick_mode"
        const val EXTRA_PICK_RESULT = "extra_pick_result"
        // Batas ukuran pick sama seperti /upload (100 MB).
        const val PICK_MAX_BYTES = 100L * 1024 * 1024
        private const val PREFS_UI = "senzspace_ui"
        private const val KEY_SORT = "fm_sort"
        private const val KEY_VIEW = "fm_view"
        private const val KEY_SHOW_HIDDEN = "fm_show_hidden"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val lockLauncher = LockGate.register(this)
    private val pathStack = ArrayDeque<File>()
    private val selected = mutableSetOf<File>()
    private var query = ""
    private var sortMode = "date_desc"
    // v0.1.31 (ala MiXplorer): mode tampil list/grid, clipboard
    // Potong/Salin/Tempel, tampilkan file tersembunyi.
    private var viewMode = "list"
    private var showHidden = false
    private var clipboard = listOf<File>()
    private var clipboardCut = false

    // v0.1.56: mode pilih (lihat EXTRA_PICK_MODE) + launcher system
    // picker ("Pilih dari HP") — jalan kedua bila file di luar SenzSpace.
    private var pickMode = false
    private val pickFromPhoneLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            pickFromPhoneUri(result.data!!.data!!)
        }
    }

    private lateinit var content: LinearLayout
    private lateinit var crumbBar: TextView
    private lateinit var backBtn: android.widget.ImageButton
    private lateinit var toolbarRow: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var sortBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var pasteBtn: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var actionCount: TextView

    private fun currentDir(): File? = pathStack.lastOrNull()

    private fun sortLabel(): String = when (sortMode) {
        "name" -> "Nama A–Z"
        "date_asc" -> "Terlama"
        "size_desc" -> "Terbesar"
        "type" -> "Tipe file"
        else -> "Terbaru"
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
        FileStore.ensure(this)
        val uiPrefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        sortMode = uiPrefs.getString(KEY_SORT, "date_desc") ?: "date_desc"
        viewMode = uiPrefs.getString(KEY_VIEW, "list") ?: "list"
        showHidden = uiPrefs.getBoolean(KEY_SHOW_HIDDEN, false)
        intent.getStringExtra(EXTRA_FOLDER)?.let { key ->
            pathStack.addLast(FileStore.folderDir(this, key))
        }
        // v0.1.56: mode pilih (Opsi A) — FM dibuka dari chooser WebView.
        pickMode = intent.getBooleanExtra(EXTRA_PICK_MODE, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0B1220"))
        }

        // App bar
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        appBar.addView(TextView(this).apply {
            text = "File Manager"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
        })
        appBar.addView(TextView(this).apply {
            // v0.1.56: mode pilih — judul menjelaskan tugasnya.
            text = if (pickMode) "Ketuk file untuk dilampirkan • salinan masuk _Inbox"
            else "Folder per Space • pindah antar-Space"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8A94A6"))
        })
        root.addView(appBar)

        // Breadcrumb + kembali (hanya di dalam folder)
        val crumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        // v0.1.32: tombol kembali jadi ikon SVG chevron-left yang
        // lebih besar (ikon 28dp, area sentuh 44dp) — dulu cuma
        // huruf ‹ kecil.
        backBtn = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#5B5BD6")
            )
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        backBtn.setOnClickListener { goUp() }
        crumbBar = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#8A94A6"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        crumbRow.addView(backBtn)
        crumbRow.addView(crumbBar)
        root.addView(crumbRow)

        // Toolbar file view: cari + urut + buat folder
        toolbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        searchBox = EditText(this).apply {
            hint = "Cari file…"
            textSize = 13f
            setTextColor(Color.parseColor("#E8ECF4"))
            setHintTextColor(Color.parseColor("#8A94A6"))
            // v0.1.32: sudut membulat (radius 12dp).
            setBackgroundResource(R.drawable.chip_rounded)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    render()
                }
            })
        }
        sortBtn = TextView(this).apply {
            // v0.1.32: simbol urut dihapus (tanpa simbol) + sudut membulat.
            text = sortLabel()
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundResource(R.drawable.chip_rounded)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        sortBtn.setOnClickListener { showSortMenu() }
        val newFolderBtn = TextView(this).apply {
            // v0.1.32: tanda plus lebar diganti + biasa + sudut membulat.
            text = "+ Folder"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundResource(R.drawable.btn_primary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        newFolderBtn.setOnClickListener { showNewFolderDialog() }
        // v0.1.31: ganti tampilan Grid/Daftar + Tempel (clipboard).
        viewBtn = TextView(this).apply {
            // v0.1.32: simbol grid/daftar dihapus (tanpa simbol) + sudut membulat.
            text = if (viewMode == "grid") "Daftar" else "Grid"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundResource(R.drawable.chip_rounded)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        viewBtn.setOnClickListener {
            viewMode = if (viewMode == "grid") "list" else "grid"
            getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .edit().putString(KEY_VIEW, viewMode).apply()
            viewBtn.text = if (viewMode == "grid") "Daftar" else "Grid"
            render()
        }
        pasteBtn = TextView(this).apply {
            text = "Tempel"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.btn_primary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        pasteBtn.setOnClickListener { pasteClipboard() }
        // v0.1.56: mode pilih — tombol "Pilih dari HP" (jalan kedua):
        // system picker HP untuk file di luar SenzSpace; hasilnya
        // disalin ke _inbox seperti pick file SenzSpace.
        val phoneBtn = TextView(this).apply {
            text = "Pilih dari HP"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.btn_primary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        phoneBtn.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_GET_CONTENT
            ).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                pickFromPhoneLauncher.launch(
                    android.content.Intent.createChooser(intent, "Pilih file dari HP")
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this, "Tidak bisa membuka pemilih HP: ${e.message}", Toast.LENGTH_LONG
                ).show()
            }
        }
        toolbarRow.addView(searchBox)
        toolbarRow.addView(sortBtn.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        })
        toolbarRow.addView(newFolderBtn.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        })
        toolbarRow.addView(viewBtn.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        })
        toolbarRow.addView(pasteBtn.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        })
        // v0.1.56: tombol "Pilih dari HP" hanya tampil di mode pilih.
        if (pickMode) {
            toolbarRow.addView(phoneBtn.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            })
        }
        root.addView(toolbarRow)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        scroll.addView(content)
        root.addView(scroll)

        // Action bar bawah (mode pilih banyak)
        val actionScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#16233A"))
            visibility = View.GONE
        }
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        actionCount = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(8), 0, dp(8), 0)
        }
        actionBar.addView(actionCount)
        for ((label, color, fn) in listOf(
            // v0.1.31: Pindah/Salin jadi clipboard (Potong/Salin) —
            // tempel di folder tujuan lewat tombol Tempel di toolbar.
            Triple("Potong", "#5B5BD6", { cutSelected() }),
            Triple("Salin", "#0EA5A5", { copySelected() }),
            Triple("Ekspor", "#0EA5A5", { exportSelected() }),
            Triple("Bagikan", "#24344D", { shareFiles(selected.toList()) }),
            Triple("ZIP", "#0EA5A5", { zipSelected() }),
            Triple("Hapus", "#7F1D1D", { deleteSelected() }),
            Triple("Pilih semua", "#24344D", { selectAll() }),
            Triple("Balik pilihan", "#24344D", { invertSelection() }),
            Triple("Batal", "#24344D", { clearSelection() })
        )) {
            actionBar.addView(TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) }
                setOnClickListener { fn() }
            })
        }
        actionScroll.addView(actionBar)
        root.addView(actionScroll)
        // Simpan referensi scroll untuk show/hide
        actionScroll.tag = "actionScroll"
        this.actionScroll = actionScroll

        setContentView(root)
        render()
    }

    private lateinit var actionScroll: HorizontalScrollView

    override fun onResume() {
        super.onResume()
        LockGate.check(this, lockLauncher)
        render()
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

    override fun onBackPressed() {
        when {
            selected.isNotEmpty() -> clearSelection()
            pathStack.isNotEmpty() -> goUp()
            else -> super.onBackPressed()
        }
    }

    private fun goUp() {
        if (pathStack.isNotEmpty()) {
            pathStack.removeLast()
            clearSelection()
            render()
        }
    }

    private fun render() {
        content.removeAllViews()
        val dir = currentDir()
        val inFolder = dir != null
        toolbarRow.visibility = if (inFolder) View.VISIBLE else View.GONE
        updatePasteBtn()
        backBtn.visibility = if (inFolder) View.VISIBLE else View.GONE
        crumbBar.text = if (!inFolder) {
            "Semua folder"
        } else {
            "File Manager › " + pathStack.joinToString(" › ") { it.name }
        }
        updateActionBar()
        if (!inFolder) renderFolders() else renderEntries(dir!!)
    }

    /** Tombol Tempel muncul bila clipboard berisi item dan sedang di
     *  dalam folder (tujuan tempel). */
    private fun updatePasteBtn() {
        if (!::pasteBtn.isInitialized) return
        pasteBtn.visibility =
            if (clipboard.isNotEmpty() && currentDir() != null) View.VISIBLE else View.GONE
    }

    private fun updateActionBar() {
        val n = selected.size
        actionScroll.visibility = if (n > 0) View.VISIBLE else View.GONE
        actionCount.text = "$n dipilih"
    }

    private fun clearSelection() {
        selected.clear()
        render()
    }

    private fun selectAll() {
        val dir = currentDir() ?: return
        selected.clear()
        selected.addAll(visibleEntries(dir))
        render()
    }

    /** v0.1.31: balik pilihan — yang terpilih jadi batal, yang belum
     *  jadi terpilih (di folder yang sedang dibuka). */
    private fun invertSelection() {
        val dir = currentDir() ?: return
        val all = visibleEntries(dir)
        val old = selected.toSet()
        selected.clear()
        selected.addAll(all.filter { it !in old })
        render()
    }

    // ---------- Daftar folder tingkat atas ----------

    private fun renderFolders() {
        for (folder in FileStore.FOLDERS) {
            val displayName = SpaceRegistry.findBySuffix(this, folder.key)?.name
                ?: folder.name
            val count = FileStore.count(this, folder.key)
            val bytes = FileStore.totalBytes(this, folder.key)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            // Ikon folder vektor (v0.1.13) — tint warna Space-nya,
            // menggantikan titik "●" (dua elemen jadi satu).
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_nav_folder)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(folder.color)
                )
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    rightMargin = dp(12)
                }
            })
            row.addView(TextView(this).apply {
                text = displayName
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E8ECF4"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = if (count == 0) "kosong" else "$count file • ${FileStore.formatSize(bytes)}"
                textSize = 12f
                setTextColor(Color.parseColor("#8A94A6"))
            })
            row.addView(TextView(this).apply {
                text = "  ›"
                textSize = 18f
                setTextColor(Color.parseColor("#8A94A6"))
            })
            row.setOnClickListener {
                pathStack.addLast(FileStore.folderDir(this, folder.key))
                render()
            }
            content.addView(row)
            content.addView(divider())
        }
        content.addView(TextView(this).apply {
            text = "File unduhan dari tiap Space masuk ke folder Space-nya. " +
                "Pindah file ke _Inbox-Bersama supaya bisa dipakai Space lain. " +
                "Tombol Ekspor menyalin file ke folder Download/SenzSpace di HP."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dp(8), dp(16), dp(8), 0)
        })
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply { leftMargin = dp(12); rightMargin = dp(12) }
        setBackgroundColor(Color.parseColor("#16233A"))
    }

    // ---------- Isi folder (subfolder + file) ----------

    /** Entri yang terlihat: sembunyikan file titik (kecuali pengaturan
     *  "Tampilkan file tersembunyi"), saring pencarian, urut sesuai
     *  sortMode. Dipakai render + Pilih semua + Balik pilihan. */
    private fun visibleEntries(dir: File): List<File> {
        val all = FileStore.listEntries(dir)
            .filter { showHidden || !it.name.startsWith(".") }
        val filtered = if (query.isBlank()) all
            else all.filter { it.name.contains(query, ignoreCase = true) }
        val folders = filtered.filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
        val files = filtered.filter { it.isFile }
        val sortedFiles = when (sortMode) {
            "name" -> files.sortedBy { it.name.lowercase() }
            "date_asc" -> files.sortedBy { it.lastModified() }
            "size_desc" -> files.sortedByDescending { it.length() }
            "type" -> files.sortedBy { it.extension.lowercase() + it.name.lowercase() }
            else -> files.sortedByDescending { it.lastModified() }
        }
        return folders + sortedFiles
    }

    private fun renderEntries(dir: File) {
        val entries = visibleEntries(dir)
        if (entries.isEmpty()) {
            content.addView(TextView(this).apply {
                text = if (query.isNotBlank()) "Tidak ada file bernama \"$query\"."
                    else "Folder kosong — file unduhan dari Space akan muncul di sini."
                textSize = 13f
                setTextColor(Color.parseColor("#8A94A6"))
                setPadding(dp(8), dp(24), dp(8), 0)
            })
            return
        }
        if (viewMode == "grid") {
            renderEntriesGrid(entries)
            return
        }
        for (entry in entries) content.addView(entryRow(entry))
    }

    /** v0.1.31: tampilan grid 3 kolom (ikon besar + nama) — ala
     *  galeri file manager. Ketuk/buka dan tahan/pilih sama seperti
     *  mode daftar. */
    private fun renderEntriesGrid(entries: List<File>) {
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for (entry in entries) grid.addView(entryCell(entry))
        content.addView(grid)
    }

    private fun entryCell(entry: File): View {
        val isFolder = entry.isDirectory
        val isSelected = selected.contains(entry)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(
                    android.widget.GridLayout.UNDEFINED, 1f
                )
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            if (isSelected) setBackgroundColor(Color.parseColor("#1B2A44"))
        }
        cell.addView(android.widget.ImageView(this).apply {
            if (isFolder) {
                setImageResource(R.drawable.ic_nav_folder)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#E5B567")
                )
            } else {
                setImageResource(FileStore.iconResFor(entry))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#8A94A6")
                )
            }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        cell.addView(TextView(this).apply {
            text = entry.name
            textSize = 12f
            setTextColor(Color.parseColor("#E8ECF4"))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        cell.setOnClickListener {
            // v0.1.56: mode pilih — ketuk file = pilih (salin ke
            // _inbox); ketuk folder tetap drill-down.
            if (pickMode && !isFolder && selected.isEmpty()) {
                pickFile(entry)
            } else if (selected.isNotEmpty()) {
                toggleSelect(entry)
            } else if (isFolder) {
                pathStack.addLast(entry)
                render()
            } else if (FileStore.isPreviewable(entry)) {
                viewFile(entry)
            } else {
                openFile(entry)
            }
        }
        cell.setOnLongClickListener {
            // v0.1.56: mode pilih — tahan tidak memilih banyak.
            if (pickMode) true else { toggleSelect(entry); true }
        }
        return cell
    }

    private fun entryRow(entry: File): View {
        val isFolder = entry.isDirectory
        val isSelected = selected.contains(entry)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (isSelected) setBackgroundColor(Color.parseColor("#1B2A44"))
        }
        // Ikon tipe 40dp — vektor garis minimalis (v0.1.13), bukan emoji.
        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundColor(Color.parseColor("#1B2A44"))
        }
        iconBox.addView(android.widget.ImageView(this).apply {
            if (isFolder) {
                setImageResource(R.drawable.ic_nav_folder)
                // Subfolder: kuning folder klasik.
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#E5B567")
                )
            } else {
                setImageResource(FileStore.iconResFor(entry))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#8A94A6")
                )
            }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        row.addView(iconBox)
        // Nama + meta
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) }
        }
        texts.addView(TextView(this).apply {
            text = entry.name
            textSize = 15f
            setTextColor(Color.parseColor("#E8ECF4"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = if (isFolder) {
                val n = entry.listFiles()?.size ?: 0
                "$n item"
            } else {
                "${FileStore.formatSize(entry.length())}  •  ${FileStore.formatDate(entry.lastModified())}"
            }
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
        })
        row.addView(texts)
        // Kanan: ceklis (mode pilih) atau tombol menu
        // v0.1.32: radio pilih dan tombol menu diganti ikon SVG (tanpa simbol teks).
        if (selected.isNotEmpty()) {
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(
                    if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle
                )
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(if (isSelected) "#5B5BD6" else "#8A94A6")
                )
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setPadding(dp(8), 0, dp(4), 0)
            })
        } else {
            // v0.1.33: area sentuh 44dp (dulu kotak 20dp, sulit ditekan)
            // — ikon tetap 20dp di tengah.
            val more = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_more_vertical)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#8A94A6")
                )
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            more.setOnClickListener { showEntryMenu(more, entry) }
            row.addView(more)
        }
        row.setOnClickListener {
            // v0.1.56: mode pilih — ketuk file = pilih (salin ke
            // _inbox); ketuk folder tetap drill-down.
            if (pickMode && !isFolder && selected.isEmpty()) {
                pickFile(entry)
            } else if (selected.isNotEmpty()) {
                toggleSelect(entry)
            } else if (isFolder) {
                pathStack.addLast(entry)
                render()
            } else if (FileStore.isPreviewable(entry)) {
                viewFile(entry)
            } else {
                openFile(entry)
            }
        }
        row.setOnLongClickListener {
            // v0.1.56: mode pilih — tahan tidak memilih banyak.
            if (pickMode) true else { toggleSelect(entry); true }
        }
        return row
    }

    private fun toggleSelect(entry: File) {
        if (selected.contains(entry)) selected.remove(entry) else selected.add(entry)
        render()
    }

    // ---------- Menu per entri ----------

    private fun showEntryMenu(anchor: View, entry: File) {
        val menu = PopupMenu(this, anchor)
        val isFolder = entry.isDirectory
        if (!isFolder) {
            // Lihat = pratinjau di dalam app (gambar/teks/video); untuk
            // tipe lain viewer menampilkan tombol Buka ke app lain.
            menu.menu.add("Lihat").setOnMenuItemClickListener { viewFile(entry); true }
            menu.menu.add("Buka").setOnMenuItemClickListener { openFile(entry); true }
            menu.menu.add("Bagikan").setOnMenuItemClickListener { shareFiles(listOf(entry)); true }
        }
        menu.menu.add("Pindah…").setOnMenuItemClickListener { showMoveDialog(listOf(entry)); true }
        // v0.1.31: Potong/Salin masuk clipboard — tempel di folder
        // tujuan lewat tombol Tempel di toolbar.
        menu.menu.add("Potong").setOnMenuItemClickListener { cutToClipboard(listOf(entry)); true }
        menu.menu.add("Salin").setOnMenuItemClickListener { copyToClipboard(listOf(entry)); true }
        if (entry.extension.lowercase() == "zip") {
            menu.menu.add("Ekstrak di sini").setOnMenuItemClickListener { unzipFile(entry); true }
        } else {
            menu.menu.add("Buat ZIP").setOnMenuItemClickListener { zipFiles(listOf(entry)); true }
        }
        menu.menu.add("Ganti nama").setOnMenuItemClickListener { showRenameDialog(entry); true }
        menu.menu.add("Detail").setOnMenuItemClickListener { showDetailsDialog(entry); true }
        if (!isFolder) {
            menu.menu.add("Ekspor").setOnMenuItemClickListener { exportFiles(listOf(entry)); true }
        }
        menu.menu.add("Hapus").setOnMenuItemClickListener { showDeleteDialog(listOf(entry)); true }
        menu.show()
    }

    private fun showSortMenu() {
        val menu = PopupMenu(this, sortBtn)
        for ((key, label) in listOf(
            "date_desc" to "Terbaru",
            "date_asc" to "Terlama",
            "name" to "Nama A–Z",
            "size_desc" to "Terbesar",
            "type" to "Tipe file"
        )) {
            menu.menu.add(label).setOnMenuItemClickListener {
                sortMode = key
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putString(KEY_SORT, key).apply()
                sortBtn.text = sortLabel()
                render()
                true
            }
        }
        menu.show()
    }

    // ---------- Aksi file ----------

    /** Lihat = pratinjau di dalam app (FileViewerActivity). */
    private fun viewFile(file: File) {
        val intent = Intent(this, FileViewerActivity::class.java)
        intent.putExtra(FileViewerActivity.EXTRA_FILE, file.absolutePath)
        startActivity(intent)
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, FileStore.mimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Buka dengan"))
        } catch (e: Exception) {
            Toast.makeText(
                this, "Tidak ada app untuk membuka file ini", Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- Mode pilih v0.1.56 (Opsi A) ----------

    /** Ketuk file dalam mode pilih — salin ke _inbox dulu (jejak
     *  staging), lalu kembalikan path HASIL. JANGAN PERNAH kembalikan
     *  path asli folder Space. */
    private fun pickFile(file: File) {
        if (file.length() > PICK_MAX_BYTES) {
            Toast.makeText(
                this, "File lebih dari 100 MB — tidak bisa dilampirkan",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val staged = FileStore.copy(this, file, "_inbox")
        if (staged == null) {
            Toast.makeText(
                this, "Gagal menyalin file ke _Inbox", Toast.LENGTH_LONG
            ).show()
            return
        }
        finishWithPick(staged)
    }

    /** File dari system picker ("Pilih dari HP") — salin isinya ke
     *  _inbox (nama unik bila bentrok), lalu kembalikan path hasil
     *  seperti pick file SenzSpace. Tolak > 100 MB. */
    private fun pickFromPhoneUri(uri: android.net.Uri) {
        val rawName = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        val clean = rawName.trim().replace("/", "_").replace("\\", "_")
        if (clean.isEmpty() || clean == "." || clean == ".." || clean.contains("..")) {
            Toast.makeText(this, "Nama file tidak bisa dipakai", Toast.LENGTH_LONG).show()
            return
        }
        val inboxDir = FileStore.folderDir(this, "_inbox")
        inboxDir.mkdirs()
        val dest = FileStore.uniqueFile(inboxDir, clean)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: run {
                Toast.makeText(this, "Tidak bisa membaca file itu", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            dest.delete()
            Toast.makeText(this, "Gagal menyalin file: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (dest.length() > PICK_MAX_BYTES) {
            dest.delete()
            Toast.makeText(
                this, "File lebih dari 100 MB — tidak bisa dilampirkan",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        finishWithPick(dest)
    }

    private fun finishWithPick(staged: File) {
        val result = Intent().putExtra(EXTRA_PICK_RESULT, staged.absolutePath)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun shareFiles(files: List<File>) {
        val onlyFiles = files.filter { it.isFile }
        if (onlyFiles.isEmpty()) {
            Toast.makeText(this, "Folder tidak bisa dibagikan", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uris = onlyFiles.map {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = FileStore.mimeType(onlyFiles[0])
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(intent, "Bagikan"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMoveDialog(entries: List<File>) {
        val dests = FileStore.FOLDERS
        val names = dests.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pindah ${entries.size} item ke…")
            .setItems(names) { _, which ->
                val dest = dests[which]
                var ok = 0
                for (e in entries) {
                    if (e.parentFile == FileStore.folderDir(this, dest.key)) continue
                    if (FileStore.move(this, e, dest.key) != null) ok++
                }
                Toast.makeText(this, "$ok item dipindah ke ${dest.name}", Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- Clipboard ala MiXplorer (v0.1.31) ----------
    // Potong/Salin hanya menaruh item di clipboard; Tempel mengeksekusi
    // di folder yang sedang dibuka (bisa subfolder, bukan cuma folder
    // tingkat atas seperti dialog Pindah/Salin lama).

    private fun copyToClipboard(entries: List<File>) {
        if (entries.isEmpty()) return
        clipboard = entries.toList()
        clipboardCut = false
        Toast.makeText(
            this,
            "${entries.size} item disalin — buka folder tujuan, lalu Tempel",
            Toast.LENGTH_LONG
        ).show()
        updatePasteBtn()
        clearSelection()
    }

    private fun cutToClipboard(entries: List<File>) {
        if (entries.isEmpty()) return
        clipboard = entries.toList()
        clipboardCut = true
        Toast.makeText(
            this,
            "${entries.size} item dipotong — buka folder tujuan, lalu Tempel",
            Toast.LENGTH_LONG
        ).show()
        updatePasteBtn()
        clearSelection()
    }

    private fun copySelected() {
        if (selected.isEmpty()) return
        copyToClipboard(selected.toList())
    }

    private fun cutSelected() {
        if (selected.isEmpty()) return
        cutToClipboard(selected.toList())
    }

    private fun pasteClipboard() {
        val dir = currentDir() ?: return
        val items = clipboard
        if (items.isEmpty()) return
        var ok = 0
        for (f in items) {
            if (!f.exists()) continue
            val dest = FileStore.uniqueFile(dir, f.name)
            val done = try {
                if (clipboardCut) {
                    f.renameTo(dest)
                } else if (f.isDirectory) {
                    f.copyRecursively(dest)
                } else {
                    f.copyTo(dest, overwrite = false)
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (done) ok++
        }
        if (clipboardCut) clipboard = emptyList()
        Toast.makeText(
            this,
            "$ok item ditempel di ${dir.name}",
            Toast.LENGTH_SHORT
        ).show()
        updatePasteBtn()
        render()
    }

    // ---------- ZIP (v0.1.31) ----------

    private fun zipSelected() {
        if (selected.isEmpty()) return
        zipFiles(selected.toList())
        clearSelection()
    }

    private fun zipFiles(files: List<File>) {
        val dir = currentDir() ?: return
        val base = if (files.size == 1) files[0].nameWithoutExtension
            else "arsip-${files.size}-item"
        Thread {
            try {
                val dest = FileStore.uniqueFile(dir, "$base.zip")
                java.util.zip.ZipOutputStream(
                    java.io.BufferedOutputStream(java.io.FileOutputStream(dest))
                ).use { zos ->
                    for (f in files) addToZip(zos, f, f.name)
                }
                runOnUiThread {
                    Toast.makeText(this, "ZIP dibuat: ${dest.name}", Toast.LENGTH_LONG).show()
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal membuat ZIP: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun addToZip(
        zos: java.util.zip.ZipOutputStream, file: File, entryName: String
    ) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { addToZip(zos, it, "$entryName/${it.name}") }
        } else {
            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun unzipFile(file: File) {
        val dir = currentDir() ?: return
        Thread {
            try {
                val destDir = File(dir, file.nameWithoutExtension)
                destDir.mkdirs()
                val destCanonical = destDir.canonicalPath
                java.util.zip.ZipInputStream(
                    java.io.BufferedInputStream(file.inputStream())
                ).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val out = File(destDir, entry.name)
                        // Anti Zip-Slip (v0.1.75, audit #12): bandingkan
                        // dengan dest + separator — sibling yang berbagi
                        // prefix nama (mis. archive2 vs archive) DITOLAK.
                        // File yang canonical-nya SAMA dengan folder
                        // tujuan juga ditolak; entri direktori itu
                        // sendiri (mkdirs) dibolehkan.
                        val outCanon = out.canonicalPath
                        val inside = outCanon.startsWith(
                            destCanonical + java.io.File.separator
                        )
                        if (!inside && !(entry.isDirectory && outCanon == destCanonical)) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Diekstrak ke ${destDir.name}", Toast.LENGTH_LONG).show()
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal mengekstrak: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun moveSelected() {
        if (selected.isEmpty()) return
        showMoveDialog(selected.toList())
    }

    private fun exportFiles(files: List<File>) {
        var ok = 0
        for (f in files.filter { it.isFile }) {
            if (FileStore.exportToDownloads(this, f) != null) ok++
        }
        Toast.makeText(
            this,
            if (ok > 0) "$ok file diekspor ke ${FileStore.exportLocationLabel(this)}"
            else "Gagal mengekspor",
            Toast.LENGTH_LONG
        ).show()
        clearSelection()
    }

    private fun exportSelected() {
        if (selected.isEmpty()) return
        exportFiles(selected.toList())
    }

    private fun showDeleteDialog(entries: List<File>) {
        AlertDialog.Builder(this)
            .setTitle("Hapus ${entries.size} item?")
            .setMessage("Dihapus permanen dari HP (folder beserta isinya).")
            .setPositiveButton("Hapus") { _, _ ->
                var ok = 0
                for (e in entries) {
                    if (FileStore.delete(this, e)) ok++
                }
                Toast.makeText(this, "$ok item dihapus", Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        showDeleteDialog(selected.toList())
    }

    private fun showRenameDialog(entry: File) {
        val input = EditText(this).apply {
            setText(entry.name)
            setSelection(entry.name.length)
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
        }
        AlertDialog.Builder(this)
            .setTitle("Ganti nama")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (FileStore.rename(this, entry, name) != null) {
                    Toast.makeText(this, "Nama diganti", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal mengganti nama", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showNewFolderDialog() {
        val dir = currentDir() ?: return
        val input = EditText(this).apply {
            hint = "Nama folder, mis. Episode 37"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
        }
        AlertDialog.Builder(this)
            .setTitle("Buat folder di \"${dir.name}\"")
            .setView(input)
            .setPositiveButton("Buat") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (FileStore.newFolder(this, dir, name) != null) {
                    Toast.makeText(this, "Folder \"$name\" dibuat", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal — nama sudah ada / tidak valid", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showDetailsDialog(entry: File) {
        val details = StringBuilder()
        details.append("Nama: ${entry.name}\n")
        details.append("Jenis: ${if (entry.isDirectory) "Folder" else FileStore.mimeType(entry)}\n")
        if (entry.isFile) {
            details.append("Ukuran: ${FileStore.formatSize(entry.length())}\n")
        } else {
            // v0.1.31: ukuran total rekursif + hitungan file/folder.
            val total = FileStore.totalBytesRecursive(entry)
            var files = 0
            var folders = 0
            entry.walkTopDown().forEach {
                if (it.isFile) files++
                else if (it.isDirectory && it != entry) folders++
            }
            details.append("Ukuran total: ${FileStore.formatSize(total)}\n")
            details.append("Isi: $files file • $folders folder\n")
        }
        details.append("Diubah: ${FileStore.formatDate(entry.lastModified())}\n")
        val root = FileStore.rootDir(this)
        val rel = try { entry.relativeTo(root).path } catch (e: Exception) { entry.absolutePath }
        details.append("Lokasi: SenzSpace/$rel")
        AlertDialog.Builder(this)
            .setTitle("Detail")
            .setMessage(details.toString())
            .setPositiveButton("Tutup", null)
            .show()
    }
}
