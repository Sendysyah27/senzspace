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
        newTask: Boolean = false,
        promptText: String? = null,
        promptFiles: List<String>? = null,
        promptUrl: String? = null
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
            // v0.1.38: payload prompt API - teks + file _inbox + URL.
            if (promptText != null) {
                putExtra(MainActivity.EXTRA_API_PROMPT_TEXT, promptText)
                if (promptFiles != null) {
                    putStringArrayListExtra(
                        MainActivity.EXTRA_API_PROMPT_FILES,
                        ArrayList(promptFiles)
                    )
                }
                if (promptUrl != null) {
                    putExtra(MainActivity.EXTRA_API_PROMPT_URL, promptUrl)
                }
            }
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
        splitWith: Int? = null,
        promptText: String? = null,
        promptFiles: List<String>? = null,
        promptUrl: String? = null
    ) {
        ctx.startActivity(
            intentFor(
                ctx, entry, clearData, splitWith, newTask = true,
                promptText = promptText, promptFiles = promptFiles,
                promptUrl = promptUrl
            )
        )
    }
}
