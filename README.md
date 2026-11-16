# SenzSpace

Multi-browser multi-akun untuk produksi konten AI — semua akun jalan bersamaan di satu aplikasi Android, tanpa login-logout.

> **Nama kerja sebelumnya:** Senz Browser. Nama final (D6, terkunci 16 Nov 2026): **SenzSpace**.

Pencetus ide & pemilik tujuan: Sendy Syah Ramadhan. Partner build: Jhon (Muse).
PRD lengkap: `docs/` — lihat PRD v1 di goal Senz Browser (15 halaman, D1–D6 TERKUNCI).

## Masalah yang diselesaikan

Sendy punya 6 akun Claude (free) + 1 akun Google AI Studio untuk naskah & TTS.
Pindah akun hari ini = logout, login lagi, verifikasi, konteks tab hilang.
SenzSpace memberi tiap akun satu **Space** (= Profile): cookie, login, riwayat,
dan folder unduhan sendiri — seperti browser terpisah, tapi dalam 1 app.

- Space bisa di-custom: buat / hapus / rename sendiri, **1 sampai maksimal 10 Space**.
- Preset awal 7 Space: Claude 1–6 + AI Studio. Bukan angka mati.
- Target workflow: cuma 2 app — Muse (Jhon) + SenzSpace.

## Batas jujur

SenzSpace mengelola sesi login yang sah milik Sendy sendiri. App ini **tidak**
menjanjikan bisa melewati limit penggunaan, CAPTCHA, verifikasi, atau kebijakan
layanan Claude / Google. Kalau platform memaksa login ulang, app menampilkan
statusnya dengan jelas — bukan menyembunyikannya. Tidak ada otomasi klik / bot
yang mengoperasikan Claude tanpa Sendy, dan tidak ada pengakalan limit.

## Status: Slice S1 — 7 Space hidup (LULUS S0, ADR-01)

S0 **LULUS PENUH 16 Nov 2026 20:24 WIB** (di HP Sendy): 2 Space login akun
berbeda tanpa saling mengeluarkan, login Google jalan, sesi bertahan setelah
tutup-buka app. Bukti dicatat di `docs/ADR-01.md`.

S1 menghidupkan ketujuh Space preset (Claude 1–6 + AI Studio), masing-masing
di proses Android terpisah dengan direktori data WebView sendiri, plus chip
status di Dashboard yang jujur ("Aktif" hijau kalau Space sedang login,
"Belum login" kalau belum) — status ditulis tiap halaman selesai dimuat.

**S1 lanjutan, slice File Manager (v0.1.2-s1fm):** File Manager + Inbox
Bersama (PRD F7) dan unduhan per Space (PRD F6). Tiap Space punya folder
unduhan sendiri; tombol ⬇ di top bar Browser membuka File Manager di folder
Space itu dan menunjukkan jumlah file; file bisa dipindah ke folder Space
lain / _Inbox-Bersama / _Arsip-Episode, atau dihapus.

**S1 lanjutan, slice Ekspor (v0.1.3-s1ex):** tombol Ekspor per file (D5) —
menyalin file ke folder publik **Download/SenzSpace** di HP lewat MediaStore,
jadi bisa dibuka pengelola file HP / dilampirkan ke app lain. Folder kerja
utama tetap di dalam app; ekspor hanya saat pengguna mengetuk Ekspor.

**S1 lanjutan, slice Kunci App (v0.1.4-s1lock):** kunci app PIN 4 digit +
biometrik (D4, mockup v2 layar 06) + layar Pengaturan. Kunci MATI secara
default — pengaktifan dipilih pengguna di Pengaturan. PIN disimpan sebagai
hash SHA-256 (bukan teks biasa); setelah 5× salah, app jeda 30 detik.
Kunci berlaku tiap app dibuka / kembali dari latar.

**Perbaikan v0.1.7-s1fix2 (temuan HP 20:53–20:57 WIB):** FC saat
keluar-masuk Space — akar sebenarnya: `setDataDirectorySuffix()` hanya
boleh dipanggil sekali per proses; proses `:spaceN` tetap hidup setelah
Activity keluar, jadi masuk lagi memanggilnya kedua kali → crash.
Penjaga static sekali-per-proses. (Fix S0.4 pop-up tetap berlaku untuk
jalurnya.) PIN kunci app TERVERIFIKASI Sendy 20:57 WIB ("pin aman pin
beranda pin space aktif") — kunci jalan di dashboard maupun Space aktif.

**S1 lanjutan, slice CRUD Space (v0.1.6-s1crud):** tambah / ubah nama /
hapus Space, maks 10 (D1–D2, mockup v2 layar 04). Kartu "＋ Space baru" di
dashboard (nama + preset situs + warna); tahan lama di kartu Space untuk
Ubah nama / Hapus. Hapus Space ikut membersihkan sesi WebView slot itu
(supaya Space baru di slot yang sama tidak mewarisi login lama); file
unduhan tetap ada di File Manager. Dashboard sekarang dirender dari
registry Space (preset 7 awal tetap sama).

Yang **belum** ada di S1 (menyusul): freeze Space (hemat RAM).

## Arsitektur

| Lapisan | Teknologi | Tanggung jawab |
|---|---|---|
| Core (Rust) | Rust + `rust/profile-core` | Registry Space, penegakan batas maks 10 Space, preset 7 |
| Shell (Android) | Kotlin, satu Activity utama | Dashboard Space, navigasi, kunci (S4) |
| Browser per Space | Android WebView, proses terpisah per Space, `WebView.setDataDirectorySuffix` | Render situs, cookie & storage terisolasi per Space |
| Penyimpanan | App-private storage | Data sesi WebView; file unduhan terindeks (S3) |

Catatan S0: Rust core sudah ada sebagai crate yang dites (`cargo test`),
integrasi JNI/UniFFI ke shell Android dijadwalkan S1. Aturan maks 10 Space
di S0 ditegakkan di Rust core (tes) dan dicerminkan di shell Kotlin
(konstanta yang sama) sampai JNI tersambung — lihat `docs/ADR-01.md`.

## Build lokal

```bash
# Rust core
cargo test -p profile-core --manifest-path rust/profile-core/Cargo.toml

# Android (butuh JDK 21 + Android SDK 34 + Gradle 8.5)
export JAVA_HOME=$HOME/jdk-21-sys
export ANDROID_HOME=$HOME/android-sdk
gradle :app:assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
# Catatan: Gradle daemon lokal di VM ini gagal komunikasi (daemon mati
# tanpa error di log) — build resmi adalah CI GitHub, bukan build lokal.
```

CI: `.github/workflows/android.yml` — `cargo test` + `assembleDebug` tiap push.

## Tes S1 di HP (bahasa bayi)

1. Install APK debug (nimpa, tanpa uninstall). Buka SenzSpace.
2. Dashboard sekarang menampilkan **7 kartu aktif**: Claude 1–6 + AI Studio.
   Masing-masing punya titik warna sendiri + chip status.
3. Tap **Claude 1** — harusnya masih login dari S0. Kembali ke dashboard:
   chip Claude 1 berubah jadi **"Aktif"** (hijau).
4. Tap **Claude 3, 4, 5, 6** satu per satu, login akun Claude yang berbeda
   di tiap Space. **LULUS** bila: tiap Space menampilkan akunnya sendiri,
   dan pindah Space tidak mengeluarkan akun Space lain.
5. Tap **AI Studio**, login Google. **LULUS** bila: AI Studio kebuka
   (halaman kerja, bukan halaman login) dan chip-nya jadi "Aktif".
6. Tutup app sepenuhnya, buka lagi.
7. **LULUS** bila: semua Space yang sudah login tetap login, dan chip
   di dashboard menunjukkan "Aktif" untuk semuanya.

## Tes File Manager di HP (bahasa bayi)

1. Buka **Claude 1**, unduh satu file (mis. lampiran dari Claude). Tunggu
   notifikasi "Unduhan selesai" dari Android.
2. Di top bar Browser, tombol **⬇** sekarang menunjukkan angka **1**.
   Tap tombol itu → File Manager terbuka di folder Claude 1.
3. **LULUS** bila: file yang barusan diunduh ada di daftar, lengkap dengan
   ukuran dan tanggalnya.
4. Tap **"Pindah…"** di file itu → pilih **_Inbox-Bersama**.
   **LULUS** bila: muncul pesan "Dipindah ke _Inbox-Bersama", dan file
   hilang dari folder Claude 1.
5. Buka folder **_Inbox-Bersama** dari daftar folder. **LULUS** bila:
   file itu ada di sana.
6. Kembali ke Dashboard, tap kartu **File Manager**. **LULUS** bila:
   9 folder tampil (Claude 1–6, AI Studio, _Inbox-Bersama,
   _Arsip-Episode), masing-masing dengan jumlah file yang benar.

## Tes Ekspor di HP (bahasa bayi)

1. Di File Manager, buka folder yang berisi file (mis. _Inbox-Bersama).
2. Tap **"Ekspor"** di salah satu file. **LULUS** bila: muncul pesan
   "Diekspor ke Download/SenzSpace/<nama file>".
3. Buka pengelola file HP (Files), masuk ke folder **Download →
   SenzSpace**. **LULUS** bila: file itu ada di sana dan bisa dibuka /
   dibagikan ke app lain.
4. File aslinya tetap ada di folder SenzSpace di dalam app — Ekspor itu
   menyalin, bukan memindah. **LULUS** bila: file masih ada di File Manager.

## Tes Kunci App di HP (bahasa bayi)

1. Buka **Pengaturan** (kartu ⚙️ di dashboard). Saklar "Kunci App (PIN)"
   harusnya **MATI** — kunci tidak aktif sendiri.
2. Nyalakan saklar → layar "Buat PIN" muncul. Masukkan PIN 4 angka,
   lalu ulangi PIN yang sama. **LULUS** bila: muncul "PIN dibuat —
   kunci app aktif".
3. Tutup app sepenuhnya, buka lagi. **LULUS** bila: layar kunci muncul
   dan dashboard TIDAK kelihatan sebelum PIN benar.
4. Masukkan PIN yang benar. **LULUS** bila: dashboard kebuka normal.
   (Kalau HP kamu punya sidik jari/wajah terdaftar, tombol biometrik
   juga muncul — coba, harusnya langsung kebuka.)
5. Tutup app, buka lagi, masukkan PIN **salah** 5×. **LULUS** bila:
   muncul "5× salah — jeda 30 detik" dan keypad tidak bisa ditekan
   selama jeda.
6. Matikan kunci: Pengaturan → matikan saklar → masukkan PIN yang benar.
   **LULUS** bila: saklar mati dan buka app lagi sudah tidak minta PIN.

## Tes perbaikan FC + badge AI Studio di HP (bahasa bayi)

1. Install APK v0.1.5-s1fix (nimpa, tanpa uninstall).
2. Buka **AI Studio** (yang kemarin sudah login). Kembali ke dashboard.
   **LULUS** bila: chip AI Studio sekarang **"Aktif"** (hijau), bukan
   "Belum login" lagi.
3. Login/logout ulang akun Claude di Space mana pun (buka pop-up Google,
   tutup, kembali ke beranda). **LULUS** bila: tidak ada lagi dialog
   "SenzSpace has stopped".
4. Kalau FC muncul lagi: buka **File Manager → _Log-Crash** — di sana ada
   file `crash-<tanggal>.txt`. Tap **Ekspor** di file itu, kirim ke Jhon.
   **LULUS** bila: file log-nya ada dan isinya kebaca.

## Tes CRUD Space di HP (bahasa bayi)

1. Di dashboard, di bawah kartu Space ada kartu **"＋ Space baru"**
   (tulisan `7/10`). Tap kartu itu.
2. Isi nama (cth: "Claude 7"), pilih situs **Claude**, pilih warna,
   tap **"Buat Space & Login"**. **LULUS** bila: browser Space baru
   kebuka di halaman login Claude, dan kembali ke dashboard ada kartu
   baru "Claude 7" (tulisan jadi `8/10`).
3. Login akun ke-7 di Space baru itu. **LULUS** bila: chip-nya jadi
   "Aktif" dan tidak mengeluarkan akun Space lain.
4. **Tahan lama** (tekan agak lama) di kartu "Claude 7" → pilih
   **"Ubah nama"** → ganti jadi "Claude Tambahan". **LULUS** bila:
   nama kartu berubah.
5. Tahan lama lagi → **"Hapus Space"** → konfirmasi Hapus.
   **LULUS** bila: kartu hilang, tulisan kembali `7/10`, dan file
   unduhan lama (kalau ada) tetap ada di File Manager.
6. Ulangi tambah Space sampai 10. **LULUS** bila: Space ke-11 ditolak
   dengan pesan "Maksimal 10 Space".

## Tes Multi Live v2 di HP (bahasa bayi)

Mode Beranda: semua Space yang sedang berjalan kelihatan gambar
halamannya (hidup) dalam kotak VERTIKAL (kayak layar HP). Desainnya
dari Figma "01 — Beranda · Multi Live" (frame Figma masih tile
landscape lama — update Figma menyusul).

1. Install APK v0.1.9-s1multilive2 (nimpa, tanpa uninstall). Saat
   pertama buka, HP mungkin minta **"Izinkan notifikasi"** — tap
   **Izinkan**. (Notifikasi itu penjaga supaya Space tidak dimatikan
   sistem; kalau ditolak, Space tetap jalan tapi lebih gampang mati.)
2. Buka **Claude 1**, tunggu halaman kebuka, tekan **Back**.
   **LULUS** bila: balik ke Beranda, dan di bawah layar TIDAK ADA
   lagi kotak log kuning (sudah dihapus).
3. Di Beranda, tunggu 2 detik. **LULUS** bila: kotak Claude 1
   menampilkan gambar halaman Claude secara VERTIKAL (tulisan
   "Evening, how are things?" kebaca, bukan gepeng landscape),
   ada tulisan **LIVE** merah dan "live baru saja".
4. Di Claude 1, ketik sesuatu supaya Claude menjawab, lalu tekan
   Back ke Beranda selagi Claude mengetik. **LULUS** bila: gambar
   di kotak Claude 1 ikut berubah saat jawaban mengalir (bukan
   diam 5 detik).
5. Ulangi langkah 2–3 untuk Claude 2 dan AI Studio. **LULUS** bila:
   kotaknya menampilkan gambar halaman masing-masing.
6. Tarik panel notifikasi HP ke bawah. **LULUS** bila: ada
   notifikasi "SenzSpace — Claude 1 berjalan" (dan Space lain yang
   sudah dibuka) dalam satu grup. Itu guard anti-kill yang bekerja.
7. Tes anti-kill: buka semua Space sekali, tekan tombol Home,
   tunggu 10 menit (atau buka 2–3 app lain dulu), buka SenzSpace
   lagi. **LULUS** bila: tile-tile masih menampilkan gambar live
   (bukan abu-abu) dan Space masih login.
8. Tap kotak **AI Studio**. **LULUS** bila: langsung masuk ke
   halaman yang sudah login (tidak minta login ulang).
9. Tap **"Daftar"** → jadi daftar kartu kayak dulu. Tap
   **"Multi Live"** lagi → balik ke grid.
10. Kalau kotak tetap abu-abu polos setelah 10 detik: buka File
    Manager → folder **_Live** — di sana harus ada file
    `space1.jpg` (dan seterusnya). Kalau folder kosong, kirim
    kabar ke Jhon (tangkapan layar WebView belum jalan).

## Tes v0.1.10-s1proper di HP (bahasa bayi)

1. Install APK v0.1.10-s1proper (nimpa, tanpa uninstall).
2. **Top bar Space**: buka Claude 1. **LULUS** bila: di bar atas
   ada 4 tombol: **⌄** (minimize → balik ke Beranda, Space tetap
   jalan), **↻** (muat ulang halaman), **⌂** (kembali ke halaman
   awal Space), **⬇** (unduhan).
3. **Tile tidak hitam lagi**: di Beranda, semua tile menampilkan
   sapaan Claude di tengah tile ("What shall we think through?").
   **LULUS** bila: tidak ada tile hitam polos (dulu Claude 3–6
   hitam karena tangkapan ngambil bagian atas halaman).
4. **Notifikasi gabung**: tarik panel notifikasi. **LULUS** bila:
   hanya ada SATU baris "SenzSpace • Space berjalan — N Space
   berjalan"; geser baris itu ke bawah (atau tap panah) →
   kelihatan semua Space (Claude 1–6 + AI Studio) di dalamnya.
5. **Chip Limit**: Claude 4 sedang kena limit 5 jam (reset
   10:30 PM). **LULUS** bila: tile Claude 4 menampilkan chip
   kuning **"Limit • 10:30 PM"** dan tulisan bawah "limit, reset
   10:30 PM" — tanpa perlu buka Space-nya.
6. **Pengaturan lengkap**: buka Pengaturan. **LULUS** bila: ada
   bagian **Multi Live** (Refresh: 2 detik — tap untuk ganti
   2/5/10 detik; Guard anti-kill on/off), **Baterai** (status
   Dikecualikan/Dibatasi + tombol "Minta pengecualian baterai"),
   **Perawatan** (info ukuran folder kerja + tombol "Bersihkan
   thumbnail live"), plus Kunci App seperti dulu.
7. **File Manager native**: buka File Manager. **LULUS** bila:
   barisnya ringkas (ikon + nama + ukuran/tanggal + ⋮), tap
   file → terbuka di app lain (mis. gambar di Galeri); tahan
   file → muncul bar bawah (Pindah / Salin / Ekspor / Bagikan /
   Hapus); tombol ⋮ → menu (Buka, Bagikan, Pindah, Salin, Ganti
   nama, Detail, Ekspor, Hapus); ada kolom **Cari file…**,
   tombol **⇅ Terbaru** (tap → pilih urutan), dan **＋ Folder**
   (buat subfolder, mis. di _Arsip-Episode). Tap subfolder →
   masuk ke dalamnya; tombol Back HP naik satu level.
8. **Stabil**: di Beranda, biarkan 1 menit. **LULUS** bila: tile
   tidak berkedip-kedip tiap 2 detik dan teks "live …" hanya
   berubah bila waktunya berubah.

## Tes unduh blob v0.1.11 di HP (bahasa bayi)

Akar masalah (temuan HP 23:08): lampiran Claude disajikan sebagai URL
`blob:` dan DownloadManager tidak bisa menangani skema itu ("Can not
handle uri: blob:…"). Sekarang isi blob diambil lewat halaman lalu
ditulis langsung ke folder Space.

1. Install APK v0.1.11-s1blobfix (nimpa, tanpa uninstall).
2. Buka **Claude 4** → chat "Produksi episode m…" (yang ada file
   PROJECT STATE .md + Scene .html). Tap tombol unduh di file
   **PROJECT STATE**. **LULUS** bila: muncul toast "Tersimpan di
   folder Claude 4: PROJECT STATE.md" (bukan "Gagal mengunduh").
3. Tap tombol unduh di file **Scene**. **LULUS** bila: toast
   "Tersimpan di folder Claude 4: Scene.html".
4. Tap **Download all**. **LULUS** bila: file .zip tersimpan di
   folder Claude 4.
5. Buka File Manager → folder **Claude 4**. **LULUS** bila: ketiga
   file ada di sana dan bisa dibuka (tap file .md → terbuka di
   viewer; tap .html → terbuka di browser).
6. **Top bar di jendela kecil**: buka Claude 4 dalam jendela
   mengambang (pop-up view) atau split-screen. **LULUS** bila:
   nama Space tetap SATU baris (dipotong "…") dan keempat tombol
   (⌄ ↻ ⌂ ⬇) tetap kelihatan — tidak wrap 4 baris seperti dulu.

## Tes file chooser + ikon v0.1.12 di HP (bahasa bayi)

1. Install APK v0.1.12-s1chooser-icons (nimpa, tanpa uninstall).
2. **Upload file ke Claude**: buka Claude 1 → tap **＋** → **Add files**
   → **Choose files or photos**. **LULUS** bila: pemilih file sistem
   terbuka (Galeri/File) — bukan diam tanpa respon seperti dulu.
   Pilih 1 file (mis. .md dari folder Claude 4) → **LULUS** bila:
   file muncul sebagai lampiran di kolom chat Claude dan bisa dikirim.
3. **Ikon top bar**: di Space mana pun, **LULUS** bila: 4 tombol atas
   adalah ikon garis putih minimalis — panah bawah (⌄ minimize),
   panah melingkar (↻ reload), rumah (⌂ home), panah ke nampan
   (⬇ unduhan) — bukan lagi huruf yang tampilannya beda-beda.
4. **Badge unduhan**: kalau folder Space sudah ada file-nya,
   **LULUS** bila: ada angka merah kecil di pojok kanan atas ikon
   unduhan (jumlah file); tap ikon unduhan → File Manager folder itu.
5. **Ikon app**: tutup app, lihat di layar utama / laci app.
   **LULUS** bila: ikon SenzSpace adalah 4 kotak membulat 2×2
   (indigo, teal, biru langit, putih) di atas latar navy — bukan
   lagi huruf "S" lama. (Ikon lama mungkin masih muncul di cache
   launcher; kalau begitu, restart HP sekali lalu cek lagi.)

## Tes v0.1.13-s1view-icons di HP (bahasa bayi)

1. Install APK v0.1.13-s1view-icons (nimpa, tanpa uninstall).
2. **Navbar**: di Beranda, **LULUS** bila: bawah layar ada 3 ikon
   garis — rumah (Beranda, indigo), folder (File Manager, abu),
   gear (Pengaturan, abu) — masing-masing dengan label di bawahnya,
   TANPA kotak/pill background di belakangnya.
3. **Ikon notifikasi**: buka 1 Space, tarik panel notifikasi.
   **LULUS** bila: ikon kecil di baris "SenzSpace — … berjalan"
   adalah 4 kotak kecil (bukan lagi huruf "i" dalam lingkaran).
4. **Ikon File Manager**: buka File Manager. **LULUS** bila: tiap
   folder punya ikon folder garis (warnanya warna Space-nya),
   tiap file punya ikon garis sesuai jenisnya (gambar / video /
   musik / arsip / kode / file) — bukan lagi emoji 📁🖼️🎬.
5. **Fungsi View (Lihat)**: di folder Claude 4 (atau _Inbox-Bersama
   yang ada file MP4 10,4 MB), tap file **gambar** (mis. thumbnail
   PNG). **LULUS** bila: gambar terbuka DI DALAM app (layar gelap,
   nama file di atas, tombol ✕ + Buka) — tanpa keluar ke Galeri.
   Tap file **teks** (.md/.srt). **LULUS** bila: isinya kebaca di
   dalam app (huruf monospace). Tap file **video** (.mp4).
   **LULUS** bila: video muter di dalam app dengan kontrol
   putar/jeda. Tap **⋮** di file → **LULUS** bila: menu paling atas
   ada **Lihat** (di atas Buka). Tap file .zip → **LULUS** bila:
   muncul "Tidak bisa dilihat di sini" + tombol "Buka dengan app
   lain".

## Tes unduh AI Studio v0.1.14 di HP (bahasa bayi)

Akar masalah (temuan HP 00:35): AI Studio membuat blob audio lalu
LANGSUNG me-revoke URL-nya setelah tombol unduh ditekan — Download-
Listener baru jalan sesudahnya, jadi XHR ke URL yang sudah mati.
Sekarang isi blob ditangkap saat dibuat (cache) + revoke ditunda
60 detik.

1. Install APK v0.1.14-s1blobcache (nimpa, tanpa uninstall).
2. Buka **AI Studio** → Playground → generate suara (Speaker 1 -
   Sadaltager, teks apa saja) sampai muncul player audio.
3. Tap tombol **unduh** (ikon panah ke bawah) di bawah player.
   **LULUS** bila: muncul toast "Tersimpan di folder AI Studio:
   …" (bukan "Gagal mengunduh file (blob tidak bisa dibaca)").
4. Buka File Manager → folder **AI Studio**. **LULUS** bila: file
   audio (.wav/.mp3) ada di sana dan bisa diputar (tap → View →
   video/audio muter; atau Buka → pemutar HP).
5. Ulangi tes unduh blob di **Claude 4** (PROJECT STATE .md).
   **LULUS** bila: masih tersimpan seperti v0.1.11 — perbaikan
   ini tidak boleh merusak yang sudah jalan.

## Tes unduh AI Studio v0.1.15 di HP (bahasa bayi)

v0.1.14 masih gagal di HP (00:45) — cache + tunda revoke saja belum
cukup. v0.1.15 menambah dua hal: (1) cache dipasang JUGA ke iframe
(komponen audio bisa hidup di iframe — blob di sana tidak kelihatan
dari frame utama); (2) bila semua jalur gagal, app mencoba unduhan
native Chromium (lepas listener, klik ulang anchor, file dipindah
dari Download publik ke folder Space). Plus diagnosis: tiap percobaan
unduh blob dicatat di File Manager → folder **_Log-Blob** →
`blob-debug.txt` (isinya: cache ada/tidak, hit/tidak, berapa iframe,
alasan gagal) — Jhon bisa baca file itu lewat bridge.

1. Install APK v0.1.15-s1blobdiag (nimpa, tanpa uninstall).
2. Buka **AI Studio** → Playground → generate suara sampai muncul
   player audio.
3. Tap tombol **unduh** di bawah player. **LULUS** bila: muncul
   toast "Tersimpan di folder AI Studio: …" (boleh diawali toast
   "Mencoba unduh cara lain…" — itu fallback native yang bekerja).
4. Buka File Manager → folder **AI Studio**. **LULUS** bila: file
   audio ada di sana dan bisa diputar.
5. Kalau MASIH gagal: buka File Manager → **_Log-Blob** →
   `blob-debug.txt`, screenshot isinya dan kirim ke Jhon — di situ
   tertulis alasan persisnya (mis. cache kosong / iframe banyak /
   xhr-error).

## Tes unduh AI Studio v0.1.16 di HP (bahasa bayi)

v0.1.15 JUGA gagal (log _Log-Blob 01:02 WIB membuktikan kenapa):
URL-nya `blob:null/<uuid>` — blob audio dibuat di dalam iframe
sandboxed (kotak terkunci, origin "null") dan HANYA bisa dibaca dari
dalam kotak itu. XHR, cache, dan klik-ulang dari frame utama PASTI
gagal; fallback native pun gagal karena kliknya datang dari luar
kotak. Satu-satunya jalur yang bekerja: unduhan native Chromium dari
klik user sendiri di dalam iframe — dan itu MATI selama
DownloadListener membajak unduhan. v0.1.16 MELEPAS listener total:
Chromium mengunduh sendiri ke Download publik, lalu pengawas app
memindahkan file yang selesai ke folder Space (tiap 2 detik selagi
Space di depan; hanya unduhan baru selagi Space di depan yang
dipindah, supaya unduhan Chrome tidak ikut tercuri).

1. Install APK v0.1.16-s1nativedl (nimpa, tanpa uninstall).
2. Buka **AI Studio** → Playground → generate suara sampai muncul
   player audio.
3. Tap tombol **unduh** di bawah player. **LULUS** bila: muncul
   toast "Tersimpan di folder AI Studio: …" (tanpa toast gagal
   sebelumnya; file mampir sebentar di Download publik lalu pindah
   sendiri — jangan kaget kalau sempat terlihat di sana).
4. Buka File Manager → folder **AI Studio**. **LULUS** bila: file
   audio ada di sana dan bisa diputar. Nama file ikut nama asli dari
   AI Studio (bukan UUID).
5. Ulangi tes unduh blob di **Claude 4** (PROJECT STATE .md).
   **LULUS** bila: masih tersimpan di folder Claude 4 seperti
   v0.1.11 — jalur baru ini menggantikan jalur lama untuk SEMUA
   unduhan.
6. Kalau masih gagal: File Manager → **_Log-Blob** →
   `blob-debug.txt` (baris "PINDAH …" / "PINDAH GAGAL …").

## Tes unduh AI Studio v0.1.17 di HP (bahasa bayi)

v0.1.16 malah bikin tombol unduh MATI TOTAL (temuan HP 01:19) —
pelajaran: TANPA DownloadListener, WebView TIDAK mengunduh apa-apa
(Chromium di WebView tidak punya download manager bawaan seperti
Chrome; app yang wajib menangani unduhan). v0.1.17 mengembalikan
listener + menyerang akar yang sebenarnya: skrip penangkap blob
sekarang disisipkan ke SETIAP dokumen (frame utama + iframe) lewat
shouldInterceptRequest — jadi skripnya berjalan DI DALAM iframe
sandboxed pembuat blob, meng-cache isinya, dan mengirimnya ke frame
utama via postMessage (tetap jalan lintas origin), terus ke folder
Space. Pengawas unduhan v0.1.16 dihapus (tidak dibutuhkan lagi).

1. Install APK v0.1.17-s1iframeinject (nimpa, tanpa uninstall).
2. Buka **AI Studio** → Playground → generate suara sampai muncul
   player audio.
3. Tap tombol **unduh** di bawah player. **LULUS** bila: tombol
   merespon + muncul toast "Tersimpan di folder AI Studio: …".
4. Buka File Manager → folder **AI Studio**. **LULUS** bila: file
   audio ada di sana dan bisa diputar.
5. Ulangi tes unduh blob di **Claude 4** (PROJECT STATE .md).
   **LULUS** bila: masih tersimpan di folder Claude 4 seperti
   v0.1.11.
6. Kalau masih gagal: File Manager → **_Log-Blob** →
   `blob-debug.txt` (baris "INJECT: …" membuktikan skrip masuk ke
   dokumen; baris "dl url=…" + "GAGAL: …" bila masih gagal).

## Tes unduh AI Studio v0.1.18 di HP (bahasa bayi)

v0.1.17 masih gagal (log 01:32 WIB): injeksi MASUK ke dokumen utama
+ 2 iframe layanan (auth_warmup, bscframe) TAPI tidak ada jawaban
postMessage — pembuat blob adalah iframe yang dokumennya TIDAK lewat
jaringan (srcdoc/about:blank, dibuat saat generate). v0.1.18 menambah
perangkap iframe di frame utama: tiap iframe srcdoc / data:text/html
yang dimasukkan ke DOM (appendChild/insertBefore/replaceChild,
setAttribute, property srcdoc/src, MutationObserver) disisipi skrip
penangkap blob yang sama SEBELUM dimuat — jadi skripnya jalan di
dalam kotak pembuat blob dan mengirim isinya via postMessage.

1. Install APK v0.1.18-s1srcdoctrap (nimpa, tanpa uninstall).
2. Buka **AI Studio** → Playground → generate suara sampai muncul
   player audio.
3. Tap tombol **unduh** di bawah player. **LULUS** bila: muncul
   toast "Tersimpan di folder AI Studio: …" dengan nama asli
   "Generated Audio … .wav".
4. Buka File Manager → folder **AI Studio**. **LULUS** bila: file
   .wav ada di sana dan bisa diputar.
5. Ulangi tes unduh blob di **Claude 4** (PROJECT STATE .md).
   **LULUS** bila: masih tersimpan di folder Claude 4 seperti
   v0.1.11.
6. Kalau masih gagal: File Manager → **_Log-Blob** →
   `blob-debug.txt` (baris "TRAP srcdoc …" / "TRAP data: …"
   membuktikan perangkap menangkap iframe-nya; baris "dl url=…"
   + "GAGAL: …" bila masih gagal).

## Tes nama unduhan v0.1.19 di HP (bahasa bayi)

v0.1.18 berhasil mengunduh (isi file benar, bisa diputar setelah
rename) TAPI nama file salah: AI Studio jadi UUID + `.txt`, Claude 4
jadi UUID + `.bin`. Akar: nama tebakan WebView (UUID + ekstensi
salah) dipakai, nama asli dari tombol unduh situs tidak dipakai.
v0.1.19: nama asli dari situs MENANG atas tebakan; bila nama asli
tidak ketemu, ekstensi diperbaiki dari isi file (WAV/MP3/PDF/ZIP/
PNG/JPG/MP4).

1. Install APK v0.1.19-s1blobname (nimpa, tanpa uninstall).
2. AI Studio → generate suara → tap unduh. **LULUS** bila toast
   berbunyi "Tersimpan di folder AI Studio: Generated Audio … .wav"
   (nama asli + .wav, BUKAN UUID + .txt) dan file bisa langsung
   diputar tanpa rename.
3. Claude 4 → unduh lampiran (mis. PROJECT STATE .md).
   **LULUS** bila nama file asli + ekstensi benar (mis.
   "PROJECT STATE.md", BUKAN UUID + .bin).

## Kontrol app dari Jhon (via Termux bridge)

SenzSpace menulis statusnya ke file JSON di HP (hanya status yang
tampil di Beranda — tanpa sesi/cookie/token):
`Android/data/id.senzspace.debug/files/SenzSpace/_status/senzspace-status.json`

- Baca status: `termux-exec "cat /sdcard/Android/data/id.senzspace.debug/files/SenzSpace/_status/senzspace-status.json"`
- Buka Space dari Jhon: `termux-exec "am start -n id.senzspace.debug/.Space1Activity"` (ganti Space1 dengan Space2…Space6, AiStudioActivity, Space8…Space10)
- Buka Beranda: `termux-exec "am start -n id.senzspace.debug/.MainActivity"`

## Keamanan

Tidak ada password yang disimpan sebagai teks biasa. Login dilakukan Sendy
sendiri di halaman resmi situs, di dalam Space-nya. Tidak ada telemetri,
tidak ada pengiriman data ke server mana pun di v1. Semua data tinggal di HP.
Repo ini publik dan tidak berisi secret apa pun.
