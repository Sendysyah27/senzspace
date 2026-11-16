package id.senzspace

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Dashboard S0: 2 Space aktif (Claude 1, Claude 2).
 * Preset penuh 7 Space (D2) ditampilkan sebagai daftar; Space 3-7
 * berstatus "S1" dan belum bisa dibuka di prototipe ini — jujur di UI,
 * bukan disembunyikan.
 *
 * Aturan maks 10 Space (D1) dicerminkan di sini sampai JNI Rust tersambung (S1):
 * lihat profile-core (Rust) sebagai sumber kebenaran.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val MAX_SPACES = 10
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_COLOR = "extra_space_color"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tvSubtitle).text =
            "Ruang kerja 7 Space — 2 aktif di S0"

        findViewById<View>(R.id.btnSpace1).setOnClickListener {
            openSpace(
                Space1Activity::class.java,
                "Claude 1", "#5B5BD6", "https://claude.ai"
            )
        }
        findViewById<View>(R.id.btnSpace2).setOnClickListener {
            openSpace(
                Space2Activity::class.java,
                "Claude 2", "#0EA5A5", "https://claude.ai"
            )
        }
    }

    private fun openSpace(
        cls: Class<*>,
        name: String,
        color: String,
        url: String
    ) {
        val intent = Intent(this, cls).apply {
            putExtra(EXTRA_SPACE_NAME, name)
            putExtra(EXTRA_SPACE_COLOR, color)
            putExtra(EXTRA_URL, url)
        }
        startActivity(intent)
    }
}
