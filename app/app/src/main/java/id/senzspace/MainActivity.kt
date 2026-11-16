package id.senzspace

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// S1: 7 Space aktif (Claude 1-6 + AI Studio). Chip status dibaca dari file
// space_<suffix>.txt yang ditulis BrowserActivity tiap halaman selesai dimuat
// (1 = login, 0 = belum) — dibaca ulang di onResume, jadi chip selalu jujur.
// (SharedPreferences TIDAK dipakai di sini: tiap Space jalan di proses berbeda
// dan cache prefs antar proses bisa basi; file dibaca segar dari disk.)
// Aturan maks 10 Space (D1): sumber kebenaran = Rust profile-core;
// MAX_SPACES di bawah hanya cermin sampai JNI tersambung.
class MainActivity : AppCompatActivity() {
    companion object {
        const val MAX_SPACES = 10
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_COLOR = "extra_space_color"
    }

    private data class SpaceDef(
        val buttonId: Int,
        val statusId: Int,
        val activity: Class<*>,
        val name: String,
        val color: String,
        val url: String,
        val suffix: String
    )

    private val spaces = listOf(
        SpaceDef(R.id.btnSpace1, R.id.tvStatus1, Space1Activity::class.java, "Claude 1", "#5B5BD6", "https://claude.ai", "space1"),
        SpaceDef(R.id.btnSpace2, R.id.tvStatus2, Space2Activity::class.java, "Claude 2", "#0EA5A5", "https://claude.ai", "space2"),
        SpaceDef(R.id.btnSpace3, R.id.tvStatus3, Space3Activity::class.java, "Claude 3", "#E07A5F", "https://claude.ai", "space3"),
        SpaceDef(R.id.btnSpace4, R.id.tvStatus4, Space4Activity::class.java, "Claude 4", "#E5B567", "https://claude.ai", "space4"),
        SpaceDef(R.id.btnSpace5, R.id.tvStatus5, Space5Activity::class.java, "Claude 5", "#7BC47F", "https://claude.ai", "space5"),
        SpaceDef(R.id.btnSpace6, R.id.tvStatus6, Space6Activity::class.java, "Claude 6", "#D66BA5", "https://claude.ai", "space6"),
        SpaceDef(R.id.btnSpace7, R.id.tvStatus7, AiStudioActivity::class.java, "AI Studio", "#38BDF8", "https://aistudio.google.com", "aistudio")
    )

    private val lockLauncher = LockGate.register(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.activity_main)
        FileStore.ensure(this)
        for (s in spaces) {
            findViewById<View>(s.buttonId).setOnClickListener { openSpace(s) }
        }
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
        refreshStatusChips()
    }

    override fun onStop() {
        super.onStop()
        LockGate.onStopped()
    }

    private fun refreshStatusChips() {
        for (s in spaces) {
            val loggedIn = try {
                openFileInput("space_${s.suffix}.txt").bufferedReader().readText().trim() == "1"
            } catch (e: Exception) {
                false
            }
            findViewById<TextView>(s.statusId).apply {
                text = if (loggedIn) "Aktif" else "Belum login"
                setTextColor(Color.parseColor(if (loggedIn) "#4ADE80" else "#8A94A6"))
            }
        }
    }

    private fun openSpace(s: SpaceDef) {
        val intent = Intent(this, s.activity).apply {
            putExtra(EXTRA_SPACE_NAME, s.name)
            putExtra(EXTRA_SPACE_COLOR, s.color)
            putExtra(EXTRA_URL, s.url)
        }
        startActivity(intent)
    }
}
