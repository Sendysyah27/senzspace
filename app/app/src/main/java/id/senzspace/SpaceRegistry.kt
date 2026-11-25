package id.senzspace

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Registry Space (S1 CRUD, D1–D2): daftar Space tersimpan, maks 10.
// Slot 1-10 = proses + direktori data WebView tetap (space1..space6,
// aistudio untuk slot 7, space8..space10). Nama/warna/URL bisa diubah;
// preset 9 (Claude 1-6 + AI Studio + YT Studio slot 9-10, sejak
// v0.1.58) juga boleh diubah/dihapus
// (D2). Sejak v0.1.58 SEMUA 10 slot terisi — tambah Space baru butuh
// perubahan aturan D1 (maks 10), bukan sekadar tambah preset.
// Disimpan sebagai JSON di SharedPreferences — BUKAN sesi/secret, hanya
// nama + warna + URL. Aturan maks 10 (D1): sumber kebenaran tetap Rust
// profile-core; registry ini cermin di shell sampai JNI tersambung.
object SpaceRegistry {
    private const val PREFS = "senzspace_registry"
    private const val KEY_SPACES = "spaces_json"
    const val MAX_SPACES = 10

    data class SpaceEntry(
        val slot: Int,
        val name: String,
        val color: String,
        val url: String,
        val preset: Boolean
    )

    val PALETTE = listOf(
        "#5B5BD6", "#0EA5A5", "#E07A5F", "#E5B567", "#7BC47F",
        "#D66BA5", "#38BDF8", "#A78BFA", "#22D3EE", "#FB7185"
    )

    fun suffixForSlot(slot: Int): String =
        if (slot == 7) "aistudio" else "space$slot"

    fun activityForSlot(slot: Int): Class<*> {
        return when (slot) {
            1 -> Space1Activity::class.java
            2 -> Space2Activity::class.java
            3 -> Space3Activity::class.java
            4 -> Space4Activity::class.java
            5 -> Space5Activity::class.java
            6 -> Space6Activity::class.java
            7 -> AiStudioActivity::class.java
            8 -> Space8Activity::class.java
            9 -> Space9Activity::class.java
            else -> Space10Activity::class.java
        }
    }

    // v0.1.58: preset YT Studio slot 9-10.
    // Warna = 2 warna PALETTE pertama yang belum terpakai (slot 1-7 pakai
    // 7 warna pertama; slot 8 Transkrip menduplikasi #E5B567 milik slot 4).
    // Aturan Space (maks 10, preset) sumber kebenarannya Rust profile-core;
    // entri ini cermin di shell, sama seperti preset lain di seed().
    private val YT_STUDIO_ENTRIES = listOf(
        SpaceEntry(9, "YT Studio", "#A78BFA", "https://studio.youtube.com", true),
        SpaceEntry(10, "YT Studio 2", "#22D3EE", "https://studio.youtube.com", true)
    )

    private fun seed(): List<SpaceEntry> = listOf(
        SpaceEntry(1, "Claude 1", "#5B5BD6", "https://claude.ai", true),
        SpaceEntry(2, "Claude 2", "#0EA5A5", "https://claude.ai", true),
        SpaceEntry(3, "Claude 3", "#E07A5F", "https://claude.ai", true),
        SpaceEntry(4, "Claude 4", "#E5B567", "https://claude.ai", true),
        SpaceEntry(5, "Claude 5", "#7BC47F", "https://claude.ai", true),
        SpaceEntry(6, "Claude 6", "#D66BA5", "https://claude.ai", true),
        SpaceEntry(7, "AI Studio", "#38BDF8", "https://aistudio.google.com", true)
    ) + YT_STUDIO_ENTRIES

    fun load(ctx: Context): MutableList<SpaceEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SPACES, null)
        if (raw == null) {
            val seeded = seed().toMutableList()
            save(ctx, seeded)
            return seeded
        }
        return try {
            val arr = JSONArray(raw)
            // v0.1.58 migrasi satu-kali (idempotent): registry di HP yang
            // sudah ada TIDAK kenal slot 9/10 (seed lama cuma 7) — tambah
            // entri YT Studio HANYA bila slot itu kosong. JANGAN PERNAH
            // menimpa entri yang sudah ada (slot 8 Transkrip + slot 1-7
            // tidak disentuh). Simpan hanya bila ada yang ditambahkan.
            val out = mutableListOf<SpaceEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SpaceEntry(
                        o.getInt("slot"),
                        o.getString("name"),
                        o.getString("color"),
                        o.getString("url"),
                        o.optBoolean("preset", false)
                    )
                )
            }
            out.sortBy { it.slot }
            val used = out.map { it.slot }.toSet()
            val missing = YT_STUDIO_ENTRIES.filter { it.slot !in used }
            if (missing.isNotEmpty()) {
                out.addAll(missing)
                out.sortBy { it.slot }
                save(ctx, out)
            }
            out.toMutableList()
        } catch (e: Exception) {
            seed().toMutableList()
        }
    }

    fun save(ctx: Context, list: List<SpaceEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("slot", e.slot)
                put("name", e.name)
                put("color", e.color)
                put("url", e.url)
                put("preset", e.preset)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SPACES, arr.toString()).apply()
    }

    fun findBySuffix(ctx: Context, suffix: String): SpaceEntry? {
        return load(ctx).find { suffixForSlot(it.slot) == suffix }
    }

    /** Tambah Space di slot kosong pertama. Null bila sudah 10 Space. */
    fun add(ctx: Context, name: String, color: String, url: String): SpaceEntry? {
        val list = load(ctx)
        if (list.size >= MAX_SPACES) return null
        val used = list.map { it.slot }.toSet()
        val slot = (1..MAX_SPACES).firstOrNull { it !in used } ?: return null
        val entry = SpaceEntry(slot, name, color, url, false)
        list.add(entry)
        list.sortBy { it.slot }
        save(ctx, list)
        return entry
    }

    fun rename(ctx: Context, slot: Int, newName: String) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.slot == slot }
        if (i >= 0) {
            list[i] = list[i].copy(name = newName)
            save(ctx, list)
        }
    }

    /** Hapus Space. Sesi WebView slot itu ikut dibersihkan (direktori data
     *  WebView dihapus, best-effort) supaya Space baru di slot yang sama
     *  tidak mewarisi login Space yang dihapus. File unduhan TIDAK dihapus
     *  — tetap ada di File Manager. */
    fun remove(ctx: Context, slot: Int) {
        val list = load(ctx)
        list.removeAll { it.slot == slot }
        save(ctx, list)
        try {
            val suffix = suffixForSlot(slot)
            File(ctx.applicationInfo.dataDir, "app_webview_$suffix")
                .deleteRecursively()
            File(ctx.filesDir, "space_$suffix.txt").delete()
            // v0.1.85 (audit CODE-12): hapus JUGA marker limit Space
            // ini — Space baru di slot yang sama tidak boleh mewarisi
            // limit akun yang sudah dihapus (/spaces melapor limit
            // untuk akun yang tidak pernah kena limit).
            SpaceStatus.clearLimit(ctx, suffix)
        } catch (e: Exception) {
            // Best-effort: hapus registry sudah cukup.
        }
    }
}
