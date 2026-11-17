# Panduan Pengembang SenzSpace

Dokumen internal untuk pengembang & penguji: arsitektur, build, dan
panduan tes per slice (bahasa bayi) — dipindah dari README utama saat
repo dibuka untuk semua pengguna (17 Nov 2026). README utama sekarang
adalah halaman produk untuk pengguna.

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

## Tes Beku Live v0.1.20 di HP (bahasa bayi)

Baru: tiap Space bisa dibekukan Live-nya — gambar di kartu Beranda
berhenti update, tapi browser + sesi Space tetap jalan (bukan
dimatikan). Caranya: **tahan lama** kartu Space di Beranda → menu
muncul (Ubah nama / Matikan Live / Hapus Space).

1. Install APK v0.1.20-s1livefreeze (nimpa, tanpa uninstall).
2. Di Beranda (mode Multi Live), **tahan lama** kartu Claude 1.
   **LULUS** bila menu muncul dengan 3 pilihan: "Ubah nama",
   "Matikan Live", "Hapus Space".
3. Tap **"Matikan Live"**. **LULUS** bila: toast "Live dibekukan —
   browser tetap jalan"; chip kartu berubah jadi **"Beku"** (abu-abu);
   badge LIVE hilang; teks bawah jadi "live dibekukan"; gambar kartu
   berhenti berubah walau halaman di Space-nya berubah.
4. Buka Claude 1 (tap kartu). **LULUS** bila: halaman + sesi tetap
   jalan normal (beku cuma matiin gambar live, bukan browser).
5. Tahan lama kartu lagi → tap **"Nyalakan Live"**. **LULUS** bila:
   chip balik "Aktif", badge LIVE muncul lagi, gambar kartu update
   lagi.
6. Mode Daftar (toggle Beranda): kartu yang dibekukan chip-nya
   "Beku" juga. Hapus Space yang beku → Space baru di slot yang sama
   tidak ikut beku.

## Tes Perbaikan UX v0.1.31 di HP (bahasa bayi)

Latar: 10 laporan dari HP Sendy (17 Nov 15:59) — live tidak aktif
lagi setelah dibekukan; split-screen tidak jalan; app tidak bisa
horizontal; geser di dalam Space mau diganti tombol; tombol top
bar mau ada animasi sentuh; notifikasi Space cuma buka Beranda;
bersihkan data Space 8 ikut menghapus Space 7; navbar mau ikon
saja + melayang; Pengaturan mau ada pilihan folder ekspor.
Tiga akar masalahnya: (1) split memakai "catatan bersama"
(static) yang tidak tembus antar-proses — Space jalan di proses
berbeda, jadi catatan itu selalu kosong; (2) bersihkan data memakai
CLEAR_TOP yang menutup SEMUA activity di atasnya, termasuk Space
lain; (3) skrip penangkap blob disisipkan ke SEMUA situs, termasuk
halaman login ChatGPT — sekarang hanya di claude.ai + AI Studio.

1. Install APK v0.1.31-s1ux31 (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. **Beku → nyalakan live.** Tahan lama kartu Claude 1 → "Matikan
   Live" → tutup total app (hapus dari recent) → buka app lagi →
   tahan lama kartu → "Nyalakan Live". **LULUS** bila: Space Claude
   1 terbuka sendiri dan setelah kembali ke Beranda, tile-nya
   live lagi (chip "Aktif", gambar update).
3. **Split-screen vertikal.** Di Beranda, tahan lama kartu Claude 1
   → "Buka berdampingan" → pilih Claude 2. **LULUS** bila: layar
   terbagi ATAS-BAWAH (Claude 1 di atas, Claude 2 di bawah, karena
   HP portrait) dan kedua Space bisa dipakai. (Bila HP menolak
   split, Space 2 dibuka biasa — itu batas HP, bukan bug.)
4. **Horizontal.** Putar HP ke landscape di Beranda, di dalam Space,
   di File Manager, di Pengaturan. **LULUS** bila: tampilan ikut
   miring ke landscape di semua layar (walau auto-rotate sistem
   mati).
5. **Tombol pindah Space.** Di dalam Space, tap tombol ⇌ (dua panah
   berbalik) di top bar. **LULUS** bila: muncul daftar Space AKTIF
   lain; pilih satu → pindah ke Space itu. Geser kiri-kanan di
   dalam Space sudah tidak ada lagi.
6. **Animasi sentuh.** Tekan-tahan tombol di top bar (⌄, ⟳, ⌂, ⇌,
   ⬇). **LULUS** bila: tombol mengecil sedikit saat ditekan, lalu
   kembali normal saat dilepas.
7. **Notifikasi bisa diklik ke Space.** Turunkan shade notifikasi →
   tap notifikasi "Claude 2 berjalan". **LULUS** bila: langsung
   masuk ke Space Claude 2 (bukan cuma Beranda). Notifikasi
   ringkasan grup tetap membuka Beranda.
8. **Bersihkan data tidak nyasar.** Login di Space 7 (AI Studio)
   dan Space 8 (misal ChatGPT) — keduanya Aktif. Tahan lama kartu
   Space 8 → "Bersihkan data Space" → "Bersihkan". **LULUS** bila:
   Space 8 minta login ulang, TAPI Space 7 tetap Aktif (tidak
   ikut terhapus / tidak perlu login ulang). Bila suffix tidak
   cocok, muncul toast "Batal: Space tidak cocok — data TIDAK
   dihapus".
9. **Login ChatGPT tidak stuck.** Di Space 8, buka chatgpt.com →
   login Google → isi alamat Gmail. **LULUS** bila: bisa lanjut
   mengisi dan login sampai masuk (halaman login tidak lagi
   diganggu skrip app — situs selain Claude/AI Studio dimuat
   persis seperti Chrome).
10. **Navbar melayang.** Di Beranda, perhatikan bawah layar.
    **LULUS** bila: hanya ada 3 ikon (🏠 📁 ⚙) tanpa teks, tanpa
    background — melayang di atas konten, Beranda terasa
    fullscreen; ikon masih bisa ditekan (File Manager, Pengaturan).
11. **File Manager ala MiXplorer.** Buka File Manager → masuk folder
    Space → tap tombol "▦ Grid". **LULUS** bila: file tampil grid
    3 kolom. Pilih 1 file → ⋮ → "Buat ZIP" → **LULUS** bila file
    .zip muncul. ⋮ pada .zip → "Ekstrak di sini" → **LULUS** bila
    folder hasil ekstrak muncul. Tahan lama 1 file → "Potong" →
    pindah ke folder lain → "Tempel" → **LULUS** bila file pindah.
    Menu urut → "Tipe file" → **LULUS** bila file terurut per
    ekstensi. ⋮ pada folder → Detail → **LULUS** bila tampil
    ukuran total + jumlah file/folder.
12. **Custom folder ekspor.** Pengaturan → "Penyimpanan" → "Pilih
    folder ekspor" → pilih folder (misal di Download). Kembali ke
    File Manager → ekspor 1 file. **LULUS** bila: toast menyebut
    nama folder pilihan (bukan Download/SenzSpace) dan file ada di
    folder itu. Tap "Reset ke default" → **LULUS** bila label
    kembali "Download/SenzSpace". Switch "Tampilkan file
    tersembunyi" ON → **LULUS** bila file yang namanya diawali
    titik ikut tampil di File Manager.

## Tes Bersihkan Data Space v0.1.30 di HP (bahasa bayi)

Latar: Google 400 TERUS di Space 7 (11:36) meski Space sudah
dihapus-buat lagi dan v0.1.29 sudah dipasang. Tersangka: cookie
.google.com yang RUSAK di toko cookie Space itu — cookie rusak
bikin Google menolak request dengan 400, dan penghapusan folder
data saat proses Space masih hidup tidak selalu berhasil, jadi
cookie rusak bertahan. Obat: menu baru "Bersihkan data Space"
(tahan lama kartu) — cookie + storage + cache dihapus DI DALAM
proses Space itu sendiri, lalu halaman awal dimuat ulang.

1. Install APK v0.1.30-s1cleardata (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Di Beranda, TAHAN LAMA kartu Space 7 (STUDIO) → pilih
   **"Bersihkan data Space"** → tap **"Bersihkan"**.
   **LULUS** bila: muncul toast "Data Space dibersihkan" dan
   halaman Google AI Studio / halaman login Google muncul
   (bukan lagi halaman error 400).
3. Login akun Google seperti biasa.
   **LULUS** bila: masuk ke halaman AI Studio (Playground).
4. (Opsional, bila sempat) tes unduhan audio AI Studio sekali —
   **LULUS** bila file .wav tersimpan dengan nama asli.

## Tes Login AI Studio v0.1.29 di HP (bahasa bayi)

Latar: login Google di Space 7 gagal dengan dialog "Something went
wrong" (09:14), padahal di Chrome aman dan login Google pernah
LULUS di gate S0. Tersangka: skrip penangkap blob (trap) yang
disisipkan ke SEMUA halaman ikut mengubah halaman login Google
yang penuh iframe — halaman itu rusak. v0.1.29: halaman login
Google (accounts.google.com) TIDAK lagi diambil alih — WebView
memuatnya sendiri apa adanya, persis seperti Chrome.

1. Install APK v0.1.29-s1noauthinject (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Buka Space 7 (AI Studio) → login akun Google seperti biasa.
   **LULUS** bila: masuk ke halaman AI Studio (Playground),
   tanpa dialog "Something went wrong".
3. Setelah login, tes unduhan audio AI Studio sekali (tombol
   unduh di player) — **LULUS** bila file .wav tersimpan dengan
   nama asli (bukti trap tetap jalan di halaman AI Studio-nya,
   hanya halaman login yang dilewati).

## Tes Unduhan v0.1.28 di HP (bahasa bayi)

v0.1.27 TERBUKTI JALAN (Sendy 08:29, Space 2): file
`utf-8''files.zip` mendarat di folder Claude 2 — jalur https
(fetch WebView) akhirnya hidup setelah kurung diperbaiki. Sisa
cacat kecil: nama file masih berawalan `utf-8''` karena server
mengirim `filename*=utf-8''...` (huruf kecil) sedangkan penghapus
awalan di kode hanya kenal `UTF-8''` (huruf besar). v0.1.28
membuat penghapusnya tidak peduli besar-kecil huruf.

1. Install APK v0.1.28-s1utf8name (NIMPA — JANGAN uninstall,
   uninstall = semua sesi login hilang, harus login ulang).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Buka Space mana pun → tap **"Unduh semua"** atau 1 lampiran.
   **LULUS** bila: nama file bersih tanpa awalan `utf-8''`
   (mis. `files.zip`, bukan `utf-8''files.zip`).

## Tes Unduhan via WebView v0.1.27 di HP (bahasa bayi)

AKAR MASALAH KETEMU (bukti log v0.1.26 08:07 + 08:08): jembatan
SenzHttp HIDUP (probe: "object"), tapi skrip fetch-nya TIDAK
PERNAH JALAN (js-result: null, "fetch mulai" tidak muncul).
Penyebabnya: **satu kurung kurawal `}` hilang** di ujung skrip —
seluruh skrip gagal dibaca WebView, jadi diam total. v0.1.27
memperbaiki kurungnya (sudah dicek sintaksnya pakai node
--check SEBELUM dikirim — pelajaran: cek JS dulu baru push).
Bonus: lampiran satuan sekarang dapat nama asli (bukan UUID)
dan file teks tersimpan sebagai .txt (bukan .bin).

1. Install APK v0.1.27-s1bracefix (nimpa, tanpa uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Buka Claude 3 → tap **"Unduh semua"**.
   **LULUS** bila: toast "Tersimpan di folder Claude 3:
   download-files.zip" + file ada di space3. (Tidak ada lagi
   toast "tidak merespon".)
3. Tap juga 1 lampiran satuan (mis. MP4) — namanya sekarang
   harus nama asli, bukan UUID.

## Tes Unduhan via WebView v0.1.26 di HP (bahasa bayi)

v0.1.25 membuktikan: listener jalan ("UNDUH-WEB mulai" 07:52:39),
tapi WebView tidak merespon dalam 6 detik (watchdog 07:52:45) —
"fetch mulai" tidak pernah muncul. v0.1.26 menyelidiki + memperbaiki:
jembatan SenzHttp didaftarkan ulang tepat sebelum dipakai, URL
dikirim lewat `window.__senzDlUrl` (aman dari karakter aneh), dan
hasil selidikannya dicatat di log ("probe SenzHttp" + "js-result").

1. Install APK v0.1.26-s1webprobe (nimpa, tanpa uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Buka Claude 3 → tap **"Unduh semua"**.
   **LULUS** bila: toast "Tersimpan di folder Claude 3:
   download-files.zip" + file ada di space3.
3. **Bila masih gagal:** kirim `_Log-Blob/blob-debug.txt` ke Jhon.
   Baris baru akan menjawab: probe "undefined" = jembatan tidak
   hidup di halaman; probe "object" + js-result "null" = string JS
   gagal jalan; probe "object" + ada "fetch mulai" = fetch-nya
   yang macet. Bonus: tap juga 1 lampiran satuan (mis. MP4) —
   itu menguji jalur blob (SenzBlob) yang terpisah, biar ketahuan
   apakah SEMUA jembatan mati atau cuma SenzHttp.

## Tes Unduhan via WebView v0.1.25 di HP (bahasa bayi)

v0.1.24 DIAM TOTAL (bukti log 07:40: 0 baris UNDUH-WEB — fetch
tidak pernah lapor balik). v0.1.25 menambahkan: log "UNDUH-WEB
mulai" saat tombol ditekan, toast "Mengunduh …" di awal, laporan
tiap langkah dari WebView, dan watchdog 6 detik yang berteriak
bila WebView tidak merespon sama sekali.

1. Install APK v0.1.25-s1webdiag (nimpa, tanpa uninstall).
   **PENTING: setelah install, TUTUP TOTAL app-nya dulu** (hapus
   dari recent apps) lalu buka lagi — supaya proses lama yang
   masih jalan tidak menahan kode lama.
2. Buka Claude 3 → tap **"Unduh semua"**.
   **LULUS** bila: toast "Mengunduh download-files.zip…" muncul,
   lalu toast "Tersimpan di folder Claude 3: download-files.zip"
   + file ada di space3.
3. **Bila masih diam / gagal:** kirim `_Log-Blob/blob-debug.txt`
   ke Jhon. Baris barunya akan nunjukin persis di mana berhentinya:
   - tidak ada "UNDUH-WEB mulai" → tombol tidak sampai ke
     downloader (listener tidak jalan);
   - ada "mulai" tapi ada "tidak ada respon dari WebView" →
     jembatan SenzHttp tidak hidup;
   - ada "fetch mulai" tapi tidak ada "fetch ok" → server tidak
     menjawab fetch dari WebView.

## Tes Unduhan via WebView v0.1.24 di HP (bahasa bayi)

RENCANA B: hop 0 ke claude.ai ditolak 403 walau cookie + header
browser dikirim dari luar WebView (bukti log 07:19: "UNDUH hop 0:
claude.ai -> 403") — server mengenali jejak TLS non-browser.
Sekarang unduhan http(s) di-fetch LANGSUNG dari halaman (jaringan
WebView sendiri: cookie lengkap, header browser asli, TLS Chrome),
isi di-streaming per potongan ke folder Space (file besar aman).

1. Install APK v0.1.24-s1webfetch (nimpa, tanpa uninstall).
2. Buka Claude 3 → tap **"Unduh semua"**.
   **LULUS** bila: toast "Tersimpan di folder Claude 3:
   download-files.zip" + file ada di space3 (cek via ⬇ top bar).
3. Coba juga 1 lampiran satuan (MP4 / SRT / TXT).
   **LULUS** bila tersimpan dengan nama asli + ekstensi benar.
4. **GAGAL** bila: masih toast "Gagal mengunduh: server menolak
   (403)" atau alasan lain. Kalau gagal, kirim
   `_Log-Blob/blob-debug.txt` ke Jhon — baris "UNDUH-WEB GAGAL"
   akan nyebut alasannya.

## Tes Unduhan 403 v0.1.23 di HP (bahasa bayi)

Perbaikan: v0.1.22 masih ditolak server (403) — HttpURLConnection
polos dicurigai bot. Sekarang unduhan mengirim header lengkap ala
browser (Accept, Accept-Language, Sec-Fetch-*) + cookie yang diset
server di tengah redirect ikut dibawa ke hop berikutnya. Tiap hop
dicatat di `_Log-Blob/blob-debug.txt` ("UNDUH hop 0: <host> -> 403").

1. Install APK v0.1.23-s1dl403 (nimpa, tanpa uninstall).
2. Buka Claude 3 → tap **"Unduh semua"**.
   **LULUS** bila: toast "Tersimpan di folder Claude 3:
   download-files.zip" + file ada di space3.
3. **GAGAL** bila: masih toast "Gagal mengunduh: server menolak
   (403)". Kalau gagal, kirim `_Log-Blob/blob-debug.txt` ke Jhon —
   baris "UNDUH hop …" akan nunjukin di hop ke berapa + host mana
   yang menolak. Rencana B (bila ini masih gagal): unduh lewat
   WebView-nya langsung (fetch dari halaman, header + cookie 100%
   sama kayak browser).

## Tes Unduhan dalam App v0.1.22 di HP (bahasa bayi)

Perbaikan: DownloadManager sistem DIBUANG (bukti HP 06:59 — 4×
"Couldn't download items"). Unduhan http(s) sekarang diunduh
sendiri oleh app, cookie sesi ikut di tiap redirect, nama asli
diambil dari server, progress tampil di notifikasi.

1. Install APK v0.1.22-s1inappdl (nimpa, tanpa uninstall).
2. Buka Claude 3 → tap **"Unduh semua"** di paket episode.
   **LULUS** bila: muncul toast "Mengunduh download-files.zip…",
   lalu notifikasi progress naik (0% → 100%), lalu toast
   "Tersimpan di folder Claude 3: download-files.zip".
3. Tap **⬇ di top bar** → folder space3. **LULUS** bila file
   `download-files.zip` ada di sana (bukan .bin, bukan nama acak).
4. Coba juga tap 1 lampiran (mis. MP4 / SRT) → unduh satuan.
   **LULUS** bila tersimpan dengan nama asli + ekstensi benar.
5. **GAGAL** bila: muncul notifikasi "Couldn't download items"
   lagi, atau toast "Gagal mengunduh: server menolak (403)".
   Kalau gagal, kirim file `_Log-Blob/blob-debug.txt` ke Jhon
   (baris UNDUH GAGAL / SIMPAN sekarang tercatat di sana).

## Tes Split-screen + Geser Top Bar v0.1.21 di HP (bahasa bayi)

Baru (perintah Sendy 06:45 — pindah Space HANYA untuk yang aktif):
geser top bar cuma muter di Space yang chip-nya "Aktif" (sudah login);
yang "Belum login" dilewatin. Split-screen juga cuma menawarkan Space
aktif lain.

**A. Split-screen 2 Space:**
1. Install APK v0.1.21-s1split-swipe (nimpa, tanpa uninstall).
2. Di Beranda, **tahan lama** kartu Claude 1 → tap **"Buka
   berdampingan"**. **LULUS** bila muncul daftar Space aktif lain
   (yang Belum login tidak ada di daftar).
3. Pilih mis. Claude 2. **LULUS** bila: layar terbagi dua — Claude 1
   di satu sisi, Claude 2 di sisi lain, keduanya jalan + bisa
   dipakai bersamaan.
4. Kalau HP menolak split (bukan semua HP mengizinkan), Space yang
   dipilih terbuka biasa fullscreen — itu batas HP, bukan bug app.
   Catatan: masuk split tidak me-reload halaman (WebView tetap hidup).

**B. Geser top bar pindah Space (hanya yang aktif):**
1. Buka Claude 1. **Geser kiri** di bar atas (dari nama Space, usap
   cepat ke kiri). **LULUS** bila: pindah ke Space aktif berikutnya
   (toast "→ <nama>"), Space yang Belum login dilewatin.
2. **Geser kanan** di bar atas. **LULUS** bila: balik ke Space aktif
   sebelumnya.
3. Bila cuma 1 Space aktif, geser menampilkan toast "Tidak ada Space
   aktif lain" (bukan diam / bukan pindah ke yang belum login).
4. Tap tombol top bar (⌄ ↻ ⌂ ⬇) tetap berfungsi normal — geser
   tidak merusak tap.

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
