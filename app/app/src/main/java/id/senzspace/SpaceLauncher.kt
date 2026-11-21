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
        promptUrl: String? = null,
        promptProject: String? = null,
        ttsScript: String? = null,
        ttsVoice: String? = null,
        ttsModel: String? = null,
        ttsUrl: String? = null,
        ttsSpeaker: String? = null,
        navigateUrl: String? = null,
        studioAction: String? = null,
        studioFile: String? = null,
        studioTitle: String? = null,
        studioDescription: String? = null,
        studioSchedule: String? = null
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
                if (promptProject != null) {
                    putExtra(MainActivity.EXTRA_API_PROMPT_PROJECT, promptProject)
                }
            }
            // v0.1.39: payload TTS API.
            if (ttsScript != null) {
                putExtra(MainActivity.EXTRA_API_TTS_SCRIPT, ttsScript)
                if (ttsVoice != null) {
                    putExtra(MainActivity.EXTRA_API_TTS_VOICE, ttsVoice)
                }
                if (ttsModel != null) {
                    putExtra(MainActivity.EXTRA_API_TTS_MODEL, ttsModel)
                }
                if (ttsUrl != null) {
                    putExtra(MainActivity.EXTRA_API_TTS_URL, ttsUrl)
                }
                if (ttsSpeaker != null) {
                    putExtra(MainActivity.EXTRA_API_TTS_SPEAKER, ttsSpeaker)
                }
            }
            // v0.1.52: URL navigasi API (POST /navigate).
            if (navigateUrl != null) {
                putExtra(MainActivity.EXTRA_API_NAVIGATE_URL, navigateUrl)
            }
            // v0.1.60: payload eksekutor YouTube Studio (POST /studio).
            if (studioAction != null) {
                putExtra(MainActivity.EXTRA_API_STUDIO_ACTION, studioAction)
                if (studioFile != null) {
                    putExtra(MainActivity.EXTRA_API_STUDIO_FILE, studioFile)
                }
                if (studioTitle != null) {
                    putExtra(MainActivity.EXTRA_API_STUDIO_TITLE, studioTitle)
                }
                if (studioDescription != null) {
                    putExtra(MainActivity.EXTRA_API_STUDIO_DESCRIPTION, studioDescription)
                }
                if (studioSchedule != null) {
                    putExtra(MainActivity.EXTRA_API_STUDIO_SCHEDULE, studioSchedule)
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
        promptUrl: String? = null,
        promptProject: String? = null,
        ttsScript: String? = null,
        ttsVoice: String? = null,
        ttsModel: String? = null,
        ttsUrl: String? = null,
        ttsSpeaker: String? = null,
        navigateUrl: String? = null,
        studioAction: String? = null,
        studioFile: String? = null,
        studioTitle: String? = null,
        studioDescription: String? = null,
        studioSchedule: String? = null
    ) {
        ctx.startActivity(
            intentFor(
                ctx, entry, clearData, splitWith, newTask = true,
                promptText = promptText, promptFiles = promptFiles,
                promptUrl = promptUrl, promptProject = promptProject,
                ttsScript = ttsScript, ttsVoice = ttsVoice,
                ttsModel = ttsModel, ttsUrl = ttsUrl,
                ttsSpeaker = ttsSpeaker, navigateUrl = navigateUrl,
                studioAction = studioAction, studioFile = studioFile,
                studioTitle = studioTitle,
                studioDescription = studioDescription,
                studioSchedule = studioSchedule
            )
        )
    }
}
