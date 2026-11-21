package id.senzspace

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

// Kunci App (D4, mockup v2 layar 06): PIN 4 digit + biometrik, jeda
// setelah 5× salah. PIN TIDAK PERNAH disimpan sebagai teks biasa — yang
// disimpan hanya hash SHA-256 (salt acak per install) + status aktif.
// Pengaktifan dipilih pengguna (D4): kunci MATI secara default sampai
// dihidupkan di Pengaturan.
object LockStore {
    private const val PREFS = "senzspace_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"

    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 30_000L

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun hasPin(ctx: Context): Boolean = prefs(ctx).contains(KEY_HASH)

    private fun salt(ctx: Context): String {
        val p = prefs(ctx)
        var s = p.getString(KEY_SALT, null)
        if (s == null) {
            s = UUID.randomUUID().toString()
            p.edit().putString(KEY_SALT, s).apply()
        }
        return s
    }

    private fun hash(pin: String, salt: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setupPin(ctx: Context, pin: String) {
        prefs(ctx).edit()
            .putString(KEY_HASH, hash(pin, salt(ctx)))
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val stored = prefs(ctx).getString(KEY_HASH, null) ?: return false
        return hash(pin, salt(ctx)) == stored
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun clearPin(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_HASH)
            .putBoolean(KEY_ENABLED, false)
            .putInt(KEY_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    /** Catat percobaan gagal. Kembalikan jumlah percobaan saat ini. */
    fun recordFailure(ctx: Context): Int {
        val p = prefs(ctx)
        val n = p.getInt(KEY_ATTEMPTS, 0) + 1
        val edit = p.edit().putInt(KEY_ATTEMPTS, n)
        if (n >= MAX_ATTEMPTS) {
            edit.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
            edit.putInt(KEY_ATTEMPTS, 0)
        }
        edit.apply()
        return n
    }

    fun resetFailures(ctx: Context) {
        prefs(ctx).edit().putInt(KEY_ATTEMPTS, 0).apply()
    }

    fun attemptsLeft(ctx: Context): Int =
        MAX_ATTEMPTS - prefs(ctx).getInt(KEY_ATTEMPTS, 0)

    fun isLockedOut(ctx: Context): Boolean =
        System.currentTimeMillis() < prefs(ctx).getLong(KEY_LOCKOUT_UNTIL, 0L)

    fun lockoutRemainingMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()
}

// Gerbang kunci: dipakai semua Activity (Main, Browser, File Manager,
// Pengaturan) supaya kembali dari latar selalu lewat kunci bila aktif.
// Status "sudah dibuka" hanya hidup di memori (mati saat proses mati).
object LockGate {
    var verified = false
        private set
    private var inFlight = false

    fun register(
        activity: androidx.appcompat.app.AppCompatActivity
    ): androidx.activity.result.ActivityResultLauncher<android.content.Intent> {
        return activity.registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            inFlight = false
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                verified = true
            } else {
                // Batal / tombol kembali di layar kunci = jangan masuk app.
                activity.finish()
            }
        }
    }

    fun check(
        activity: androidx.appcompat.app.AppCompatActivity,
        launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    ) {
        if (LockStore.isEnabled(activity) && !verified && !inFlight) {
            inFlight = true
            val intent = android.content.Intent(activity, LockActivity::class.java)
            intent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_VERIFY)
            launcher.launch(intent)
        }
    }

    fun onStopped() {
        if (!inFlight) verified = false
    }

    fun markVerified() {
        verified = true
        inFlight = false
    }
}
