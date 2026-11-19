package id.senzspace

import android.content.Context
import android.content.Intent

// Peluncur Space yang dipakai Beranda DAN server API (v0.1.36).
// Satu tempat membangun Intent buka Space — supaya perilaku dari
// tombol Beranda dan dari API identik: REORDER_TO_FRONT (pakai
// instance Space yang sudah jalan, jangan bikin WebView kedua).
object SpaceLauncher {
    fun intentFor(
        ctx: Context,
        entry: SpaceRegistry.SpaceEntry,
        clearData: Boolean = false,
        splitWith: Int? = null,
        newTask: Boolean = false
    ): Intent {
        return Intent(ctx, SpaceRegistry.activityForSlot(entry.slot)).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                if (newTask) Intent.FLAG_ACTIVITY_NEW_TASK else 0
            if (clearData) {
                putExtra(MainActivity.EXTRA_CLEAR_DATA, true)
                putExtra(
                    MainActivity.EXTRA_CLEAR_SUFFIX,
                    SpaceRegistry.suffixForSlot(entry.slot)
                )
            }
            if (splitWith != null) putExtra(MainActivity.EXTRA_SPLIT_WITH, splitWith)
            putExtra(MainActivity.EXTRA_SPACE_NAME, entry.name)
            putExtra(MainActivity.EXTRA_SPACE_COLOR, entry.color)
            putExtra(MainActivity.EXTRA_URL, entry.url)
        }
    }

    /** Buka Space dari konteks non-Activity (misal ApiService):
     *  butuh NEW_TASK supaya ActivityManager mengizinkan. */
    fun open(
        ctx: Context,
        entry: SpaceRegistry.SpaceEntry,
        clearData: Boolean = false,
        splitWith: Int? = null
    ) {
        ctx.startActivity(intentFor(ctx, entry, clearData, splitWith, newTask = true))
    }
}
