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

// File Manager native v2 (S1, PRD F7 — perintah Sendy 22:31 "kaku, kurang
// native, tambahkan fungsi tool yang lengkap").
// Pola app file manager Android pada umumnya:
// - Baris ringkas 56dp (ikon + nama + meta + tombol ⋮), bukan kartu besar
//   dengan 3 tombol per file.
// - Navigasi drill-down dengan breadcrumb + tombol Back naik satu level
//   (folder bisa berisi subfolder, mis. per episode di _Arsip).
// - Tap file = Buka (viewer sistem via FileProvider); tahan = pilih banyak
//   (action bar bawah: Pindah / Salin / Ekspor / Bagikan / Hapus).
// - Menu ⋮ per file: Buka, Bagikan, Pindah, Salin, Ganti nama, Detail,
//   Ekspor, Hapus.
// - Cari (filter nama), Urut (Nama / Terbaru / Terlama / Terbesar —
//   tersimpan), Buat folder, Detail file (ukuran, tanggal, path).
// Folder tujuan Pindah/Salin tetap folder tingkat atas (model F7).
class FileManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "extra_folder"
        private const val PREFS_UI = "senzspace_ui"
        private const val KEY_SORT = "fm_sort"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val lockLauncher = LockGate.register(this)
    private val pathStack = ArrayDeque<File>()
    private val selected = mutableSetOf<File>()
    private var query = ""
    private var sortMode = "date_desc"

    private lateinit var content: LinearLayout
    private lateinit var crumbBar: TextView
    private lateinit var backBtn: TextView
    private lateinit var toolbarRow: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var sortBtn: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var actionCount: TextView

    private fun currentDir(): File? = pathStack.lastOrNull()

    private fun sortLabel(): String = when (sortMode) {
        "name" -> "Nama A–Z"
        "date_asc" -> "Terlama"
        "size_desc" -> "Terbesar"
        else -> "Terbaru"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        FileStore.ensure(this)
        sortMode = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getString(KEY_SORT, "date_desc") ?: "date_desc"
        intent.getStringExtra(EXTRA_FOLDER)?.let { key ->
            pathStack.addLast(FileStore.folderDir(this, key))
        }

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
            text = "Folder per Space • pindah antar-Space"
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
        backBtn = TextView(this).apply {
            text = "‹ "
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
            setPadding(0, 0, dp(4), 0)
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
            setBackgroundColor(Color.parseColor("#16233A"))
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
            text = "⇅ ${sortLabel()}"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundColor(Color.parseColor("#16233A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        sortBtn.setOnClickListener { showSortMenu() }
        val newFolderBtn = TextView(this).apply {
            text = "＋ Folder"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundColor(Color.parseColor("#5B5BD6"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        newFolderBtn.setOnClickListener { showNewFolderDialog() }
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
            Triple("Pindah", "#5B5BD6", { moveSelected() }),
            Triple("Salin", "#0EA5A5", { copySelected() }),
            Triple("Ekspor", "#0EA5A5", { exportSelected() }),
            Triple("Bagikan", "#24344D", { shareFiles(selected.toList()) }),
            Triple("Hapus", "#7F1D1D", { deleteSelected() }),
            Triple("Pilih semua", "#24344D", { selectAll() }),
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
        backBtn.visibility = if (inFolder) View.VISIBLE else View.GONE
        crumbBar.text = if (!inFolder) {
            "Semua folder"
        } else {
            "File Manager › " + pathStack.joinToString(" › ") { it.name }
        }
        updateActionBar()
        if (!inFolder) renderFolders() else renderEntries(dir!!)
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
        selected.addAll(FileStore.listEntries(dir))
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
            // menggantikan titik "●" + emoji 📁 (dua elemen jadi satu).
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

    private fun renderEntries(dir: File) {
        val all = FileStore.listEntries(dir)
        val filtered = if (query.isBlank()) all
            else all.filter { it.name.contains(query, ignoreCase = true) }
        val folders = filtered.filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
        val files = filtered.filter { it.isFile }
        val sortedFiles = when (sortMode) {
            "name" -> files.sortedBy { it.name.lowercase() }
            "date_asc" -> files.sortedBy { it.lastModified() }
            "size_desc" -> files.sortedByDescending { it.length() }
            else -> files.sortedByDescending { it.lastModified() }
        }
        val entries = folders + sortedFiles
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
        for (entry in entries) content.addView(entryRow(entry))
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
        // Kanan: ceklis (mode pilih) atau ⋮ (menu)
        if (selected.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = if (isSelected) "◉" else "◯"
                textSize = 22f
                setTextColor(
                    Color.parseColor(if (isSelected) "#5B5BD6" else "#8A94A6")
                )
                setPadding(dp(8), 0, dp(4), 0)
            })
        } else {
            val more = TextView(this).apply {
                text = "⋮"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#8A94A6"))
                setPadding(dp(12), dp(4), dp(4), dp(4))
            }
            more.setOnClickListener { showEntryMenu(more, entry) }
            row.addView(more)
        }
        row.setOnClickListener {
            if (selected.isNotEmpty()) {
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
            toggleSelect(entry)
            true
        }
        return row
    }

    private fun toggleSelect(entry: File) {
        if (selected.contains(entry)) selected.remove(entry) else selected.add(entry)
        render()
    }

    // ---------- Menu ⋮ per entri ----------

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
        menu.menu.add("Salin").setOnMenuItemClickListener { copyEntries(listOf(entry)); true }
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
            "size_desc" to "Terbesar"
        )) {
            menu.menu.add(label).setOnMenuItemClickListener {
                sortMode = key
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putString(KEY_SORT, key).apply()
                sortBtn.text = "⇅ ${sortLabel()}"
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

    private fun copyEntries(entries: List<File>) {
        // Salin ke folder saat ini bila beda folder tidak dipilih: tanya
        // tujuan dulu (sama seperti Pindah).
        val dests = FileStore.FOLDERS
        val names = dests.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Salin ${entries.size} item ke…")
            .setItems(names) { _, which ->
                val dest = dests[which]
                var ok = 0
                for (e in entries) {
                    if (FileStore.copy(this, e, dest.key) != null) ok++
                }
                Toast.makeText(this, "$ok item disalin ke ${dest.name}", Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun copySelected() {
        if (selected.isEmpty()) return
        copyEntries(selected.toList())
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
            if (ok > 0) "$ok file diekspor ke Download/SenzSpace" else "Gagal mengekspor",
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
                    if (FileStore.delete(e)) ok++
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
                if (FileStore.rename(entry, name) != null) {
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
                if (FileStore.newFolder(dir, name) != null) {
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
            val n = entry.listFiles()?.size ?: 0
            details.append("Isi: $n item\n")
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
