package id.senzspace

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Dialog pilih Space bergaya kartu (v0.1.32): tiap baris = titik
 *  warna Space + nama + situs + chip status (Aktif / Beku / Limit /
 *  Belum login) — jadi kelihatan mana yang aktif dan mana yang
 *  belum login. Dipakai "Pindah Space" (BrowserActivity) dan
 *  "Buka berdampingan" (MainActivity). */
object SpacePickerDialog {

    /** Status Space untuk chip: (teks, warna). Limit mengalahkan
     *  Beku/Aktif — sama seperti precedence chip di Beranda. */
    fun statusOf(ctx: Context, entry: SpaceRegistry.SpaceEntry): Pair<String, String> {
        val suffix = SpaceRegistry.suffixForSlot(entry.slot)
        val limit = SpaceStatus.readLimit(ctx, suffix)
        if (limit != null) {
            return if (limit == "LIMIT") "Limit" to "#FBBF24"
            else "Limit • $limit" to "#FBBF24"
        }
        if (LiveFreeze.isFrozen(ctx, suffix)) return "Beku" to "#8A94A6"
        if (SpaceStatus.readLogin(ctx, suffix)) return "Aktif" to "#34D399"
        return "Belum login" to "#8A94A6"
    }

    /** LIVE (v0.1.33): Space dianggap live bila thumbnail-nya segar
     *  (< 60 dtk, sama seperti LIVE_FRESH_SEC di Beranda) dan tidak
     *  dibekukan. Beda dengan "Aktif" (sudah login) — Space bisa Aktif
     *  tapi tidak live bila prosesnya sudah mati / thumbnail basi. */
    fun isLive(ctx: Context, suffix: String): Boolean {
        if (LiveFreeze.isFrozen(ctx, suffix)) return false
        val f = FileStore.liveThumbFile(ctx, suffix)
        if (!f.exists()) return false
        val ageMs = System.currentTimeMillis() - f.lastModified()
        return ageMs in 0 until 60_000
    }

    /** Saring daftar ke Space LIVE saja (di luar excludeSuffix).
     *  Bila tidak ada yang live, kembalikan daftar asal (fallback) —
     *  dialog tidak boleh kosong. */
    fun liveOnly(
        ctx: Context,
        spaces: List<SpaceRegistry.SpaceEntry>,
        excludeSuffix: String?
    ): List<SpaceRegistry.SpaceEntry> {
        val live = spaces.filter {
            val s = SpaceRegistry.suffixForSlot(it.slot)
            s != excludeSuffix && isLive(ctx, s)
        }
        return if (live.isNotEmpty()) live else spaces
    }

    fun show(
        ctx: Context,
        title: String,
        spaces: List<SpaceRegistry.SpaceEntry>,
        excludeSuffix: String?,
        onPick: (SpaceRegistry.SpaceEntry) -> Unit
    ) {
        val dialog = Dialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.roundedBg("#16233A", 18, ctx)
            setPadding(
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 20),
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 12)
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            setPadding(0, 0, 0, UiKit.dp(ctx, 8))
        })
        var first = true
        for (entry in spaces) {
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            if (suffix == excludeSuffix) continue
            if (!first) {
                root.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 1)
                    )
                    setBackgroundColor(Color.parseColor("#24344D"))
                })
            }
            first = false
            val (statusText, statusColor) = statusOf(ctx, entry)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12))
                isClickable = true
                isFocusable = true
            }
            // Titik warna Space
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 10), UiKit.dp(ctx, 10)
                )
                background = UiKit.dotBg(entry.color)
            })
            // Nama + situs
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = UiKit.dp(ctx, 12) }
            }
            texts.addView(TextView(ctx).apply {
                text = entry.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E8ECF4"))
            })
            texts.addView(TextView(ctx).apply {
                text = entry.url.removePrefix("https://").removePrefix("http://")
                textSize = 12f
                setTextColor(Color.parseColor("#8A94A6"))
            })
            row.addView(texts)
            // Chip status
            row.addView(TextView(ctx).apply {
                text = statusText
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(statusColor))
                setBackgroundResource(R.drawable.chip_bg)
                setPadding(
                    UiKit.dp(ctx, 10), UiKit.dp(ctx, 4),
                    UiKit.dp(ctx, 10), UiKit.dp(ctx, 4)
                )
            })
            row.setOnClickListener { dialog.dismiss(); onPick(entry) }
            root.addView(row)
        }
        // Batal
        root.addView(TextView(ctx).apply {
            text = "Batal"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#5B5BD6"))
            gravity = Gravity.END
            setPadding(0, UiKit.dp(ctx, 12), UiKit.dp(ctx, 4), UiKit.dp(ctx, 8))
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }
}
