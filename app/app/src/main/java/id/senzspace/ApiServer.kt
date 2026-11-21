package id.senzspace

import android.app.ActivityManager
import android.provider.Settings
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

// Token API (v0.1.36): satu token acak per install, disimpan di
// SharedPreferences senzspace_ui (kunci api_token), bisa dilihat +
// dibuat ulang di Pengaturan. Token BUKAN sesi login Claude/AI Studio:
// token ini hanya mengizinkan pemanggilan API (via ADB forward) dari
// perangkat ini. Token dibaca dari perangkat via run-as (shared_prefs)
// — token TIDAK PERNAH ditulis di repo, chat, atau memori.
object ApiToken {
    private const val PREFS = "senzspace_ui"
    private const val KEY = "api_token"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("api_enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("api_enabled", on).apply()
    }

    fun getOrCreate(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrEmpty()) return existing
        val token = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    fun regenerate(ctx: Context): String {
        val token = UUID.randomUUID().toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, token).apply()
        return token
    }

    fun check(ctx: Context, authHeader: String?): Boolean {
        val token = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return false
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false
        val given = authHeader.substring("Bearer ".length)
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            given.toByteArray(Charsets.UTF_8)
        )
    }
}

// API kontrol lokal SenzSpace (v0.1.36). Server HTTP minimal, stdlib
// saja (ServerSocket, tanpa dependensi baru): bind 127.0.0.1:19335 —
// localhost SAJA, tidak di-bind ke 0.0.0.0, tanpa TLS (jalur loopback).
// Tiap rute wajib
// header "Authorization: Bearer <token>"; tanpa token -> 401.
// Rute: GET /ping, /status, /spaces, /files, /download, /thumb;
// POST /open, /freeze. Balasan JSON, kecuali /download + /thumb
// (byte mentah file). Dijalankan ApiService (foreground,
// START_STICKY) supaya tetap hidup walau Beranda ditutup; API baru
// aktif setelah app dibuka sekali (tidak ada BootReceiver di v1).
// Keamanan: token dicek per request; folder file dibatasi ke folder
// kerja SenzSpace (path traversal ditolak); tidak ada sesi/cookie
// yang pernah dikirim lewat API.
class ApiServer(private val ctx: Context) {
    companion object {
        const val PORT = 19335
        // v0.1.39: format suara VO default.
        const val DEFAULT_VOICE = "Very bright, high-pitched, cheerful Indonesian male voice, fast-paced, energetic, punchy, crisp, lively, natural Indonesian accent, speed 1.4x"
        // v0.1.41: model TTS starred + voice (nama).
        const val DEFAULT_TTS_MODEL = "Gemini 3.8 Flash Lite TTS"
        const val DEFAULT_TTS_SPEAKER = "Sadaltager"
        private const val TAG = "SenzApi"
        private val FILE_KEYS = setOf(
            "space1", "space2", "space3", "space4", "space5", "space6",
            "aistudio", "space8", "space9", "space10",
            "_inbox", "_arsip", "_log-blob", "_log-crash", "_live"
        )
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val server = ServerSocket(
                    PORT, 8, InetAddress.getByName("127.0.0.1")
                )
                serverSocket = server
                Log.i(TAG, "API aktif di 127.0.0.1:$PORT")
                while (running) {
                    try {
                        val client = server.accept()
                        pool.execute { handle(client) }
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept gagal: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API gagal start: ${e.message}")
            }
        }, "senz-api-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
    }

    // ---- HTTP minimal: baca header byte-per-byte dari stream mentah
    // (BufferedReader bisa menelan byte body ke buffernya sendiri).
    private fun readLine(input: InputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.US_ASCII)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                send(client, 400, "application/json", errJson("bad request"))
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyBytes, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            val body = String(bodyBytes, 0, read, Charsets.UTF_8)
            val bodyRaw = bodyBytes.copyOf(read)
            if (!ApiToken.check(ctx, headers["authorization"])) {
                send(client, 401, "application/json", errJson("unauthorized"))
                return
            }
            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()
            route(client, method, path, query, body, bodyRaw)
        } catch (e: Exception) {
            Log.w(TAG, "handle gagal: ${e.message}")
            try {
                send(client, 500, "application/json", errJson("internal error"))
            } catch (_: Exception) {
            }
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx < 0) {
                out[URLDecoder.decode(pair, "UTF-8")] = ""
            } else {
                out[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
        }
        return out
    }

    private fun errJson(msg: String): ByteArray =
        JSONObject().put("error", msg).toString().toByteArray(Charsets.UTF_8)

    private fun send(client: Socket, code: Int, contentType: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun sendJson(client: Socket, code: Int, obj: JSONObject) {
        send(client, code, "application/json", obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun route(client: Socket, method: String, path: String, query: Map<String, String>, body: String, bodyBytes: ByteArray = ByteArray(0)) {
        when {
            method == "GET" && path == "/ping" -> {
                sendJson(client, 200, JSONObject().apply {
                    put("ok", true)
                    put("app", "SenzSpace")
                    put("version", BuildConfig.VERSION_NAME)
                    put("build", BuildConfig.VERSION_CODE)
                })
            }
            method == "GET" && path == "/status" -> sendJson(client, 200, statusJson())
            method == "GET" && path == "/spaces" -> {
                val arr = JSONArray()
                for (s in spaceJsonList()) arr.put(s)
                sendJson(client, 200, JSONObject().put("spaces", arr))
            }
            method == "POST" && path == "/open" -> {
                val req = JSONObject(body)
                val slot = req.optInt("slot", -1)
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                // v0.1.37: JUJUR soal tampil. Android (BAL) melarang
                // service di background menarik activity ke depan:
                // Space ke-launch tapi task-nya visible=false (insiden
                // 19 Nov 20:28, Space 3). Jadi: launch tetap jalan
                // (pemanasan WebView), tapi balasan beda menurut
                // keadaan app — foreground = langsung tampil,
                // background = notifikasi-tap.
                // v0.1.42: izin SYSTEM_ALERT_WINDOW ("tampil di atas
                // aplikasi lain") MEMBEBASKAN launch dari BAL — app
                // pemegang izin boleh menarik activity ke depan dari
                // background. Jadi overlay == tampil (tanpa ketuk).
                val visible = appHasVisibleWindow()
                val overlay = Settings.canDrawOverlays(ctx)
                SpaceLauncher.open(
                    ctx, entry,
                    clearData = req.optBoolean("clearData", false),
                    splitWith = if (req.has("splitWith")) req.optInt("splitWith") else null
                )
                if (visible || overlay) {
                    sendJson(client, 200, JSONObject().apply {
                        put("opened", entry.name)
                        put("slot", entry.slot)
                        put("visible", true)
                        if (overlay && !visible) put("via", "overlay")
                    })
                } else {
                    postOpenNotification(entry)
                    sendJson(client, 202, JSONObject().apply {
                        put("opened", entry.name)
                        put("slot", entry.slot)
                        put("visible", false)
                        put("needs_tap", true)
                        put("note", "app di background — ketuk notifikasi 'Buka ${entry.name}' untuk menampilkan Space")
                    })
                }
            }
            method == "POST" && path == "/upload" -> {
                // v0.1.38: kirim file dari VM ke folder kerja app
                // (biasanya _inbox, untuk dilampirkan /prompt). Body =
                // byte mentah file, BUKAN JSON (String body merusak
                // byte biner). Nama file disaring: tanpa pemisah path.
                val folder = query["folder"] ?: "_inbox"
                val name = query["name"] ?: ""
                if (folder !in FILE_KEYS) {
                    sendJson(client, 400, JSONObject().put("error", "folder tidak diizinkan"))
                    return
                }
                if (name.isBlank() || name.contains('/') || name.contains('\\') ||
                    name == "." || name == ".." || name.contains("..")
                ) {
                    sendJson(client, 400, JSONObject().put("error", "nama file tidak valid"))
                    return
                }
                if (bodyBytes.size > 100 * 1024 * 1024) {
                    sendJson(client, 413, JSONObject().put("error", "file > 100MB"))
                    return
                }
                val dir = FileStore.folderDir(ctx, folder)
                dir.mkdirs()
                // v0.1.56: nama bentrok → tambah (1), (2) (uniqueFile),
                // JANGAN timpa file lama diam-diam (audit §6.1). Balasan
                // membawa nama FINAL yang dipakai.
                val dest = FileStore.uniqueFile(dir, name)
                dest.writeBytes(bodyBytes)
                sendJson(client, 200, JSONObject().apply {
                    put("uploaded", dest.name)
                    put("folder", folder)
                    put("size", bodyBytes.size)
                })
            }
            method == "POST" && path == "/stage" -> {
                // v0.1.56 (Opsi B): salin/pindah satu file kerja ke
                // _inbox supaya bisa dilampirkan /prompt — staging yang
                // tadinya cuma bisa lewat layar FM. toFolder DIKUNCI
                // "_inbox": tidak ada jalan pintas antar-Space tanpa
                // lewat _inbox. File yang sudah di _inbox = sudah
                // ter-stage, langsung dikembalikan namanya.
                val req = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    sendJson(client, 400, JSONObject().put("error", "body bukan JSON"))
                    return
                }
                val space = req.optString("space", "")
                val path = req.optString("path", "")
                val move = req.optBoolean("move", false)
                if (space.isBlank() || path.isBlank()) {
                    sendJson(client, 400, JSONObject().put("error", "space + path wajib diisi"))
                    return
                }
                val src = resolveFile(space, path, mustBeDir = false)
                if (src == null || !src.isFile) {
                    sendJson(client, 404, JSONObject().put("error", "file tidak ketemu: $path"))
                    return
                }
                val inboxDir = FileStore.folderDir(ctx, "_inbox")
                if (src.parentFile == inboxDir) {
                    sendJson(client, 200, JSONObject().apply {
                        put("staged", src.name)
                        put("folder", "_inbox")
                        put("size", src.length())
                        put("moved", false)
                        put("already_staged", true)
                    })
                    return
                }
                val staged = if (move) FileStore.move(ctx, src, "_inbox")
                else FileStore.copy(ctx, src, "_inbox")
                if (staged == null) {
                    sendJson(client, 500, JSONObject().put("error", "gagal menyalin file ke _inbox"))
                    return
                }
                sendJson(client, 200, JSONObject().apply {
                    put("staged", staged.name)
                    put("folder", "_inbox")
                    put("size", staged.length())
                    put("moved", move)
                })
            }
            method == "POST" && path == "/delete" -> {
                // v0.1.56: hapus satu file/folder kerja (audit §6.2 —
                // _inbox menumpuk tanpa sapu; TIDAK ADA sapu otomatis,
                // hapus hanya atas perintah eksplisit per file).
                // Guard: resolveFile yang sama (allowlist folder +
                // canonical path), dan root folder TIDAK BOLEH dihapus.
                val req = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    sendJson(client, 400, JSONObject().put("error", "body bukan JSON"))
                    return
                }
                val space = req.optString("space", "")
                val path = req.optString("path", "")
                if (space.isBlank() || path.isBlank()) {
                    sendJson(client, 400, JSONObject().put("error", "space + path wajib diisi"))
                    return
                }
                val target = resolveFile(space, path, mustBeDir = false)
                if (target == null || !target.exists()) {
                    sendJson(client, 404, JSONObject().put("error", "file tidak ketemu: $path"))
                    return
                }
                val baseCanon = try {
                    FileStore.folderDir(ctx, space).canonicalPath
                } catch (_: Exception) {
                    sendJson(client, 500, JSONObject().put("error", "gagal membaca folder"))
                    return
                }
                val targetCanon = try {
                    target.canonicalPath
                } catch (_: Exception) {
                    sendJson(client, 500, JSONObject().put("error", "gagal membaca file"))
                    return
                }
                if (targetCanon == baseCanon) {
                    sendJson(client, 400, JSONObject().put("error", "tidak boleh menghapus folder itu sendiri"))
                    return
                }
                if (!FileStore.delete(target)) {
                    sendJson(client, 500, JSONObject().put("error", "gagal menghapus: $path"))
                    return
                }
                sendJson(client, 200, JSONObject().apply {
                    put("deleted", target.name)
                    put("space", space)
                })
            }
            method == "POST" && path == "/prompt" -> {
                // v0.1.38: kirim prompt (+ lampiran _inbox) ke chat
                // Claude di satu Space. HANYA atas perintah eksplisit
                // per kirim - tidak ada auto-kirim di mana pun.
                // API menitipkan payload lewat intent extra;
                // BrowserActivity (proses Space) yang mengeksekusi di
                // WebView-nya sendiri, lalu menulis status ke file
                // api-prompt-<suffix>.json (baca via /prompt-status).
                val req = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    sendJson(client, 400, JSONObject().put("error", "body bukan JSON"))
                    return
                }
                val slot = req.optInt("slot", -1)
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val text = req.optString("text", "")
                if (text.isBlank() || text.length > 50000) {
                    sendJson(client, 400, JSONObject().put("error", "teks kosong / terlalu panjang"))
                    return
                }
                val url = req.optString("url", "").ifBlank { null }
                // v0.1.40: nama Project tujuan (mis. "SSR CONTENT STUDIO").
                // BrowserActivity menavigasi sendiri dari tombol kiri atas
                // (sidebar) ke Project itu - user TIDAK perlu kirim URL.
                val project = req.optString("project", "").ifBlank { null }
                if (url != null && !url.startsWith("https://claude.ai/")) {
                    sendJson(client, 400, JSONObject().put("error", "url harus https://claude.ai/..."))
                    return
                }
                val files = mutableListOf<String>()
                val filesArr = req.optJSONArray("files")
                if (filesArr != null) {
                    for (i in 0 until filesArr.length()) {
                        val name = filesArr.optString(i, "")
                        val f = resolveFile("_inbox", name, mustBeDir = false)
                        if (f == null || !f.isFile) {
                            sendJson(client, 404, JSONObject().put("error", "file _inbox tidak ketemu: $name"))
                            return
                        }
                        files.add(name)
                    }
                }
                val suffix = SpaceRegistry.suffixForSlot(slot)
                val queuedAt = writePromptStatus(suffix, "queued", "menunggu Space tampil")
                val visible = appHasVisibleWindow()
                val overlay = Settings.canDrawOverlays(ctx)
                SpaceLauncher.open(
                    ctx, entry,
                    promptText = text, promptFiles = files,
                    promptUrl = url, promptProject = project
                )
                // v0.1.42: gagal-jujur — bila payload tidak diambil
                // Space dalam 60 detik, status jadi failed (jangan
                // diam di "queued" seperti insiden 00:28-00:41).
                watchPickup(suffix, "prompt", queuedAt, overlay)
                if (visible || overlay) {
                    sendJson(client, 202, JSONObject().apply {
                        put("queued", entry.name)
                        put("slot", entry.slot)
                        put("files", org.json.JSONArray(files))
                        put("visible", true)
                        if (overlay && !visible) put("via", "overlay")
                        put("status", "queued")
                    })
                } else {
                    postOpenNotification(entry)
                    sendJson(client, 202, JSONObject().apply {
                        put("queued", entry.name)
                        put("slot", entry.slot)
                        put("files", org.json.JSONArray(files))
                        put("visible", false)
                        put("needs_tap", true)
                        put("status", "queued")
                        put("note", "Tugas sedang berjalan di background — hasilnya dilaporkan setelah selesai (ketuk notifikasi kalau mau lihat)")
                    })
                }
            }
            method == "GET" && path == "/prompt-status" -> {
                val slot = query["slot"]?.toIntOrNull() ?: -1
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val suffix = SpaceRegistry.suffixForSlot(slot)
                val f = java.io.File(ctx.filesDir, "api-prompt-$suffix.json")
                if (!f.exists()) {
                    sendJson(client, 200, JSONObject().put("status", "none"))
                    return
                }
                try {
                    sendJson(client, 200, JSONObject(f.readText()))
                } catch (_: Exception) {
                    sendJson(client, 200, JSONObject().put("status", "unreadable"))
                }
            }
            method == "POST" && path == "/tts" -> {
                // v0.1.39-41: generate VO di AI Studio (Space 7) lewat API.
                // HANYA atas perintah eksplisit per generate - tidak ada
                // auto-generate. Payload lewat intent extra; AiStudio
                // (proses Space) yang mengeksekusi di WebView-nya.
                // Alur v0.1.41: Run
                // settings -> kartu model -> Starred -> model TTS ->
                // Create new dialog -> Style (format suara) -> Speaker
                // (nama voice) -> naskah -> Run -> tunggu -> unduh
                // (blob trap menyimpan wav ke folder aistudio).
                // Status ditulis ke api-tts-<suffix>.json.
                val req = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    sendJson(client, 400, JSONObject().put("error", "body bukan JSON"))
                    return
                }
                val slot = req.optInt("slot", 7)
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val script = req.optString("script", "")
                if (script.isBlank() || script.length > 50000) {
                    sendJson(client, 400, JSONObject().put("error", "naskah kosong / terlalu panjang"))
                    return
                }
                // voice = STYLE (teks "Describe the voice style"),
                // BUKAN nama voice. Nama voice = speaker (Sadaltager).
                val voice = req.optString("voice", "").ifBlank { DEFAULT_VOICE }
                val model = req.optString("model", "").ifBlank { DEFAULT_TTS_MODEL }
                val speaker = req.optString("speaker", "").ifBlank { DEFAULT_TTS_SPEAKER }
                val url = req.optString("url", "").ifBlank { null }
                if (url != null && !url.startsWith("https://aistudio.google.com/")) {
                    sendJson(client, 400, JSONObject().put("error", "url harus https://aistudio.google.com/..."))
                    return
                }
                val suffix = SpaceRegistry.suffixForSlot(slot)
                val queuedAt = writeTtsStatus(suffix, "queued", "menunggu Space tampil")
                val visible = appHasVisibleWindow()
                val overlay = Settings.canDrawOverlays(ctx)
                SpaceLauncher.open(
                    ctx, entry,
                    ttsScript = script, ttsVoice = voice,
                    ttsModel = model, ttsUrl = url,
                    ttsSpeaker = speaker
                )
                // v0.1.42: gagal-jujur 60 detik (lihat /prompt).
                watchPickup(suffix, "tts", queuedAt, overlay)
                if (visible || overlay) {
                    sendJson(client, 202, JSONObject().apply {
                        put("queued", entry.name)
                        put("slot", entry.slot)
                        put("visible", true)
                        if (overlay && !visible) put("via", "overlay")
                        put("status", "queued")
                    })
                } else {
                    postOpenNotification(entry)
                    sendJson(client, 202, JSONObject().apply {
                        put("queued", entry.name)
                        put("slot", entry.slot)
                        put("visible", false)
                        put("needs_tap", true)
                        put("status", "queued")
                        put("note", "Tugas sedang berjalan di background — hasilnya dilaporkan setelah selesai (ketuk notifikasi kalau mau lihat)")
                    })
                }
            }
            method == "GET" && path == "/tts-status" -> {
                val slot = query["slot"]?.toIntOrNull() ?: 7
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val suffix = SpaceRegistry.suffixForSlot(slot)
                val f = java.io.File(ctx.filesDir, "api-tts-$suffix.json")
                if (!f.exists()) {
                    sendJson(client, 200, JSONObject().put("status", "none"))
                    return
                }
                try {
                    sendJson(client, 200, JSONObject(f.readText()))
                } catch (_: Exception) {
                    sendJson(client, 200, JSONObject().put("status", "unreadable"))
                }
            }
            method == "GET" && path == "/page" -> {
                // v0.1.52: baca keadaan halaman Space (ditulis
                // BrowserActivity ke api-page-<suffix>.json tiap
                // halaman selesai + tiap ~20 dtk). Balasan membawa
                // umur snapshot + basi/tidak — snapshot TIDAK PERNAH
                // segar bila proses Space mati (mis. sesudah install
                // APK baru). Isi: url (tanpa query), title, text
                // (awal halaman), tail (akhir halaman), generating,
                // limit + limitReset.
                val slot = query["slot"]?.toIntOrNull() ?: -1
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val suffix = SpaceRegistry.suffixForSlot(slot)
                val f = java.io.File(ctx.filesDir, "api-page-$suffix.json")
                if (!f.exists()) {
                    sendJson(client, 200, JSONObject().put("status", "none"))
                    return
                }
                try {
                    val obj = JSONObject(f.readText())
                    val ageSec = ((System.currentTimeMillis() - obj.optLong("updated", 0L)) / 1000L).coerceAtLeast(0L)
                    obj.put("ageSec", ageSec)
                    obj.put("stale", ageSec > 120)
                    sendJson(client, 200, obj)
                } catch (_: Exception) {
                    sendJson(client, 200, JSONObject().put("status", "unreadable"))
                }
            }
            method == "POST" && path == "/navigate" -> {
                // v0.1.52: muat URL di Space (mis. balik ke chat
                // produksi yang tadi ke-reset ke home). Host URL HARUS
                // sama dengan situs Space itu (claude.ai untuk Space
                // Claude, aistudio.google.com untuk AI Studio) — URL
                // ke situs lain DITOLAK.
                val req = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    sendJson(client, 400, JSONObject().put("error", "body bukan JSON"))
                    return
                }
                val slot = req.optInt("slot", -1)
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val url = req.optString("url", "")
                val wantHost = try {
                    android.net.Uri.parse(url).host ?: ""
                } catch (_: Exception) {
                    ""
                }
                val ownHost = try {
                    android.net.Uri.parse(entry.url).host ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (!url.startsWith("https://") || wantHost.isEmpty() || wantHost != ownHost) {
                    sendJson(client, 400, JSONObject().put("error", "url harus https://<situs Space ini>/..."))
                    return
                }
                val visible = appHasVisibleWindow()
                val overlay = Settings.canDrawOverlays(ctx)
                SpaceLauncher.open(ctx, entry, navigateUrl = url)
                if (visible || overlay) {
                    sendJson(client, 200, JSONObject().apply {
                        put("navigated", entry.name)
                        put("slot", entry.slot)
                        put("url", url)
                        put("visible", true)
                        if (overlay && !visible) put("via", "overlay")
                    })
                } else {
                    postOpenNotification(entry)
                    sendJson(client, 202, JSONObject().apply {
                        put("navigated", entry.name)
                        put("slot", entry.slot)
                        put("url", url)
                        put("visible", false)
                        put("needs_tap", true)
                        put("note", "app di background — ketuk notifikasi 'Buka ${entry.name}' untuk menampilkan Space")
                    })
                }
            }
            method == "POST" && path == "/freeze" -> {
                val req = JSONObject(body)
                val slot = req.optInt("slot", -1)
                val entry = SpaceRegistry.load(ctx).firstOrNull { it.slot == slot }
                if (entry == null) {
                    sendJson(client, 404, JSONObject().put("error", "no Space on slot $slot"))
                    return
                }
                val frozen = req.optBoolean("frozen", false)
                LiveFreeze.setFrozen(ctx, SpaceRegistry.suffixForSlot(slot), frozen)
                sendJson(client, 200, JSONObject().apply {
                    put("slot", slot)
                    put("frozen", frozen)
                })
            }
            method == "GET" && path == "/files" -> {
                val file = resolveFile(query["space"], query["path"], mustBeDir = true)
                if (file == null) {
                    sendJson(client, 404, JSONObject().put("error", "folder tidak ketemu"))
                    return
                }
                val arr = JSONArray()
                for (f in FileStore.listEntries(file)) {
                    arr.put(JSONObject().apply {
                        put("name", f.name)
                        put("dir", f.isDirectory)
                        put("size", if (f.isFile) f.length() else 0)
                        put("modified", f.lastModified())
                    })
                }
                sendJson(client, 200, JSONObject().put("entries", arr))
            }
            method == "GET" && path == "/download" -> {
                val file = resolveFile(query["space"], query["path"], mustBeDir = false)
                if (file == null || !file.isFile) {
                    sendJson(client, 404, JSONObject().put("error", "file tidak ketemu"))
                    return
                }
                val bytes = file.readBytes()
                val out = client.getOutputStream()
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${FileStore.mimeType(file)}\r\n" +
                    "Content-Disposition: attachment; filename=\"${file.name}\"\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
            method == "GET" && path == "/thumb" -> {
                val suffix = query["space"] ?: ""
                val file = FileStore.liveThumbFile(ctx, suffix)
                if (!file.exists()) {
                    sendJson(client, 404, JSONObject().put("error", "thumbnail tidak ada"))
                    return
                }
                send(client, 200, "image/jpeg", file.readBytes())
            }
            else -> sendJson(client, 404, JSONObject().put("error", "rute tidak dikenal"))
        }
    }

    /** Tulis status eksekusi prompt ke filesDir internal
     *  (api-prompt-<suffix>.json). File yang SAMA ditulis
     *  BrowserActivity dari proses Space; main process hanya
     *  membaca + menulis status awal "queued". */
    private fun writePromptStatus(suffix: String, status: String, detail: String): Long {
        val now = System.currentTimeMillis()
        try {
            val obj = JSONObject().apply {
                put("status", status)
                put("detail", detail)
                put("updated", now)
            }
            java.io.File(ctx.filesDir, "api-prompt-$suffix.json")
                .writeText(obj.toString())
        } catch (_: Exception) {
        }
        return now
    }

    /** v0.1.42 - GAGAL-JUJUR: bila payload prompt/tts tidak diambil
     *  Space dalam 60 detik (file status masih "queued" dengan
     *  timestamp yang SAMA seperti saat ApiServer menulisnya —
     *  artinya BrowserActivity tidak pernah jalan / consume* tidak
     *  pernah dipanggil), ubah status jadi failed dengan alasan
     *  yang jelas. Jangan diam di "queued" 12 menit seperti
     *  insiden VO Charger 00:28-00:41. Bila activity akhirnya
     *  jalan (telat), tulisannya sendiri (running/done/failed)
     *  menimpa status ini — tidak ada konflik. */
    private fun watchPickup(suffix: String, kind: String, queuedAt: Long, overlay: Boolean) {
        Thread({
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val fileName = if (kind == "prompt") "api-prompt-$suffix.json" else "api-tts-$suffix.json"
            val file = java.io.File(ctx.filesDir, fileName)
            val cur = try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                return@Thread
            }
            if (cur.optString("status") == "queued" && cur.optLong("updated") == queuedAt) {
                val reason = if (!overlay) {
                    "Space tidak tampil dalam 60 detik — izin 'Tampil di atas aplikasi lain' belum diberikan. Buka SenzSpace → Pengaturan → API Kontrol → 'Beri izin', lalu kirim ulang tugasnya."
                } else {
                    "Space tidak tampil dalam 60 detik walau izin overlay sudah ada — activity Space kemungkinan dimatikan Android (tekanan RAM) dan launch gagal. Buka Space sekali, lalu kirim ulang tugasnya."
                }
                try {
                    JSONObject().apply {
                        put("status", "failed")
                        put("detail", reason)
                        put("updated", System.currentTimeMillis())
                    }.let { file.writeText(it.toString()) }
                } catch (_: Exception) {
                }
            }
        }, "senz-pickup-watch").apply { isDaemon = true }.start()
    }

    /** Tulis status eksekusi TTS ke filesDir internal
     *  (api-tts-<suffix>.json). File yang SAMA ditulis
     *  BrowserActivity dari proses Space; main process hanya
     *  membaca + menulis status awal "queued". */
    private fun writeTtsStatus(suffix: String, status: String, detail: String): Long {
        val now = System.currentTimeMillis()
        try {
            val obj = JSONObject().apply {
                put("status", status)
                put("detail", detail)
                put("updated", now)
            }
            java.io.File(ctx.filesDir, "api-tts-$suffix.json")
                .writeText(obj.toString())
        } catch (_: Exception) {
        }
        return now
    }

    /** Selesaikan space + path relatif ke satu File di dalam folder
     *  kerja SenzSpace. Path traversal (../ keluar folder) ditolak:
     *  canonical path target WAJIB diawali canonical path folder. */
    private fun resolveFile(space: String?, rel: String?, mustBeDir: Boolean): File? {
        if (space == null || space !in FILE_KEYS) return null
        val base = FileStore.folderDir(ctx, space)
        val target = if (rel.isNullOrEmpty()) base else File(base, rel)
        val baseCanon = try {
            base.canonicalPath
        } catch (_: Exception) {
            return null
        }
        val targetCanon = try {
            target.canonicalPath
        } catch (_: Exception) {
            return null
        }
        if (targetCanon != baseCanon && !targetCanon.startsWith("$baseCanon/")) return null
        if (!target.exists()) return null
        if (mustBeDir && !target.isDirectory) return null
        return target
    }

    // v0.1.37: app punya jendela yang terlihat? Benar hanya bila
    // ada proses app ini dengan importance FOREGROUND (100, activity
    // teratas) atau VISIBLE (200, activity terlihat tapi bukan
    // teratas). Proses ApiService sendiri importance-nya
    // FOREGROUND_SERVICE (125) — itu TIDAK dihitung, karena
    // foreground service TIDAK memberi izin BAL.
    private fun appHasVisibleWindow(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.processName.startsWith(ctx.packageName) &&
                (it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        } == true
    }

    // v0.1.37: notifikasi "ketuk untuk menampilkan Space". PendingIntent
    // dari notifikasi DIKECUALIKAN dari larangan BAL — ketukan user
    // boleh menarik activity ke depan. Channel sama dengan ApiService.
    private fun postOpenNotification(entry: SpaceRegistry.SpaceEntry) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 &&
            mgr.getNotificationChannel(ApiService.CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ApiService.CHANNEL_ID,
                    "API kontrol",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Server API lokal SenzSpace (localhost) untuk kontrol API"
                }
            )
        }
        val intent = SpaceLauncher.intentFor(ctx, entry, newTask = true)
        val pending = PendingIntent.getActivity(
            ctx, 1300 + entry.slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, ApiService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SenzSpace — Buka ${entry.name}")
            .setContentText("Ketuk untuk menampilkan Space ini")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        mgr.notify(1300 + entry.slot, notif)
    }

    private fun liveProcessNames(): Set<String> {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.mapNotNull { it.processName }
                ?.filter { it.startsWith("id.senzspace.debug") }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun spaceJsonList(): List<JSONObject> {
        val spaces = SpaceRegistry.load(ctx)
        val procs = liveProcessNames()
        val now = System.currentTimeMillis()
        return spaces.map { entry ->
            val suffix = SpaceRegistry.suffixForSlot(entry.slot)
            val thumb = FileStore.liveThumbFile(ctx, suffix)
            val thumbAgeSec = if (thumb.exists()) (now - thumb.lastModified()) / 1000 else -1
            JSONObject().apply {
                put("slot", entry.slot)
                put("name", entry.name)
                put("color", entry.color)
                put("url", entry.url)
                put("preset", entry.preset)
                put("logged_in", SpaceStatus.readLogin(ctx, suffix))
                put("limit", SpaceStatus.readLimit(ctx, suffix) ?: JSONObject.NULL)
                put("frozen", LiveFreeze.isFrozen(ctx, suffix))
                put("process_alive", procs.contains("id.senzspace.debug:$suffix"))
                put("files", FileStore.count(ctx, suffix))
                put("thumb_age_sec", thumbAgeSec)
                val newest = FileStore.listFiles(ctx, suffix)
                    .maxByOrNull { it.lastModified() }
                if (newest != null) {
                    put("last_file", JSONObject().apply {
                        put("name", newest.name)
                        put("modified", newest.lastModified())
                    })
                } else {
                    put("last_file", JSONObject.NULL)
                }
            }
        }
    }

    private fun statusJson(): JSONObject {
        val arr = JSONArray()
        for (s in spaceJsonList()) arr.put(s)
        val interval = ctx.getSharedPreferences("senzspace_ui", Context.MODE_PRIVATE)
            .getLong("live_interval_ms", 2000L)
        return JSONObject().apply {
            put("app", "SenzSpace")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("overlay_permission", Settings.canDrawOverlays(ctx))
            put("live_interval_ms", interval)
            put("spaces", arr)
        }
    }
}
