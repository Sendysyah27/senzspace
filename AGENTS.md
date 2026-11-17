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
- REPO ALL-USERS (17 Nov 08:48-08:54): deskripsi repo =
  user-facing (ID), topik 8, README produk + LICENSE MIT,
  panduan tes/dev pindah ke docs/PANDUAN-PENGEMBANG.md.
  RELEASE ASSET TIDAK BISA DIUNGGAH DARI VM: bearer surrogate
  tidak di-swap di uploads.github.com (401 Bad credentials,
  terverifikasi 2x) — APK rilis masuk tree repo di
  releases/SenzSpace-vX.Y.Z.apk + link raw.githubusercontent.
  PERINGATAN push-tree: mengganti SELURUH tree — file yang
  ditambah via put-file langsung di GitHub AKAN TERHAPUS oleh
  push-tree berikutnya kalau tidak ada juga di tree lokal
  (insiden 08:52: APK releases/ hilang, diperbaiki 08:53).
- SHORTCUT UNDUHAN TERBARU — ATURAN TETAP (17 Nov 12:06,
  perintah Sendy): TIAP RILIS WAJIB: (1) APK rilis disalin ke
  releases/SenzSpace-vX.Y.Z.apk DAN timpa
  releases/SenzSpace-latest.apk dengan file yang sama; (2)
  tombol "Unduh versi terbaru" di README selalu menaut ke
  raw.githubusercontent.../releases/SenzSpace-latest.apk;
  (3) GitHub Release tag vX.Y.Z dengan body menautkan APK
  terbaru + APK versi itu. APK versi lama TIDAK dihapus
  (rollback). JANGAN taruh file kerja di root repo (insiden
  17 Nov: ci27/29/33.log + logs_url*.txt + artifact_url.txt
  sempat ter-push — dibersihkan 12:06; .gitignore diperketat
  *.log, *_url.txt + pengecualian !releases/*.apk).
  Screenshot produk di docs/screenshots/ (asli dari HP Sendy).
- UX 31 (17 Nov, v0.1.31): (1) JANGAN pakai static companion
  untuk handoff antar-proses — static hidup per-proses, Space
  jalan di proses berbeda; kirim lewat intent extra + onNewIntent.
  (2) Trap injeksi blob HANYA untuk host di daftar putih
  TRAP_HOSTS (claude.ai + subdomain, aistudio.google.com +
  subdomain) — situs lain dimuat 100% utuh seperti Chrome
  (login ChatGPT sempat rusak karena trap).
  (3) Bersihkan data Space: SELALU REORDER_TO_FRONT +
  onNewIntent + cek EXTRA_CLEAR_SUFFIX di BrowserActivity —
  JANGAN PERNAH CLEAR_TOP (menutup activity Space lain di task
  yang sama; insiden clear Space 8 ikut menghapus Space 7).
  (4) Timer live dihidupkan ulang di onResume (idempotent) —
  "Nyalakan Live" juga membuka Space-nya.
- UX 32 (17 Nov, v0.1.32): NOL EMOJI di app — semua ikon harus
  vektor SVG minimalis gaya Feather (stroke putih 2, ujung bulat).
  Menu/dialog pakai kartu desain (UiKit.roundedBg + SpacePickerDialog),
  bukan AlertDialog polos: header detail (titik warna + nama + chip
  status + situs + jumlah file), baris ikon + label + hint. Tombol
  fungsi = persegi panjang membulat radius 12dp (btn_primary /
  btn_teal / btn_dark / chip_rounded).
