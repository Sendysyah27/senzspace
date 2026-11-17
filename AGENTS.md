# SenzSpace — AGENTS.md

Baca PRD v1 (goal Senz Browser, 15 halaman, D1–D6 TERKUNCI) sebelum mengubah kode.
Jangan cuma diagnosa dari kodenya.

## Aturan

- Slice kecil, masing-masing bisa dibuktikan di HP. Tidak ada klaim "selesai"
  tanpa bukti yang bisa Sendy pegang sendiri.
- Rust core adalah sumber kebenaran aturan Space (maks 10, preset 7).
  Jangan duplikasi aturan itu di Kotlin tanpa komentar yang menunjuk ke Rust.
- Jangan simpan password / token / sesi sebagai teks biasa. Jangan masukkan
  secret apa pun ke repo publik ini.
- Batas jujur: app mengelola sesi sah, bukan membobol limit / CAPTCHA platform.
- Tes bahasa bayi untuk tiap slice ada di README.md.
- Tiap kirim APK: tegaskan INSTALL MENIMPA, JANGAN UNINSTALL
  (uninstall = semua sesi login 7 Space hilang, harus login
  ulang semua — insiden 17 Nov 08:23, Sendy tidak sengaja
  uninstall sebelum install v0.1.27).
