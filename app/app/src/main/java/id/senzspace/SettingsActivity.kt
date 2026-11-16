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

// Pengaturan (mockup v2 layar 05, isi awal = kunci app D4).
// Kunci MATI secara default; pengaktifan dipilih pengguna (D4).
class SettingsActivity : AppCompatActivity() {

    private lateinit var lockSwitch: Switch
    private lateinit var changePinBtn: Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(40, 48, 40, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

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
        val lockLabel = TextView(this).apply {
            text = "🔒  Kunci App (PIN)"
            textSize = 16f
            setTextColor(Color.parseColor("#E8ECF4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
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
            setBackgroundColor(Color.parseColor("#5B5BD6"))
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

        setContentView(root)
        refresh()
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
    }
}
