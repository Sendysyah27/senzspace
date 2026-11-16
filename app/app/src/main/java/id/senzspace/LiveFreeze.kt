package id.senzspace

import android.content.Context

/** Beku Live per Space (v0.1.20): tile Beranda berhenti update tapi
 *  browser + sesi tetap jalan — yang dibekukan hanya tangkapan
 *  thumbnail live, bukan Space-nya. Disimpan di prefs "senzspace_ui"
 *  (sama seperti live_interval_ms) supaya Beranda (proses utama) dan
 *  proses :spaceN membaca bendera yang sama. */
object LiveFreeze {
    private const val PREFS = "senzspace_ui"

    fun isFrozen(ctx: Context, suffix: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("live_frozen_$suffix", false)

    fun setFrozen(ctx: Context, suffix: String, frozen: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("live_frozen_$suffix", frozen).apply()
    }
}
