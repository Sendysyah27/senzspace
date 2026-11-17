package id.senzspace

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

// Tambah Space (mockup v2 layar 04): nama + preset situs + warna, maks 10 (D1).
// Desain: label 12sp/700 kapital margin-bawah 6dp, input radius 12dp
// pad 11×13dp 13.5sp, chip preset 36dp, titik warna Ø34dp gap 10dp,
// CTA 52dp radius 14dp 15sp/800. "Buat Space & Login" langsung membuka
// halaman login situsnya di Space baru.
class AddSpaceActivity : AppCompatActivity() {

    private var selectedSite = "https://claude.ai"
    private var selectedColor = SpaceRegistry.PALETTE[0]
    private lateinit var urlInput: EditText
    private lateinit var colorRow: LinearLayout
    private val siteButtons = mutableListOf<Button>()
    private val colorButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.1.31: ikuti sensor putar HP (berlaku walau auto-rotate sistem mati).
        // v0.1.33: rotasi MENGIKUTI setelan sistem (auto-rotate HP) —
        // SENSOR (v0.1.31) memutar app dari sensor walau auto-rotate
        // sistem mati; UNSPECIFIED = perilaku default Android.
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        CrashLog.install(this)

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
            text = "Space baru"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(0, 0, 0, 8)
        }
        val sub = TextView(this).apply {
            text = "Satu akun = satu Space. Maksimal 10 Space (D1)."
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)
        root.addView(sub)

        root.addView(label("NAMA SPACE"))
        val nameInput = EditText(this).apply {
            hint = "cth: Claude 7"
            textSize = 13.5f
            setTextColor(Color.parseColor("#E8ECF4"))
            setHintTextColor(Color.parseColor("#8A94A6"))
            setBackgroundColor(Color.parseColor("#16233A"))
            setPadding(26, 22, 26, 22)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 28 }
        }
        root.addView(nameInput)

        root.addView(label("SITUS"))
        val siteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 28 }
        }
        val sites = listOf(
            "Claude" to "https://claude.ai",
            "AI Studio" to "https://aistudio.google.com",
            "Situs lain" to ""
        )
        for ((labelText, url) in sites) {
            val btn = Button(this).apply {
                text = labelText
                textSize = 13f
                minimumHeight = 72
                setTextColor(Color.WHITE)
                setBackgroundColor(
                    Color.parseColor(if (url == selectedSite) "#5B5BD6" else "#24344D")
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 12 }
            }
            btn.setOnClickListener {
                selectedSite = url
                urlInput.visibility =
                    if (url.isEmpty()) android.view.View.VISIBLE
                    else android.view.View.GONE
                refreshSiteButtons()
            }
            siteButtons.add(btn)
            siteRow.addView(btn)
        }
        root.addView(siteRow)

        urlInput = EditText(this).apply {
            hint = "https://…"
            textSize = 13.5f
            setTextColor(Color.parseColor("#E8ECF4"))
            setHintTextColor(Color.parseColor("#8A94A6"))
            setBackgroundColor(Color.parseColor("#16233A"))
            setPadding(26, 22, 26, 22)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 28 }
        }
        root.addView(urlInput)

        root.addView(label("WARNA IDENTITAS"))
        colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        }
        for (c in SpaceRegistry.PALETTE) {
            val dot = Button(this).apply {
                text = if (c == selectedColor) "●" else "○"
                textSize = 18f
                setTextColor(Color.parseColor(c))
                setBackgroundColor(Color.parseColor("#00000000"))
                minimumHeight = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 16 }
            }
            dot.setOnClickListener {
                selectedColor = c
                refreshColorButtons()
            }
            colorButtons.add(dot)
            colorRow.addView(dot)
        }
        root.addView(colorRow)

        val cta = Button(this).apply {
            text = "Buat Space & Login"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5B5BD6"))
            minimumHeight = 104 // 52dp
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cta.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Isi nama Space dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var url = selectedSite
            if (url.isEmpty()) {
                url = urlInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                if (url == "https://") {
                    Toast.makeText(this, "Isi alamat situs dulu", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            val entry = SpaceRegistry.add(this, name, selectedColor, url)
            if (entry == null) {
                Toast.makeText(this, "Maksimal 10 Space", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            FileStore.ensure(this)
            val intent = Intent(this, SpaceRegistry.activityForSlot(entry.slot)).apply {
                putExtra(MainActivity.EXTRA_SPACE_NAME, entry.name)
                putExtra(MainActivity.EXTRA_SPACE_COLOR, entry.color)
                putExtra(MainActivity.EXTRA_URL, entry.url)
            }
            startActivity(intent)
            finish()
        }
        root.addView(cta)

        setContentView(root)
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 12)
        }
    }

    private fun refreshSiteButtons() {
        for (btn in siteButtons) {
            val url = when (btn.text.toString()) {
                "Claude" -> "https://claude.ai"
                "AI Studio" -> "https://aistudio.google.com"
                else -> ""
            }
            btn.setBackgroundColor(
                Color.parseColor(if (url == selectedSite) "#5B5BD6" else "#24344D")
            )
        }
    }

    private fun refreshColorButtons() {
        for ((i, btn) in colorButtons.withIndex()) {
            btn.text = if (SpaceRegistry.PALETTE[i] == selectedColor) "●" else "○"
        }
    }
}
