package id.senzspace

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

// Pengaturan (mockup v2 layar 05). Isi awal = kunci app (D4).
// v0.1.10: ditambah Multi Live (interval refresh + guard anti-kill),
// Baterai (pengecualian optimasi), Perawatan (bersihkan thumbnail +
// info penyimpanan) — perintah Sendy 22:29 "di pengaturan cuman
// kunci pin doang?".
// Kunci MATI secara default; pengaktifan dipilih pengguna (D4).
class SettingsActivity : AppCompatActivity() {

    private lateinit var lockSwitch: Switch
    private lateinit var changePinBtn: Button
    private lateinit var guardSwitch: Switch
    private lateinit var intervalValue: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var storageInfo: TextView
    // v0.1.31: folder ekspor custom + tampilkan file tersembunyi.
    private lateinit var exportPathValue: TextView
    private lateinit var hiddenSwitch: Switch
    private var expectingLockResult = false

    private val lockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        expectingLockResult = false
        if (result.resultCode == RESULT_OK) {
            // Hasil setup/change: kunci sudah aktif (setupPin menyimpan).
            // Hasil verify saat mematikan: matikan + hapus PIN.
            if (pendingAction == "disable") {
                LockStore.clearPin(this)
                Toast.makeText(this, "Kunci app dimatikan", Toast.LENGTH_SHORT).show()
            }
            pendingAction = null
            refresh()
        } else {
            pendingAction = null
            refresh()
        }
    }

    private var pendingAction: String? = null

    // v0.1.31: pemilih folder ekspor (SAF). URI pohon disimpan
    // persisten (takePersistableUriPermission) supaya tetap bisa
    // ditulis setelah app ditutup.
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = displayNameOf(uri)
            FileStore.setCustomExport(this, uri, name)
            Toast.makeText(this, "Folder ekspor: $name", Toast.LENGTH_LONG).show()
            refresh()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan folder: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayNameOf(uri: android.net.Uri): String {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)
                else uri.lastPathSegment ?: "Folder pilihan"
            } ?: (uri.lastPathSegment ?: "Folder pilihan")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Folder pilihan"
        }
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

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(40, 48, 40, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "Pengaturan"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)

        val lockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        // v0.1.32: emoji kunci diganti ikon SVG minimalis (tanpa emoji).
        val lockLabel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        lockLabel.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#8A94A6")
            )
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        lockLabel.addView(TextView(this).apply {
            text = "Kunci App (PIN)"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(10), 0, 0, 0)
        })
        lockSwitch = Switch(this)
        lockRow.addView(lockLabel)
        lockRow.addView(lockSwitch)
        root.addView(lockRow)

        val lockHint = TextView(this).apply {
            text = "Kunci aktif sejak MVP (D4) — pengaktifan dipilih kamu. " +
                "PIN disimpan sebagai hash, bukan teks biasa. " +
                "Setelah 5× salah, app jeda 30 detik."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 32)
        }
        root.addView(lockHint)

        changePinBtn = Button(this).apply {
            text = "Ubah PIN"
            setTextColor(Color.WHITE)
            // v0.1.32: tombol persegi panjang membulat (radius 12dp).
            setBackgroundResource(R.drawable.btn_primary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        changePinBtn.setOnClickListener {
            expectingLockResult = true
            val intent = android.content.Intent(this, LockActivity::class.java)
            intent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE)
            lockLauncher.launch(intent)
        }
        root.addView(changePinBtn)

        // ---------- Multi Live ----------
        root.addView(sectionTitle("Multi Live"))
        // Refresh interval
        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        intervalRow.addView(TextView(this).apply {
            text = "Refresh Multi Live"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        intervalValue = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
        }
        intervalRow.addView(intervalValue)
        intervalRow.setOnClickListener { showIntervalDialog() }
        root.addView(intervalRow)
        root.addView(TextView(this).apply {
            text = "Seberapa sering tile Beranda mengambil gambar baru dari " +
                "Space yang berjalan. 2 detik = paling live."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 16)
        })
        // Guard anti-kill
        val guardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        // v0.1.32: emoji perisai diganti ikon SVG minimalis (tanpa emoji).
        val guardLabel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        guardLabel.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#8A94A6")
            )
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        guardLabel.addView(TextView(this).apply {
            text = "Guard anti-kill"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(dp(10), 0, 0, 0)
        })
        guardRow.addView(guardLabel)
        guardSwitch = Switch(this)
        guardRow.addView(guardSwitch)
        root.addView(guardRow)
        root.addView(TextView(this).apply {
            text = "Tiap Space dijaga service-nya supaya sistem tidak " +
                "mematikan Space yang lama tidak dibuka. Matikan bila " +
                "tidak mau ada notifikasi \"Space berjalan\"."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 16)
        })
        guardSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == isGuardEnabled()) return@setOnCheckedChangeListener
            setGuardEnabled(checked)
            if (checked) {
                for (e in SpaceRegistry.load(this)) {
                    KeepAlive.start(this, SpaceRegistry.suffixForSlot(e.slot), e.name)
                }
                Toast.makeText(this, "Guard anti-kill hidup", Toast.LENGTH_SHORT).show()
            } else {
                for (e in SpaceRegistry.load(this)) {
                    KeepAlive.stop(this, SpaceRegistry.suffixForSlot(e.slot))
                }
                KeepAliveService.cancelSummary(this)
                Toast.makeText(this, "Guard anti-kill mati", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

        // ---------- Baterai ----------
        root.addView(sectionTitle("Baterai"))
        val batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        batteryRow.addView(TextView(this).apply {
            text = "Jalan di latar"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        batteryStatus = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        batteryRow.addView(batteryStatus)
        root.addView(batteryRow)
        root.addView(TextView(this).apply {
            text = "Tanpa pengecualian ini, Doze (hemat baterai Android) bisa " +
                "menidurkan Space walau guard anti-kill hidup. Tap tombol " +
                "di bawah, lalu pilih \"Izinkan\" di dialog sistem."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 16)
        })
        val batteryBtn = Button(this).apply {
            text = "Minta pengecualian baterai"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.btn_teal)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        batteryBtn.setOnClickListener { requestBatteryException() }
        root.addView(batteryBtn)

        // ---------- Penyimpanan (v0.1.31) ----------
        root.addView(sectionTitle("Penyimpanan"))
        exportPathValue = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(exportPathValue)
        root.addView(TextView(this).apply {
            text = "Folder tujuan saat kamu mengekspor file dari File Manager. " +
                "Default: Download/SenzSpace. Folder pilihan tersimpan " +
                "dan tetap bisa ditulis setelah app ditutup."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 16)
        })
        val exportPickBtn = Button(this).apply {
            text = "Pilih folder ekspor"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.btn_primary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        exportPickBtn.setOnClickListener { exportPicker.launch(null) }
        root.addView(exportPickBtn)
        val exportResetBtn = Button(this).apply {
            text = "Reset ke default (Download/SenzSpace)"
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundResource(R.drawable.btn_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        exportResetBtn.setOnClickListener {
            FileStore.clearCustomExport(this)
            Toast.makeText(
                this,
                "Folder ekspor direset ke Download/SenzSpace",
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
        root.addView(exportResetBtn)
        // Tampilkan file tersembunyi (dipakai File Manager).
        val hiddenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 8)
        }
        hiddenRow.addView(TextView(this).apply {
            text = "Tampilkan file tersembunyi"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        hiddenSwitch = Switch(this)
        hiddenRow.addView(hiddenSwitch)
        root.addView(hiddenRow)
        root.addView(TextView(this).apply {
            text = "File/folder yang namanya diawali titik (misal .nomedia) " +
                "ikut tampil di File Manager."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 16)
        })
        hiddenSwitch.setOnCheckedChangeListener { _, checked ->
            uiPrefs().edit().putBoolean("fm_show_hidden", checked).apply()
        }

        // ---------- Perawatan ----------
        root.addView(sectionTitle("Perawatan"))
        storageInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(storageInfo)
        val clearBtn = Button(this).apply {
            text = "Bersihkan thumbnail live"
            setTextColor(Color.parseColor("#E8ECF4"))
            setBackgroundResource(R.drawable.btn_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        clearBtn.setOnClickListener {
            val dir = FileStore.liveDir(this)
            val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            var n = 0
            for (f in files) if (f.delete()) n++
            Toast.makeText(
                this,
                "$n thumbnail dihapus — tile terisi lagi dalam beberapa detik",
                Toast.LENGTH_LONG
            ).show()
        }
        root.addView(clearBtn)

        val version = TextView(this).apply {
            text = "SenzSpace ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                "Repo: github.com/Sendysyah27/senzspace"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 48, 0, 0)
        }
        root.addView(version)

        lockSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LockStore.isEnabled(this)) return@setOnCheckedChangeListener
            if (checked) {
                // Hidupkan: buat PIN dulu.
                expectingLockResult = true
                val intent = android.content.Intent(this, LockActivity::class.java)
                intent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_SETUP)
                lockLauncher.launch(intent)
            } else {
                // Matikan: verifikasi PIN dulu.
                pendingAction = "disable"
                expectingLockResult = true
                val intent = android.content.Intent(this, LockActivity::class.java)
                intent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_VERIFY)
                lockLauncher.launch(intent)
            }
        }

        setContentView(scroll)
        refresh()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 32, 0, 16)
        }
    }

    private fun uiPrefs() = getSharedPreferences("senzspace_ui", MODE_PRIVATE)

    private fun isGuardEnabled(): Boolean =
        uiPrefs().getBoolean("guard_enabled", true)

    private fun setGuardEnabled(on: Boolean) {
        uiPrefs().edit().putBoolean("guard_enabled", on).apply()
    }

    private fun intervalLabel(ms: Long): String = when (ms) {
        5000L -> "5 detik"
        10000L -> "10 detik"
        else -> "2 detik"
    }

    private fun showIntervalDialog() {
        val options = arrayOf("2 detik (paling live)", "5 detik", "10 detik (hemat)")
        val values = longArrayOf(2000L, 5000L, 10000L)
        val current = uiPrefs().getLong("live_interval_ms", 2000L)
        val checked = values.indexOf(current).let { if (it < 0) 0 else it }
        android.app.AlertDialog.Builder(this)
            .setTitle("Refresh Multi Live")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                uiPrefs().edit().putLong("live_interval_ms", values[which]).apply()
                intervalValue.text = intervalLabel(values[which])
                Toast.makeText(this, "Refresh: ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun batteryIgnored(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun requestBatteryException() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Tidak bisa membuka pengaturan baterai", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = LockStore.isEnabled(this)
        lockSwitch.isChecked = enabled
        changePinBtn.visibility =
            if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        if (::guardSwitch.isInitialized) {
            guardSwitch.isChecked = isGuardEnabled()
        }
        if (::intervalValue.isInitialized) {
            intervalValue.text = intervalLabel(
                uiPrefs().getLong("live_interval_ms", 2000L)
            )
        }
        if (::batteryStatus.isInitialized) {
            val ignored = batteryIgnored()
            batteryStatus.text = if (ignored) "Dikecualikan" else "Dibatasi"
            batteryStatus.setTextColor(
                Color.parseColor(if (ignored) "#34D399" else "#FBBF24")
            )
        }
        if (::exportPathValue.isInitialized) {
            exportPathValue.text = FileStore.exportLocationLabel(this)
        }
        if (::hiddenSwitch.isInitialized) {
            hiddenSwitch.isChecked = uiPrefs().getBoolean("fm_show_hidden", false)
        }
        if (::storageInfo.isInitialized) {
            val rootDir = FileStore.rootDir(this)
            val total = FileStore.totalBytesRecursive(rootDir)
            var fileCount = 0
            rootDir.walkTopDown().forEach { if (it.isFile) fileCount++ }
            val spaces = SpaceRegistry.load(this).size
            storageInfo.text = "Folder kerja: ${FileStore.formatSize(total)} • " +
                "$fileCount file • $spaces Space"
        }
    }
}
