package id.senzspace

import android.app.ActivityManager
import android.content.Context
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
// ia cuma mengizinkan Jhon (Muse, lewat ADB forward) memanggil API
// app ini dari HP Sendy sendiri. Jhon membaca token dari HP via
// run-as (shared_prefs) — token TIDAK PERNAH ditulis di repo, chat,
// atau memori.
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

// API kontrol lokal SenzSpace (v0.1.36, perintah Sendy "pake jalur
// API"). Server HTTP minimal, stdlib saja (ServerSocket, tanpa
// dependensi baru): bind 127.0.0.1:19335 — localhost SAJA, tidak
// di-bind ke 0.0.0.0, tanpa TLS (jalur loopback). Tiap rute wajib
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
            if (!ApiToken.check(ctx, headers["authorization"])) {
                send(client, 401, "application/json", errJson("unauthorized"))
                return
            }
            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()
            route(client, method, path, query, body)
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

    private fun route(client: Socket, method: String, path: String, query: Map<String, String>, body: String) {
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
                SpaceLauncher.open(
                    ctx, entry,
                    clearData = req.optBoolean("clearData", false),
                    splitWith = if (req.has("splitWith")) req.optInt("splitWith") else null
                )
                sendJson(client, 200, JSONObject().apply {
                    put("opened", entry.name)
                    put("slot", entry.slot)
                })
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
            put("live_interval_ms", interval)
            put("spaces", arr)
        }
    }
}
