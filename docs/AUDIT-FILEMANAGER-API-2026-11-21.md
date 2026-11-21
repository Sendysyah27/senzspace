# Audit File Manager + API SenzSpace — bisakah File Manager in-app jadi sumber lampiran? (21 Nov 2026)

Perintah Sendy 21 Nov 10:11: "AUDIT APP NYA SAMA TOOLS YG BISA DI GUNAKAN PAKE API, file manajer di dalam app harusnya bisa di gunakan saat masukan lampiran ke space nya, jadi ngga hanya file manager handphone doang."

Metode: baca `AGENTS.md` + `docs/AUDIT-API-2026-11-20.md` dulu, lalu grep +
baca potongan `BrowserActivity.kt` (3.912 baris), `ApiServer.kt` (888),
`FileManagerActivity.kt` (1.105), `FileStore.kt` (378), `SpaceLauncher.kt`
(110), `AndroidManifest.xml`, `file_paths.xml`, `senz.py`, skill
`senzspace-control`. READ-ONLY: tidak ada kode yang diubah, tidak ada push,
tidak ada build.

Jawaban singkat: **hari ini TIDAK BISA — dan bukan karena satu bug, melainkan
lima celah yang saling mengunci (AKAR 7–11).** File Manager in-app itu sendiri
sudah bisa melihat SEMUA file yang dibutuhkan Space; yang tidak ada adalah
jembatan dari File Manager kembali ke chooser WebView di proses Space.

---

## 1. Alur lampiran HARI INI, langkah demi langkah

### (a) Via API — `/upload` + `/prompt --file` (jalur produksi)

1. VM: `senz.py upload --folder _inbox --file EPISODE_PROMPT.md`
   → `POST /upload` (`ApiServer.kt:308`): body = byte mentah file (bukan
   JSON), nama disaring (tanpa `/`, tanpa `..`), maks 100 MB, folder harus
   ada di allowlist `FILE_KEYS` (`ApiServer.kt:100-104`). File ditulis ke
   `FileStore.folderDir(ctx, "_inbox")` = `getExternalFilesDir("SenzSpace")/_inbox`.
2. VM: `senz.py prompt --slot 3 --text ... --file EPISODE_PROMPT.md`
   → `POST /prompt` (`ApiServer.kt:339`): tiap nama di `files[]` DICEK
   keberadaannya di `_inbox` via `resolveFile("_inbox", name)` — tidak
   ketemu → **404** (`"file _inbox tidak ketemu: <nama>"`). Payload
   dititipkan lewat intent extra (`SpaceLauncher.kt`, `EXTRA_API_PROMPT_TEXT`
   / `EXTRA_API_PROMPT_FILES` / `EXTRA_API_PROMPT_URL` / `EXTRA_API_PROMPT_PROJECT`).
3. `BrowserActivity.consumeApiPrompt` (`BrowserActivity.kt:1197-1228`):
   nama file di-resolve HANYA dari folder `_inbox` (baris 1210:
   `File(FileStore.folderDir(this, "_inbox"), n)`), disimpan ke
   `pendingPromptFiles`.
4. `maybeRunApiPrompt` → `runPromptDiagnose` (1316, composer ProseMirror
   ketemu?) → `runPromptAttach` (1341): JS klik tombol attach Claude
   (label EN+ID) → `waitPromptChooser` (1357) menunggu flag
   `promptChooserServed` → `onShowFileChooser` (1028): karena
   `pendingPromptFiles != null`, **langsung sodorkan URI FileProvider**
   dari file `_inbox` (1037-1064), set `promptChooserServed = true`,
   `return true` — **TANPA pemilih file, TANPA layar, TANPA File Manager**.
5. `runPromptCheckAttached` (1369) cek nama file muncul sebagai chip di
   form composer → `runPromptFill` (1386, `execCommand('insertText')`) →
   `runPromptSend` (1404, klik tombol kirim).

**Catatan jujur:** langkah 5 menulis status `"sent"` begitu tombol kirim
diklik dan tidak nonaktif — tanpa verifikasi pesan benar-benar keluar dari
composer. Ini sudah tercatat sebagai kandidat perbaikan v0.1.56 nomor 1
(di audit 20 Nov belum ada; muncul dari tes live 20 Nov 22:55). Bukan
temuan baru audit ini, disebut supaya tidak dikira tertutup.

### (b) Via layar — tombol attach di chat Claude (jalur manual)

1. User ketuk tombol attach di chat Claude → sheet Claude muncul
   ("Upload or add files" — posisi baris BERVARIASI per Space: y≈1645 di
   Space 1/2, y≈1880 di Space 3/4, selalu screenshot dulu).
2. Ketuk "Choose files or photos" → halaman memicu `<input type="file">` →
   WebView memanggil `onShowFileChooser` (`BrowserActivity.kt:1028`).
3. `pendingPromptFiles` = null (tidak ada payload API) → jatuh ke cabang
   fallback (1065-1100): intent `ACTION_GET_CONTENT` + `createChooser(...,
   "Pilih file")` → **`fileChooserLauncher`** (199-220) → **system file
   manager HP** (DocumentsUI / app Files / Google Drive / provider dokumen
   lain) tampil. Inilah satu-satunya titik di mana file manager HP muncul.
4. User pilih file → hasil kembali ke `fileChooserLauncher` (ClipData
   untuk banyak file, `data.data` untuk satu) → `fileChooserCallback
   .onReceiveValue(uris)` → Claude menerima URI → chip lampiran muncul.

**File apa yang BISA / TIDAK BISA dijangkau system file manager HP:**

| Sumber file | Bisa dipilih lewat system picker? | Bukti |
|---|---|---|
| Download publik HP (`Download/`, `Download/SenzSpace/` sesudah Ekspor) | ✅ BISA | MediaStore / folder publik, terlihat DocumentsUI |
| Google Drive / provider dokumen app lain | ✅ BISA | `ACTION_GET_CONTENT` + `CATEGORY_OPENABLE` |
| File kerja SenzSpace (`Android/data/id.senzspace.debug/files/SenzSpace/*`: folder per-Space, `_inbox`, `_arsip`) | ❌ TIDAK BISA | Scoped storage Android 11+: app Files tidak bisa masuk ke `Android/data/` app lain; SenzSpace TIDAK punya `DocumentsProvider` (grep: nol hasil di semua `.kt` + manifest) — satu-satunya provider adalah `FileProvider` (`AndroidManifest.xml:131-139`, `exported=false`, `grantUriPermissions=true`) yang hanya melayani URI per-file atas permintaan, bukan browsing. `file_paths.xml` hanya membuka `<external-files-path path="SenzSpace/" />` untuk FileProvider, bukan untuk picker sistem. |
| File status internal (`api-*.json` di `filesDir`) | ❌ TIDAK BISA (dan memang tidak boleh) | `filesDir` internal tidak terekspos ke mana pun |

Perilaku "system picker tidak bisa masuk `Android/data/` app lain" adalah
perilaku platform Android 11+ — **BELUM TERVERIFIKASI live di POVA 5**
(dari kode + platform; belum ada tes yang membuka picker lalu mencoba
menavigasi ke folder SenzSpace). Yang TERVERIFIKASI dari kode: tidak ada
`DocumentsProvider`, FileProvider `exported=false`, dan tidak ada jalur lain
yang mengekspos folder kerja ke picker sistem.

**Jalan memutar yang ADA hari ini (layar, manual, 2 langkah):**
- File Manager in-app → pilih file di folder Space → menu "Pindah…" →
  `_Inbox-Bersama` → lalu lampirkan lewat API `/prompt --file`
  (jalur (a)). Atau: File Manager → "Ekspor" → file tersalin ke publik
  `Download/SenzSpace` (`FileStore.exportToDownloads`, `FileStore.kt`)
  → baru bisa dipilih system picker di jalur (b). Keduanya butuh sentuhan
  layar + pindah folder dulu; tidak ada yang langsung.

---

## 2. File Manager in-app HARI INI — apa yang bisa, apa yang tidak

`FileManagerActivity.kt` (1.105 baris), jalan di **proses utama**
(manifest: tanpa `android:process`, `exported=false`, tanpa intent-filter).
Dibuka dari 2 tempat, keduanya `startActivity` polos tanpa hasil kembali:
- Beranda: `MainActivity.kt:277` (`navFiles` → `FileManagerActivity`, tanpa extra).
- Dari dalam Space: ikon Unduhan di top bar `BrowserActivity.kt:808-813`
  (dengan `EXTRA_FOLDER = dataDirectorySuffix`, jadi langsung membuka
  folder Space itu).

**Yang BISA dilihat:** semua folder `FileStore.FOLDERS` (`FileStore.kt:21-38`)
— `space1`–`space6`, `aistudio`, `space8`–`space10`, `_inbox`
(`_Inbox-Bersama`), `_arsip`, `_log-crash`, `_log-blob`, `_live` — plus
subfolder di dalamnya (drill-down, `listEntries`). Artinya: **file unduhan
tiap Space, file `_inbox`, dan file episode di `_arsip` SUDAH terlihat di
File Manager hari ini.** Yang TIDAK terlihat: publik `Download/SenzSpace`
(FM tidak punya root ke sana; ekspor hanya satu arah keluar) dan
`filesDir` internal (file status `api-*.json`).

**Yang BISA dilakukan** (menu per entri `showEntryMenu`, baris 703-740,
+ toolbar): Lihat (pratinjau gambar/teks/video di `FileViewerActivity`),
Buka (`ACTION_VIEW` via FileProvider), Bagikan (`ACTION_SEND` /
`ACTION_SEND_MULTIPLE` via FileProvider), Pindah… (dialog tujuan = semua
folder `FOLDERS`, termasuk `_inbox`), Potong/Salin/Tempel (clipboard),
Buat ZIP / Ekstrak di sini, Ganti nama, Detail, Ekspor (ke
`Download/SenzSpace` publik, MediaStore atau pohon SAF custom), Hapus.
Cari, urut (Terbaru/Terlama/Nama/Terbesar/Tipe), tampil list/grid,
tampilkan file tersembunyi.

**Batasannya (ini intinya):**
- **Tidak ada mode "pilih file".** Companion object hanya punya
  `EXTRA_FOLDER` (baris 41). Grep `setResult` / `RESULT_OK` / `EXTRA_PICK`
  di file ini = **NOL**. Tidak ada intent-filter `ACTION_GET_CONTENT` /
  `ACTION_OPEN_DOCUMENT` di manifest. File Manager adalah activity
  mandiri, bukan picker — ia tidak pernah mengembalikan apa pun ke
  pemanggil.
- **Tidak tahu siapa yang memanggilnya.** `EXTRA_FOLDER` hanya menentukan
  folder awal yang dibuka (tampilan), bukan "kembalikan file pilihan ke
  Space X". Tidak ada extra "tujuan Space" / "slot pemanggil".
- **Tidak bisa dipanggil dari `onShowFileChooser`.** Lihat AKAR 7–9.

---

## 3. Inventaris TOOLS VIA API — lengkap

Server: `ApiServer.kt`, HTTP stdlib, bind `127.0.0.1:19335`, Bearer token
per install (`ApiToken`, prefs `senzspace_ui`/`api_token`). Klien:
`senz.py` (API via `adb forward tcp:19335`; `procs`/`shot`/`type`/`tap`/
`key`/`home` tetap ADB perangkat — `type`/`tap`/`key`/`shot` resmi
debug-only sejak v0.1.55).

### Route API (semua terverifikasi ada di `ApiServer.kt`)

| Route | Baris | Fungsi soal file |
|---|---|---|
| `GET /ping` | 250 | versi/build app |
| `GET /status` | 258 | status + `overlay_permission` + semua Space |
| `GET /spaces` | 259 | daftar Space: `logged_in`, `limit`, `process_alive`, `files` (jumlah), `last_file`, `thumb_age_sec` |
| `POST /open` | 264 | buka Space (overlay, jujur visible/needs_tap) |
| `POST /upload` | 308 | tulis byte mentah → folder allowlist (`FILE_KEYS`: space1–6, aistudio, space8–10, `_inbox`, `_arsip`, `_log-blob`, `_log-crash`, `_live`), nama disaring, maks 100 MB |
| `POST /prompt` | 339 | kirim teks + `files[]` (HARUS sudah ada di `_inbox`, 404 bila tidak), `url` (claude.ai), `project` |
| `GET /prompt-status` | 421 | baca `api-prompt-<suffix>.json` |
| `POST /tts` | 440 | generate VO Space 7 (script/voice/model/speaker/url) |
| `GET /tts-status` | 510 | baca `api-tts-<suffix>.json` |
| `GET /page` | 529 | snapshot halaman + `ageSec` + `stale` |
| `POST /navigate` | 560 | muat URL di Space (host HARUS sama dengan situs Space) |
| `POST /freeze` | 616 | bekukan/aktifkan live Space |
| `GET /files` | 631 | list isi folder (`?space=<key>&path=<rel>`, subfolder didukung, traversal ditolak via canonical path) |
| `GET /download` | 648 | byte mentah satu file di folder kerja |
| `GET /thumb` | 665 | thumbnail live `_live/<suffix>.jpg` |

### Perintah `senz.py` (semua terverifikasi di `senz.py`)

`ping`, `status`, `spaces`, `procs` (ADB), `files --space --path`,
`download --space --path --out`, `thumb --space --out`, `open --slot/--name
--split --clear`, `upload --folder --file --name`, `prompt --slot/--name
--text/--text-file --file (bisa diulang) --url --project`,
`prompt-status`, `tts --slot/--name --script/--script-file --voice --model
--speaker --url`, `tts-status`, `page --slot/--name`, `navigate
--slot/--name --url`, `freeze --slot --on/--off`, `shot [out]` (ADB),
`home` (ADB), `type`/`tap`/`key` (ADB, debug-only).

### Matriks kemampuan app vs API

| Kemampuan | Via API? | Catatan |
|---|---|---|
| Buka Space | ✅ BISA | `/open` |
| Lihat status / daftar Space | ✅ BISA | `/status`, `/spaces` |
| Baca halaman (teks, limit, chats[]) | ✅ BISA | `/page` |
| Navigasi URL di Space | ✅ BISA | `/navigate` (host harus sama) |
| Kirim prompt teks | ✅ BISA | `/prompt` |
| Kirim prompt + lampiran | ⚠️ SEPARUH | hanya file yang SUDAH ada di `_inbox`; sumber file cuma `/upload` dari VM (jalur (a)) — tidak bisa ambil file dari folder Space lain, tidak bisa pilih dari FM |
| Upload file dari VM ke HP | ✅ BISA | `/upload` (folder allowlist, 100 MB) |
| List file di HP | ✅ BISA | `/files` (semua folder kerja + subfolder) |
| Unduh file dari HP ke VM | ✅ BISA | `/download` (byte mentah) |
| Thumbnail live | ✅ BISA | `/thumb` |
| Generate VO (TTS) | ✅ BISA | `/tts` (tap unduh v0.1.51 BELUM TERVERIFIKASI live — dari audit 20 Nov) |
| Freeze live | ✅ BISA | `/freeze` |
| Buka File Manager in-app | ❌ TIDAK BISA | tidak ada route; tidak ada intent ke FM dari `ApiServer` |
| Pilih file dari FM sebagai lampiran | ❌ TIDAK BISA | lihat AKAR 7–9 |
| Pindah / salin / hapus / ganti nama file di HP | ❌ TIDAK BISA | `FileStore.move/copy/delete/rename` hanya dipanggil dari FM (layar); tidak ada route API-nya |
| Ekspor file ke Download publik | ❌ TIDAK BISA | `exportToDownloads` hanya dari FM (layar) |
| Stage file Space → `_inbox` | ❌ TIDAK BISA (via API) | hanya manual lewat FM (layar) — ATAU dua hop lewat VM: `/download` lalu `/upload` ke `_inbox` (bisa hari ini, tapi lewat VM, bukan app) |
| Buka app sekali sesudah install | ⚠️ SEPARUH | BootReceiver v0.1.55 menutup kasus reboot; pasca-install masih butuh satu kali buka (`am startservice` BELUM TERVERIFIKASI — dari audit 20 Nov) |
| Login akun baru / verifikasi platform | ❌ TIDAK BISA | tetap tangan Sendy |
| Screenshot | ⚠️ SEPARUH | hanya via ADB `shot` (debug), bukan via API |

**Yang belum ada rutenya (ringkas):** buka File Manager; pilih file dari
File Manager; pindah/salin/hapus/ganti-nama file; ekspor ke Download;
stage file antar-folder; hapus file `_inbox` yang sudah tidak dipakai.

---

## 4. GAP UTAMA — kenapa File Manager TIDAK BISA jadi sumber lampiran hari ini

Melanjutkan penomoran audit 20 Nov (AKAR 1–6 sudah dipakai):

### AKAR 7 — `onShowFileChooser` hanya punya DUA cabang, dan File Manager bukan salah satunya
`BrowserActivity.kt:1028-1100` (WebView utama). Cabang 1 (1037-1064): bila
`pendingPromptFiles != null` → sodorkan URI FileProvider dari `_inbox`,
tanpa UI. Cabang 2 (1065-1100): bila tidak → `ACTION_GET_CONTENT` +
`createChooser(..., "Pilih file")` → system picker HP. **Tidak ada cabang
ketiga yang membuka `FileManagerActivity`.** (WebView pop-up punya
`onShowFileChooser` sendiri di baris 629 yang SELALU system picker —
tapi pop-up hanya dipakai login OAuth, bukan chat; dampak kecil, dicatat
saja.)

### AKAR 8 — File Manager tidak punya kontrak "kembalikan URI"
`FileManagerActivity.kt`: companion hanya `EXTRA_FOLDER` (baris 41);
grep `setResult` / `RESULT_OK` = **nol**; manifest (`AndroidManifest.xml:
105-107`): `exported=false`, tanpa intent-filter; kedua call site
(`MainActivity.kt:277`, `BrowserActivity.kt:808-813`) memakai
`startActivity` polos — bukan `ActivityResultLauncher`, bukan
`startActivityForResult`. Secara arsitektur Android, activity yang tidak
pernah `setResult` tidak bisa dipakai sebagai picker, seberapa pun
lengkapnya daftar file yang ia tampilkan.

### AKAR 9 — Pemisahan proses memutus callback chooser
File Manager jalan di **proses utama** (manifest, tanpa `android:process`).
WebView + `fileChooserCallback` (`ValueCallback<Array<Uri>>`,
`BrowserActivity.kt:194`) hidup di proses **`:spaceN`** (mis.
`Space1Activity`, `android:process=":space1"`, manifest baris 42-47;
dan seterusnya sampai `:space10` + `:aistudio`). Callback itu objek
in-memory di proses Space — tidak bisa diserahkan ke activity proses
lain. Satu-satunya jembatan antar-proses yang ada hari ini adalah intent
extra (`SpaceLauncher.kt`, pola UX 31: "static companion tidak bisa
dipakai antar-proses, kirim lewat intent extra") + file di external-files
dir + file status JSON di `filesDir`. File Manager tidak terhubung ke
satupun dari ketiganya sebagai pengirim hasil.

### AKAR 10 — Sumber lampiran API dikunci ke `_inbox` saja
`consumeApiPrompt` (`BrowserActivity.kt:1210`) resolve nama file HANYA
dari `FileStore.folderDir(this, "_inbox")`; `POST /prompt`
(`ApiServer.kt:339-360`) menolak file di luar `_inbox` dengan 404.
Akibatnya file unduhan Space (folder `spaceN`) TIDAK BISA dilampirkan
langsung — harus dipindah manual ke `_inbox` dulu. Penguncian ini
disengaja sebagai staging (mencegah lampiran Space A bocor ke Space B
tanpa jejak), tapi File Manager — yang justru melihat semua folder —
tidak terhubung ke staging itu. **Inilah celah yang paling dekat dengan
perintah Sendy:** FM sudah bisa memindahkan file ke `_inbox` (menu
"Pindah…"), tapi tidak ada yang memintanya melakukannya dalam rangka
lampiran.

### AKAR 11 — Scoped storage menutup pintu belakang
Bahkan jalur layar pun tidak bisa melampirkan file kerja SenzSpace tanpa
ekspor/pindah dulu (lihat tabel §1b): system picker tidak bisa masuk ke
`Android/data/id.senzspace.debug/` (Android 11+), app tidak punya
`DocumentsProvider`, dan `FileProvider` (`AndroidManifest.xml:131-139`,
`file_paths.xml`) hanya melayani URI per-file atas permintaan eksplisit,
bukan browsing. Jadi "pilih file kerja dari picker HP" bukan sekadar
belum dibangun — **secara platform tidak mungkin tanpa DocumentsProvider
atau tanpa FM sebagai picker.** (Bagian platform: BELUM TERVERIFIKASI
live di POVA 5; bagian kode: terverifikasi.)

---

## 5. OPSI PERBAIKAN

### Opsi A — File Manager jadi picker, pilih → salin ke `_inbox` → sodorkan ke chooser (DIREKOMENDASIKAN)

**Cara kerja (2 kalimat):** `onShowFileChooser` membuka File Manager
dalam mode pilih; user ketuk satu file di folder mana pun, File Manager
menyalin file itu ke `_inbox` lalu mengembalikan path-nya; BrowserActivity
mengubah path itu jadi URI FileProvider dan menyerahkannya ke
`fileChooserCallback` — persis seperti jalur `_inbox` API hari ini.

**File + fungsi yang diubah:**
- `FileManagerActivity.kt`: tambah `EXTRA_PICK_MODE` di companion (di
  samping `EXTRA_FOLDER`, baris 41); di mode pilih, ketuk file →
  `FileStore.copy(ctx, file, "_inbox")` → `setResult(RESULT_OK, intent
  berisi path hasil)` + `finish()`; tambah tombol "Pilih dari HP" yang
  memicu `ACTION_GET_CONTENT` (supaya file di luar SenzSpace tetap bisa
  dipilih — system picker tetap ada sebagai jalan kedua, bukan
  satu-satunya).
- `BrowserActivity.kt`: tambah `ActivityResultLauncher` baru untuk FM
  (pola sama seperti `fileChooserLauncher`, baris 199-220); cabang
  fallback `onShowFileChooser` (1065-1100) diganti: buka FM mode pilih
  (bukan langsung `ACTION_GET_CONTENT`); hasil launcher → `FileProvider
  .getUriForFile(...)` + `grantUriPermission` (pola yang sama seperti
  cabang `_inbox`, baris 1040-1052) → `fileChooserCallback
  .onReceiveValue([uri])`; bila hasil batal → `onReceiveValue(null)`
  (pola yang sama seperti launcher sekarang).
- `AndroidManifest.xml` + `file_paths.xml`: **tidak perlu diubah** — FM
  sudah `exported=false` (satu app), dan seluruh root `SenzSpace/` sudah
  terekspos ke FileProvider.
- `ApiServer.kt` / `senz.py`: **tidak perlu diubah** — jalur API tidak
  tersentuh.

**Risiko:**
- Isolasi Space: file dari folder Space A bisa dilampirkan ke chat Space B
  — TAPI hanya bila user sendiri yang memilihnya di FM, dan file itu
  disalin dulu ke `_inbox` (jejak staging tetap ada, sama seperti
  "Pindah…" manual hari ini). Aturan "lampiran API otomatis HANYA dari
  `_inbox`" tidak berubah. Yang harus dijaga: mode pilih JANGAN
  mengembalikan path asli folder Space (itu akan melewati `_inbox` dan
  menghilangkan jejak) — selalu salin ke `_inbox` dulu.
- Proses: FM (proses utama) → hasil kembali ke BrowserActivity (proses
  `:spaceN`) lewat `startActivityForResult` — dimediasi sistem, pola
  ini standar Android; tapi bila FM dimatikan RAM saat memilih, hasil
  batal → callback harus dapat `null` (sudah ditangani pola launcher).
- Duplikasi storage: salinan `_inbox` menggandakan file besar (video) —
  beri batas (mis. tolak > 100 MB seperti `/upload`) atau beri
  peringatan; file `_inbox` lama menumpuk — butuh sapu berkala (lihat
  temuan lain).
- WebView membaca URI `content://` dari FileProvider app sendiri — pola
  ini SUDAH terbukti di cabang `_inbox` API (dipakai produksi sejak
  v0.1.38), jadi risiko teknisnya rendah.

### Opsi B — Route API baru `POST /stage`: pindah/salin file antar-folder via API (pelengkap, bukan pengganti A)

**Cara kerja (2 kalimat):** tambah route yang menyalin/memindah satu file
dari folder kerja mana pun ke `_inbox`, lalu `/prompt --file` dipakai
seperti biasa — staging yang hari ini hanya bisa lewat layar FM jadi
bisa lewat API.

**File + fungsi yang diubah:**
- `ApiServer.kt`: route baru `POST /stage` (di samping `/upload`,
  baris 308) — pakai `resolveFile` yang sama (canonical path, tolak
  `../`) + `FileStore.copy`/`move`; `toFolder` dikunci HANYA `"_inbox"`.
- `senz.py`: subcommand baru `stage --space space3 --path <file> [--move]`.
- `SKILL.md` (`senzspace-control`): dokumentasikan `stage` sebelum
  `prompt`.

**Risiko:** rendah — reuse `resolveFile` + `FileStore.copy`; tetap satu
arah ke `_inbox` (tidak ada jalan pintas antar-Space tanpa lewat
`_inbox`). **Kekurangan:** tidak memenuhi perintah Sendy secara harfiah
— File Manager tetap tidak dipakai sebagai sumber lampiran. Nilai
praktisnya: menutup gap "file Space tidak bisa dilampirkan" dari sisi
API, dan bisa dibangun duluan karena kecil. Direkomendasikan sebagai
pendamping Opsi A, bukan pengganti.

### Opsi C — `DocumentsProvider`: folder kerja SenzSpace terlihat oleh system picker (DIPERTIMBANGKAN, TIDAK DIREKOMENDASIKAN)

**Cara kerja (2 kalimat):** implementasikan `DocumentsProvider` dengan
root `SenzSpace/` sehingga system picker HP bisa menelusuri folder kerja
app — jalur layar (b) langsung bisa memilih file Space tanpa FM.

**File yang diubah:** kelas `DocumentsProvider` baru + entri
`<provider>` di manifest + `file_paths`-setara untuk dokumen.
**Risiko (alasan ditolak):** paling invasif; sekali root terekspos,
**app lain** yang memakai SAF bisa menelusuri folder kerja bila user
memberi akses — permukaan keamanan membesar justru di folder yang
menyimpan unduhan episode + log; tetap tidak memberi UX "pilih dari File
Manager SenzSpace" yang diminta Sendy; dan tidak membantu jalur API sama
sekali. Hanya dipertimbangkan bila Sendy secara eksplisit mau system
picker sebagai jalan utama.

**Rekomendasi: bangun Opsi A dulu (FM picker + salin ke `_inbox`), lalu
Opsi B (`/stage`) sebagai pelengkap API.** Keduanya menjaga invarian
"lampiran selalu lewat `_inbox`" — tidak ada lampiran Space A ke Space B
tanpa jejak staging.

---

## 6. Temuan lain (di luar file manager)

1. **`POST /upload` menimpa tanpa `uniqueFile`.** `ApiServer.kt:308` menulis
   `dest.writeBytes(bodyBytes)` langsung — upload nama yang sama menimpa
   file lama di `_inbox` diam-diam. Di tempat lain (`saveDownloadedBytes`,
   `FileStore.copy/move`) bentrok nama ditangani dengan `(1)`, `(2)`
   (`FileStore.uniqueFile`). Risiko: file episode tertimpa bila nama sama.
   Perbaikan kecil: pakai `uniqueFile` dan kembalikan nama final di
   balasan (atau tolak bila sudah ada — pilih satu, jangan diam-diam).
2. **File `_inbox` menumpuk, tidak ada sapu.** Tidak ada route hapus dan
   tidak ada pembersihan otomatis; FM hapus hanya lewat layar. Bila Opsi
   A dibangun (tiap pilih = satu salinan `_inbox`), penumpukan makin
   cepat — pasangkan dengan sapu berkala (mis. hapus salinan `_inbox`
   yang tidak dipakai prompt > 7 hari) atau setidaknya route hapus.
3. **Keheningan `consumeApiPrompt` bila file hilang di tengah jalan.**
   `BrowserActivity.kt:1209-1213`: nama file yang tidak ada di `_inbox`
   dibuang diam-diam (`mapNotNull`) — bila file terhapus antara validasi
   `POST /prompt` (404 bila tidak ada) dan eksekusi, daftar lampiran
   menyusut tanpa pesan, dan `runPromptCheckAttached` membandingkan
   dengan daftar yang SUDAH menyusut itu — jadi bisa "berhasil" dengan
   lampiran lebih sedikit dari yang diminta. Perbaikan kecil: bandingkan
   dengan jumlah nama yang diminta, bukan jumlah file yang ketemu.
4. **Pop-up `onShowFileChooser` selalu system picker**
   (`BrowserActivity.kt:629`). Pop-up hanya dipakai login OAuth (bukan
   chat), jadi dampak rendah — tapi bila suatu hari halaman di pop-up
   butuh lampiran, ia tidak punya jalur `_inbox`. Catat saja, bukan
   prioritas.
5. **`GET /files` + `/download` sudah menutup kebutuhan baca VM.**
   Allowlist `FILE_KEYS` mencakup `_live` (thumbnail) dan `_log-*`
   (log diagnosis) — kebutuhan debug VM sudah terpenuhi; yang hilang
   hanya operasi tulis/pindah (lihat §3).

---

## Status

Audit selesai 21 Nov 2026 (read-only, tanpa ubah kode). Belum ada build
yang diusulkan dari audit ini — Opsi A + B menunggu Gas Sendy. Yang masih
BELUM TERVERIFIKASI live (ditandai di atas): perilaku system picker
terhadap `Android/data/` di POVA 5; `am startservice` pasca-install;
tap unduh VO v0.1.51 (dari audit 20 Nov).
