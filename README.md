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

Yang **belum** ada di S1 (menyusul): CRUD Space penuh sampai 10, freeze.

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

## Keamanan

Tidak ada password yang disimpan sebagai teks biasa. Login dilakukan Sendy
sendiri di halaman resmi situs, di dalam Space-nya. Tidak ada telemetri,
tidak ada pengiriman data ke server mana pun di v1. Semua data tinggal di HP.
Repo ini publik dan tidak berisi secret apa pun.
