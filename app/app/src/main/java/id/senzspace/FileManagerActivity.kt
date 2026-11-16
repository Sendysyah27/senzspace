package id.senzspace

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.io.File

// File Manager S1 (PRD F7, mockup v2 layar 03): folder per Space +
// _Inbox-Bersama + _Arsip-Episode; pindah file antar folder; hapus.
// Desain (mockup v2 presisi): app bar pad 12/16/6dp, judul 20sp/800,
// sub 11.5sp muted; isi pad 14/16/12dp, gap folder 10dp; kartu folder
// radius 14dp stroke 1dp bg #16233A; baris file pad 9×13dp, ikon tipe
// 30dp r8, tombol Pindah min-tinggi 32dp. Alur: Browser ⬇ → folder Space
// → "Pindah…" → pilih tujuan (mis. _Inbox-Bersama) → file siap dipakai
// Space lain. Buka file (viewer) + ekspor Download publik = slice berikut.
class FileManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "extra_folder"
    }

    private var currentFolder: String? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileStore.ensure(this)
        currentFolder = intent.getStringExtra(EXTRA_FOLDER)

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
            setPadding(32, 24, 32, 12)
        }
        val title = TextView(this).apply {
            text = "File Manager"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
        }
        val sub = TextView(this).apply {
            text = "Folder per Space • pindah antar-Space"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8A94A6"))
        }
        appBar.addView(title)
        appBar.addView(sub)
        root.addView(appBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 0, 28, 24)
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        if (currentFolder == null) renderFolders() else renderFiles(currentFolder!!)
    }

    private fun card(): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            radius = 28f // 14dp
            strokeWidth = 2
            setStrokeColor(Color.parseColor("#24344D"))
            setCardBackgroundColor(Color.parseColor("#16233A"))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
        }
    }

    private fun renderFolders() {
        for (folder in FileStore.FOLDERS) {
            val count = FileStore.count(this, folder.key)
            val bytes = FileStore.totalBytes(this, folder.key)
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(26, 22, 26, 22)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val dot = TextView(this).apply {
                text = "●"
                textSize = 18f
                setTextColor(Color.parseColor(folder.color))
            }
            val name = TextView(this).apply {
                text = "  📁  ${folder.name}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E8ECF4"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val info = TextView(this).apply {
                text = if (count == 0) "kosong" else "$count file • ${FileStore.formatSize(bytes)}"
                textSize = 12f
                setTextColor(Color.parseColor("#8A94A6"))
            }
            inner.addView(dot)
            inner.addView(name)
            inner.addView(info)
            val c = card()
            c.addView(inner)
            c.setOnClickListener {
                currentFolder = folder.key
                render()
            }
            content.addView(c)
        }
        val hint = TextView(this).apply {
            text = "File unduhan dari tiap Space masuk ke folder Space-nya. " +
                "Pindah file ke _Inbox-Bersama supaya bisa dipakai Space lain."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(4, 32, 4, 0)
        }
        content.addView(hint)
    }

    private fun renderFiles(folderKey: String) {
        val folder = FileStore.folderByKey(folderKey) ?: return
        val files = FileStore.listFiles(this, folderKey)

        val back = TextView(this).apply {
            text = "← Semua folder"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
            setPadding(4, 8, 4, 16)
        }
        back.setOnClickListener {
            currentFolder = null
            render()
        }
        content.addView(back)

        val header = TextView(this).apply {
            text = "📁 ${folder.name}  •  ${files.size} file"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(4, 0, 4, 8)
        }
        content.addView(header)

        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Folder kosong — file unduhan dari ${folder.name} " +
                    "akan muncul di sini."
                textSize = 13f
                setTextColor(Color.parseColor("#8A94A6"))
                setPadding(4, 24, 4, 0)
            }
            content.addView(empty)
            return
        }

        for (file in files) {
            content.addView(fileRow(file, folderKey))
        }
    }

    private fun fileRow(file: File, folderKey: String): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 18, 26, 18)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = FileStore.iconFor(file)
            textSize = 20f
        }
        val name = TextView(this).apply {
            text = "  ${file.name}"
            textSize = 13f
            setTextColor(Color.parseColor("#E8ECF4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(icon)
        top.addView(name)
        val meta = TextView(this).apply {
            text = "${FileStore.formatSize(file.length())}  •  ${FileStore.formatDate(file.lastModified())}"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(62, 6, 0, 12)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(62, 0, 0, 0)
        }
        val moveBtn = Button(this).apply {
            text = "Pindah…"
            minimumHeight = 64 // 32dp
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5B5BD6"))
        }
        moveBtn.setOnClickListener { showMoveDialog(file, folderKey) }
        val delBtn = Button(this).apply {
            text = "Hapus"
            minimumHeight = 64
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundColor(Color.parseColor("#24344D"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 16 }
        }
        delBtn.setOnClickListener { showDeleteDialog(file) }
        actions.addView(moveBtn)
        actions.addView(delBtn)

        inner.addView(top)
        inner.addView(meta)
        inner.addView(actions)
        val c = card()
        c.addView(inner)
        return c
    }

    private fun showMoveDialog(file: File, fromKey: String) {
        val dests = FileStore.FOLDERS.filter { it.key != fromKey }
        val names = dests.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pindah \"${file.name}\" ke…")
            .setItems(names) { _, which ->
                val dest = dests[which]
                val moved = FileStore.move(this, file, dest.key)
                if (moved != null) {
                    Toast.makeText(
                        this,
                        "Dipindah ke ${dest.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "Gagal memindah file", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showDeleteDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Hapus \"${file.name}\"?")
            .setMessage("File dihapus permanen dari HP.")
            .setPositiveButton("Hapus") { _, _ ->
                if (FileStore.delete(file)) {
                    Toast.makeText(this, "Dihapus", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal menghapus", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
