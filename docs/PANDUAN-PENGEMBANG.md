# Panduan Pengembang — SenzSpace (referensi teknis)

Dokumen ini adalah referensi teknis pengujian dan build. Panduan pengujian
per versi yang lama (bahasa bayi, berisi hasil tes) diarsipkan secara privat
dan tidak diterbitkan di repo ini.

## Arsitektur

| Lapisan | Teknologi | Keterangan |
|---|---|---|
| Aturan Space | Rust (`rust/profile-core/`) | Sumber kebenaran: maks 10 Space, preset 9 (Claude 1–6 + AI Studio + YT Studio 1–2) |
| Shell Android | Kotlin (`app/`) | WebView terisolasi per Space (satu proses per Space, `WebView.setDataDirectorySuffix`) |
| Desain | `docs/MOCKUP-v1.md` | Token + layout per layar; Figma = sumber kebenaran visual |
| Keputusan awal | `docs/ADR-01.md` | Engine v1: WebView terisolasi + gerbang prototipe |

Aturan teknis lengkap (UX 31–35, KONTROL MCP, API 36–58, jebakan patch):
`AGENTS.md` di root repo — baca sebelum mengubah kode.

## Build

Build resmi lewat GitHub Actions (`.github/workflows/android.yml`).
Persyaratan build lokal: JDK 21, Android SDK 34, Gradle 8.5.

APK rilis masuk tree repo di `releases/SenzSpace-vX.Y.Z.apk` + timpa
`releases/SenzSpace-latest.apk` dengan file yang sama (lihat AGENTS.md,
aturan "SHORTCUT UNDUHAN TERBARU"). APK versi lama tidak dihapus
(rollback). Jangan taruh file kerja di root repo (`.gitignore`:
`*.log`, `*_url.txt`, pengecualian `!releases/*.apk`).

Kunci debug tetap: `debug.keystore` di repo — tanpa ini tiap run memakai
kunci debug baru dan pengguna harus uninstall + login ulang tiap build.

## Instalasi

**Install menimpa, JANGAN uninstall** — uninstall menghapus semua sesi
login di semua Space (harus login ulang semua). Verifikasi sesudah
install: `apksigner verify` + sidik jari sertifikat harus cocok dengan
rilis sebelumnya.

## Keamanan patch (wajib sebelum push)

- JANGAN PERNAH pakai pola `open(f,"w").write(open(f).read())` — open
  mode "w" memotong file dulu, baru read baca file kosong (file jadi
  0 byte, CI gagal).
- SEBELUM push: periksa `wc -c` semua file yang disentuh patch — file
  0 byte = STOP, pulihkan dari commit GitHub terakhir dulu, baru push.
- Tiap ubah string JS: `node --check` pada string yang diekstrak —
  ekstraksi harus mulai dari baris `val js =` DI DALAM fungsi yang sama.
- Tiap ubah Kotlin: periksa keseimbangan kurung (`kotlin_balance.py`).
  Di dalam lambda `Thread({...})` (Runnable SAM), `return` polos tidak
  boleh — pakai `return@Thread`.
- Jangan masukkan secret apa pun (password, token, sesi) ke repo.

## Pengujian API (smoke check)

Server API lokal: `127.0.0.1:19335`, Bearer token per install (baca via
`run-as`, simpan di Pengaturan → API Kontrol; token tidak pernah ditulis
di repo). Tool kontrol: `senz.py` (lihat skill `senzspace-control`).

- `GET /ping` — server hidup.
- `GET /status` — status app + izin overlay.
- `GET /spaces` — daftar Space + status login/limit per Space.
- `GET /page?slot=N` — snapshot halaman Space (url, title, text, tail,
  generating, limit, chats[]) + `ageSec`/`stale` (umur >120 detik = basi).
- `POST /open {slot}` — buka Space (balasan `visible`/`via`).
- `POST /prompt {slot, text, files?, url?}` — kirim prompt (hanya atas
  perintah eksplisit per kirim; lampiran harus sudah ada di `_inbox`).
- `POST /tts {slot, script, voice?, model?, url?}` — generate VO (hanya
  atas perintah eksplisit per generate; `voice` = deskripsi gaya suara,
  `speaker` = nama voice).
- `POST /navigate {slot, url}` — muat URL di Space (host harus sama
  dengan situs Space itu, situs lain ditolak 400).
- `POST /upload /stage /delete` — kelola file Space (`_inbox` sebagai
  folder staging; `/delete` tidak boleh menghapus root folder).

Harapan perilaku per rute ada di `AGENTS.md` (bagian API 36–58).
Hasil pengujian per versi tidak diterbitkan di repo ini.
