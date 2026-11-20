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

## Tes Style v0.1.44 di HP (bahasa bayi)

v0.1.44 mencoba dua cara baru supaya format suara Riko masuk ke
kotak Style (v0.1.43 membuktikan kotak itu menolak isi lewat
kode — ketahuan dalam 30 detik, VO dibatalkan, tidak generate
suara yang salah): cara 1 = isi lewat kode tapi dengan event
yang membawa datanya; cara 2 = ketik beneran lewat jalur
keyboard HP. Kalau dua-duanya gagal, aplikasi mengirimkan
"foto" isi kotaknya supaya Jhon tahu persis kenapa.

1. Install APK v0.1.44 (nimpa — JANGAN uninstall).
2. Tutup SenzSpace, kembali ke Muse. JANGAN buka Space 7.
   Bilang ke Jhon: "generate VO Charger di Space 7". LULUS bila:
   Jhon jawab "done" + nama file VO, DAN file wav ada di File
   Manager → aistudio — tanpa kamu ketuk apa pun.
3. Cek suara: buka Space 7, putar file VO — harus terdengar
   ceria, cepat, dan bersemangat (gaya Riko). Kalau suaranya
   datar/normal, format Style tidak menempel — kabari Jhon.
4. Kalau "failed", baca alasannya dan kirim ke Jhon (ada
   "Dump kolom" di ujung alasan — itu bukti buat perbaikan
   berikutnya).

## Tes Style + Unduh v0.1.43 di HP (bahasa bayi)

v0.1.43 memperbaiki dua cacat yang ketahuan di tes v0.1.42
(14:12-14:13): (1) format suara Riko tidak masuk ke kotak Style
— kotak "Describe the voice style" kosong di screenshot walau
eksekutor mengira sudah terisi; (2) tombol unduh diklik terlalu
cepat (audio belum selesai di-generate) dan kena tombol dialog
lama — file VO tidak muncul.

1. Install APK v0.1.43 (nimpa — JANGAN uninstall). Izin overlay
   dari v0.1.42 sudah ada, tidak perlu diulang.
2. Tutup SenzSpace, kembali ke Muse. JANGAN buka Space 7.
   Bilang ke Jhon: "generate VO Charger di Space 7". LULUS bila:
   Jhon jawab "done" + nama file VO, DAN file wav ada di File
   Manager → aistudio — tanpa kamu ketuk apa pun.
3. Cek suara: buka Space 7, putar file VO — harus terdengar
   ceria, cepat, dan bersemangat (gaya Riko). Kalau suaranya
   datar/normal, format Style tidak menempel — kabari Jhon.
4. Kalau "failed", baca alasannya: "format suara tidak
   menempel" berarti kotak Style menolak isi (bahan perbaikan);
   "VO tidak selesai dalam 10 menit" berarti generate-nya lama —
   kabari Jhon.

## Tes Tanpa Ketuk v0.1.42 di HP (bahasa bayi)

v0.1.42 memperbaiki satu hal besar: tugas dari Jhon harus jalan
tanpa kamu ketuk apa pun, walau layar Space sudah dihancurkan
Android saat RAM menipis (itu yang bikin VO Charger mentok
"menunggu Space tampil" 12 menit di v0.1.41).

Caranya: SenzSpace minta izin sekali — "Tampil di atas aplikasi
lain". Izin ini membuat app boleh membuka layar Space dari
background. Kalau izin belum diberikan dan tugas mentok, status
tugas berubah jadi "failed" dengan alasan jelas dalam 60 detik
(jangan diam di "menunggu" lagi).

1. Install APK v0.1.42 (nimpa — JANGAN uninstall). Buka SenzSpace
   sekali. Kalau muncul kotak "Buka Space tanpa ketuk", ketuk
   "Beri izin" lalu di halaman sistem nyalakan "Izinkan tampil
   di atas aplikasi lain" (cuma sekali ini). Kalau kotaknya tidak
   muncul, buka Pengaturan → API Kontrol → "Beri izin tampil di
   atas aplikasi lain".
2. Tutup SenzSpace, kembali ke Muse. JANGAN buka Space 7.
   Bilang ke Jhon: "generate VO Charger di Space 7". LULUS bila:
   Jhon jawab "done" + nama file VO, DAN file wav ada di File
   Manager → aistudio — tanpa kamu ketuk apa pun.
3. Kalau "failed" dalam 60 detik, baca alasan yang Jhon kirim:
   "izin belum diberikan" berarti langkah 1 belum selesai;
   "activity dimatikan RAM" berarti Android membunuh layar
   Space walau izin sudah ada — kabari Jhon, itu bahan v0.1.43.

## Tes TTS API v0.1.41 di HP (bahasa bayi)

v0.1.41 membangun ulang TTS pakai alur ASLI dari 8 screenshot
kamu (23:28-23:32). Yang dulu salah: model dicari lewat hamburger
kiri (tidak ada entri TTS di sana), dan format Riko dikira nama
voice. Yang benar:

1. Run settings (ikon slider, kanan atas) → 2. kartu model →
3. tab Starred → "Gemini 3.8 Flash Lite TTS" → 4. "Create new
dialog" → 5. tombol Style → ketik format Riko → 6. dropdown
"Speaker 1" → "Sadaltager" → 7. naskah di kotak besar → 8. Run
pojok kanan bawah → 9. selesai, klik unduh.

Catatan pemanasan: Space yang prosesnya mati total TIDAK BISA
dinyalakan dari background — ketuk tile-nya sekali dulu, baru
tugas jalan sendiri. Space yang prosesnya sudah hangat jalan
tanpa tap.

1. Install APK v0.1.41 (nimpa — JANGAN uninstall). Buka SenzSpace
   sekali, ketuk tile Space 7 sekali (pemanasan).
2. Bilang ke Jhon: "generate VO Charger di Space 7". LULUS bila:
   Jhon jawab "done" + nama file VO, DAN file wav ada di File
   Manager → aistudio.
3. Kalau "failed", Jhon sebut alasannya + dump isi halaman — itu
   bahan perbaikan berikutnya, kamu tidak perlu apa-apa.

## Tes TTS API v0.1.40 di HP (bahasa bayi)

v0.1.40 memperbaiki dua hal dari tes 22:24 (v0.1.39 gagal karena
Space 7 mendarat di halaman preview suara — 0 kolom isian, bukan
halaman TTS):

1. **Jhon TIDAK PERLU kamu ketuk notifikasi apa pun.** Bukti:
   diagnose TTS 22:24 itu jalan sendiri di background (Space 7
   kebuka tanpa tampil, halaman ke-load, diagnose jalan, status
   failed tertulis) — padahal kamu tidak mengetuk apa pun. Jadi
   mulai v0.1.40, Jhon kirim tugas dan tugasnya jalan di
   background; kamu tetap di app Muse, Jhon yang kabarin
   hasilnya. Notifikasi "Buka ..." cuma ada kalau kamu mau
   mengintip — bukan syarat.
2. **TTS navigasi sendiri ke halaman TTS.** Kalau Space 7 bukan
   di halaman TTS, Jhon buka menu garis tiga, pilih entri model
   starred / TTS, lalu diagnose ulang. Kolom isian yang dihitung
   sekarang termasuk contenteditable (kotak teks AI Studio bisa
   jadi bukan textarea biasa).

Langkah tes (sama seperti 39, tanpa ketuk notifikasi):
1. Install APK v0.1.40 (nimpa — JANGAN uninstall). Buka SenzSpace sekali.
2. Bilang ke Jhon: "generate VO Charger di Space 7". LULUS bila:
   Jhon jawab "queued" lalu "done" + nama file VO, DAN file wav
   ada di File Manager → aistudio. Kamu tidak perlu mengetuk apa
   pun selama proses.
3. Kalau "failed", Jhon sebut alasannya + apa yang dia lihat di
   halaman (daftar tombol/kolom) — itu bahan perbaikan berikutnya.

## Tes Prompt API v0.1.40 di HP — prompt masuk Project (bahasa bayi)

v0.1.40: prompt bisa masuk ke Project Claude tanpa kamu kirim
URL. Jhon yang navigasi sendiri dari tombol kiri atas (sidebar)
di Space, pilih Project "SSR CONTENT STUDIO", buka chat baru di
dalamnya, baru kirim prompt + lampiran.

1. Install APK v0.1.40 (nimpa — JANGAN uninstall).
2. Bilang ke Jhon: "kirim prompt Charger ke Space 2, Project SSR CONTENT STUDIO".
   LULUS bila: Jhon jawab "sent", DAN di HP (kalau kamu intip
   Space 2) chat baru di dalam Project SSR CONTENT STUDIO itu
   berisi prompt + 3 lampiran (packet, prompt, VO).
3. Kalau "failed", Jhon sebut alasannya (misal "Project tidak
   ketemu di sidebar" = nama Project beda / belum kebuka).

## Tes TTS API v0.1.39 di HP (bahasa bayi)

v0.1.39 bikin Jhon bisa generate VO di AI Studio (Space 7) lewat
API — tanpa kamu mengetik apa pun: Jhon yang memilih model TTS
yang sudah kamu starred, mengisi format suara, mengisi naskah,
menekan Run, menunggu sampai selesai, lalu mengunduh VO-nya.
File VO (wav) langsung masuk File Manager → folder aistudio.
Setiap generate HANYA jalan kalau kamu yang memerintahkan
(Jhon tidak pernah generate sendiri).

1. **Install APK v0.1.39 (nimpa — JANGAN uninstall).** Buka
   SenzSpace sekali. LULUS bila: notifikasi "SenzSpace API aktif"
   muncul di panel notifikasi.
2. **Buka Space 7 (AI Studio).** Pastikan halaman TTS-nya
   kebuka (Playground TTS — tempat kamu biasa generate VO).
   LULUS bila: kamu melihat kolom naskah + tombol Run di layar.
   (Kalau masih di halaman utama AI Studio, buka dulu halaman
   TTS-nya seperti biasa — sekali saja, sesi-nya nempel.)
3. **Bilang ke Jhon: "generate VO Kesemutan di Space 7".**
   LULUS bila: Jhon jawab "queued" (bukan error).
4. **Tunggu (VO naskah 1 menit biasanya selesai 1–3 menit).**
   Jangan mengetuk apa pun di Space 7 selama Jhon bekerja.
   LULUS bila: Jhon jawab "done" + nama file VO-nya, DAN file
   wav itu ada di File Manager → aistudio.
5. **Bilang ke Jhon "cek status TTS Space 7".** LULUS bila:
   Jhon jawab "done". Kalau jawab "failed", dia juga menyebut
   alasannya (misal "kolom naskah tidak ketemu" = halaman TTS
   belum kebuka; "model tidak ketemu" = nama model berubah).

Kalau langkah 4 file VO tidak muncul: kemungkinan besar tombol
di halaman TTS berubah (AI Studio ganti tampilan) — Jhon yang
memperbaiki selektornya, kamu tidak perlu apa-apa. VO yang
dihasilkan pakai format suara Riko yang sudah kamu pilih
("Very bright, high-pitched, cheerful..." + speed 1.4x).

## Tes Prompt API v0.1.38 di HP (bahasa bayi)

v0.1.38 bikin Jhon bisa mengirim prompt + lampiran ke chat Claude di
satu Space — lewat API, bukan lewat ketuk layar. Alurnya: file
(Production Packet + Episode Prompt) diunggah dulu ke folder
`_inbox` di HP, lalu Jhon kirim perintah `prompt` — app yang
mengisi kolom chat Claude, melampirkan 2 file itu, dan menekan
kirim. Setiap kirim HANYA jalan kalau kamu yang memerintahkan
(Jhon tidak pernah mengirim sendiri).

1. **Install APK v0.1.38 (nimpa — JANGAN uninstall).** Buka
   SenzSpace sekali. LULUS bila: notifikasi "SenzSpace API aktif"
   muncul di panel notifikasi.
2. **Pilih satu Space Claude yang kosong** (sesi terakhirnya sudah
   beres, video sudah jadi). Bilang ke Jhon: "upload 2 file
   Kesemutan, kirim prompt tes ke Space <nomor>". LULUS bila:
   Jhon jawab "queued" (bukan error).
3. **Buka Space itu (atau ketuk notifikasi "Buka ..." bila ada).**
   LULUS bila: di chat Claude Space itu muncul prompt tes + 2
   file terlampir + Claude mulai menjawab.
4. **Bilang ke Jhon "cek status prompt Space <nomor>".** LULUS
   bila: Jhon jawab "sent". Kalau jawab "failed", dia juga
   menyebut alasannya (misal "kolom chat tidak ketemu" atau
   "tombol attach tidak ketemu") — itu jujur, bukan gagal diam-diam.
5. **Tes `continue last checkpoint`:** setelah Claude berhenti
   (jeda), bilang ke Jhon "kirim 'continue last checkpoint' ke
   Space <nomor>". LULUS bila: pesan itu terkirim dan Claude
   lanjut bekerja.

Kalau langkah 3 prompt tidak muncul: kemungkinan besar selektor
tombol Claude berubah (Claude ganti tampilan) — Jhon yang
memperbaiki selektornya, kamu tidak perlu apa-apa. Jangan
mengetuk apa pun di layar Space itu selama Jhon mengirim (nanti
kolom chat-nya ketutup).

## Tes Open Jujur v0.1.37 di HP (bahasa bayi)

v0.1.36 punya satu kebohongan: `open` via API jawab "opened"
padahal Space-nya tidak muncul di layar kalau app lagi di
background (aturan Android: service di background tidak boleh
menarik layar ke depan). v0.1.37 memperbaikinya: API jawab jujur
+ kirim notifikasi yang bisa diketuk.

1. **Install APK v0.1.37 (nimpa — JANGAN uninstall).** Tutup
   SenzSpace sampai tidak ada di layar (buka app lain, misal
   Muse). LULUS bila: SenzSpace tidak terlihat di layar.
2. **Bilang ke Jhon "buka Space 3 via API".** LULUS bila: Jhon
   jawab "needs_tap" (bukan "opened" polos) DAN di panel
   notifikasi muncul "SenzSpace — Buka eneng127".
3. **Ketuk notifikasi itu.** LULUS bila: Space eneng127 langsung
   muncul di layar.
4. **Sekarang, saat SenzSpace masih kebuka di layar, bilang ke
   Jhon "buka Space 4 via API".** LULUS bila: Space sendy753
   langsung muncul TANPA notifikasi (Jhon jawab "visible").
5. **Bilang ke Jhon "bekukan Space 2 via API", lalu "buka
   beku Space 2".** LULUS bila: Jhon jawab frozen true lalu
   false, dan tile Space 2 di Beranda ikut berubah.

Kalau langkah 2 tidak ada notifikasi: cek Pengaturan HP →
Notifikasi → SenzSpace — izin notifikasi harus hidup.

## Tes API Kontrol v0.1.36 di HP (bahasa bayi)

API = jalan khusus di dalam app supaya Jhon (Muse) bisa mengecek
dan menggerakkan SenzSpace dari komputernya — lewat jalur API
(HTTP localhost), bukan lewat perintah ADB mentah. Token = kata
sandi khusus API ini (acak, cuma Jhon yang pakai).

1. **Install APK v0.1.36 (nimpa — JANGAN uninstall).** Buka
   SenzSpace sekali. LULUS bila: di panel notifikasi muncul satu
   baris "SenzSpace API aktif" (sekali saja, tidak bunyi).
2. **Buka Pengaturan (ikon gerigi di Beranda).** Cari bagian
   "API Kontrol (untuk Jhon)". LULUS bila: saklarnya HIDUP dan ada
   token (huruf-angka panjang) di bawahnya.
3. **Matikan saklar API, tunggu 5 detik, hidupkan lagi.** LULUS
   bila: notifikasi "SenzSpace API aktif" hilang saat dimatikan
   dan muncul lagi saat dihidupkan.
4. **Ketuk token sekali.** LULUS bila: muncul toast "Token
   disalin". (Jangan kirim token itu ke siapa pun.)
5. **Bilang ke Jhon "cek status via API".** Jhon membaca status
   8 Space dari API tanpa mengganggu layar HP kamu. LULUS bila:
   Jhon bisa menyebut nama Space, mana yang login, mana yang
   prosesnya mati — tanpa kamu membuka apa pun.
6. **Bilang ke Jhon "buka Space 3 via API".** LULUS bila: Space
   eneng127 terbuka di HP kamu. Lalu bilang "baca blob-debug.txt
   via API" — LULUS bila Jhon bisa menampilkan isi log itu
   (dulu tidak bisa dibaca dari jauh sama sekali).

Kalau langkah 1 gagal (tidak ada notifikasi API): cek Pengaturan
→ API Kontrol — saklarnya harus HIDUP. Kalau langkah 5/6 gagal,
kirim screenshot Pengaturan → API Kontrol ke Jhon.

## Tes Live Jujur v0.1.35 di HP (bahasa bayi)

Latar: cek POVA 5, 19 Nov 09:42–09:53 (8 Space, RAM free ~412MB).
SENDY09 dibuka setelah idle 649 menit: layar HITAM total 60–90
detik. Tile senzwh mentok gambar skeleton padahal sesinya jalan.
Tile eneng127 nunjukin banner basi "limit sampai 1:50 AM" padahal
kuota sudah reset — tapi tulisannya "live baru saja". Penyebab:
renderer WebView dibunuh sistem saat RAM menipis, dan app tidak
punya penangan untuk itu — WebView diam hitam, gambar tile ikut
basi. v0.1.35 memperbaikinya: renderer mati langsung dimuat ulang
otomatis; tiap Space kirim detak ke Beranda; Beranda tidak lagi
bilang "Semua live" kalau ada Space yang mati/gambarnya basi.

1. Install APK v0.1.35-s1live35 (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Buka Space yang sudah lama tidak dibuka (mis. SENDY09).
   **LULUS** bila: konten langsung muncul (tidak hitam 60–90
   detik). Kalau sempat muncul toast "Renderer Space mati —
   memuat ulang", itu normal: artinya renderer memang mati dan
   app sudah memulihkannya sendiri.
3. Di Beranda, lihat tulisan di bawah "SenzSpace".
   **LULUS** bila: tulisannya jujur — "Semua live" hanya bila
   semua Space yang login memang live; kalau ada yang mati,
   tulisannya "7 dari 8 live" (angka sesuai kenyataan).
4. Cari tile yang sub-nya "Space tidak berjalan, tap untuk
   membuka" (kalau ada). **LULUS** bila: gambar tile-nya redup
   (bukan terang seolah live) dan tidak ada badge LIVE. Tap tile
   itu → Space terbuka normal → balik ke Beranda, tile jadi LIVE.
5. Buka satu Space, biarkan Claude mengetik jawaban, balik ke
   Beranda selagi dia mengetik. **LULUS** bila: gambar tile ikut
   berubah mengikuti ketikan (bukan diam di gambar lama).
6. Regresi unduhan (jangan rusak yang sudah benar): 1x unduh
   audio AI Studio + 1x unduh lampiran Claude. **LULUS** bila:
   keduanya tersimpan dengan nama benar (lihat tes v0.1.34).

## Tes Unduhan AI Studio v0.1.34 di HP (bahasa bayi)

Latar: unduhan audio AI Studio gagal intermiten — log 17 Nov
18:16–18:46 menunjukkan 8x GAGAL beruntun (toast "blob tidak
bisa dibaca"), padahal 14:34–14:43 sempat 5x berhasil. Penyebab:
iframe audio kadang belum siap saat diminta (cache kosong,
jawaban hilang). v0.1.34 memperbaikinya: trap menyapu iframe
yang sudah ada, jawaban diteruskan antar-frame, permintaan
ditahan sampai cache siap, kirim ulang di 3 detik, batas tunggu
15 detik.

1. Install APK v0.1.34-s1blob34 (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. Buka Space AI Studio (SSR) → Playground TTS → generate audio
   (atau pakai audio yang sudah ada, mis. yang 6:54).
3. Tap tombol unduh (ikon panah bawah) di player audio.
   **LULUS** bila: toast "Tersimpan di folder AI Studio:
   Generated Audio ... .wav" + badge unduh di top bar naik 1.
   Buka File Manager → aistudio → file .wav-nya ada dan bisa
   diputar.
4. Ulangi 3x (generate baru → unduh; atau unduh audio lain).
   **LULUS** bila: 3 dari 3 berhasil (dulu gagal berselang-seling).
   Kalau masih ada yang gagal, kirim lagi file
   `_Log-Blob/blob-debug.txt` (File Manager → _Log-Blob) — log
   baru sekarang mencatat baris "core: req ..." / "core: balas ..."
   yang menunjukkan persis di mana macetnya.
5. Cek sisi Claude tidak rusak: unduh 1 lampiran dari Claude
   (mis. file .mp4 / .zip). **LULUS** bila tetap tersimpan normal.

## Tes Perbaikan v0.1.33 di HP (bahasa bayi)

Latar: 3 screenshot Sendy (17 Nov 17:13–17:26) — rotasi, daftar
Pindah Space, dan tombol titik-tiga File Manager.

1. Install APK v0.1.33-s1ui33 (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. **Rotasi ngikutin sistem.** Nyalakan "Rotasi otomatis" di HP
   (pengaturan cepat di atas layar), lalu miringkan HP ke
   horizontal. **LULUS** bila: Beranda / Space / File Manager ikut
   jadi horizontal. Lalu MATIKAN "Rotasi otomatis" dan miringkan
   lagi. **LULUS** bila: app TETAP portrait (tidak ikut muter).
   Dulu (v0.1.31) app muter dari sensor walau auto-rotate mati;
   sekarang ngikutin setelan sistem.
3. **Pindah Space cuma yang LIVE.** Buka Beranda, lihat tile mana
   yang ada badge LIVE merah (di screenshot Sendy cuma 3: Claude
   753, Claude Nengz, Claude SS). Masuk salah satu Space, tap
   tombol ⇌ di top bar. **LULUS** bila: daftarnya HANYA Space yang
   live (yang badge LIVE-nya nyala), BUKAN semua 6 yang Aktif.
   Catatan: kalau tidak ada Space lain yang live, daftar balik
   ke yang Aktif (supaya dialog tidak kosong) — itu normal.
   Menu "Buka berdampingan" (tahan lama kartu) aturannya sama.
4. **Tombol titik-tiga gampang ditekan.** Buka File Manager →
   masuk folder Space. **LULUS** bila: tombol titik-tiga di kanan
   tiap file gampang ditekan (area sentuhnya sekarang 44dp —
   sebesar tombol kembali); tap sekali langsung buka menu
   (Lihat, Buka, Bagikan, Pindah, Potong, Salin, ...).

## Tes Polish UI v0.1.32 di HP (bahasa bayi)

Latar: 4 screenshot Sendy (17 Nov 16:34–16:37) — Pengaturan masih
pakai emoji (gembok, perisai) dan tombolnya kotak tajam; tombol
File Manager kotak tajam dan tombol kembali kecil; menu tahan lama
kartu dan dialog Pindah Space masih polos tanpa detail. Aturan
baru: SEMUA ikon di app harus SVG minimalis, nol emoji.

1. Install APK v0.1.32-s1ui32 (NIMPA — JANGAN uninstall).
   Tutup total app dulu (hapus dari recent apps), lalu buka lagi.
2. **Pengaturan.** Buka Pengaturan. **LULUS** bila: baris "Kunci App
   (PIN)" pakai ikon gembok garis (bukan emoji 🔒); baris "Guard
   anti-kill" pakai ikon perisai garis (bukan emoji 🛡️); SEMUA
   tombol (Ubah PIN, Minta pengecualian baterai, Pilih folder
   ekspor, Reset, Bersihkan thumbnail) sudutnya MEMBULAT
   (persegi panjang membulat, bukan kotak tajam).
3. **File Manager.** Buka File Manager → masuk folder Space.
   **LULUS** bila: kotak cari + tombol "Terbaru", "+ Folder",
   "Grid", "Tempel" semuanya sudut membulat; tombol kembali di
   kiri breadcrumb jadi panah < BESAR (gampang ditekan);
   TIDAK ADA lagi simbol aneh di tombol (dulu ada ⇅ ▦ ☰ ＋).
   Tahan lama 1 file (mode pilih) → **LULUS** bila: lingkaran
   pilih jadi ikon ceklis SVG; tombol titik-tiga di tiap baris
   jadi ikon titik-tiga SVG.
4. **Menu tahan lama Space.** Di Beranda, tahan lama kartu Claude
   (misal Claude Nengz). **LULUS** bila: muncul KARTU gelap sudut
   membulat — atasnya ada titik warna + nama Space + chip status
   (Aktif/Beku/Limit/Belum login) + situs + jumlah file; tiap
   baris ada ikon garis + label + penjelasan kecil (misal
   "Bersihkan data Space — Hapus cookie + login, file unduhan
   aman"); "Hapus Space" berwarna merah; bawah ada "Batal".
5. **Pindah Space.** Di dalam Space, tap tombol ⇌ di top bar.
   **LULUS** bila: muncul kartu yang sama — tiap Space ada chip
   statusnya (mana yang Aktif hijau, mana yang Belum login
   abu-abu, mana yang Limit kuning / Beku). Pilih satu → pindah.
   Menu "Buka berdampingan" (tahan lama kartu) tampilannya sama.
6. **Kunci App.** Keluar app, buka lagi (bila kunci aktif) atau
   nyalakan kunci di Pengaturan. **LULUS** bila: logo atas pakai
   ikon gembok garis + tulisan "SenzSpace" (bukan emoji);
   tombol biometrik pakai ikon gembok-buka + "Sidik jari / wajah".
7. **Sweep emoji.** Jalan-jalan ke semua layar (Beranda, Space,
   File Manager, Pengaturan, Tambah Space, Kunci). **LULUS** bila:
   TIDAK ADA satu pun emoji di mana pun.

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

## Kontrol app dari Jhon (jalur API — v0.1.36)

Jhon mengontrol SenzSpace lewat **API lokal di dalam app**
(HTTP 127.0.0.1:19335, token Bearer) — BUKAN lewat perintah ADB
mentah. Server dijalankan `ApiService` (foreground, START_STICKY)
dan hidup setelah app dibuka sekali. Satu perintah `senz.py` =
satu fungsi tool, lihat
`~/workspace/custom-connectors/custom.senzspace/` + skill
`senzspace-control`.

```bash
python3 ~/workspace/custom-connectors/custom.senzspace/bin/senz.py status
python3 ~/workspace/custom-connectors/custom.senzspace/bin/senz.py spaces
python3 ~/workspace/custom-connectors/custom.senzspace/bin/senz.py files --space space3
python3 ~/workspace/custom-connectors/custom.senzspace/bin/senz.py download --space _log-blob --path blob-debug.txt
python3 ~/workspace/custom-connectors/custom.senzspace/bin/senz.py open --slot 3
```

- `status`/`spaces`/`files`/`download`/`thumb`/`ping` lewat API:
  status lengkap, daftar file per Space, unduh file apa pun di
  folder kerja (termasuk log `_log-blob`/`_log-crash` dan
  thumbnail live — dulu TIDAK BISA dibaca dari VM).
- `procs`/`shot`/`type`/`tap`/`key`/`home` tetap lewat ADB
  (bukan urusan app — itu level perangkat).
- `open`/`freeze`/`type`/`tap`/`key` mengambil alih layar HP —
  hanya atas perintah eksplisit Sendy untuk aksi itu persis.
- Token API: acak per install, di `senzspace_ui` (kunci
  `api_token`), tampil + bisa dibuat ulang di Pengaturan → API
  Kontrol. Jhon membaca token dari HP via `run-as` — token
  TIDAK PERNAH ditulis di repo, chat, atau memori.

## Keamanan

Tidak ada password yang disimpan sebagai teks biasa. Login dilakukan Sendy
sendiri di halaman resmi situs, di dalam Space-nya. Tidak ada telemetri,
tidak ada pengiriman data ke server mana pun di v1. Semua data tinggal di HP.
Repo ini publik dan tidak berisi secret apa pun.
