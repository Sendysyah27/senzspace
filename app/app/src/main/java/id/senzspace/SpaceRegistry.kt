package id.senzspace

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Registry Space (S1 CRUD, D1–D2): daftar Space tersimpan, maks 10.
// Slot 1-10 = proses + direktori data WebView tetap (space1..space6,
// aistudio untuk slot 7, space8..space10). Nama/warna/URL bisa diubah;
// preset 7 awal (Claude 1-6 + AI Studio) juga boleh diubah/dihapus (D2).
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

    private fun seed(): List<SpaceEntry> = listOf(
        SpaceEntry(1, "Claude 1", "#5B5BD6", "https://claude.ai", true),
        SpaceEntry(2, "Claude 2", "#0EA5A5", "https://claude.ai", true),
        SpaceEntry(3, "Claude 3", "#E07A5F", "https://claude.ai", true),
        SpaceEntry(4, "Claude 4", "#E5B567", "https://claude.ai", true),
        SpaceEntry(5, "Claude 5", "#7BC47F", "https://claude.ai", true),
        SpaceEntry(6, "Claude 6", "#D66BA5", "https://claude.ai", true),
        SpaceEntry(7, "AI Studio", "#38BDF8", "https://aistudio.google.com", true)
    )

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
            out.sortedBy { it.slot }.toMutableList()
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
        } catch (e: Exception) {
            // Best-effort: hapus registry sudah cukup.
        }
    }
}
