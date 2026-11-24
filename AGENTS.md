# SenzSpace — AGENTS.md

Baca PRD v1 (goal Senz Browser, 15 halaman, D1–D6 TERKUNCI) sebelum mengubah kode.
Jangan cuma diagnosa dari kodenya.

## Aturan

- Repo publik hanya berisi konten TEKNIS. DILARANG di repo ini: hasil tes
  per versi, narasi pembaruan, nama orang, referensi diskusi/chat, cerita
  insiden. Changelog per versi = perubahan teknis saja, tanpa hasil tes.
- Slice kecil, masing-masing bisa dibuktikan di perangkat. Tidak ada klaim
  "selesai" tanpa bukti yang bisa dipegang sendiri.
- Rust core adalah sumber kebenaran aturan Space (maks 10, preset 9).
  Jangan duplikasi aturan itu di Kotlin tanpa komentar yang menunjuk ke Rust.
- Jangan simpan password / token / sesi sebagai teks biasa. Jangan masukkan
  secret apa pun ke repo publik ini.
- Batas jujur: app mengelola sesi sah, bukan membobol limit / CAPTCHA platform.
- Panduan pengujian teknis: docs/PANDUAN-PENGEMBANG.md.
- Tiap kirim APK: tegaskan INSTALL MENIMPA, JANGAN UNINSTALL
  (uninstall = semua sesi login hilang, harus login ulang semua).
- REPO ALL-USERS: deskripsi repo = user-facing (ID), topik 8, README produk
  + LICENSE MIT, panduan tes/dev pindah ke docs/PANDUAN-PENGEMBANG.md.
  RELEASE ASSET TIDAK BISA DIUNGGAH DARI VM: bearer surrogate
  tidak di-swap di uploads.github.com (401 Bad credentials) — APK rilis
  masuk tree repo di releases/SenzSpace-vX.Y.Z.apk + link
  raw.githubusercontent. PERINGATAN push-tree: mengganti SELURUH tree —
  file yang ditambah via put-file langsung di GitHub AKAN TERHAPUS oleh
  push-tree berikutnya kalau tidak ada juga di tree lokal.
- SHORTCUT UNDUHAN TERBARU — ATURAN TETAP: TIAP RILIS WAJIB: (1) APK rilis
  disalin ke releases/SenzSpace-vX.Y.Z.apk DAN timpa
  releases/SenzSpace-latest.apk dengan file yang sama; (2) tombol "Unduh
  versi terbaru" di README selalu menaut ke
  raw.githubusercontent.../releases/SenzSpace-latest.apk; (3) GitHub
  Release tag vX.Y.Z dengan body menautkan APK terbaru + APK versi itu.
  APK versi lama TIDAK dihapus (rollback). JANGAN taruh file kerja di root
  repo (.gitignore diperketat *.log, *_url.txt + pengecualian
  !releases/*.apk). Screenshot produk di docs/screenshots/.
- UX 31 (17 Nov, v0.1.31): (1) JANGAN pakai static companion
  untuk handoff antar-proses — static hidup per-proses, Space
  jalan di proses berbeda; kirim lewat intent extra + onNewIntent.
  (2) Trap injeksi blob HANYA untuk host di daftar putih
  TRAP_HOSTS (claude.ai + subdomain, aistudio.google.com +
  subdomain) — situs lain dimuat 100% utuh seperti Chrome
  (login ChatGPT sempat rusak karena trap).
  (3) Bersihkan data Space: SELALU REORDER_TO_FRONT +
  onNewIntent + periksa EXTRA_CLEAR_SUFFIX di BrowserActivity —
  JANGAN PERNAH CLEAR_TOP (menutup activity Space lain di task
  yang sama).
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
- KONTROL MCP (19 Nov): tool `senz.py` di
  `~/workspace/custom-connectors/custom.senzspace/` + skill
  `senzspace-control` — kontrol app dari VM lewat ADB relay
  (status/spaces/procs/shot; open/type/tap/key debug-only).
  File unduhan + thumbnail + _status + _log TIDAK BISA dibaca
  dari VM (scoped storage) — butuh perubahan app bila mau.
- API 36 (19 Nov, v0.1.36): KONTROL LEWAT API, bukan ADB mentah.
  ApiServer HTTP minimal stdlib (ServerSocket 127.0.0.1:19335,
  TANPA TLS — loopback saja, JANGAN bind 0.0.0.0) + ApiService
  foreground START_STICKY (notif LOW "SenzSpace API aktif"; tanpa
  izin notifikasi jalan tanpa foreground — JANGAN panggil
  startForegroundService dalam keadaan itu, nanti dituduh tidak
  kunjung foreground). Auth: Bearer token acak per install
  (ApiToken, prefs senzspace_ui kunci api_token; tampil +
  regenerate di Pengaturan → API Kontrol; token dibaca via
  run-as; token TIDAK PERNAH di repo/chat/memori). Rute: GET
  /ping /status /spaces /files /download /thumb; POST /open
  /freeze. /files + /download: kunci folder allowlist + canonical
  path WAJIB di dalam folder kerja (tolak ../). open/freeze pakai
  SpaceLauncher (satu tempat bangun Intent — Beranda + API
  identik). Baca header byte-per-byte dari stream mentah
  (BufferedReader bisa menelan byte body). senz.py v2 ngomong ke
  API via `adb forward tcp:19335 tcp:19335`; procs/shot/type/tap/
  key/home tetap ADB (level perangkat).
- API 58 (21 Nov, v0.1.58): 2 SPACE YOUTUBE STUDIO SLOT 9-10.
  Slot 9 = "YT Studio" (#A78BFA), slot 10 = "YT Studio 2" (#22D3EE),
  URL keduanya https://studio.youtube.com. Semua 10 slot kini
  terisi — tambah Space baru butuh perubahan aturan D1 (maks 10),
  bukan sekadar preset baru; preset kini 9. Mekanisme: registry di
  perangkat sudah berisi 8 entri, jadi ubah seed() saja tidak
  berpengaruh pada install yang sudah ada — SpaceRegistry.load()
  migrasi satu-kali yang idempotent: tambah entri YT Studio HANYA
  bila slot 9/10 kosong, tidak pernah menimpa entri yang sudah ada;
  simpan hanya bila ada yang ditambahkan. seed() ikut diperluas
  dengan kedua entri yang sama (fresh install langsung mendapat
  keduanya). Warna = 2 warna PALETTE pertama yang belum terpakai
  (slot 8 menduplikasi #E5B567 milik slot 4 — bukan warna kosong).
  Tanpa izin baru, tanpa ubah manifest (activity + service
  :space9/:space10 sudah ada), tanpa sentuh prompt/TTS/chooser/
  file manager/TRAP_HOSTS/BootReceiver/rute API — Space YT Studio
  hanya browsing biasa (eksekutor prompt khusus Claude, TTS khusus
  AI Studio). Halaman YT Studio mengarah ke sign-in Google sampai
  akun masuk di Space itu.
- API 59 (21 Nov, v0.1.59): NAMA ASISTEN DIHAPUS DARI STRING UI.
  Semua kemunculan nama asisten di string yang tampil ke pengguna
  diganti padanan netral atau dihapus bila tidak perlu: judul seksi
  API Kontrol di Pengaturan, penjelasan server API lokal, toast
  sesudah token baru dibuat ("perbarui token di klien API"), teks
  izin overlay di Pengaturan + dialog Beranda, deskripsi channel
  notifikasi API (ApiService + ApiServer), nilai meta-data
  foreground service di manifest, dua alasan gagal-jujur
  watchPickup ("lalu kirim ulang tugasnya"), dan dua note status
  antrean /prompt dan /tts (sebelumnya bersuara orang pertama).
  Tanpa ubah perilaku:
  token, izin overlay, channel notifikasi, dan alur watchPickup
  tidak berubah; tidak ada perubahan fungsional lain.
- API 60 (21 Nov, v0.1.60): EKSEKUTOR YOUTUBE STUDIO. POST /studio
  {slot, action, file?, title?, description?, schedule?} + GET
  /studio-status?slot=. Aturan host: HANYA studio.youtube.com —
  slot diterima bila entry.url Space itu berhost studio.youtube.com
  (berbasis host, BUKAN nomor slot); host lain 400. Aksi: (a)
  dashboard — ringkasan channel dari teks halaman (nama channel,
  subscriber, views 28 hari, watch time 28 hari, 5 video teratas +
  views; field yang tidak terurai = string kosong, bukan gagal);
  (b) videos — daftar 10 video pertama halaman Content (judul,
  URL edit, visibilitas, tanggal, views; baris dari link judul
  a[href="/video/<id>/edit"], views = angka pertama sesudah
  tanggal); (c) upload — file HARUS sudah di _inbox (stage via
  /stage atau /upload), title wajib (maks 100 karakter),
  description maks 5000, schedule "now" atau "YYYY-MM-DD HH:MM"
  zona waktu akun Studio. Rantai upload: diagnose (tombol Create +
  sudah login — halaman sign-in Google = gagal) -> klik Create ->
  klik "Upload videos" -> chooser sodorkan file _inbox (cabang
  onShowFileChooser yang sama seperti prompt; dialog kebuka tanpa
  flag tersaji = gagal) -> isi judul (baca-balik: isi HARUS diawali
  judul) + deskripsi -> audience "No, it's not made for kids" ->
  tunggu pemrosesan selesai (maks 30 menit, progres tiap 1 menit)
  -> Next x3 (Video elements -> Checks -> Visibility; tombol Next
  nonaktif ditunggu sampai aktif, maks 3 menit untuk Checks) ->
  Visibility: Public + Publish, atau Schedule + isi tanggal/jam
  -> Schedule/Publish -> VERIFIKASI video muncul di daftar Content
  (cocok judul persis) baru status done + videoUrl (klik tombol
  saja bukan bukti). Status ke api-studio-<suffix>.json (filesDir
  internal, dibaca /studio-status); watchPickup 60 dtk untuk
  panggilan awal (pola prompt/TTS); upload jalan async. Helper
  klik: studioClickThenVerify — klik JS -> tunggu 1200 ms ->
  verifikasi keadaan berikut (poll 3x1 dtk) -> bila gagal dan
  tombol satu-kali (Schedule/Publish saja), tap beneran sekali di
  rect tombol (dispatchTouchEvent, pola v0.1.51) lalu verifikasi
  lagi; tombol nonaktif dilewati (kliknya tidak berbuat apa-apa).
  Pencarian tombol dialog-scope (ytcp-uploads-dialog) untuk tombol
  di dalam dialog upload supaya tidak kena tombol halaman di
  belakang modal. Selektor dwi-bahasa EN+ID (pola v0.1.55); format
  tanggal mengikuti bahasa UI akun (EN MM/DD/YYYY, ID DD/MM/YYYY),
  jam EN dicoba 24-jam dulu lalu 12-jam ("2:30 PM"). Verifikasi
  langkah Next pakai isi khas langkah tujuan (daftar langkah di
  sidebar menampilkan semua nama langkah, jadi nama langkah saja
  bukan bukti). File: BrowserActivity.kt (eksekutor + status),
  ApiServer.kt (rute /studio + /studio-status + watchPickup),
  MainActivity.kt (EXTRA_API_STUDIO_*), SpaceLauncher.kt (intent
  + extra studio), senz.py (studio + studio-status). Tanpa ubah
  perilaku: eksekutor prompt/TTS, FM picker, BootReceiver,
  SpaceRegistry, dan rute lain tidak disentuh.
- API 61 (21 Nov, v0.1.61): EKSEKUTOR STUDIO + PROMPT LEBIH JUJUR.
  (a) Upload Studio: tunggu dialog 30 dtk (dulu 15 dtk); dialog
  diterima bila ada kolom judul ATAU heading "Upload videos" /
  "Unggah video" di dalam dialog; nama file yang dipilih dicek
  kemunculannya di teks halaman bila memungkinkan. Bila dialog
  tidak kebuka, detail gagal membawa bukti: 300 karakter pertama
  teks halaman + label tombol + ada/tidaknya dialog, file-input,
  dan flag chooser tersaji. Verifikasi akhir tidak berubah: video
  harus muncul di daftar Content (cocok judul persis) baru status
  done + videoUrl.
  (b) Prompt fill: kolom chat dibaca dulu sebelum diisi. Kosong ->
  langsung isi. Sama persis dengan teks pending (sisa percobaan
  sebelumnya) -> hapus dulu (select-all + delete, fallback
  textContent='' + event input) lalu isi. Berisi teks LAIN -> gagal
  jujur "kolom chat berisi teks lain" + 80 karakter pertama — teks
  itu tidak disentuh dan tidak dikirim.
  (c) Prompt send: klik Send saja bukan bukti terkirim. Sesudah
  klik, verifikasi ~10 dtk (poll 5x2 dtk): kolom harus kosong DAN
  (teks tampil sebagai pesan user ATAU tombol Stop ada). Kolom
  masih berisi teks -> gagal "pesan tidak terkirim (masih di kolom
  chat)". Tombol kirim tidak ada karena Claude sedang menjawab ->
  gagal "Claude sedang menjawab (pesan masih di kolom chat)".
  Status "sent" tidak pernah ditulis sebelum verifikasi lolos.
  (d) Limit reset: pola "returns at H:MM AM/PM" dan "until H:MM
  AM/PM" ditambahkan di pageSnapshotJs dan pengamat limit live —
  halaman upsell memakai frasa itu, bukan "resets at".
  (e) Dasbor Studio: periksa teks khas dasbor ("Current
  subscribers" / "Channel dashboard" + angka) sebelum mengurai;
  hasil urai pertama yang kosong semua diulang sekali sesudah
  ~10 dtk; bila tetap kosong, status tetap "done" (field kosong
  bukan gagal) tapi detail menyatakan eksplisit bacaannya kosong
  dan halaman mungkin belum lengkap. Tanpa ubah perilaku:
  eksekutor TTS, FM picker, BootReceiver, SpaceRegistry, dan rute
  lain tidak disentuh.
- API 62 (21 Nov, v0.1.62): SINGKIRKAN INTERSTITIAL STUDIO.
  Sesudah install, WebView Studio memuat halaman "Try out the
  YouTube Studio app" (teks "CONTINUE TO STUDIO") di root maupun
  halaman Content — dasbor tidak tampil, tombol Create tidak ada,
  dan diagnose menemukan daftar tombol KOSONG (elemennya bukan
  <button>). Fix: (a) runStudioDismissInterstitial berjalan
  SEBELUM alur upload, dashboard, dan videos — probe JS deteksi
  interstitial dari teks halaman ("try out the youtube studio
  app" / "continue to studio" / "lanjutkan ke studio" / "coba
  aplikasi youtube studio"); (b) klik elemen berteks persis
  "CONTINUE TO STUDIO" / "LANJUTKAN KE STUDIO" (cari di
  button/a/div/span/[role]/[onclick], pilih yang paling dalam;
  "GET THE APP" tidak pernah diklik); (c) poll ~10 dtk sampai
  dasbor muncul (tombol Create ada ATAU teks "Channel dashboard" /
  "Dasbor channel" ada); (d) gagal jujur dengan bukti bila
  interstitial tetap ada — 300 karakter pertama teks halaman +
  dump kontrol (helper failStudioWithEvidence, pola
  runStudioWaitDialog). Tanpa interstitial, probe balik 'clean'
  dan alur lanjut tanpa jeda. Tanpa ubah perilaku: rantai upload
  (tunggu dialog 30 dtk, verifikasi Content), parse dasbor,
  eksekutor prompt/TTS, dan rute lain tidak disentuh.
- API 63 (21 Nov, v0.1.63): KLIK "SELECT FILES" SENDIRI DI DIALOG
  UPLOAD. Klik menu "Upload videos" membuka dialog TANPA memicu
  file chooser — chooser hanya dipicu klik "Select files" di dalam
  dialog — jadi flag studioChooserServed tidak pernah terisi dan
  tunggu dialog gagal dengan label "dialog sudah kebuka dari sesi
  sebelumnya", padahal dialog itu baru dibuka run ini sendiri
  (label itu menyesatkan). Fix: (a) runStudioWaitDialog tidak lagi
  gagal saat dialog kebuka tanpa flag — eksekutor klik "Select
  files" / "Pilih file" sendiri (sekali saja per run, flag
  studioSelectFilesClicked — klik kedua jatuh ke pemilih file di
  layar karena file _inbox sudah terpakai), lewat JS
  studioSelectFilesJs yang mencari di
  button/a/div/span/[role="button"]/[role="link"]/[onclick] dalam
  ytcp-uploads-dialog, teks persis, elemen paling dalam (pola
  interstitial v0.1.62); tombol tidak ketemu -> gagal jujur dengan
  bukti teks halaman (failStudioWithEvidence); (b) sesudah klik,
  poll sampai nama file muncul di teks halaman (cek lama diperluas:
  nama file dicari juga di nilai kolom input/textarea — judul
  terisi nama file sesudah dipilih, dan nilai input tidak masuk
  innerText); (c) bila anggaran 30 dtk habis dan chooser tidak
  pernah tersaji -> gagal jujur dengan bukti; bila chooser sudah
  tersaji tapi nama file tidak tampil -> tetap lanjut isi judul
  (isi judul punya verifikasi baca-balik sendiri). Pemulihan ini
  berlaku sama untuk dialog yang baru dibuka run ini maupun dialog
  sisa sesi sebelumnya — keduanya tidak dibedakan. Tanpa ubah
  perilaku: dismissal interstitial, tunggu dialog 30 dtk, isi
  judul/deskripsi, audience, visibilitas, dan verifikasi Content
  tidak disentuh.
- API 64 (21 Nov, v0.1.64): TAP BENERAN "SELECT FILES" DI DIALOG
  UPLOAD. Klik JS .click() pada "Select files" tidak memicu file
  chooser — onShowFileChooser tidak pernah terpanggil dan run gagal
  "file tidak tersaji ke chooser sesudah klik 'Select files'",
  padahal dialog kebuka dan tombolnya ada. Akar yang diduga: Chrome
  butuh gestur terpercaya (user activation) untuk membuka file
  chooser; klik sintetis dari evaluateJavascript tidak terpercaya
  dan diabaikan — preseden yang sama: tombol Download TTS v0.1.51
  (JS .click() nol aktivitas blob di semua run, tap beneran yang
  jalan). Fix: (a) studioSelectFilesJs tidak lagi klik — ia
  mengembalikan rect titik tengah tombol (CSS px): cari elemen
  berteks persis yang sama seperti v0.1.63 (button/a/div/span/
  [role]/[onclick] dalam ytcp-uploads-dialog, paling dalam),
  scrollIntoView ke tengah viewport dulu, balik 'x|y' atau '0';
  (b) runStudioClickSelectFiles mengetuk rect itu beneran via
  studioTapAt (dispatchTouchEvent DOWN+UP, CSS px x webView.scale
  — helper yang sama dipakai Schedule/Publish v0.1.60, pola
  ttsTapAt v0.1.48; TANPA koordinat mati, rect dibaca live dari
  DOM tiap tap); (c) guard sekali-per-run (studioSelectFilesClicked,
  reset di consumeApiStudio) dan poll sesudah tap tidak berubah:
  tunggu 2 dtk lalu poll chooser tersaji + nama file; bila chooser
  sudah tersaji tapi nama tidak tampil, tetap lanjut isi judul
  (verifikasi baca-balik judul yang menjaga). Tanpa ubah perilaku:
  dismissal interstitial, tunggu dialog 30 dtk, isi judul/
  deskripsi, audience, visibilitas, dan verifikasi Content
  tidak disentuh.
- API 65 (21 Nov, v0.1.65): FALLBACK TAP BENERAN UNTUK TOMBOL
  CREATE. Klik JS pada tombol Create tidak membuka menu — teks
  "Upload videos" tidak pernah muncul di body sesudah jeda 1200
  ms + verifikasi 3x1 dtk — padahal klik JS yang sama bekerja di
  run sebelumnya, jadi penyebabnya diduga kondisi halaman sesudah
  install/reload (menu belum terpasang / popup menutupi), bukan
  aturan deterministik. Fix: pemanggilan studioClickThenVerify
  untuk tombol Create di runStudioUploadDiagnose kini
  allowTap=true (dulu false) — bila klik JS gagal membuka menu,
  helper mengetuk tombol SEKALI beneran di rect live-nya
  (studioTapAt, CSS px x webView.scale; tanpa koordinat mati)
  lalu verifikasi lagi 2x. Jalur fallback itu sudah ada di helper
  dan dipakai Schedule/Publish sejak v0.1.60; komentar studioTapAt
  diperbarui (Create kini termasuk pemakai, tap tetap sekali
  karena tap kedua bisa menutup menu). verifyJs, jeda 1200 ms,
  ronde verifikasi 3x, dan pesan gagal tidak berubah. Tanpa ubah
  perilaku: dismissal interstitial, tap beneran "Select files",
  tunggu dialog 30 dtk, isi judul/deskripsi, audience,
  visibilitas, dan verifikasi Content tidak disentuh.
- API 66 (21 Nov, v0.1.66): SELIDIKI KEGAGALAN MENU CREATE.
  Klik JS tombol Create + tap beneran (v0.1.65) sama-sama tidak
  membuka menu — teks "Upload videos" tidak pernah muncul, padahal
  dasbor sehat (tombol Create ada di header, tanpa interstitial)
  dan klik JS yang sama bekerja di run sebelumnya. Dua akar yang
  diduga: (a) selektor menemukan tombol Create yang salah
  (duplikat tersembunyi / di luar viewport — filter lama cuma
  offsetParent); (b) menu kebuka lambat sesudah klik JS, lalu tap
  fallback menutupnya lagi. Fix: (a) studioButtonJs kini memilih
  kandidat yang BENAR-BENAR terlihat — offsetParent non-null DAN
  rect di dalam viewport DAN computed style visibility/display DAN
  opacity > 0; bila ada beberapa kandidat, yang terlihat
  didahulukan (urutan DOM di antara yang terlihat); bila tidak ada
  yang terlihat, balik "0|" (tidak ketemu) — berlaku untuk klik
  dan untuk rect tap (elemen yang sama); (b) studioClickThenVerify
  kini terima verifyIntervalMs (default 1000 ms — pemanggil lain
  tidak berubah); langkah Create memakai 2000 ms, jadi jendela
  verifikasi sesudah klik JS jadi ~7,2 dtk (1200 ms + 3x2 dtk)
  sebelum tap fallback jalan; (c) SEBELUM tap, keadaan
  diverifikasi sekali lagi — bila menu sudah kebuka, tap dilewati
  dan alur lanjut (menutup celah race antara verifikasi terakhir
  dan tap); (d) onFail kini membawa jejak percobaan (klik JS ok /
  hasil tiap verifikasi / rect tap) dan langkah Create gagal
  lewat failStudioCreateMenu — probe studioCreateProbeJs
  (ditemukan / terlihat / tag / rect / dalam-viewport / menu) +
  300 karakter pertama teks halaman + dump kontrol (pola
  failStudioWithEvidence). Tanpa ubah perilaku: dismissal
  interstitial, tap beneran "Select files", tunggu dialog 30 dtk,
  isi judul/deskripsi, audience, visibilitas, dan verifikasi
  Content tidak disentuh; "Upload videos" tetap allowTap=false.
- API 57 (21 Nov, v0.1.57): AUTOSTART SESUDAH INSTALL/UPDATE.
  BootReceiver kini menangani MY_PACKAGE_REPLACED di samping
  BOOT_COMPLETED — cabang yang sama persis (ApiToken.isEnabled →
  ApiService.start, yang sudah menangani cabang izin notifikasi
  sendiri per API 36); manifest tambah action MY_PACKAGE_REPLACED
  di intent-filter BootReceiver. TIDAK perlu izin baru (aksi ini
  hanya dikirim sistem ke app sendiri). BOOT_COMPLETED tidak
  berubah. Perilaku: sesudah install/update, API hidup sendiri
  tanpa buka app (ping OK dalam beberapa menit).
- API 56 (21 Nov, v0.1.56): FILE MANAGER JADI PICKER + /stage + /delete.
  Isi: (a) OPSI A — FileManagerActivity mode pilih (EXTRA_PICK_MODE
  di samping EXTRA_FOLDER): ketuk file → FileStore.copy ke _inbox →
  setResult EXTRA_PICK_RESULT (path HASIL, JANGAN PERNAH path asli
  folder Space) + finish(); tombol "Pilih dari HP"
  (ACTION_GET_CONTENT, jalan kedua — isi disalin ke _inbox, nama
  unik bila bentrok); tolak file >100 MB seperti /upload.
  BrowserActivity: fmPickLauncher baru (pola fileChooserLauncher,
  yang lama DIHAPUS — kedua cabang chooser kini lewat FM); cabang
  fallback onShowFileChooser utama (1065-1100) DAN pop-up OAuth
  (629) diganti launchFmPicker() — pop-up ikut cabang yang sama
  karena berbagi launcher + callback, perubahannya kecil. Hasil
  launcher → FileProvider.getUriForFile + grantUriPermission
  (pola cabang _inbox 1040-1052) → fileChooserCallback.onReceiveValue;
  batal → onReceiveValue(null). Manifest + file_paths.xml TIDAK
  diubah. (b) OPSI B — POST /stage (di samping /upload): body JSON
  {space, path, move?} → resolveFile yang sama (canonical, tolak
  ../) + FileStore.copy/move; toFolder DIKUNCI "_inbox"; file yang
  sudah di _inbox dikembalikan langsung (already_staged). senz.py:
  `stage --space KEY --path REL [--move]`. (c) POST /delete:
  {space, path} → resolveFile + guard root folder TIDAK BOLEH
  dihapus; senz.py: `delete --space KEY --path REL`. TIDAK ADA sapu
  otomatis (hapus file user diam-diam di luar lingkup — keputusan
  dicatat). (d) /upload nama bentrok → FileStore.uniqueFile,
  balasan "uploaded" = nama FINAL (dulu menimpa diam-diam).
  (e) consumeApiPrompt: file lampiran hilang antara validasi POST
  /prompt dan eksekusi → status FAILED dengan nama file yang hilang
  (dulu mapNotNull membuang diam-diam, kirim bisa "berhasil" dengan
  lampiran kurang). Catatan: pop-up chooser sudah ikut FM; tanpa
  sapu otomatis _inbox (sengaja); verifikasi "sent" beneran +
  tunggu modal limit + /prompt tanpa project buka chat baru tetap
  kandidat terpisah, belum dibangun.
- API 55 (20 Nov, v0.1.55): DWI-BAHASA + BOOTRECEIVER. Isi: semua
  selektor UI kini EN + ID, dicocokkan tanpa peduli huruf
  besar-kecil — sidebar (buka/tutup sidebar, bilah sisi, buka menu
  navigasi), New chat / Chat baru, Send / Kirim pesan, attach
  (Lampirkan/Tambah file/Unggah file + regex lampir/unggah), Run /
  Jalankan, Download / Unduh, Starred / Berbintang, Create new dialog
  / Buat dialog baru, Style / Gaya, Speaker 1 / Pembicara 1, placeholder
  Style "gaya suara", Run settings fallback "pengaturan run",
  generating "Berhenti". Label "Menu" DIHAPUS dari semua daftar toggle
  (snapshot + runPromptProject) — di UI Indonesia ia kena tombol ⋮
  chat; fallback substring cuma "sidebar"/"bilah". Snapshot: field
  ke-10 sbClicked (label yang diklik / "fallback:<label>"), field
  ke-11 uiLang ("id"/"en" dari teks halaman); bila tidak ada tombol
  yang cocok, __senzSbTried TIDAK diset (coba lagi tick berikut).
  BootReceiver baru (BOOT_COMPLETED + izin RECEIVE_BOOT_COMPLETED) —
  ApiService start sesudah HP reboot tanpa buka app.
- API 54 (20 Nov, v0.1.54): BUKA SIDEBAR DULU SEBELUM KUMPULKAN
  LINK CHAT. chats[] kosong walau snapshot segar — asumsi "link
  sidebar tetap ada di DOM selagi ketutup" gugur. Isi:
  pageSnapshotJs kini membuka sidebar dulu bila link 0 dan belum
  pernah dicoba di halaman ini — klik tombol sidebar (label
  "Toggle/Open sidebar", "Open navigation menu", "Menu", atau
  mengandung "sidebar"/"menu"; selektor yang sama dengan navigasi
  Project v0.1.40, hanya tombol terlihat); link dibaca di snapshot
  berikut (~20 dtk), lalu sidebar ditutup lagi ("Close sidebar").
  Hasil bagus disimpan di window.__senzSbChats dan dipakai lagi
  bila kumpul berikut kosong (file jangan ketimpa daftar kosong);
  sesudah sekali berhasil (__senzSbDone) sidebar tidak dibuka lagi.
  URL dinormalisasi dari /chat/<id> di href (terima absolut, query,
  trailing slash; dedupe by id). Field ke-9 = chatLinksSeen
  (jumlah mentah a[href*="/chat/"] sebelum filter — diagnosa:
  0 = memang tidak ada link di DOM). Perilaku: sesudah install +
  buka Space, tunggu ~40 detik (2 tick: buka, kumpul+tutup);
  /page membawa chats[] + chatLinksSeen > 0.
- API 53 (20 Nov, v0.1.53): SNAPSHOT BAWA DAFTAR LINK CHAT SIDEBAR.
  Masalah: chat bisa ke-reset ke /new sesudah install, dan snapshot
  v0.1.52 hanya membawa teks (~700 karakter, sidebar ketutup) — URL
  chat (claude.ai/chat/<uuid>) tidak ketahuan, jadi POST /navigate
  belum bisa dipakai untuk kembali ke chat itu. Isi: pageSnapshotJs
  kini kumpulkan juga semua <a href="/chat/<id>"> di DOM (maks 20,
  dedupe by href, judul dibersihkan dari "^"/"~") sebagai field
  ke-8 snapshot ("judul^url" dipisah "~"); capturePageSnapshot
  menulisnya sebagai array `chats: [{title, url}]` di
  api-page-<suffix>.json — GET /page langsung membawanya tanpa
  perubahan ApiServer/senz.py. Asumsi: link sidebar tetap ada di
  DOM walau sidebar ketutup (tampilan HP). Bila kosong, fallback
  berikutnya: buka sidebar dulu lewat JS sebelum kumpulkan link
  (belum dibangun).
- API 52 (20 Nov, v0.1.52): FULL AKSES APP LEWAT API BRIDGE. Isi:
  (a) GET /page?slot= — BrowserActivity menulis snapshot halaman ke
  api-page-<suffix>.json tiap halaman selesai + tiap ~20 dtk (tick
  live ke-10): url (tanpa query), title, text (awal halaman, 4000
  karakter), tail (akhir halaman, 1500 karakter), generating
  (tombol Stop ada = Claude sedang ngetik, best effort), limit +
  limitReset (EN "5-hour message limit" / "out of free messages",
  ID "kehabisan pesan gratis" / "Penggunaan gratis"; reset EN
  "resets at 10:50 PM", ID "pukul 00.20"). ApiServer menambah
  ageSec + stale (umur >120 dtk = basi — snapshot TIDAK PERNAH
  segar bila proses Space mati, mis. sesudah install APK baru).
  (b) POST /navigate {slot, url} — muat URL di Space; host HARUS
  sama dengan situs Space itu (claude.ai / aistudio.google.com),
  situs lain DITOLAK 400; payload lewat intent extra
  EXTRA_API_NAVIGATE_URL (MainActivity + SpaceLauncher, pola sama
  seperti promptUrl v0.1.38) + consumeApiNavigate di
  onCreate/onNewIntent. (c) Snapshot yang menemukan limit JUGA
  menulis file limit SpaceStatus — field `limit` di /spaces jadi
  jujur; snapshot TIDAK PERNAH menghapus file limit (halaman home
  tidak menampilkan modal, jadi "tidak ketemu" belum tentu "sudah
  bebas" — penghapusan tetap urusan LiveBridge). (d) senz.py:
  `page --slot N` + `navigate --slot N --url ...`. Catatan: isi
  chat Claude dibaca sebagai TEKS TERLIHAT (innerText), bukan
  struktur pesan — tahap checkpoint tetap dibaca dari kata-kata
  di teks, bukan field khusus; dan snapshot cuma hidup selama
  proses Space hidup.
- API 51 (20 Nov, v0.1.51): TUTUP SHEET SPEAKER + UNDUH PAKAI TAP
  BENERAN + CATAT PERCOBAAN STYLE. (a) eksekutor tidak pernah
  menutup sheet Speaker — sesudah pilih Sadaltager sheet
  ketinggalan kebuka + Gboard naik; (b) JS .click() tombol
  Download nol aktivitas blob di 4 run — blob-debug.txt hanya
  INJECT, tanpa TRAP data / core: req; yang jalan hanya TAP
  BENERAN. Fix: (a) closeSpeakerSheet (JS cari tombol tutup:
  aria-label close/tutup atau teks ×/✕/Close) dipanggil sesudah
  pilih speaker (lalu isi naskah, 800 ms) DAN sebelum tap unduh
  (best effort — gagal ketemu tombol tutup BUKAN gagal run,
  naskah tetap terisi dengan sheet kebuka); (b) runTtsDownload
  kini baca rect tengah tombol Download TERAKHIR
  (ttsDownloadButtonRectJs, 'x|y|count') lalu ttsTapAt
  (dispatchTouchEvent DOWN+UP, pola yang sama dengan tap kotak
  Style v0.1.48 — CSS px x webView.scale); ttsDownloadInfo kini
  "ditap beneran (1 dari N, titik=…)" supaya alasan gagal jujur;
  (c) waitTtsFile: file belum muncul sesudah 6x periksa -> TAP SEKALI
  LAGI (ttsDownloadRetried, sekali saja), baru gagal jujur;
  (d) ttsStyleAttempt dicatat (0 = sudah menempel sebelum
  percobaan 1) — status sukses Style kini bawa nomor percobaan,
  dan status "done" VO membawa "(Style percobaan N)" supaya run
  berikutnya ketahuan percobaan mana yang lolos. Catatan: tap
  sintetis dari dalam app mungkin tetap tidak memicu handler yang
  sama dengan jari beneran — bila gagal, bukti di blob-debug.txt
  + ttsDownloadInfo; kandidat berikutnya = tap ikon unduh di bar
  player, bukan tombol Download di dialog.
- API 50 (20 Nov, v0.1.50): PASTIKAN MENU KEBUKA DULU + NOMOR
  PERCOBAAN DIBAWA TERUS. Bug v0.1.49: verifikasi #1 lolos -> klik
  tutup menutup menu -> framework TIDAK commit nilai DOM ke label
  tombol -> verifikasi #2 (label) gagal -> retry langsung ke
  waitTtsStyleField yang cari kolom di menu KETUTUP -> 10 dtk
  '0' -> failed "kolom tidak muncul (percobaan 2)", padahal
  tinggal dibuka lagi. Percobaan 4 (tap + IME hidup) tidak pernah
  jalan di v0.1.49. Fix: (a) ensureStyleMenuOpen baru — tiap retry
  periksa dulu kolomnya ada (ttsStyleFieldStateJs): ada -> langsung
  isi (TANPA klik, jangan sampai klik malah menutup menu yang
  sudah kebuka); tidak ada -> klik tombol Style (ttsClickStyleJs,
  terima label "Style" atau ber-probe), tunggu 1000 ms, periksa lagi;
  masih tidak ada -> gagal jujur dengan nomor percobaan. Dipakai
  retryTtsStyle untuk semua percobaan 2-4. (b) waitTtsStyleButton
  kini bawa nomor percobaan (dulu hardcode 1 di pemanggilan
  waitTtsStyleField — percobaan ulang selalu dilaporkan sebagai
  percobaan 1). Verifikasi #2 (label) TETAP sebagai bukti. Catatan:
  tap eksekutor = dispatchTouchEvent (sentuhan sintetis dari dalam
  app), bukan jari beneran — sistem bisa saja tidak membukakan
  keyboard untuknya; bila attempt 4 gagal juga, jalur API buat VO
  mentok di Style.
- API 49 (20 Nov, v0.1.49): VERIFIKASI LEWAT LABEL TOMBOL + TAP
  DICEK. Sesudah style menempel, tombol Style BERGANTI label jadi
  teks style ("Very bright, high-p…") sehingga ttsClickStyleJs
  (cari teks "Style") tidak menemukannya lagi -> klik tutup/buka-
  ulang gagal -> baca #2 gagal -> failed, walau nilainya sudah
  menempel. Fix: (a) runTtsStyle periksa dulu label tombol — sudah
  berisi probe -> langsung ke Speaker (jangan isi ulang); (b)
  verifyTtsStyleClosed verifikasi #2 = baca LABEL tombol Style
  yang tertutup (harus berisi probe) — tidak perlu buka menu lagi;
  (c) ttsClickStyleJs(probe) terima label "Style" ATAU label
  ber-probe; (d) tap percobaan 4: jeda 500 ms sebelum baca rect
  (menu selesai animasi), periksa sesudah tap kotak masih ada +
  fokusnya (ttsStyleFieldStateJs), catat rect+scale+titik+state
  ke ttsLastTapInfo (muncul di dump gagal), bila menu ketutup
  buka lagi + tap ulang sekali.
- API 48 (20 Nov, v0.1.48): TAP BENERAN + IME HIDUP. Mekanisme
  yang bekerja: nilai hanya menempel lewat sesi IME hidup — tap
  kotak -> keyboard terbuka -> paste dari papan klip -> teks
  menempel, tombol Style menampilkan teks style. Semua percobaan
  programmatic tidak pernah punya sesi IME hidup. Fix: percobaan
  4 = ttsTypeLiveIme: JS cari posisi tengah kotak
  (getBoundingClientRect, 'x|y') -> ttsTapAt kirim sentuhan
  DOWN+UP via webView.dispatchTouchEvent (tap aplikasi ke
  WebView-nya sendiri, koordinat dari posisi elemen) -> tunggu
  1200 ms (keyboard naik) -> ttsTypeReal commitText lewat
  onCreateInputConnection yang kini koneksi HIDUP -> verifikasi.
  retryTtsStyle kini 1 -> 2 -> 3 -> 4 -> dump; pesan gagal
  "setelah 4 percobaan".
- API 47 (20 Nov, v0.1.47): KLIK KOTAK + ISI ULANG TERTUNDA.
  Klik Style berhasil, tapi 3 cara isi gagal; dump menjawab dua
  hal: (a) probe eq=true = setter JALAN, framework yang menghapus
  nilai sesudah JS call selesai; (b) active=BUTTON = focus()
  tidak memindahkan fokus dari tombol Style, jadi commitText +
  KeyEvent tidak sampai. Fix: (a) ttsClickStyleFieldJs — KLIK
  kotaknya beneran (bukan cuma focus()), lalu cek
  document.activeElement===kotak ('focus'/'nofocus'/'0', dicatat
  di status percobaan 2 dan 3); (b) ttsFillJs voice: ISI ULANG
  TERTUNDA — setTimeout 300 ms cari ULANG kotaknya (query segar)
  lalu set nilai lagi via setter + event input, kalahkan tick
  reset / ganti elemen; verifikasi baca-balik percobaan 1 ditunda
  600 ms supaya isi ulang sempat jalan.
- API 46 (20 Nov, v0.1.46): KLIK STYLE DIULANG + SELEKTOR LUAS.
  "Tombol Style tidak ketemu" walau tombolnya ada di editor
  (dialog fresh: Speaker Fola, naskah kosong). Dugaan: eksekutor
  menembak terlalu dini (halaman masih loading/transisi) ATAU
  kontrol Style di dialog baru bukan <button>. Fix: (a) klik
  Style DIULANG — poll 10x1 dtk (waitTtsStyleButton) sebelum
  gagal, seperti tunggu kolom; (b) ttsClickStyleJs tiga lapis —
  <button> dulu (hasil 'btn'), lalu [role="button"] ('role'), lalu
  elemen daun teks persis "Style" ('leaf'); status mencatat lapis
  yang kena; (c) gagal total = runTtsDumpButtons: maks 15 label
  tombol terlihat + origin+path URL (tanpa query) ke detail gagal.
  Tutup/buka menu di verifikasi tetap pakai ttsClickStyleJs yang
  sama (hasilnya diabaikan, urutan lapis tidak berubah).
- API 45 (20 Nov, v0.1.45): KETIK KEYEVENT + PROBE SATU-TUGAS.
  Dump menunjukkan kotaknya TEXTAREA biasa (placeholder +
  aria-label "Describe the voice style"), terlihat, tapi len=0
  sesudah JS fill DAN commitText. Dugaan: commitText tidak sampai
  ke halaman (InputConnection manual mati bila WebView tidak
  pegang fokus window) dan/atau framework me-reset nilai async.
  Fix: (a) percobaan 3 = KETIK LEWAT TOMBOL KEYBOARD: fokus kolom
  lalu dispatchKeyEvent DOWN+UP per karakter dari Kotlin (huruf,
  angka, spasi, koma, titik, strip; shift untuk huruf besar;
  25 ms antar event) — tanpa IME, tanpa koneksi input, seperti
  keyboard fisik; (b) probe satu-tugas di runTtsDumpStyle: set
  'PROBE123' via setter lalu baca balik DALAM JS call yang sama
  — eq=true berarti setter jalan dan nilai dihapus framework
  secara async, eq=false berarti setter dicegat langsung; (c)
  dump tambah activeElement + disabled + readOnly per kolom.
  Alur percobaan kini 1 (JS) -> 2 (commitText) -> 3 (KeyEvent)
  -> dump + gagal jujur.
- API 44 (20 Nov, v0.1.44): KETIK BENERAN + DUMP BUKTI.
  Verifikasi menangkap Style kosong dalam 30 detik dan VO
  dibatalkan (tidak generate dengan suara default), tapi fill-nya
  tidak pernah menempel: setter + event 'input'/'change' sintetis
  ditolak framework AI Studio (nilai hilang sebelum Run; kotak
  naskah biasa masuk, kotak Style tidak). Fix: (a) ttsFillJs
  untuk voice — setter + event SELALU dijalankan walau execCommand
  mengaku sukses, event pertama InputEvent ber-data (inputType
  insertText, data = teks) karena sebagian framework menghitung
  nilai baru dari event.data dan Event polos bisa mengosongkan
  model; (b) percobaan 2 = KETIK BENERAN lewat jalur IME: fokus
  kolom (ttsFocusStyleJs: focus + select-all) lalu
  InputConnection.commitText dari Kotlin
  (webView.onCreateInputConnection(EditorInfo())) — event
  input-nya trusted seperti ketikan pengguna; (c) gagal total =
  runTtsDumpStyle: dump anatomi maks 8 kolom input terlihat
  (tag | placeholder | aria-label | panjang isi | hasProbe
  'Very bright') ke detail gagal supaya perbaikan berikutnya
  berbasis bukti. Verifikasi baca-balik 2x (sebelum tutup +
  tutup-buka) tetap berlaku untuk kedua percobaan.
- API 43 (20 Nov, v0.1.43): STYLE MENEMPEL + UNDUH TEPAT WAKTU.
  Dua cacat yang diperbaiki: (a) Style: fill lama mengembalikan
  len>0 tapi kotak "Describe the voice style" tetap kosong (kena
  kolom lain ATAU framework menghapus nilai saat menu ditutup) —
  audio ter-generate tanpa format Riko; (b) Unduh: waitTtsDone
  terpicu prematur oleh <audio> (audio baru siap ~2 menit),
  runTtsDownload klik tombol Download pertama di DOM (dialog
  lama) — nol aktivitas blob di blob-debug.txt. Fix: Style =
  tunggu kolom muncul (poll 10x1 dtk, placeholder persis
  "describe the voice style") -> isi (matcher voice utamakan
  placeholder persis + event input DAN change) -> BACA BALIK
  berisi "Very bright" -> tutup menu -> buka lagi -> BACA BALIK
  lagi; gagal verifikasi = ulang 1x, lalu failed jujur (jangan
  generate dengan gaya salah). Unduh = waitTtsDone syarat KETAT
  (tombol Download ADA DAN audio readyState==4 + duration>0);
  runTtsDownload klik tombol TERAKHIR (dialog terbaru); detail
  gagal mencatat tombol yang diklik.
- API 42 (20 Nov, v0.1.42): TANPA KETUK — BENERAN. Akar: proses
  :aistudio HIDUP tapi activity-nya sudah dihancurkan Android
  (tekanan RAM) — intent /tts tidak pernah sampai, status diam
  "queued". "Process alive" BUKAN "activity hidup". Fix: (a) IZIN
  SYSTEM_ALERT_WINDOW ("Tampil di atas aplikasi lain", manifest +
  minta SEKALI di Beranda saat kunci app mati + baris status/tombol
  di Pengaturan → API Kontrol) — pemegang izin ini DIKECUALIKAN
  dari BAL, jadi launch Space dari background boleh menarik
  activity ke depan tanpa ketuk; /open, /prompt, /tts: visible =
  appHasVisibleWindow() ATAU overlay (balasan "via": "overlay"
  bila overlay yang menghidupkan); /status tambah field
  overlay_permission supaya bisa diperiksa dari VM. (b) GAGAL-JUJUR
  60 DETIK: sesudah /prompt dan /tts menulis "queued", thread
  pengawas (watchPickup) tidur 60 dtk lalu periksa file status —
  bila masih "queued" dengan timestamp YANG SAMA (BrowserActivity
  tidak pernah jalan), tulis failed + alasan jelas (izin overlay
  belum diberikan, atau activity dimatikan RAM walau izin sudah
  ada). Bila activity akhirnya jalan telat, tulisannya
  (running/done) menimpa failed itu — tidak ada konflik. (c) Izin
  overlay SATU KALI — sesudah diberikan, tugas API jalan sendiri;
  penolakan tidak mengganggu app.
  JEBAKAN PATCH: baris `open(f,"w").write(open(f).read())`
  MENGOSONGKAN file — open mode "w" memotong file DULU, baru read
  baca file kosong. ApiServer.kt jadi 0 byte, CI gagal "Unresolved
  reference: ApiServer/ApiToken" di semua file lain. JANGAN PERNAH
  pakai pola itu; patch script tidak boleh menulis ulang file yang
  baru dibacanya dengan cara itu. SEBELUM push: periksa `wc -c`
  semua file yang disentuh patch — file 0 byte = STOP, pulihkan
  dari commit GitHub terakhir dulu, baru push ulang. (Kedua:
  MainActivity.kt tidak mengimpor android.widget.Button —
  dialog baru v0.1.42 pakai Button() tanpa import.) (Ketiga: di
  dalam lambda `Thread({...})` (Runnable SAM), `return` polos
  TIDAK BOLEH — Kotlin menolak "'return' is not allowed here";
  pakai `return@Thread`.)
- API 41 (19 Nov, v0.1.41): TTS ALUR ASLI.
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
  — alur hamburger DIHAPUS).
  (c) Diagnose state-aware: ada "Speaker 1" + kolom = editor
  dialog (langsung Style); ada "Create new dialog" = halaman
  utama TTS (klik itu); selain itu = alur pilih model. Kolom
  isian: textarea + [contenteditable] apa pun + input +
  [role=textbox]; naskah = kolom TERLUAS (area), bukan rows.
  Gagal di satu langkah -> status failed membawa DUMP inventaris
  halaman / isi menu. (d) Eksekusi background jalan bila proses
  Space sudah hangat; cold start dari background gagal (proses
  :aistudio tetap mati, /open timeout). "Tanpa tap" berlaku untuk
  proses yang sudah hangat; Space yang mati total butuh satu ketuk
  pemanasan dulu.
- API 40 (19 Nov, v0.1.40): NAVIGASI OTOMATIS + TANPA TAP.
  (a) TTS: bila diagnose 0 kolom isian, eksekutor TIDAK langsung
  gagal — buka menu "Open navigation menu", klik entri model
  starred (bila diminta) atau entri TTS (Text to speech / TTS /
  Generate speech), diagnose ulang (max 3x). Kolom isian yang
  dihitung: textarea + [contenteditable=true] + input[type=text];
  isi mendukung contenteditable (textContent + input event).
  Contoh halaman yang memicu navigasi: halaman preview suara
  (tombol "Preview dialog audio", 0 textarea) — halaman TTS satu
  navigasi jauhnya. (b) PROMPT: param `project` (nama project) —
  eksekutor klik tombol kiri atas (sidebar: aria-label Toggle/Open
  sidebar / Menu) -> klik nama Project (cocok persis dulu, lalu
  mengandung) -> tunggu URL /project/ -> klik "New chat" -> alur
  lama (diagnose -> attach -> isi -> kirim). User TIDAK perlu kirim
  URL Project. (c) TANPA TAP: eksekusi prompt/TTS jalan di
  background — activity kebuka tak tampil, WebView tetap load +
  JS tetap jalan. Balasan 202 membawa note bahwa tugas berjalan
  di background. Notifikasi "Buka <nama>" tetap ada sebagai opsi
  intip, BUKAN syarat.
- API 39 (19 Nov, v0.1.39): TTS API — POST /tts {slot=7, script,
  voice?, model?, url?} (script max 50.000 char, voice default =
  format Riko, url HARUS https://aistudio.google.com/...)
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
  FileProvider file _inbox (TANPA pemilih file) -> periksa chip ->
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
  dumpsys topResumedActivity BISA BASI — kebenaran foreground =
  screenshot.
- API 67 (21 Nov, v0.1.67): JALUR KIRIM PROMPT ANDAL (OPT-1 + OPT-2).
  (a) Fill: execCommand telanjang tidak memberitahu model ProseMirror
  — DOM berisi teks tapi tombol Send tetap nonaktif. Fix:
  execCommand dulu; bila isi sesudahnya != teks yang diminta
  (normalisasi whitespace), fallback textContent + InputEvent
  ('input', inputType:'insertText', data) + Event('input') +
  Event('change'), lalu isi ulang tertunda 300 ms bila framework
  menghapus nilainya (pola ttsFillJs — TTS sendiri tidak disentuh).
  Verifikasi sesudah clear: clear yang gagal (isi lama masih ada)
  -> gagal jujur "teks lama di kolom chat tidak bisa dihapus",
  jangan lanjut isi. Verifikasi akhir 500 ms sesudah isi
  (runPromptFillVerify): innerText (normalisasi) HARUS == teks
  pending DAN tombol Send ada dan tidak disabled — bila tidak,
  gagal jujur dengan bukti (isi kolom 80 karakter pertama /
  keadaan tombol). Fill-guard v0.1.61 tidak berubah (teks lain ->
  tolak, "kolom chat berisi teks lain").
  (b) Verify-send: pencocokan teks mentah ke seluruh body lolos
  oleh teks basi (frasa yang sama di pesan Claude sendiri).
  Fix: runPromptSend menangkap sidik jari SEBELUM klik (jumlah
  kemunculan teks prompt di body + jumlah elemen user-turn
  [data-testid="user-message"]) dan runPromptVerifySend
  mewajibkan angka itu BERTAMBAH (poll 5x2 dtk tidak berubah) di
  samping gerbang lama (kolom kosong DAN (Stop ada ATAU teks
  terlihat)). Status "sent" tetap tidak pernah ditulis sebelum
  verifikasi lolos. Tanpa ubah perilaku: eksekutor TTS, Studio,
  FM picker, BootReceiver, SpaceRegistry, dan rute lain tidak
  disentuh.
- API 68 (21 Nov, v0.1.68): SINYAL MONITOR JUJUR (OPT-8 + OPT-15 +
  OPT-16 + OPT-17).
  (a) Snapshot wall-clock (OPT-17): snapshot halaman tidak lagi
  ikut tick live — timer terpisah (snapshotRunnable) tiap 20 dtk.
  Dulu tiap 10 tick = ~100 dtk pada live interval 10 dtk, nyaris
  menyentuh ambang basi 120 dtk. Ambang basi + field ageSec/stale
  + isi snapshot tidak berubah; live interval kini hanya mengatur
  frekuensi thumbnail.
  (b) Thumbnail jujur (OPT-8): file thumbnail HANYA ditulis bila
  piksel berubah (sameAs) — mtime + thumb_age_sec kini jujur
  mencerminkan perubahan piksel terakhir. /spaces mengekspos
  last_pixel_ms (+ last_dom_ms, last_alive_ms) dari file status
  live supaya konsumen bisa membedakan renderer throttling
  (proses hidup, DOM berubah, piksel beku) dari Space mati.
  (c) Interval adaptif + tulis latar (OPT-16): saat Claude
  streaming (DOM bermutasi <15 dtk terakhir atau snapshot
  terakhir bilang generating), tangkapan dibatasi 1×/5 dtk,
  bukan ~1×/dtk; saat tenang batas lama 1 dtk. Kompresi JPEG +
  tulis file pindah ke single-thread executor latar (draw WebView
  tetap di UI thread); thread latar tidak pernah menyentuh
  lastThumbBmp (tetap milik UI thread).
  (d) Latch snapshot ber-timeout (OPT-15): pageSnapRunning
  direset paksa sesudah 10 dtk bila callback evaluateJavascript
  tidak menyala (renderer hang), dijaga nomor urut supaya reset
  tidak membersihkan latch tangkapan baru; kejadian ditulis ke
  file marker _live/<suffix>-snapstuck.txt (file terpisah — parser
  Beranda mensyaratkan status live tepat 3 kolom) dan diekspos
  /spaces sebagai snapshot_stuck_ms (0 = tidak pernah macet).
  Tanpa ubah perilaku: eksekutor prompt (OPT-1/OPT-2), TTS,
  Studio, FM picker, BootReceiver, SpaceRegistry, dan rute lain
  tidak disentuh.
- API 69 (21 Nov, v0.1.69): UNDUH TTS LEWAT IKON BAR PLAYER.
  (a) Pemilihan tombol unduh: selektor lama hanya cocok <button>
  berlabel atau teks PERSIS 'Download'/'Unduh' lalu ambil yang
  terakhir di DOM — tombol di bar player (aria-label mengandung
  'Download audio' atau elemen [role="button"]) lolos dari
  selektor itu. Kini pencocokan diperluas: aria-label MENGANDUNG
  'download'/'unduh', teks mengandung (maks 30 karakter), elemen
  button + [role="button"]; di antara kandidat, utamakan yang
  paling dekat dengan bar player — jarak vertikal ke tombol
  putar/jeda (teks/aria 'play_arrow', 'pause_circle', 'play',
  'pause', 'putar', 'jeda'), fallback ke elemen <audio> bila
  tingginya > 0; bila tidak ada jangkar, tetap ambil yang
  terakhir di DOM (perilaku lama).
  (b) Dump kandidat: ttsDownloadButtonRectJs kini mengembalikan
  'x|y|count|dump' — dump = label@x,y tiap kandidat (maks 6,
  pisah ' ;; '); disimpan ke ttsDownloadCandidates dan dibawa ke
  detail gagal supaya tombol yang ditap bisa diidentifikasi
  tanpa tebakan.
  (c) Retry tap sekali (v0.1.51) + verifikasi file di folder
  aistudio tidak berubah. Tanpa ubah perilaku: alur
  Style/Speaker/Run, waitTtsDone (Download ADA + audio
  readyState 4 + duration > 0), watchPickup 60 dtk, dan
  penanganan naskah.
- API 70 (21 Nov, v0.1.70): ALUR PROJECT LEWAT COMPOSER HALAMAN PROJECT.
  (a) URL live, bukan cache: waitPromptProjectUrl kini membaca
  location.href lewat evaluateJavascript tiap percobaan (budget
  tetap 5×2 dtk). currentUrl hanya update di onPageStarted/
  onPageFinished, sedangkan navigasi sidebar Claude itu SPA
  (pushState, tanpa full load) sehingga cache tidak pernah
  melihat "/project/" sesudah klik nama Project di sidebar.
  (b) Tanpa klik "New chat": sesudah URL project terkonfirmasi,
  flow langsung ke runPromptDiagnose — composer halaman Project
  sudah ada dan mengetik di situ memulai chat baru DI DALAM
  project. runPromptNewChat dihapus (tombol "New chat" tidak ada
  di halaman Project; tombol sidebar cuma "New"). promptProjectDone
  ditandai saat URL project terkonfirmasi supaya onPageFinished
  berikutnya tidak mengulang flow Project.
  (c) Menu attach: klik tombol attach di composer halaman Project
  membuka menu, bukan file chooser langsung. runPromptAttach kini
  cek dulu item menu "Upload from device"/"Unggah dari perangkat"
  yang terlihat (by teks + visibility, bukan koordinat — posisi
  menu mengikuti tinggi composer); bila ada, klik langsung; bila
  belum, klik tombol attach, tunggu 1,5 dtk, lalu cari + klik item
  menu itu; bila tidak ada menu (chooser langsung, seperti di chat
  biasa), langsung tunggu chooser. Bila beberapa elemen cocok,
  diambil yang terakhir di urutan DOM (paling dalam/spesifik).
  Project dipilih by NAMA lewat sidebar (runPromptPickProject
  tidak berubah) — Project itu per-akun, tiap akun punya ID
  project sendiri; URL project tidak pernah di-hardcode.
  Tanpa ubah perilaku: eksekutor TTS, Studio, snapshot/thumbnail,
  runPromptFill/runPromptSend, budget waitPromptChooser, fill guard.
- API 71 (22 Nov, v0.1.71): WATCHDOG API (WORKMANAGER).
  (a) ApiService hanya di-start dari MainActivity, BootReceiver,
  dan toggle Pengaturan — bila proses utama mati sementara Space
  selamat, atau activity Space dibuka langsung tanpa Beranda, API
  mati dan tidak ada yang menyalakan lagi; START_STICKY tidak
  me-restart service di bawah tekanan memori. WatchdogWorker
  (WorkManager periodik 15 mnt, minimum sistem) mengecek ApiServer
  lewat ping /ping tiap 15 mnt dan menyalakan ApiService bila tidak
  menjawab. Ping memakai Socket mentah (tanpa proxy, tidak kena
  larangan cleartext). Enqueue idempotent
  (ExistingPeriodicWorkPolicy.KEEP) dari MainActivity.onCreate,
  BootReceiver (BOOT_COMPLETED + MY_PACKAGE_REPLACED), dan
  BrowserActivity.onCreate; enqueue tidak pernah throw (aman dari
  proses Space — WorkManager utamanya hidup di proses utama).
  Worker menghormati saklar API Kontrol (ApiToken.isEnabled dicek
  tiap jalan).
  (b) BrowserActivity.onCreate juga memanggil ApiService.start
  (setiap buka Space memastikan API hidup; startService aman bila
  service sudah jalan).
  (c) Foreground service: worker memakai expedited work +
  setForegroundAsync (tunggu promosi selesai sebelum start) supaya
  startForegroundService legal dari background (Android 12+).
  Notifikasi foreground worker: channel "senzspace_watchdog",
  LOW, tipe dataSync.
  (d) Dependensi baru: androidx.work:work-runtime-ktx:2.9.0
  (cocok AGP 8.5.2, compileSdk 34). Izin manifest baru:
  FOREGROUND_SERVICE_DATA_SYNC (izin normal, tanpa prompt
  runtime) — wajib untuk foreground service internal WorkManager
  bertipe dataSync di target SDK 34.
  (e) Revive dari VM: ReviveReceiver (exported, aksi tetap
  id.senzspace.debug.action.REVIVE_API) — onReceive hanya
  menyalakan ApiService milik app sendiri + mendaftarkan watchdog
  (bila saklar API Kontrol hidup); tidak membaca extra apa pun
  dari broadcast, tidak membuka activity, tidak mengubah layar.
  Start langsung dari receiver di background bisa ditolak sistem,
  jadi receiver juga mengantre one-time expedited work
  (WatchdogWorker.enqueueNow) — worker setForeground dulu baru
  start. Tool VM: `senz.py revive` (kirim broadcast, poll /ping
  ~30 dtk; HIDUP hanya bila /ping menjawab). Bila kelak tidak
  dibutuhkan, hapus receiver + perintah revive sekaligus.
  ApiService tetap exported=false.
  Tanpa ubah perilaku: eksekutor prompt/TTS/Studio,
  snapshot/thumbnail, limit detection, FileManager, KeepAlive
  per-Space, BootReceiver yang sudah ada (hanya tambah enqueue).
- API 72 (22 Nov, v0.1.72): GUARDIAN WATCHDOG (PROSES :watchdog).
  (a) Latar: watchdog WorkManager v0.1.71 tidak pernah menyalakan
  ApiService di HP ini — start foreground service dari background
  ditolak sistem (receiver/worker bukan konteks yang dikecualikan),
  WorkManager tidak pernah terjadwal, dan semua lapis menelan
  kegagalan diam-diam. WatchdogGuardService jalan di proses sendiri
  (:watchdog): foreground service (notifikasi LOW, channel
  "senzspace_watchdog" yang sama dengan worker) → uid-nya
  foreground → boleh memanggil ApiService.start() bila /ping tidak
  menjawab. Di-start HANYA dari konteks foreground
  (MainActivity.onCreate, BootReceiver, BrowserActivity.onCreate —
  di samping ApiService.start yang sudah ada, idempotent);
  ApiService tetap tanpa android:process → selalu jalan di proses
  utama, guardian hanya pemicunya. Loop Handler 60 dtk (bukan
  AlarmManager — service foreground tidak kena defer Doze untuk
  Handler); ping Socket mentah memakai ulang
  WatchdogWorker.isApiAlive (kini di companion object, bukan
  duplikasi). START_STICKY + re-start dari ketiga titik di atas.
  Guardian bukan jalur revive dari VM: start dari background
  (mis. ReviveReceiver) bisa ditolak sistem, jadi receiver tidak
  memanggilnya.
  (b) Logging: tag "SenzWatchdog" (guardian + worker +
  ApiService.start + BootReceiver) dan "SenzRevive" (receiver).
  ApiService.start kini kembalikan Boolean (true bila salah satu
  jalur start terpanggil, false bila keduanya ditolak — pemanggil
  lama yang mengabaikan nilai balik tidak rusak); tiap throw
  dicatat Log.e, tidak ditelan diam-diam. ReviveReceiver/
  BootReceiver/worker mencatat hasil start + hasil enqueue;
  isApiAlive mencatat kegagalan ping. Jalur v0.1.71 dipertahankan
  sebagai lapis kedua.
  (c) Manifest: service exported=false, proses :watchdog,
  foregroundServiceType specialUse (+ property subtype, pola
  KeepAliveService); izin FOREGROUND_SERVICE_SPECIAL_USE sudah
  dideklarasikan di manifest (dipakai KeepAliveService) — tanpa
  izin baru. Tanpa ubah perilaku: eksekutor prompt/TTS/Studio,
  snapshot/thumbnail, limit detection, FileManager, KeepAlive
  per-Space.
- API 73 (22 Nov, v0.1.73): STARTFOREGROUND PERTAMA DI ONCREATE.
  (a) Latar: v0.1.72 crash dua proses sekaligus —
  ForegroundServiceDidNotStartInTimeException untuk ApiService
  dan WatchdogGuardService — startForeground() tidak terpanggil
  dalam timeout sistem sesudah startForegroundService(). Izin
  notifikasi sudah granted; tekanan memori (LMK aktif) membuat
  proses baru start lambat; onCreate kedua service masih
  mengerjakan channel + builder + PendingIntent sebelum
  startForeground, dan try/catch di sekitar startForeground
  menelan kegagalan (service jalan tanpa foreground lalu dibunuh
  sistem). Urutan baru di onCreate ApiService, WatchdogGuardService,
  dan KeepAliveService.onStartCommand: buat channel (satu binder
  call, idempotent) → notifikasi MINIMAL (icon + judul saja) →
  startForeground() LANGSUNG tanpa try/catch yang menelan (bila
  throw, tercatat sebagai crash dengan stack jelas) → sesudah itu
  lengkapi notifikasi (PendingIntent + teks) via
  NotificationManager.notify + kerja lain. Log.i di baris pertama
  onCreate + sebelum + sesudah startForeground (tag SenzWatchdog
  untuk guardian dan ApiService).
  (b) Guardian menyalakan ApiService TANPA timeout FGS:
  ApiService.startPlain() — startService biasa (bukan
  startForegroundService), tangkap throw + Log.e + Boolean.
  Guardian tick memakai startPlain; ApiService mempromosikan diri
  ke foreground lewat startForeground pertama di onCreate-nya
  sendiri. ApiService.start() (FGS) tetap untuk 7 pemanggil lama
  (MainActivity, BootReceiver, BrowserActivity, SettingsActivity,
  ReviveReceiver, WatchdogWorker, + guardian lama).
  (c) Tanpa izin baru; jalur v0.1.71/v0.1.72 dipertahankan.
  Tanpa ubah perilaku: eksekutor prompt/TTS/Studio,
  snapshot/thumbnail, limit detection, FileManager, KeepAlive
  per-Space, BootReceiver yang sudah ada.
- API 74 (22 Nov, v0.1.74): JANGAN PERNAH STARTFOREGROUNDSERVICE
  DARI BACKGROUND.
  (a) Latar: v0.1.73 crash dua proses sekaligus lagi —
  ForegroundServiceDidNotStartInTimeException untuk ApiService
  dan WatchdogGuardService. Logcat: 06:36:20 sistem MENOLAK
  startForeground() mentah-mentah ("not allowed due to bg
  restriction") karena HP tidur — BUKAN karena onCreate lambat.
  Service jalan sebagai background service (API menjawab poll)
  tapi ServiceRecord dari startForegroundService tetap menunggu
  startForeground → dibunuh ~2,5 mnt kemudian. Guardian
  startPlain=true 2x tidak bisa membatalkan timeout FGS yang
  sudah menunggu dari panggilan startForegroundService
  sebelumnya. WorkManager periodic: "PeriodicWorkRequests cannot
  be expedited".
  (b) Semua pemanggil background pakai startService biasa:
  BootReceiver (BOOT_COMPLETED + MY_PACKAGE_REPLACED) pakai
  ApiService.startPlain() + WatchdogGuardService.startPlain()
  (baru, pola sama: startService + try/catch + Log.e + Boolean);
  ReviveReceiver pakai startPlain; WatchdogWorker.doWork pakai
  startPlain; guardian tick sudah startPlain sejak v0.1.73.
  ApiService.start() / WatchdogGuardService.start() (FGS) tetap
  HANYA untuk pemanggil foreground (MainActivity,
  BrowserActivity, SettingsActivity).
  (c) onCreate ApiService/WatchdogGuardService +
  KeepAliveService.onStartCommand: startForeground() HANYA bila
  app sedang foreground — probe murah ApiService.isAppVisible()
  (ActivityManager.runningAppProcesses, ambang IMPORTANCE_
  FOREGROUND / IMPORTANCE_VISIBLE saja; lintas proses, tanpa
  static antar-proses); bila background, lewati panggilan dan
  jalan terus sebagai background service; try/catch + Log.w
  sebagai jaring pengaman bila ROM melempar. Notifikasi minimal
  + Log.i masuk/sebelum/sesudah dari v0.1.73 dipertahankan.
  (d) WorkManager.enqueue() periodik: hapus setExpedited
  (periodic tidak boleh expedited); enqueueNow() one-time BOLEH
  tetap expedited; kegagalan enqueue tetap dicatat Log.w.
  (e) Tanpa izin baru; jalur v0.1.71–v0.1.73 dipertahankan.
  Tanpa ubah perilaku: eksekutor prompt/TTS/Studio,
  snapshot/thumbnail, limit detection, FileManager, KeepAlive
  per-Space.
- API 75 (22 Nov, v0.1.75): STARTFOREGROUNDSERVICE DIHAPUS SEPENUHNYA.
  (a) Latar: v0.1.74 masih menyisakan dua pemanggil
  startForegroundService — ApiService.start() dan
  WatchdogGuardService.start(), dipanggil MainActivity/
  BrowserActivity saat install menciptakan ulang activity stack
  selagi app belum foreground. Panggilan itu menciptakan penantian
  FGS; onCreate (probe isAppVisible benar) melewati startForeground
  saat background → penantian tidak terpenuhi → service dibunuh
  timeout. Probe + startForegroundService tidak kompatibel.
  (b) ApiService.start() / WatchdogGuardService.start() kini
  memanggil startPlain (startService biasa + try/catch +
  Log.e + Boolean); KeepAlive.start() juga startService biasa.
  Setelah perubahan ini TIDAK ADA LAGI panggilan
  startForegroundService di app/src/main.
  (c) onCreate ApiService/WatchdogGuardService +
  KeepAliveService.onStartCommand TIDAK BERUBAH: startForeground()
  hanya bila app visible (probe isAppVisible), bila background
  jalan sebagai background service; promosi ke foreground terjadi
  di onCreate/onStartCommand bila app visible. Guardian tick 60
  dtk, WorkManager tanpa setExpedited di periodic, dan logging
  SenzWatchdog/SenzRevive dipertahankan.
  (d) Tanpa izin baru; tanpa ubah perilaku: eksekutor prompt/TTS/
  Studio, snapshot/thumbnail, limit detection, FileManager,
  KeepAlive per-Space.
  (e) Perbaikan audit (9 temuan, code-only, belum dibuktikan live):
  API handle() cek Bearer token SEBELUM baca body + batas body
  per-route sebelum alokasi (/upload 100 MB, JSON lain 1 MB,
  GET 0) + batas baris/jumlah header + tangkap OutOfMemoryError;
  jembatan JS (SenzLive/SenzBlob/SenzHttp) di-gate ke host home
  Space / host trap / host login Google (+ subdomain); CSP tidak
  lagi di-strip — skrip trap blob diberi nonce halaman bila ada,
  atau dimuat sebagai file same-origin dari memori; mixed content
  NEVER_ALLOW di WebView utama + popup; /thumb validasi suffix
  terhadap suffix Space yang dikenal + containment kanonis;
  FileStore.resolveUnderRoot/isUnderRoot dipakai folderDir/copy/
  move/delete/rename/newFolder (delete/rename/newFolder kini
  butuh Context); Zip-Slip bandingkan dengan dest + separator;
  backup rules kecualikan SharedPreferences token; isApiAlive
  kirim Authorization Bearer sehingga guardian tahu API hidup.
- API 76 (22 Nov, v0.1.76): WORKER FOREGROUND CRASH DIHAPUS.
  (a) Latar: app force-close tiap dibuka sejak v0.1.74. Crash log
  HP (dropbox + crash buffer): IllegalArgumentException
  "foregroundServiceType 0x00000001 is not a subset of
  foregroundServiceType attribute 0x00000000" di
  SystemForegroundService.startForeground (WorkManager internal),
  proses utama mati ~0,5 dtk sesudah start. Merged manifest
  membuktikan SystemForegroundService dideklarasikan TANPA
  foregroundServiceType.
  (b) Akar: WatchdogWorker.doWork() memanggil setForegroundAsync
  (ForegroundInfo DATA_SYNC). Crash baru muncul di v0.1.74 karena
  sebelum itu enqueue() periodik selalu gagal sehingga doWork()
  tidak pernah jalan; v0.1.74 menghapus setExpedited dari
  periodic enqueue → worker jalan pertama kali → crash pertama.
  Try/catch di doWork() tidak menolong: exception dilempar
  asinkron di main thread di dalam Handler SystemForegroundService.
  (c) Perbaikan: setForegroundAsync + createForegroundInfo()
  DIHAPUS dari WatchdogWorker — worker jalan sebagai background
  worker biasa (hanya ping socket + startPlain, tidak butuh
  foreground). setExpedited DIHAPUS dari enqueueNow() (expedited
  work wajib foreground) → one-time request biasa. Permission
  FOREGROUND_SERVICE_DATA_SYNC DIHAPUS dari manifest (tidak ada
  pemakai lain). CHANNEL_ID worker dipertahankan (dipakai
  WatchdogGuardService untuk channel notifikasinya).
  (d) TIDAK BERUBAH: semua pemanggil startPlain, probe
  isAppVisible di onCreate, guardian tick 60 dtk, isApiAlive
  Bearer, logging SenzWatchdog/SenzRevive, periodic WorkManager
  tanpa setExpedited, seluruh perbaikan audit v0.1.75, eksekutor
  prompt/TTS/Studio, snapshot/thumbnail, limit detection,
  FileManager UI. Tanpa izin baru.
- API 77 (23 Nov, v0.1.77): PULIH OTOMATIS WEBVIEW BLANK + PING
  GUARDIAN JUJUR.
  (a) WebView blank: onSaveInstanceState menyimpan state WebView
  (saveState) dan onCreate memulihkannya (restoreState) bila
  activity dibuat ulang sistem (tekanan RAM — blok loadUrl lama
  dilewati saat savedInstanceState != null); bila pulih gagal atau
  URL tetap kosong, muat URL terakhir Space ini (dari snapshot
  api-page-<suffix>.json, fallback homeUrl) dan set currentUrl.
  onRenderProcessGone + tombol reload top bar: URL kosong →
  loadUrl URL terakhir/homeUrl (reload() no-op saat URL kosong),
  URL ada → reload seperti biasa. onResume: jaring pengaman —
  Space tampak tapi WebView tanpa URL → muat URL terakhir;
  idempotent, tidak pernah memuat ulang halaman yang ada.
  Bersihkan data (EXTRA_CLEAR_DATA) tetap didahulukan atas
  pemulihan state maupun jaring pengaman onResume.
  (b) Ping guardian: isApiAlive mencatat kelas exception + stack
  trace penuh (Log.w 3 argumen) dan status line HTTP bila server
  menjawab (401 bisa dibedakan dari server mati); token tidak bisa
  dibaca → ping gagal dengan alasan di log. Token lintas-proses:
  ApiToken menulis cermin token ke file internal api-token
  (filesDir, atomik lewat tmp + rename) tiap getOrCreate /
  regenerate; readForPing baca file dari disk tiap tick (fallback
  prefs + pulihkan cermin), TIDAK PERNAH membuat token baru;
  MODE_MULTI_PROCESS tidak dipakai. File api-token dikecualikan
  dari backup (backup_rules.xml). Pola ping tetap socket mentah,
  timeout 2000 ms, tick guardian 60 dtk tidak berubah.
  Tanpa ubah perilaku: eksekutor prompt/TTS/Studio, snapshot/
  thumbnail, limit detection, FileManager, BootReceiver,
  SpaceRegistry, rute API lain, TRAP_HOSTS tidak disentuh.
- API 78 (23 Nov, v0.1.78): GUARD ANTI-LMK (PROMOTE-ON-FOREGROUND +
  MEMORYGUARD + WATCHDOG BENAR-BENAR JALAN).
  (a) Latar: sweep via /spaces — 10/10 proses Space mati dalam
  jendela yang sama sementara proses utama + API tetap hidup; limit
  free bukan penyebab (Space yang kena limit justru hidup). Akar:
  KeepAlive/ApiService/WatchdogGuardService hanya memanggil
  startForeground bila app sedang foreground (isAppVisible,
  v0.1.74); Space hampir selalu di-start dari background (API /
  boot / revive) → service jalan sebagai background service →
  proses Space = cached → LMK membunuh paling dulu.
  (b) GuardPromoter.promoteAll() (KeepAliveService.kt): memanggil
  start + promote untuk SEMUA service guard — ApiService.promote()
  + WatchdogGuardService.promote() (startService biasa + extra
  EXTRA_PROMOTE → onStartCommand menjalankan blok startForeground
  yang sama seperti onCreate, tanpa startForegroundService baru)
  + KeepAlive.start() untuk Space yang prosesnya sedang hidup
  (jangan bangkitkan Space mati — RAM justru bertambah).
  Dipanggil dari MainActivity.onResume + BrowserActivity.onResume
  (bila flag guard_needs_promotion set) dan handler API /open
  (sesudah Space dibawa ke depan). Flag guard_needs_promotion
  (SharedPreferences senzspace_ui): diset di tiap catch-block /
  cabang background startForeground yang sudah ada, di-clear saat
  promoteAll berhasil.
  (c) MemoryGuard di tick WatchdogGuardService (60 dtk): baca
  ActivityManager.getMemoryInfo — shed SATU Space per tick bila
  lowMemory atau availMem < 300 MB (SHED_AVAIL_MB = 300). Korban =
  Space background yang prosesnya hidup, snapshot terakhirnya
  TIDAK generating, paling lama tidak aktif (LRU dari file status
  live _live/<suffix>-status.txt, fallback mtime thumbnail);
  bila semua background generating → shed nol, dicatat di log.
  Mekanisme: broadcast dalam-app
  id.senzspace.debug.action.SHED_SPACE (setPackage + receiver
  dinamis di BrowserActivity, extra slot) → saveState (jalur
  v0.1.77, disimpan per-suffix untuk restoreState) lalu
  webView.destroy() + stopLiveCapture; activity + proses +
  KeepAlive tetap hidup. Pemulihan: onResume melihat flag →
  recreate() → onCreate memulihkan state shed atau URL terakhir
  dari snapshot (v0.1.77). onResume tidak pernah menyentuh
  WebView yang sudah di-destroy.
  (d) Watchdog benar-benar jalan: WatchdogGuardService di-start
  dari ApiService.onCreate (mutual watchdog), MainActivity.onCreate,
  BootReceiver, dan ReviveReceiver — startService biasa saja.
  Guard menulis heartbeat tiap tick ke filesDir/guard-heartbeat
  (epoch ms, atomik tmp+rename, pola api-token v0.1.77) +
  guard-state.json (heartbeat_ms, shed_count, last_shed_ms,
  fgs per Space via getRunningServices); penghitung shed
  dipulihkan dari state file saat service dibuat ulang.
  (e) Observabilitas: /status tambah mem_avail_mb, mem_low,
  guard_heartbeat_age_sec (-1 = guard belum pernah tick),
  shed_count, last_shed_ms; /spaces tambah fgs per Space;
  POST /guard {"action":"shed","slot":N} → paksa shed satu slot
  (test hook; action lain 400, slot tanpa Space 404); GET /guard
  → state guard yang sama. Tanpa ubah perilaku:
  WatchdogWorker, /prompt, /tts, /studio, FileStore,
  PIN/biometrik, UI, dan cara kerja v0.1.77 tidak disentuh;
  largeHeap tidak dipakai.
- API 79 (23 Nov, v0.1.79): SHED TELEMETRY + ONTRIMMEMORY.
  (a) Latar: tes live v0.1.78 membuktikan mekanisme shed jalan
  (last_dom_ms beku, restore segar) tapi telemetrinya buta:
  shed_count tidak menghitung shed manual (counter cuma di jalur
  tick guard; POST /guard memanggil sendShedBroadcast langsung
  dari proses main), guard tidak tahu shed beneran mendarat, dan
  tidak ada onTrimMemory di mana pun (guard cuma polling RAM
  tiap 60 dtk).
  (b) Satu penulis counter: guard-state.json HANYA ditulis proses
  :watchdog; counter (shedCount, lastShedMs, shedConfirmedCount)
  HANYA dimutasi tick guard. filesDir sama untuk semua proses
  (satu UID) — marker file adalah carrier antar-proses yang sah.
  sendShedBroadcast() selain kirim broadcast JUGA menulis marker
  pending filesDir/shed-pending-<slot>-<epochMs>.json (atomik
  tmp+rename); tick guard mengonsumsi marker pending (hapus
  sesudah dibaca) → shedCount++, lastShedMs = maks(sent_ms).
  Jalur tick memoryGuard() TIDAK menulis marker pending
  (writePendingMarker=false) dan tetap menaikkan langsung —
  kalau dua-duanya jalan, satu shed dihitung ganda.
  (c) Konfirmasi shed: jalur shed bersama (shedWebViewNow, dipakai
  onShedBroadcast + onTrimMemory) menulis marker
  filesDir/shed-<suffix>.json {slot, shed_ms, source} sesudah
  webView.destroy(). Tick guard membaca marker yang lebih baru
  dari watermark per-suffix (last_confirmed_shed_ms, dipulihkan
  dari guard-state.json) → shed_confirmed_count++. Marker
  konfirmasi TIDAK dihapus guard (bukti terakhir yang dibaca
  verifikasi live). /status dan GET /guard menampilkan
  shed_confirmed_count.
  (d) onTrimMemory di BrowserActivity (AppCompatActivity =
  ComponentCallbacks2; tiap proses :spaceN punya instance
  sendiri, jadi callback milik proses itu saja): pada
  TRIM_MEMORY_RUNNING_CRITICAL / TRIM_MEMORY_COMPLETE, bila
  Space ini bukan foreground (isForeground, set di
  onResume/onPause) dan snapshot terakhirnya tidak generating
  (api-page-<suffix>.json) → jalur shed yang sama
  (shedWebViewNow: saveState + destroy + marker konfirmasi)
  seketika, tanpa menunggu tick 60 dtk. Semua Space background
  non-generating boleh shed bersamaan saat kritis. JANGAN
  pasang di ApiService (proses main hanya menerima callback
  trim proses main, tidak berguna untuk Space).
  Tanpa ubah perilaku: WatchdogWorker, SHED_AVAIL_MB (300),
  /prompt, /tts, /studio, FileStore, PIN/biometrik, UI, dan
  cara kerja v0.1.77/78 tidak disentuh; foreground service
  baru tidak ditambahkan.
- API 80 (24 Nov, v0.1.80): ATTACH REAL TAP.
  (a) Latar: kirim /prompt dengan lampiran gagal sistematis
  ("tombol attach diklik tapi pemilih file tidak muncul") —
  menu item KETEMU dan status mencatat "item menu unduh
  diklik", tapi onShowFileChooser tidak pernah dipanggil:
  klik JS .click() tidak memicu <input type=file> Claude
  (kelas yang sama dengan pelajaran klik unduh JS yang mati;
  handler menu butuh trusted pointer event, atau klik
  mendarat di parent/backdrop, atau elemen tersembunyi
  lolos filter offsetParent saja). FileProvider bukan
  penyebab (file_paths mencakup SenzSpace/).
  (b) Tap beneran: clickAttachMenuItem + clickAttachButton
  tidak lagi .click() — JS hanya mengembalikan pusat rect
  elemen TERLIHAT (vis() gaya Studio: offsetParent + rect
  di viewport + computed visibility/display/opacity),
  Kotlin mengetuk via studioTapAt yang sudah ada
  (dispatchTouchEvent DOWN+UP 120 ms, pola ttsTapAt).
  Label exact didahulukan atas contains; yang TERDALAM
  menang. waitPromptChooser(20) + runPromptCheckAttached
  + cabang serving FileProvider tidak berubah.
  (c) Diagnosa chooser jujur: catch di cabang
  pendingPromptFiles onShowFileChooser menulis status
  "gagal sodorkan file _inbox (<nama>): <pesan>" bila
  serving gagal — tidak lagi diam.
  (d) Sidebar sudah-terbuka: runPromptProject cek dulu
  nama Project / "Projects"/"Proyek" sudah kelihatan —
  bila ya, langsung runPromptPickProject tanpa klik toggle;
  pencarian toggle diperluas ke button,[role="button"].
  Tanpa ubah perilaku: WatchdogWorker, /tts, /studio,
  FileStore, PIN/biometrik, UI, shed_refused_count (bukan
  build ini), dan cara kerja v0.1.77/78/79 tidak disentuh.
- API 81 (24 Nov, v0.1.81): TTS LANDING PAGE + UNDUH BLOB:NULL.
  (a) Latar: halaman generate-speech AI Studio kini landing page
  ('Create new dialog' + carousel suara), bukan dialog TTS langsung;
  eksekutor stall di 'mencari halaman TTS AI Studio' sampai dialog
  dibuka manual (tap layar). Audio hasil generate berupa
  blob:null/<uuid> (iframe sandboxed, origin opaque) — XHR dari frame
  utama selalu gagal status=0 (same-origin policy); cache base64
  hook createObjectURL miss (hit=false) dan postMessage tidak dijawab
  (tidak ada 'core: req') karena perangkap tidak pernah jalan di
  iframe pemilik blob.
  (b) Kartu dialog diketuk beneran: runTtsCreateDialog tidak lagi JS
  .click() — JS hanya mengembalikan pusat rect kartu TERLIHAT (vis()
  gaya Studio; exact-text didahulukan, contains fallback, yang
  TERDALAM menang), Kotlin mengetuk via ttsTapAt yang sudah ada
  (kali webView.scale seperti runTtsDownload). Maks 3 ketuk, lalu
  fail dengan dump inventaris. runTtsDiagnose juga memindai elemen
  kartu 'Create new dialog' (bukan cuma innerText) untuk mengenali
  landing page.
  (c) Anti-stall: cabang retry diagnose menulis status tiap percobaan;
  watchdog global (ttsStartMs; 3 menit ke editor, 10 menit total
  sesudah editor ketemu; ditangguhkan selama fallback rekam-ulang)
  dicek di tiap langkah rantai + tick berkala 60 dtk (bila callback
  evaluateJavascript berhenti kembali); kedaluwarsa -> fail via dump
  inventaris (status timeout ditulis dulu secara sinkron).
  (d) Fetch-di-konteks-pemilik: handler pesan senz-get-blob di tiap
  frame, bila cache miss, mencoba fetch(url) di frame itu sendiri
  (frame pemilik blob BISA fetch — same-origin) lalu membalas base64
  via postMessage; diag 'core: fetch-try/fetch-ok/fetch-gagal'.
  Perangkap kini jalan di SETIAP frame (iframe bersarang), hook
  attachShadow (observe + pindai shadow root yang ada), hook
  insertAdjacentHTML / setter innerHTML / Document.write/writeln,
  dan __senzTrapRescan (pindai ulang semua iframe, termasuk di shadow
  root, saat unduh; diag 'dl scan: iframes=<n> trapped=<m>').
  Koleksi iframe saat unduh sadar-shadow-DOM.
  (e) Diagnostik gagal jujur: onBlobDiag menghitung iframe ditanya /
  menjawab 'core: req' / fetch-try; onBlobError membawa hitungan itu
  (disimpan lastBlobError, dibawa ke detail gagal waitTtsFile).
  (f) Fallback MediaRecorder (hanya bila semua jalur gagal): cari
  <audio> di frame utama yang src-nya == blob URL -> captureStream
  + MediaRecorder -> putar -> rekam sampai ended (maks durasi+15 dtk,
  cap 5 menit) -> base64 -> onBlobData; onBlobFallbackStart
  memperpanjang tunggu waitTtsFile selama durasi+30 dtk (cap 5 menit)
  dan menangguhkan watchdog; play ditolak -> XHR terakhir.
  Tanpa ubah perilaku: WatchdogWorker, eksekutor PROMPT (v0.1.80),
  eksekutor STUDIO, jalur UNDUH-WEB http(s), downloadDataUrl,
  langkah TTS Style/Speaker/Run (kecuali Create-dialog + diagnose +
  watchdog), shed_refused_count (bukan build ini; ditunda v0.1.82),
  dan cara kerja v0.1.77/78/79/80 tidak disentuh.
- API 82 (24 Nov, v0.1.82): TTS BLOB:NULL — CORE TIDAK JALAN DI
  HALAMAN BARU + AUDIO DI SHADOW DOM.
  (a) Latar: tes live v0.1.81 (24 Nov 13:21) — landing page LULUS
  (dialog kebuka sendiri), tapi unduh blob:null MASIH GAGAL:
  'Diagnosa blob: xhr-error status=0 (iframe ditanya: 2, menjawab
  core: req: 0, fetch-try: tidak)'; fallback 'tidak ada <audio>
  untuk url ini'. TIDAK ADA baris 'dl scan'/'TRAP' di log 849 baris
  — __senzTrapRescan tidak terdefinisi di frame utama saat unduh
  (perangkap tidak terpasang), hit=false padahal blob dibuat 6 menit
  SESUDAH injeksi ke '/'. Pembanding 21 Nov (UI lama): mekanisme
  sama BERHASIL 2x (core: req cache=kosong -> cache=ada -> SIMPAN
  WAV). INJECT sesudah install HANYA '/' + bscframe + generate_204
  (rute /generate-speech itu SPA client-side, dokumen sama).
  (b) Heartbeat pemasangan (A1): core memanggil
  'core: installed <url>' dan trap 'trap: installed <url>
  frames=<n>' saat terpasang — bila baris ini tidak ada di log
  berikut, injeksi tidak mengeksekusi (diagnosis pasti, bukan
  tebakan). Gerbang origin jembatan (isBridgeOriginAllowed) memakai
  URL frame utama, jadi diag dari iframe sandboxed pun diterima.
  (c) Suntikan via evaluateJavascript (A2, jalur utama baru):
  onPageFinished frame utama (hanya host TRAP_HOSTS) memanggil
  ensureBlobCoreInjected(url) SEBELUM rantai executor
  (maybeRunApiPrompt/Tts/Studio) — evaluateJavascript tidak tunduk
  CSP halaman, jadi core+trap PASTI ada di frame utama. Idempoten
  (guard __senzBlobCache/__senzTrapInstalled); evaluateJavascript
  dieksekusi berurutan, jadi suntikan jalan sebelum JS executor.
  Injeksi jaringan (shouldInterceptRequest) dipertahankan. A3:
  akar injeksi jaringan tidak bisa ditentukan dari kode saja
  (kandidat: CSP halaman baru memblokir skrip hasil injeksi —
  fallback <script src> tanpa nonce bila ekstraksi nonce gagal —
  atau trap melempar saat install); A2 menutupnya, heartbeat (b)
  yang membuktikan di log.
  (d) Shadow DOM rekursif (B): walkRoots(root, fn) mengunjungi root
  + semua shadow root turunan (rekursif); trapNode memakai walkRoots
  (bungkus srcdoc iframe di shadow root, SEBELUM blob dibuat);
  pemindaian saat trap terpasang observe + trap tiap shadow root;
  __senzTrapRescan melaporkan 'dl scan: iframes=<n> trapped=<m>
  shadowRoots=<k>'. senzAllIframes saat unduh sudah rekursif sejak
  v0.1.81 (tidak diubah).
  (e) Fallback MediaRecorder baca shadow DOM (C): senzTryRecorder
  mencari <audio src==url> via findAudio rekursif (DOM + semua
  shadow root); bila ketemu di shadow root, diag '... di shadow
  DOM' ikut di baris durasi; bila ketemu tapi captureStream/play/
  MediaRecorder/rec.start gagal, diag 'fallback: audio ketemu di
  shadow DOM tapi <alasan>' sebelum XHR terakhir. Batas tidak
  berubah (durasi+15 dtk, cap 5 menit; deadline waitTtsFile).
  (f) Fetch frame utama (D): downloadBlob, sesudah ronde postMessage
  ke iframe, juga window.postMessage senz-get-blob ke DIRI SENDIRI
  (postSelf, ronde 1 + ronde +3 dtk) — handler core frame utama
  mencoba fetch(url) di konteksnya (diag 'core: fetch-try/
  fetch-ok/fetch-gagal'); menutup kasus blob dibuat JS frame utama
  tapi hook ketinggalan. Untuk blob:null ini akan gagal — tidak
  apa, jalurnya murah.
  Tanpa ubah perilaku: WatchdogWorker, eksekutor PROMPT (v0.1.80),
  eksekutor STUDIO, jalur UNDUH-WEB http(s), downloadDataUrl,
  langkah TTS Style/Speaker/Run, runTtsCreateDialog, watchdog,
  shed_refused_count (pindah v0.1.83), cacat sidebar-toggle attach
  (/new — pindah v0.1.83).
- API 83 (24 Nov, v0.1.83): TTS — DIAGNOSE ANTI-/docs + REKAM
  LANGSUNG TANPA EVENT UNDUH + ENDPOINT /tts-record.
  (a) Latar: tes live v0.1.82 (24 Nov 14:12-14:36) — suntikan core
  TERBUKTI jalan ('core: installed' + 'trap: installed' di
  generate-speech), tapi (F1) halaman mental ke /docs — 4x kirim
  TTS gagal di level rute (pindah 5-10 dtk sesudah kirim; STABIL
  180 dtk idle + 30 dtk sesudah open biasa; :aistudio HIDUP saat
  gagal; kode eksekutor tidak menavigasi; diagnose jatuh ke alur
  model v0.1.41 yang mati di UI baru); (F2) tombol unduh UI baru
  MATI — tap beneran eksekutor (189x2110, posisi terkonfirmasi
  screenshot) + tap ADB di titik sama -> NOL baris log, aistudio
  kosong, tidak ada dialog simpan (tombol tidak memicu event
  unduh; downloadBlob tidak pernah dipanggil).
  (b) Diagnose anti-/docs (A): runTtsDiagnose mengembalikan 2
  kolom baru — location.pathname + hrs (tombol Run settings
  TERLIHAT = bukti Playground lama). Sesudah 5 retry habis:
  hrs=1 -> runTtsModelFlow (satu-satunya pemanggil yang sah);
  di /docs atau di luar halaman TTS -> loadUrl beranda
  (https://aistudio.google.com/) + diagnose ulang (siklus
  dihitung ttsDiagnoseCycles, status 'halaman /docs — kembali
  ke beranda TTS (siklus N)'); di generate-speech tapi kartu
  belum ke-render -> retry terus sampai watchdog. Anggaran
  watchdog TIDAK di-reset (3 mnt ke editor / 10 mnt total).
  (c) Rekam langsung (B): waitTtsFile, 15 dtk sesudah tap unduh
  tanpa file baru (triesLeft<=3, sekali saja) -> startDirectRecord
  (nama standar 'Generated Audio <M d, yyyy - h:mma>.webm').
  directRecordJs MANDIRI (salinan senzTryRecorder tanpa URL blob):
  findAudio rekursif (document + semua shadow root) -> captureStream
  + MediaRecorder (audio/webm;codecs=opus) -> onBlobData (jalan
  simpan sama). senzTryRecorder TIDAK bisa dipakai langsung — var
  lokal di dalam IIFE downloadBlob. __senzBlobDone di-reset false
  di awal JS (unduhan sukses sebelumnya di sesi yang sama bisa
  meninggalkan flag true dan membuang rekaman); perlindungan
  ganda-simpan tetap jalan (yang selesai duluan menang). Batas
  tetap (durasi+15 dtk, cap 5 mnt; deadline waitTtsFile
  diperpanjang). Gagal keras (tidak ada <audio>/stream/perekam/
  play ditolak) -> failTts jujur SEKARANG (bukan tunggu percobaan
  habis); waitTtsFile menerima .webm. onBlobFallbackStart (bridge)
  menulis status rekam bila ttsRecordManual.
  (d) Endpoint rekam manual (C): POST /tts-record {"slot":7,
  "file":"<opsional>"} -> 202 ASINKRON {"recording":true, "file",
  "status":"queued"} (rekaman butuh durasi+15 dtk; HTTP tidak
  menunggu SIMPAN) + GET /tts-record-status (baca
  api-ttsrecord-<suffix>.json: status/detail/updated/file/bytes;
  "done" sesudah SIMPAN). HANYA slot AI Studio (cek host
  aistudio.google.com di ApiServer; slot lain -> 400). Nama
  disanitasi + dipaksa .webm (MediaRecorder = WebM, bukan WAV);
  default 'tts-record-<slot>-<epoch>.webm'. Alur: SpaceLauncher
  (EXTRA_API_TTS_RECORD_FILE) -> consumeApiTtsRecord (onCreate +
  onNewIntent; hapus extra sesudah baca) -> maybeStartTtsRecord
  (onPageFinished + langsung bila halaman sudah termuat,
  progress 100; jeda 3 dtk untuk render SPA) -> startDirectRecord
  (manual=true, tanpa pendingTtsScript). watchPickup kind
  "ttsrecord" (60 dtk gagal-jujur). saveDownloadedBytes menulis
  status done bila ttsRecordManual.
  Tanpa ubah perilaku: WatchdogWorker, suntikan core/trap v0.1.82
  (ensureBlobCoreInjected, heartbeat, walkRoots, postSelf, fetch
  frame utama), eksekutor PROMPT (v0.1.80), eksekutor STUDIO, jalur
  UNDUH-WEB http(s), downloadDataUrl, langkah TTS Style/Speaker/
  Run, runTtsCreateDialog, watchdog, shed_refused_count (pindah
  v0.1.84), cacat sidebar-toggle attach (/new — pindah v0.1.84).
- API 84 (24 Nov, v0.1.84): TTS — REKAM LANGSUNG UTUH (STABLE
  ELEMENT + TUNGGU METADATA + GATE).
  (a) Latar: rekam langsung v0.1.83 terbukti jalan tapi tidak utuh —
  (F1) in-run: rekam mulai 1 dtk sesudah generate, durasi kebaca
  1 dtk (metadata belum ke-load) -> file 0-byte tapi status "done"
  palsu; (F2) manual /tts-record: durasi kebaca 69 dtk, file WebM
  166.457 B suara asli, tapi cuma 10,3 dtk dari 68 dtk (elemen
  berhenti memberi frame ~10 dtk masuk rekam; kandidat: SPA
  mengganti elemen <audio> saat re-render, atau elemen ke-pause).
  (b) directRecordJs ditulis ulang, ASINKRON (balik 'pending';
  'no-audio'/'no-src' tetap sinkron): findAudio rekursif
  menemukan elemen SPA -> dibuat elemen <audio> BARU di main frame
  (document.body, hidden, DI LUAR tree React SPA) dengan src =
  blob URL yang sama; tunggu metadata elemen itu (readyState>=1
  + durasi finite >0, polling 250 ms, timeout 10 dtk ->
  'metadata-timeout'); play() diverifikasi (tidak paused +
  currentTime maju dalam 3 dtk -> 'play-denied'/'not-playing');
  captureStream dari elemen itu -> MediaRecorder. Bila
  stable-element gagal load (error event) -> fallback ke elemen
  SPA dengan monitor yang sama. Monitor tiap 1 dtk ke blob-debug
  ('record-monitor: t=.. ct=.. paused=.. ended=.. attached=..
  chunks=.. bytes=..'); pause yang belum ended -> play() lagi
  ('re-play'); elemen SPA ter-detach -> 'spa-detached' (gagal
  jujur). Hasil dilaporkan via bridge baru onBlobRecordFail
  (async, alasan) + onBlobRecordDone (verdict gate saat stop).
  (c) Completion gate (in-run + manual): "done" HANYA bila bytes>0
  DAN (ended ATAU currentTime>=durasi-1 ATAU wall>=durasi);
  'empty'/'short' -> onBlobRecordDone saja, TANPA onBlobData
  (tidak ada file yang boleh jadi "done"); detail status selalu
  mencantumkan detik tertangkap vs durasi audio. Gate disimpan
  di field ttsRecordGate* (di-reset tiap startDirectRecord);
  saveDownloadedBytes menulis "done" manual hanya bila verdict
  "ok"; waitTtsFile menulis "done" in-run hanya bila file .webm
  (rekam; unduhan asli .wav) lolos gate + tidak 0-byte.
  onBlobError pada rekam manual menulis "failed" supaya status
  tidak menggantung "running".
  Tanpa ubah perilaku: WatchdogWorker, diagnose anti-/docs
  v0.1.83, suntikan core/trap v0.1.82, eksekutor PROMPT (v0.1.80),
  eksekutor STUDIO, jalur UNDUH-WEB http(s), downloadDataUrl,
  langkah TTS Style/Speaker/Run, runTtsCreateDialog, watchdog,
  shed_refused_count (pindah), cacat sidebar-toggle attach (/new
  — pindah).
