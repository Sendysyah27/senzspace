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
- UX 33 (17 Nov, v0.1.33): (a) ROTASI ngikutin sistem — ganti
  SCREEN_ORIENTATION_SENSOR (v0.1.31, abaikan toggle sistem) jadi
  UNSPECIFIED di 7 activity; (b) LIVE vs AKTIF — picker Pindah
  Space + Split hanya tampilkan Space LIVE (thumbnail segar <60 dtk
  + tidak beku; SpacePickerDialog.isLive/liveOnly), fallback ke
  Aktif bila tidak ada yang live; (c) AREA SENTUH minimal 44dp
  untuk tombol aksi di baris (titik-tiga FM).
- BLOB 34 (17 Nov, v0.1.34): unduhan blob AI Studio intermiten —
  log: dl iframes=2 hit=false -> 6 dtk -> xhr-error status=0,
  TRAP cuma 2x seharian. Fix: (a) trap SAPU iframe yang sudah ada
  saat dipasang (trapNode(documentElement)); (b) RELAY postMessage
  — get-blob diteruskan ke iframe anak, blob-data diteruskan ke
  parent (iframe bersarang); (c) ANTREAN PENDING — bila cache
  kosong saat diminta, jangan balas kosong; balas sesudah
  FileReader selesai (__senzPendingReqs/__senzFlushPending);
  (d) RETRY kirim ulang di 3 dtk + fallback XHR 6 dtk -> 15 dtk;
  (e) DIAG 'core: req ...' / 'core: balas ...' di log. JS-check
  (node --check) WAJIB tiap ubah string JS — ekstraksi harus
  mulai dari baris 'val js =' DI DALAM fungsi yang sama (bukan
  kemunculan pertama di file).
- LIVE 35 (19 Nov, v0.1.35): WebView HITAM 60-90 dtk setelah idle
  lama + tile bohong (skeleton selamanya / snapshot basi dibilang
  "live baru saja") — POVA 5, 8 Space, RAM free ~412MB. Akar:
  TIDAK ADA onRenderProcessGone (renderer dibunuh sistem -> hitam
  selamanya; draw() tile mengulang frame lama). Fix: (a)
  onRenderProcessGone di WebViewClient UTAMA -> toast + reload
  (log ke _log-crash/renderer-gone.txt); (b) HEARTBEAT per Space —
  file _live/<suffix>-status.txt "alive|dom|pixels" (tulis max 1/5
  dtk): detak 2 dtk, DOM berubah (onChanged), piksel berubah
  (sameAs vs tangkapan sebelumnya); (c) TILE JUJUR — proses mati
  (detak >30 dtk) -> sub "Space tidak berjalan, tap untuk
  membuka" + gambar diredupkan 0.4 + tanpa badge LIVE; gambar basi
  (DOM <60 dtk tapi piksel >2 mnt) -> sub "gambar tidak update";
  thumbnail korup (decode gagal) -> file dihapus biar bisa
  ditangkap ulang; (d) SUBTITLE Beranda "N dari M live" (dulu
  selalu "Semua live"). Timer Claude nyangkut = sisi Claude —
  ikut ke-reset oleh reload pemulihan renderer; tidak ada kode
  khusus untuk itu.
- KONTROL MCP (19 Nov, Jhon): tool `senz.py` di `~/workspace/custom-connectors/custom.senzspace/` + skill `senzspace-control` — kontrol app dari VM lewat ADB relay POVA (status/spaces/procs/shot terverifikasi live; open/type/tap/key belum dites live). File unduhan + thumbnail + _status + _log TIDAK BISA dibaca dari VM (scoped storage) — butuh perubahan app bila mau.
- API 36 (19 Nov, v0.1.36): KONTROL LEWAT API, bukan ADB mentah
  (perintah Sendy "pake jalur API"). ApiServer HTTP minimal stdlib
  (ServerSocket 127.0.0.1:19335, TANPA TLS — loopback saja, JANGAN
  bind 0.0.0.0) + ApiService foreground START_STICKY (notif LOW
  "SenzSpace API aktif"; tanpa izin notifikasi jalan tanpa
  foreground — JANGAN panggil startForegroundService dalam keadaan
  itu, nanti dituduh tidak kunjung foreground). Auth: Bearer token
  acak per install (ApiToken, prefs senzspace_ui kunci api_token;
  tampil + regenerate di Pengaturan → API Kontrol; Jhon baca via
  run-as; token TIDAK PERNAH di repo/chat/memori). Rute: GET
  /ping /status /spaces /files /download /thumb; POST /open
  /freeze. /files + /download: kunci folder allowlist + canonical
  path WAJIB di dalam folder kerja (tolak ../). open/freeze pakai
  SpaceLauncher (satu tempat bangun Intent — Beranda + API
  identik). Baca header byte-per-byte dari stream mentah
  (BufferedReader bisa menelan byte body). senz.py v2 ngomong ke
  API via `adb forward tcp:19335 tcp:19335`; procs/shot/type/tap/
  key/home tetap ADB (level perangkat).
- API 47 (20 Nov, v0.1.47): KLIK KOTAK + ISI ULANG TERTUNDA
  (Gas Sendy 15:50). Tes v0.1.46 (15:43): klik Style LULUS, tapi
  3 cara isi gagal; dump menjawab dua hal: (a) probe eq=true =
  setter JALAN, framework yang menghapus nilai sesudah JS call
  selesai; (b) active=BUTTON = focus() tidak memindahkan fokus
  dari tombol Style, jadi commitText + KeyEvent tidak sampai.
  Fix: (a) ttsClickStyleFieldJs — KLIK kotaknya beneran (bukan
  cuma focus()), lalu cek document.activeElement===kotak
  ('focus'/'nofocus'/'0', dicatat di status percobaan 2 dan 3);
  (b) ttsFillJs voice: ISI ULANG TERTUNDA — setTimeout 300 ms
  cari ULANG kotaknya (query segar) lalu set nilai lagi via
  setter + event input, kalahkan tick reset / ganti elemen;
  verifikasi baca-balik percobaan 1 ditunda 600 ms supaya isi
  ulang sempat jalan.
- API 46 (20 Nov, v0.1.46): KLIK STYLE DIULANG + SELEKTOR LUAS
  (Gas Sendy 15:23). Tes v0.1.45 (15:21): GAGAL +30 dtk —
  "tombol Style tidak ketemu" — padahal screenshot HP
  membuktikan tombolnya ADA di editor (dialog fresh: Speaker
  Fola, naskah kosong). Dugaan: eksekutor menembak terlalu dini
  (halaman masih loading/transisi) ATAU kontrol Style di dialog
  baru bukan <button>. Fix: (a) klik Style DIULANG — poll
  10x1 dtk (waitTtsStyleButton) sebelum gagal, seperti tunggu
  kolom; (b) ttsClickStyleJs tiga lapis — <button> dulu (hasil
  'btn'), lalu [role="button"] ('role'), lalu elemen daun teks
  persis "Style" ('leaf'); status mencatat lapis yang kena;
  (c) gagal total = runTtsDumpButtons: maks 15 label tombol
  terlihat + origin+path URL (tanpa query) ke detail gagal.
  Tutup/buka menu di verifikasi tetap pakai ttsClickStyleJs yang
  sama (hasilnya diabaikan, urutan lapis tidak berubah).
- API 45 (20 Nov, v0.1.45): KETIK KEYEVENT + PROBE SATU-TUGAS
  (Gas Sendy 15:03). Tes v0.1.44 (15:00): GAGAL +30 dtk — dump
  BEKERJA: kotaknya TEXTAREA biasa (placeholder + aria-label
  "Describe the voice style"), terlihat, tapi len=0 sesudah JS
  fill DAN commitText. Dugaan: commitText tidak sampai ke halaman
  (InputConnection manual mati bila WebView tidak pegang fokus
  window) dan/atau framework me-reset nilai async. Fix: (a)
  percobaan 3 = KETIK LEWAT TOMBOL KEYBOARD: fokus kolom lalu
  dispatchKeyEvent DOWN+UP per karakter dari Kotlin (huruf,
  angka, spasi, koma, titik, strip; shift untuk huruf besar;
  25 ms antar event) — tanpa IME, tanpa koneksi input, seperti
  keyboard fisik; (b) probe satu-tugas di runTtsDumpStyle: set
  'PROBE123' via setter lalu baca balik DALAM JS call yang sama
  — eq=true berarti setter jalan dan nilai dihapus framework
  secara async, eq=false berarti setter dicegat langsung; (c)
  dump tambah activeElement + disabled + readOnly per kolom.
  Alur percobaan kini 1 (JS) -> 2 (commitText) -> 3 (KeyEvent)
  -> dump + gagal jujur.
- API 44 (20 Nov, v0.1.44): KETIK BENERAN + DUMP BUKTI (Gas Sendy
  14:46, lanjut perbaikan Style). Tes v0.1.43 (14:43-14:44):
  GAGAL CEPAT +30 dtk — verifikasi BEKERJA (menangkap Style
  kosong dalam 30 dtk, VO dibatalkan, tidak generate suara
  default seperti v0.1.41/42), tapi fill-nya TIDAK PERNAH
  menempel: setter + event 'input'/'change' sintetis ditolak
  framework AI Studio (nilai hilang sebelum Run; kotak naskah
  biasa masuk, kotak Style tidak). Fix: (a) ttsFillJs untuk voice
  — setter + event SELALU dijalankan walau execCommand mengaku
  sukses, event pertama InputEvent ber-data (inputType
  insertText, data = teks) karena sebagian framework menghitung
  nilai baru dari event.data dan Event polos bisa mengosongkan
  model; (b) percobaan 2 = KETIK BENERAN lewat jalur IME:
  fokus kolom (ttsFocusStyleJs: focus + select-all) lalu
  InputConnection.commitText dari Kotlin
  (webView.onCreateInputConnection(EditorInfo())) — event
  input-nya trusted seperti ketikan pengguna; (c) gagal total =
  runTtsDumpStyle: dump anatomi maks 8 kolom input terlihat
  (tag | placeholder | aria-label | panjang isi | hasProbe
  'Very bright') ke detail gagal supaya perbaikan berikutnya
  berbasis bukti. Verifikasi baca-balik 2x (sebelum tutup +
  tutup-buka) tetap berlaku untuk kedua percobaan.
- API 43 (20 Nov, v0.1.43): STYLE MENEMPEL + UNDUH TEPAT WAKTU
  (perintah Sendy, screenshot 14:15: "lu ngga masukin prompt
  [format Riko] ini bro, sebelum run harusnya lu masukin ini").
  Tes v0.1.42 (14:12-14:13): OVERLAY LULUS (cold-start, visible
  via overlay, eksekutor jalan penuh <1 mnt) tapi DUA cacat:
  (a) Style: fill lama mengembalikan len>0 tapi kotak "Describe
  the voice style" TETAP KOSONG di screenshot (kena kolom lain
  ATAU framework menghapus nilai saat menu ditutup) — audio 1:25
  ter-generate TANPA format Riko; (b) Unduh: waitTtsDone terpicu
  prematur oleh <audio> di +30 dtk (audio baru siap ~2 mnt),
  runTtsDownload klik tombol Download PERTAMA di DOM (dialog
  LAMA 19 Nov) — NOL aktivitas blob di blob-debug.txt. Fix:
  Style = tunggu kolom muncul (poll 10x1 dtk, placeholder persis
  "describe the voice style") -> isi (matcher voice utamakan
  placeholder persis + event input DAN change) -> BACA BALIK
  berisi "Very bright" -> tutup menu -> buka lagi -> BACA BALIK
  lagi; gagal verifikasi = ulang 1x, lalu failed jujur (jangan
  generate dengan gaya salah). Unduh = waitTtsDone syarat KETAT
  (tombol Download ADA DAN audio readyState==4 + duration>0);
  runTtsDownload klik tombol TERAKHIR (dialog terbaru); detail
  gagal mencatat tombol yang diklik.
- API 42 (20 Nov, v0.1.42): TANPA KETUK — BENERAN (perintah Sendy,
  voice call 13:42: "Space-nya Jhon yang buka, browser-nya Jhon
  yang jalanin, semuanya lewat API"). Akar insiden 00:28-00:41:
  proses :aistudio HIDUP tapi activity-nya sudah dihancurkan
  Android (tekanan RAM) — intent /tts tidak pernah sampai,
  status diam "queued" 12 menit. "Process alive" BUKAN "activity
  hidup". Fix: (a) IZIN SYSTEM_ALERT_WINDOW ("Tampil di atas
  aplikasi lain", manifest + minta SEKALI di Beranda saat kunci
  app mati + baris status/tombol di Pengaturan → API Kontrol) —
  pemegang izin ini DIKECUALIKAN dari BAL, jadi launch Space
  dari background boleh menarik activity ke depan tanpa ketuk;
  /open, /prompt, /tts: visible = appHasVisibleWindow() ATAU
  overlay (balasan "via": "overlay" bila overlay yang
  menghidupkan); /status tambah field overlay_permission supaya
  Jhon bisa cek dari VM. (b) GAGAL-JUJUR 60 DETIK: sesudah /prompt
  dan /tts menulis "queued", thread pengawas (watchPickup) tidur
  60 dtk lalu cek file status — bila masih "queued" dengan
  timestamp YANG SAMA (BrowserActivity tidak pernah jalan),
  tulis failed + alasan jelas (izin overlay belum diberikan,
  atau activity dimatikan RAM walau izin sudah ada). Bila
  activity akhirnya jalan telat, tulisannya (running/done)
  menimpa failed itu — tidak ada konflik. (c) Izin overlay
  SATU KALI — sesudah diberikan, tugas API jalan sendiri;
  penolakan tidak mengganggu app.
  JEBAKAN PATCH (20 Nov, CI #61 GAGAL karena ini): baris
  `open(f,"w").write(open(f).read())` MENGOSONGKAN file — open
  mode "w" memotong file DULU, baru read baca file kosong.
  ApiServer.kt jadi 0 byte, CI gagal "Unresolved reference:
  ApiServer/ApiToken" di semua file lain. JANGAN PERNAH pakai
  pola itu; patch script tidak boleh menulis ulang file yang
  baru dibacanya dengan cara itu. SEBELUM push: cek `wc -c`
  semua file yang disentuh patch — file 0 byte = STOP, pulihkan
  dari commit GitHub terakhir dulu, baru push ulang. (Kedua:
  MainActivity.kt tidak mengimpor android.widget.Button —
  dialog baru v0.1.42 pakai Button() tanpa import; CI yang
  menangkapnya.) (Ketiga, CI #62: di dalam lambda `Thread({...})`
  (Runnable SAM), `return` polos TIDAK BOLEH — Kotlin menolak
  "'return' is not allowed here"; pakai `return@Thread`.)
- API 41 (19 Nov, v0.1.41): TTS ALUR ASLI (screenshot Sendy 23:28-23:32).
  (a) Urutan eksekusi: Run settings (ikon slider kanan atas) ->
  kartu model aktif -> tab Starred -> entri "Gemini 3.8 Flash Lite
  TTS" (default model) -> "Create new dialog" -> tombol "Style"
  -> isi kotak "Describe the voice style" (format Riko) -> dropdown
  "Speaker 1" -> nama voice "Sadaltager" (default speaker,
  Favorites) -> isi naskah (kotak besar, placeholder "Type '<'
  for tags") -> Run -> tunggu unduh -> blob trap simpan wav.
  (b) DUA PEMBEDA YANG DULU TERTUKAR: `voice` param = STYLE
  (teks deskripsi suara, default format Riko); `speaker` param =
  NAMA voice (default Sadaltager). Model BUKAN dipilih lewat
  hamburger kiri ("Open navigation menu" tidak berisi entri TTS
  — itu penyebab failed 23:21; alur hamburger DIHAPUS).
  (c) Diagnose state-aware: ada "Speaker 1" + kolom = editor
  dialog (langsung Style); ada "Create new dialog" = halaman
  utama TTS (klik itu); selain itu = alur pilih model. Kolom
  isian: textarea + [contenteditable] apa pun + input +
  [role=textbox]; naskah = kolom TERLUAS (area), bukan rows.
  Gagal di satu langkah -> status failed membawa DUMP inventaris
  halaman / isi menu. (d) Pelajaran 23:11-23:21: eksekusi
  background JALAN bila proses Space sudah hangat (status
  bergerak queued->running di menit ke-7,5) — tapi COLD START
  dari background gagal total (proses :aistudio tetap mati 12
  mnt + /open 45 dtk). Jadi "tanpa tap" berlaku untuk proses
  yang sudah hangat; Space yang mati total butuh satu ketuk
  pemanasan dulu. (e) STOP 23:24 ("lama lu bro, stop aja kalo lu
  bingung") — patch 41 pertama DIBATALKAN (push dimatikan, repo
  tetap v0.1.40); 23:35 Sendy kirim 8 screenshot alur asli +
  Gas ulang — v0.1.41 ini dibangun dari screenshot itu.
- API 40 (19 Nov, v0.1.40): NAVIGASI OTOMATIS + TANPA TAP.
  (a) TTS: bila diagnose 0 kolom isian, eksekutor TIDAK langsung
  gagal — buka menu "Open navigation menu", klik entri model
  starred (bila diminta) atau entri TTS (Text to speech / TTS /
  Generate speech), diagnose ulang (max 3x). Kolom isian yang
  dihitung: textarea + [contenteditable=true] + input[type=text];
  isi mendukung contenteditable (textContent + input event).
  Bukti butuh: 22:24 Space 7 mendarat di halaman preview suara
  (tombol "Preview dialog audio", "Play preview of The Grizzled
  Detective" dst, 0 textarea) — halaman TTS satu navigasi jauhnya.
  (b) PROMPT: param `project` (nama, mis. "SSR CONTENT STUDIO") —
  eksekutor klik tombol kiri atas (sidebar: aria-label Toggle/Open
  sidebar / Menu) -> klik nama Project (cocok persis dulu, lalu
  mengandung) -> tunggu URL /project/ -> klik "New chat" -> alur
  lama (diagnose -> attach -> isi -> kirim). User TIDAK perlu kirim
  URL Project. (c) TANPA TAP: eksekusi prompt/TTS jalan di
  background — activity kebuka tak tampil, WebView tetap load +
  JS tetap jalan (TERBUKTI diagnose TTS 22:24 jalan tanpa tap).
  Balasan 202 note-nya "lagi jalan di background - lu tetap di
  Muse aja". Notifikasi "Buka <nama>" tetap ada sebagai opsi
  intip, BUKAN syarat. JANGAN lagi suruh user ketuk notifikasi
  untuk menjalankan tugas.
- API 39 (19 Nov, v0.1.39): TTS API — POST /tts {slot=7, script,
  voice?, model?, url?} (script max 50.000 char, voice default =
  format Riko pilihan Sendy, url HARUS https://aistudio.google.com/...)
  + GET /tts-status?slot=. HANYA atas perintah eksplisit per
  generate — TIDAK ADA auto-generate. Payload lewat intent extra
  (EXTRA_API_TTS_*, lihat UX 31) + status file internal
  api-tts-<suffix>.json. BrowserActivity eksekusi di WebView Space 7:
  diagnose (textarea + tombol Run/Download + model aktif) -> menu
  garis tiga + pilih model starred (bila diminta + beda) -> isi format
  suara -> isi naskah -> Run -> tunggu tombol unduh/audio max 10 mnt
  -> klik unduh (blob trap simpan wav ke folder aistudio) -> status
  done + nama file. Halaman TTS bisa ber-URL root "/" (lihat
  AiStudioActivity) — eksekutor TIDAK mensyaratkan path, hanya host
  aistudio.google.com + ada kolom naskah. Bila kolom gaya suara
  tidak ketemu, run DIBATALKAN (jangan generate dengan suara salah).
  JS-check (node --check) WAJIB tiap ubah string JS.
- API 38 (19 Nov, v0.1.38): PROMPT API — POST /upload (byte mentah
  file ke folder kerja, default _inbox, maks 100MB, nama disaring)
  + POST /prompt {slot, text, files?, url?} (teks max 50.000 char,
  files HARUS sudah ada di _inbox, url HARUS https://claude.ai/...)
  + GET /prompt-status?slot=. HANYA atas perintah eksplisit per
  kirim — TIDAK ADA auto-kirim. Payload lewat intent extra
  (EXTRA_API_PROMPT_*, lihat UX 31) + status file internal
  api-prompt-<suffix>.json (proses Space tulis, ApiServer baca).
  BrowserActivity eksekusi di WebView-nya sendiri: diagnose
  composer (ProseMirror) -> klik attach -> chooser disodori URI
  FileProvider file _inbox (TANPA pemilih file) -> cek chip ->
  insertText -> klik Send. JS-check (node --check) WAJIB tiap ubah
  string JS — ekstraksi dari baris 'val js =' DI DALAM fungsi yang
  sama. Selektor DOM Claude defensif (coba beberapa aria-label);
  diagnose mengembalikan daftar aria-label tombol form composer
  supaya log status memberi tahu nama tombol yang sebenarnya bila
  selektor meleset (Claude ganti UI). /spaces tambah last_file per
  Space (bantu pilih Space kosong).
- API 37 (19 Nov, v0.1.37): /open JUJUR soal tampil — v0.1.36
  jawab "opened" padahal Space tidak muncul saat app di
  background (BAL: service background tidak boleh menarik
  activity ke depan; task Space visible=false). Fix:
  appHasVisibleWindow() (proses app importance FOREGROUND 100 /
  VISIBLE 200 — foreground service 125 TIDAK dihitung) +
  background = launch tetap jalan (pemanasan) + notifikasi
  "Buka <nama>" (PendingIntent dari notifikasi dikecualikan
  dari BAL; id 1300+slot, channel ApiService, autoCancel) +
  balas 202 needs_tap; foreground = 200 visible:true.
  dumpsys topResumedActivity BISA BASI (pernah nunjuk Facebook
  padahal user di Muse) — kebenaran foreground = screenshot.
