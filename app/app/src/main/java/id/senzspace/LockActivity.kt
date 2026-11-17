package id.senzspace

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

// Layar kunci (D4, mockup v2 layar 06): keypad PIN 4 digit + tombol
// biometrik, jeda 30 dtk setelah 5× salah. Mode:
//  - verify: buka app (dipakai LockGate)
//  - setup: buat PIN baru (2× masukkan)
//  - change: verifikasi PIN lama, lalu buat PIN baru
class LockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_VERIFY = "verify"
        const val MODE_SETUP = "setup"
        const val MODE_CHANGE = "change"
    }

    private var mode = MODE_VERIFY
    private var step = 0 // 0 = PIN lama (change) / PIN baru (setup), 1 = ulangi PIN
    private var firstPin = ""
    private var entered = ""
    private lateinit var dots: TextView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var errorView: TextView
    private lateinit var keypad: GridLayout
    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.1.31: ikuti sensor putar HP (berlaku walau auto-rotate sistem mati).
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
        if (mode == MODE_CHANGE) step = 0 else step = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(48, 120, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val logo = TextView(this).apply {
            text = "🔒 SenzSpace"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            gravity = Gravity.CENTER
        }
        titleView = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E8ECF4"))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 8)
        }
        subView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
        }
        dots = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.parseColor("#E8ECF4"))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 32)
        }
        errorView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FF6B6B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        keypad = GridLayout(this).apply {
            rowCount = 4
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        for (k in keys) {
            val btn = Button(this).apply {
                text = k
                textSize = 22f
                setTextColor(Color.parseColor("#E8ECF4"))
                setBackgroundColor(Color.parseColor("#16233A"))
                minimumHeight = 140
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(8, 8, 8, 8)
                }
            }
            btn.setOnClickListener {
                when (k) {
                    "" -> Unit
                    "⌫" -> {
                        if (entered.isNotEmpty()) {
                            entered = entered.dropLast(1)
                            renderDots()
                        }
                    }
                    else -> {
                        if (entered.length < 4) {
                            entered += k
                            renderDots()
                            if (entered.length == 4) {
                                handler.postDelayed({ onPinComplete() }, 150)
                            }
                        }
                    }
                }
            }
            keypad.addView(btn)
        }

        val bioBtn = Button(this).apply {
            text = "🔓  Sidik jari / wajah"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5B5BD6"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
        }
        val bioAvailable = biometricAvailable()
        bioBtn.visibility =
            if (mode == MODE_VERIFY && bioAvailable) android.view.View.VISIBLE
            else android.view.View.GONE
        bioBtn.setOnClickListener { showBiometric() }

        root.addView(logo)
        root.addView(titleView)
        root.addView(subView)
        root.addView(dots)
        root.addView(errorView)
        root.addView(keypad)
        root.addView(bioBtn)
        setContentView(root)

        updateStepText()
        renderDots()
        refreshLockout()

        // Di mode buka-app, tawarkan biometrik langsung bila tersedia.
        if (mode == MODE_VERIFY && bioAvailable && !LockStore.isLockedOut(this)) {
            handler.postDelayed({ showBiometric() }, 400)
        }
    }

    override fun onBackPressed() {
        // Kembali dari layar kunci = batal masuk.
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun updateStepText() {
        when (mode) {
            MODE_VERIFY -> {
                titleView.text = "Masukkan PIN"
                subView.text = "Kunci app aktif — buka dengan PIN kamu"
            }
            MODE_SETUP -> {
                if (step == 0) {
                    titleView.text = "Buat PIN"
                    subView.text = "PIN 4 angka untuk mengunci SenzSpace"
                } else {
                    titleView.text = "Ulangi PIN"
                    subView.text = "Masukkan lagi PIN yang sama"
                }
            }
            MODE_CHANGE -> {
                if (step == 0) {
                    titleView.text = "PIN lama"
                    subView.text = "Masukkan PIN kamu yang sekarang"
                } else if (step == 1) {
                    titleView.text = "PIN baru"
                    subView.text = "Buat PIN 4 angka yang baru"
                } else {
                    titleView.text = "Ulangi PIN baru"
                    subView.text = "Masukkan lagi PIN yang baru"
                }
            }
        }
    }

    private fun renderDots() {
        dots.text = (0 until 4).joinToString("  ") { i ->
            if (i < entered.length) "●" else "○"
        }
    }

    private fun onPinComplete() {
        if (LockStore.isLockedOut(this)) {
            entered = ""
            renderDots()
            refreshLockout()
            return
        }
        when (mode) {
            MODE_VERIFY -> {
                if (LockStore.verifyPin(this, entered)) {
                    LockStore.resetFailures(this)
                    unlockOk()
                } else {
                    val n = LockStore.recordFailure(this)
                    entered = ""
                    renderDots()
                    if (LockStore.isLockedOut(this)) {
                        errorView.text = "5× salah — jeda 30 detik"
                        refreshLockout()
                    } else {
                        errorView.text = "PIN salah — sisa ${LockStore.MAX_ATTEMPTS - n}× coba"
                    }
                }
            }
            MODE_SETUP -> {
                if (step == 0) {
                    firstPin = entered
                    entered = ""
                    step = 1
                    updateStepText()
                    renderDots()
                } else {
                    if (entered == firstPin) {
                        LockStore.setupPin(this, entered)
                        Toast.makeText(this, "PIN dibuat — kunci app aktif", Toast.LENGTH_SHORT).show()
                        unlockOk()
                    } else {
                        entered = ""
                        firstPin = ""
                        step = 0
                        updateStepText()
                        renderDots()
                        errorView.text = "PIN tidak sama — ulangi dari awal"
                    }
                }
            }
            MODE_CHANGE -> {
                if (step == 0) {
                    if (LockStore.verifyPin(this, entered)) {
                        LockStore.resetFailures(this)
                        entered = ""
                        step = 1
                        errorView.text = ""
                        updateStepText()
                        renderDots()
                    } else {
                        LockStore.recordFailure(this)
                        entered = ""
                        renderDots()
                        errorView.text = "PIN lama salah"
                    }
                } else if (step == 1) {
                    firstPin = entered
                    entered = ""
                    step = 2
                    updateStepText()
                    renderDots()
                } else {
                    if (entered == firstPin) {
                        LockStore.setupPin(this, entered)
                        Toast.makeText(this, "PIN diganti", Toast.LENGTH_SHORT).show()
                        unlockOk()
                    } else {
                        entered = ""
                        firstPin = ""
                        step = 1
                        updateStepText()
                        renderDots()
                        errorView.text = "PIN tidak sama — ulangi PIN baru"
                    }
                }
            }
        }
    }

    private fun unlockOk() {
        LockGate.markVerified()
        setResult(RESULT_OK)
        finish()
    }

    private fun biometricAvailable(): Boolean {
        val mgr = BiometricManager.from(this)
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            mgr.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    private fun showBiometric() {
        if (LockStore.isLockedOut(this)) return
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    LockStore.resetFailures(this@LockActivity)
                    unlockOk()
                }

                override fun onAuthenticationFailed() {
                    errorView.text = "Biometrik tidak cocok — coba lagi atau pakai PIN"
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Pengguna batal / sensor error — biarkan pakai PIN.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        errorView.text = errString.toString()
                    }
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Buka SenzSpace")
            .setSubtitle("Sidik jari atau wajah")
            .setNegativeButtonText("Pakai PIN")
            .build()
        prompt.authenticate(info)
    }

    private fun refreshLockout() {
        if (!LockStore.isLockedOut(this)) {
            keypad.isEnabled = true
            for (i in 0 until keypad.childCount) keypad.getChildAt(i).isEnabled = true
            countdownRunning = false
            return
        }
        keypad.isEnabled = false
        for (i in 0 until keypad.childCount) keypad.getChildAt(i).isEnabled = false
        if (countdownRunning) return
        countdownRunning = true
        val tick = object : Runnable {
            override fun run() {
                val left = LockStore.lockoutRemainingMs(this@LockActivity)
                if (left > 0) {
                    errorView.text = "Jeda ${left / 1000 + 1} detik — coba lagi nanti"
                    handler.postDelayed(this, 1000)
                } else {
                    errorView.text = ""
                    countdownRunning = false
                    refreshLockout()
                }
            }
        }
        handler.post(tick)
    }
}
