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

## Status: Slice S0 — Prototipe Gerbang (ADR-01)

S0 membuktikan dua hal sebelum MVP dikunci:

1. **Isolasi:** 2 Space bisa login ke situs yang sama dengan 2 akun berbeda
   secara bersamaan, tanpa saling mengeluarkan.
2. **Login pihak ketiga:** login Google (pop-up / redirect) jalan di WebView,
   dan sesi tetap login setelah app ditutup lalu dibuka lagi / HP di-restart.

Bukti S0 lulus = demo di HP Sendy, bukan klaim di README.
Hasil prototipe dicatat di `docs/ADR-01.md`.

Yang **belum** ada di S0 (masuk S1–S4): CRUD Space penuh sampai 10,
File Manager + Inbox Bersama, Download Manager, freeze, kunci app.

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

## Tes S0 di HP (bahasa bayi)

1. Install APK debug. Buka SenzSpace.
2. Tap **Space 1 — Claude 1**. Login akun Claude pertama. Biarkan terbuka.
3. Kembali ke dashboard. Tap **Space 2 — Claude 2**. Login akun Claude kedua.
4. **LULUS** bila: kedua Space menampilkan akun yang berbeda, dan pindah
   Space 1 → Space 2 tidak mengeluarkan akun yang lain.
5. Tutup app sepenuhnya, buka lagi besok / restart HP.
6. **LULUS** bila: kedua Space masih login, tanpa login ulang
   (kecuali platform sendiri yang memaksa login ulang).
7. Tes login Google: buka Space AI Studio, login Google. **LULUS** bila
   login selesai di dalam app, tanpa error WebView.

## Keamanan

Tidak ada password yang disimpan sebagai teks biasa. Login dilakukan Sendy
sendiri di halaman resmi situs, di dalam Space-nya. Tidak ada telemetri,
tidak ada pengiriman data ke server mana pun di v1. Semua data tinggal di HP.
Repo ini publik dan tidak berisi secret apa pun.
