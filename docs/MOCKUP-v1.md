# MOCKUP v1 — SenzSpace: desain keseluruhan fitur (presisi)

Status: **DRAFT v1** — dibuat 16 Nov 2026. Mockup desain presisi
keseluruhan fitur. Sampai mockup disetujui, **tidak ada satu baris UI pun
yang diubah di kode** — S0/S0.1 tetap polos, mockup ini dulu yang dinilai.

Sumber pengikat (jangan dilanggar mockup ini):
- PRD v1 (`goals/senz-browser-browser-android-custom/files/senz-browser-prd-v1/`):
  F0–F8, 5 layar wajib (hal. 9), struktur folder File Manager (hal. 8),
  D1–D6 TERKUNCI 16 Nov 2026.
- Bahasa desain yang sudah dipakai di Harness: background hitam
  `#000000`, kartu radius 18px, gelap dulu.
- Warna brand yang sudah ada di kode S0: navy `#5B5BD6`
  (`MainActivity.kt` — warna Space Claude 1 & 2).
- Aturan: mockup desain presisi dulu sebelum implementasi.
- Aturan PRD F4 (anti-salah-akun): nama + warna Space aktif tampil
  **permanen** di setiap layar browser — desain harus mencegah salah akun,
  bukan sekadar memperingatkan.

Perangkat acuan: POCO M4 Pro, portrait 1080×2400, satu tangan.
Satuan di bawah ini dp (Android), bukan px.

---

## 1. Design token (satu bahasa visual untuk semua layar)

| Token | Nilai | Pakai di |
|---|---|---|
| Background utama | `#000000` | Semua layar |
| Background kartu | `#111111` | Kartu Space, baris file, kartu unduhan |
| Background kartu aktif / terpilih | `#1A1A1A` + border warna Space 1,5dp | Space yang sedang dibuka |
| Teks utama | `#FFFFFF` | Nama Space, judul layar, nama file |
| Teks sekunder | `#9A9A9A` | Situs, aktivitas terakhir, ukuran file |
| Aksen brand | `#5B5BD6` (navy "S") | Logo, tombol utama, Space Claude default |
| Aksen sukses / Aktif | `#22C55E` | Dot status Aktif |
| Aksen peringatan / Limit | `#F59E0B` | Dot + chip "Limit" |
| Aksen bahaya / Perlu Login | `#EF4444` | Dot + chip "Perlu Login", tombol hapus |
| Aksen beku | `#6B7280` | Dot + chip "Beku" |
| Radius kartu | 18dp | Semua kartu (ikut Harness) |
| Radius chip / tombol kecil | 999dp (pill) | Chip status, tombol aksi kecil |
| Radius tombol utama | 12dp | Tombol "Buka", "+ Space Baru", dsb. |
| Jarak tepi layar | 16dp kiri-kanan | Semua layar |
| Jarak antar kartu | 12dp | Grid dashboard, list file |
| Font | System (Roboto) — judul 20sp bold, nama Space 16sp semibold, sekunder 13sp, chip 12sp semibold | — |
| Ikon | Material Symbols, outline, 24dp | Semua aksi |

### Palet warna per Space (preset awal — membedakan akun sekilas, F4)

| Space | Warna | Alasan |
|---|---|---|
| Claude 1 | `#5B5BD6` navy | Brand, sudah di kode S0 |
| Claude 2 | `#0EA5E9` biru langit | — |
| Claude 3 | `#10B981` hijau | — |
| Claude 4 | `#F59E0B` amber | — |
| Claude 5 | `#EC4899` pink | — |
| Claude 6 | `#8B5CF6` ungu | — |
| AI Studio | `#EA4335` merah Google | Mudah dibedakan dari Claude |

Warna Space bisa diganti sendiri saat buat/edit Space (F0). Warna yang sama
dipakai di: dot + strip kiri kartu dashboard, top bar browser, dan FAB/menu.

---

## 2. Layar 1 — Dashboard Space (layar utama, F3)

**Tujuan:** dalam <5 detik pengguna tahu semua akun lagi ngapain (target T2 PRD).

Layout (atas → bawah):
1. **Header (tinggi 56dp):** logo "S" navy 32dp + teks "SenzSpace" 20sp bold
   di kiri; ikon kunci (gembok) + ikon pengaturan (gear) 24dp di kanan.
   Sub-teks di bawah judul: "7 Space · 3 Aktif · 1 Limit" (angka hidup,
   dihitung dari status nyata — contoh saja di sini).
2. **Grid kartu Space, 2 kolom**, kartu 18dp radius, `#111111`, padding 14dp:
   - Baris 1: dot warna Space 10dp + nama ("Claude 1") 16sp semibold.
   - Baris 2: situs utama 13sp abu ("claude.ai" / "aistudio.google.com").
   - Baris 3: chip status pill — "● Aktif" hijau / "● Limit" amber /
     "● Perlu Login" merah / "● Beku" abu. Chip ini WAJIB ada di tiap kartu.
   - Baris 4: "Tab terakhir: Gelas Terbalik Kertas" 12sp abu (satu baris,
     elipsis) + "3 file baru" 12sp bold putih bila ada file belum dilihat
     (badge angka di pojok kanan atas kartu, lingkaran merah 20dp).
   - Baris 5: "Terakhir aktif 12 mnt lalu" 12sp abu.
   - Tap kartu = buka Space itu (1 tap, F3). Long-press kartu = menu cepat:
     Rename · Ganti warna · Bekukan/Bangunkan · Arsipkan · Hapus.
   - Space yang sedang dibuka (ada proses hidup) = border warna Space 1,5dp
     + label kecil "DIBUKA" di pojok.
3. **Kartu "S1" (Space belum dibuka di slice berjalan):** sama, tapi chip
   "S1" abu + tombol "Segera" nonaktif — jujur di UI, bukan disembunyikan
   (pola yang sudah dipakai S0 untuk Space 3–7).
4. **Tombol "+ Space Baru"** — full-width, tinggi 52dp, radius 12dp,
   background `#5B5BD6`, teks putih 16sp semibold, di bawah grid.
   Tap → Layar 6 (sheet tambah Space). Kalau sudah 10 Space, tombol tetap
   tampil tapi nonaktif + teks berubah "Maksimal 10 Space" (F0: penolakan
   jelas, bukan crash).
5. **Bottom bar (tinggi 64dp, `#111111`):** 4 tab — Space (grid) ·
   Unduhan (badge jumlah antrean aktif) · File · Pengaturan. Tab aktif =
   ikon + label warna `#5B5BD6`, sisanya abu.

**State kosong (baru install, 0 Space — tidak mungkin di preset, tapi
setelah hapus semua):** ilustrasi "S" + teks "Belum ada Space" +
tombol "+ Space Baru".

---

## 3. Layar 2 — Browser per Space (F4 + F5)

**Tujuan:** pengguna tidak pernah salah akun saat mengetik prompt.

Layout (atas → bawah):
1. **Top bar Space (tinggi 52dp, background = warna Space, BUKAN hitam):**
   dot putih 10dp + "Claude 3" 16sp semibold putih + "● Aktif" 12sp putih
   70% di kanan. **Selalu tampil, tidak bisa disembunyikan** (F4).
   Di kanan top bar: ikon ganti Space (swap, 24dp putih) + ikon File (folder).
2. **Address bar (tinggi 48dp, `#111111`, radius 12dp, margin 12dp):**
   ikon gembok 16dp + URL/judul halaman 14sp + ikon reload 20dp di kanan.
   Tap → keyboard + bisa ketik URL/pencarian.
3. **WebView** — sisa layar. Halaman situs apa adanya.
4. **Toolbar bawah browser (tinggi 56dp, `#111111`):** Back · Forward ·
   Reload · Tab (badge jumlah tab profile ini) · Menu (⋮).
   Menu (⋮) berisi: Mode Desktop (toggle per situs) · Cari di halaman ·
   Bookmark · Riwayat profile ini · Bagikan · Tutup Space (freeze).
5. **Tab switcher (sheet dari bawah):** kartu-kartu tab profile ini saja
   (judul + favicon + tombol ✕), tombol "+ Tab Baru". Tab profile lain
   tidak muncul di sini (riwayat/tab per profile, F5).

**State khusus:**
- Halaman gagal muat → bukan blank putih: kartu error di tengah WebView
  ("Tidak bisa memuat halaman — <alasan>. [Muat ulang]") + Toast alasan
  (pola S0.1).
- Login Google/pop-up → dibuka di WebView yang sama (S0.1), top bar Space
  tetap tampil selama proses login supaya akun tujuan jelas.
- Space dibekukan (F8) → saat dibuka lagi, tampil snapshot ringan halaman
  terakhir + tombol "Bangunkan" → WebView hidup lagi, sesi tetap login.

---

## 4. Layar 3 — Unduhan (F6)

**Tujuan:** tiap unduhan ketahuan asal Space-nya dan tidak hilang senyap.

Layout:
1. Header: judul "Unduhan" 20sp bold + filter chip horizontal:
   Semua · Claude 1 · … · AI Studio (scroll horizontal).
2. **Antrean aktif** (di atas): kartu per unduhan berjalan — nama file,
   "Claude 3 · 4,2 MB / 12 MB", progress bar warna Space, tombol
   Pause / Batalkan. Selesai → kartu pindah ke riwayat + notifikasi.
3. **Riwayat per profile:** baris file — ikon jenis file (dokumen/audio/
   gambar) warna Space asal + nama file + "Claude 1 · 18:12 · 2,4 MB" +
   status ("Baru" badge merah muda bila belum dilihat).
   Tap baris → aksi: Buka · Kirim ke Inbox · Bagikan · Arsipkan · Hapus.
4. **Unduhan gagal/terputus:** kartu abu dengan tombol "Coba lagi" —
   tampil di antrean, bukan hilang (PRD hal. 8).
5. Aturan penamaan tampil di UI: file duplikat otomatis ber-akhiran
   "(2)", "(3)" — file lama tidak tertimpa (tulis di teks kecil bawah
   layar: "File duplikat diberi akhiran otomatis").

---

## 5. Layar 4 — File Manager (F7 — jantung aliran dokumen)

**Tujuan:** file hasil satu akun bisa dilampirkan ke akun lain ≤3 tap.

Layout:
1. Header: judul "File" 20sp bold + ikon cari (24dp) + ikon pilih banyak.
2. **Pencarian** di bawah header (radius 12dp, `#111111`): cari lintas
   semua folder + indeks (nama file, asal Space, episode).
3. **Bagian "Inbox Bersama"** — kartu khusus di paling atas, border
   `#5B5BD6` 1,5dp, ikon inbox: "Inbox Bersama · 5 file". Ini folder
   penampung file yang disiapkan untuk dilampirkan ke mana pun.
4. **Folder per Space** — list vertikal, tiap baris: ikon folder warna
   Space + nama ("Claude-1 / Downloads") + "12 file · 48 MB" + badge
   file baru. Tap → isi folder.
5. **Arsip Episode** — baris folder: "SSR-20261116-GELAS" dst. + tombol
   "Arsip baru".
6. **Isi folder** (tap folder): list file — nama, ukuran, waktu, status
   chip: "Baru" (merah muda) / "Sudah Dipakai" (hijau) / "Diarsipkan"
   (abu). Long-press / pilih banyak → bar aksi bawah: "Kirim ke Inbox" ·
   "Arsipkan ke Episode" · "Bagikan" · "Hapus".
7. **Alur lampirkan silang (≤3 tap, wajib mulus):** di dalam chat Claude
   (Layar 2) → Lampirkan → pilih "File Manager SenzSpace" → Inbox Bersama
   → file terlampir. Tanpa keluar app, tanpa file explorer eksternal.
   Uji terima (PRD hal. 8): unduh 1 file dari Claude 1 → pindah ke Inbox
   → lampirkan ke chat Claude 2 dan Claude 3 → arsipkan ke folder episode.

---

## 6. Layar 5 — Tambah / Edit Space (F0)

Sheet dari bawah (bukan layar penuh), radius atas 18dp, `#111111`:
1. Judul "Space Baru" / "Edit Space" 18sp bold.
2. Field Nama (contoh placeholder "Claude 7").
3. Field Situs utama (placeholder "https://claude.ai", dengan pilihan cepat:
   Claude / AI Studio / Situs lain).
4. **Pilih warna:** 7 lingkaran warna palet (36dp) + "Warna kustom" —
   yang terpilih ber-ring putih 2dp.
5. **Pilih ikon:** 8 ikon (huruf "C", "A", globe, chat, mic, gambar,
   dokumen, bintang) — 40dp per ikon.
6. Tombol utama "Buat Space" (`#5B5BD6`, 52dp). Di bawahnya teks kecil:
   "Space 8 dari 10".
7. **Edit Space** (dari long-press dashboard): field yang sama +
   tombol "Hapus Space" merah di bawah (→ dialog konfirmasi, Layar 7).
8. **Penolakan Space ke-11:** dialog, bukan crash — judul "Maksimal
   10 Space", isi "Hapus atau arsipkan satu Space dulu untuk membuat
   yang baru.", tombol "Mengerti".

---

## 7. Layar 6 — Pengaturan (D4 + F8 + backup)

List berkelompok (judul grup 13sp abu, baris 56dp, `#111111`):
1. **Keamanan (D4):** "Kunci aplikasi" — toggle + pilihan "PIN" /
   "Biometrik" (pengaktifan dipilih pengguna — satu keputusan terbuka
   di mockup ini). Sub-teks: "Minta kunci setiap buka app".
2. **Space:** "Batas tab per Space" (angka, default 5) · "Bekukan Space
   non-aktif otomatis" (toggle, default ON — F8 hemat RAM) ·
   "Tampilkan status Limit" (toggle).
3. **Unduhan & File:** "Folder ekspor" (D5 — ekspor sadar-pilihan, pilih
   folder tujuan) · "Tanya sebelum menimpa" (toggle, default ON).
4. **Data:** "Backup / Restore Space" (v2 — tampil tapi nonaktif dengan
   label "v2") · "Hapus data per Space" (pilih Space → konfirmasi) ·
   "Hapus semua data" (merah, konfirmasi ganda).
5. **Tentang:** "SenzSpace v0.1.0-s0.1" · "Batas jujur" (teks PRD: app
   mengelola sesi sah, bukan membobol limit/CAPTCHA) · tautan repo.

**Layar Kunci (D4, tampil sebelum dashboard bila kunci aktif):**
background `#000000`, logo "S" 64dp di tengah, teks "SenzSpace terkunci",
PIN pad 3×4 (tombol 72dp, angka 24sp) atau prompt biometrik — sesuai
pilihan pengguna. 3x salah → jeda 30 detik + teks "Coba lagi dalam 30 dtk".

---

## 8. Dialog Hapus Space (F0 — konfirmasi eksplisit)

Judul "Hapus Claude 3?", isi: "Sesi login, cookie, riwayat, dan unduhan
di Space ini ikut terhapus. File yang sudah dipindah ke Inbox Bersama
atau Arsip Episode tetap aman." + checkbox "Pindahkan dulu unduhan ke
Arsip" (default dicentang). Tombol: "Batal" (abu) · "Hapus" (merah).
Setelah hapus: slot kosong bisa dipakai Space baru (F0).

---

## 9. Yang TIDAK ada di mockup ini (sengaja, sesuai PRD)

- Split-view 2 profile, catatan per profile, pengingat limit pulih,
  template nama file, ekspor/impor Space → v1.1–v2 (PRD hal. 5).
- Otomasi klik/bot, pengakalan limit, sinkronisasi cloud, ekstensi
  umum, iOS/desktop → tidak dibangun (PRD hal. 5).
- Tab dihitung per profile, bukan global (F5).

---

## 10. Urutan bangun (setelah mockup ini disetujui)

1. Token + Dashboard (Layar 1) — S1.
2. Browser top bar permanen + address bar + toolbar (Layar 2) — S1.
3. Tambah/Edit/Hapus Space + penolakan ke-11 (Layar 5+8) — S1.
4. Unduhan (Layar 3) + File Manager + Inbox (Layar 4) — S2/S3.
5. Pengaturan + Kunci app (Layar 6 + layar kunci) — S4.
6. Freeze/bangunkan (F8) — menyertai tiap slice.

## 11. Keputusan terbuka (hanya 1)

- **D4 — cara kunci app:** PIN, biometrik, atau keduanya? Mockup di atas
  mendukung ketiganya; yang dipilih pengguna yang dibangun dulu.
  (PRD D4: "kunci app sejak MVP (pengaktifan dipilih pengguna)".)

---

*Perubahan dari S0 polos:* S0 sekarang = 2 tombol + teks + top bar warna
polos (`MainActivity.kt`, `BrowserActivity.kt`). Mockup ini mengganti
semuanya dengan 7 layar di atas — tapi TIDAK mengubah isolasi/proses
(ADR-01) dan TIDAK menyentuh login (S0.1 tetap berlaku).
