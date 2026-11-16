# ADR-01 — Engine v1: WebView terisolasi + gerbang prototipe

Status: **PROTOTIPE S0 — menunggu bukti di HP Sendy**
Keputusan PRD: D3 (terkunci 16 Nov 2026) — engine v1 memakai WebView terisolasi,
dengan gerbang prototipe ini. Bila prototipe gagal di salah satu titik,
alternatif yang dievaluasi adalah engine browser embed alternatif, dengan
konsekuensi ukuran app dan RAM lebih besar. **Tidak ada fitur lain yang
dibangun sebelum prototipe isolasi + login ini lulus.**

## Yang harus dibuktikan (S0)

1. **Isolasi:** 2 Space login ke situs yang sama (claude.ai) dengan 2 akun
   berbeda secara bersamaan, tanpa saling mengeluarkan.
2. **Login pihak ketiga:** login Google (pop-up / redirect) jalan di WebView.
3. **Persistensi:** sesi tetap login setelah force-close dan restart HP
   (kecuali platform memaksa login ulang).

## Cara isolasi di S0

- Tiap Space jalan di **proses Android terpisah** (`android:process=":space1"`
  / `":space2"` di `AndroidManifest.xml`).
- Tiap proses memanggil `WebView.setDataDirectorySuffix("space1" | "space2")`
  **sebelum** WebView pertama dibuat di proses itu. Suffix berbeda =
  direktori data (cookie, storage, cache) berbeda = sesi tidak bocor.
- `CookieManager` dan storage WebView hidup di dalam data directory itu,
  jadi logout di Space 1 tidak menyentuh Space 2.

Keterbatasan prototipe yang diakui terbuka: pola "satu subclass Activity +
satu proses per Space" ini skalanya terbatas — untuk 10 Space penuh (S1),
arsitektur proses harus digeneralisasi (proses dinamis per Space atau
engine embed). S0 sengaja hanya membuktikan 2 Space dulu.

## Hasil

| Titik | Hasil | Bukti |
|---|---|---|
| Isolasi 2 akun situs sama | BELUM DIUJI DI HP | — |
| Login Google di WebView | LULUS (percobaan 4, S0.3) | Screenshot 16 Nov 18:27 WIB: Space 1 terbuka, top bar "● Claude 1 — SenzSpace S0" tampil, WebView putih kosong setelah login. Halaman login sempat tampil, jadi WebView jalan — yang blank halaman sesudah login. Dugaan: pop-up login (Google) tidak tertangani (`setSupportMultipleWindows(false)`, tanpa `onCreateWindow`) + cookie pihak ketiga ditolak + UA WebView ("; wv"). Perbaikan S0.1: pop-up dialihkan ke WebView utama, cookie pihak ketiga diterima, UA dibersihkan dari "; wv", plus Toast saat halaman gagal muat. Percobaan 2 (19:03 WIB, APK fix ba65c3a): app kebuka (force close teratasi), login SELESAI tapi halaman sesudah login MASIH blank putih, tanpa Toast error. Dugaan baru: forward pop-up S0.1 pakai shouldOverrideUrlLoading yang tidak terpanggil untuk load awal pop-up, jadi URL sempat nyangkut di pop-up tersembunyi. S0.2: forward pop-up lewat onPageStarted + panel log diagnostik di layar (MULAI/SELESAI/HTTP/JS) supaya penyebab blank bisa di-screenshot — logcat HP tidak bisa ditarik (adbd mati). Percobaan 3 (19:13 WIB, S0.2): panel log hanya menampilkan SATU warning JS dari komponen Material Google (VfPpkd-..., halaman accounts.google.com), tanpa error — alur OAuth yang patah, bukan halaman gagal muat. Diagnosis S0.3: callback OAuth Claude didesain jalan di pop-up dan menyerahkan token via window.opener.postMessage; memuat URL login di WebView utama (S0.1/S0.2) menghilangkan opener → login 'selesai' tapi blank putih. Fix S0.3: pop-up jadi WebView asli yang ditampilkan (claude.ai tetap hidup di belakang sebagai opener), onCloseWindow mengembalikan WebView utama; panel log kini menampilkan URL halaman utama di baris pertama + mencatat gagal muat subresource. Percobaan 4 (19:22 WIB, S0.3): LULUS — Claude kebuka penuh dan login ('You're here!', 'Free plan', input 'How can I help you today?', Sonnet 5.5 Medium), URL https://claude.ai/new; log hanya warning Permissions-Policy yang tidak berbahaya (local-fonts, otp-credentials, serial, usb, web-share). Temuan 20:16 WIB: login KEDUA (Space 2) selesai → kembali ke beranda malah FC ('SenzSpace has stopped', dialog Xiaomi). Dugaan: closePopupWindow() menghancurkan pop-up WebView secara sinkron di dalam callback onCloseWindow — WebView masih menyelesaikan callback itu. Fix S0.4: teardown pop-up ditunda via rootLayout.post (lepas dari parent + destroy setelah callback selesai). Temuan 20:17 WIB: setelah FC, Space 1 balik ke halaman login (https://claude.ai/login) — sesi TIDAK tersimpan. Penyebab: WebView hanya menulis cookie ke disk secara berkala; FC sebelum flush menghapus sesi yang baru login. Fix S0.4 (tambahan): CookieManager.flush() tiap onPageFinished + onPause, supaya cookie sesi langsung tertulis ke disk. |
| Sesi bertahan setelah restart | BELUM DIUJI DI HP | — |

Diisi setelah tes bahasa bayi di `README.md` dijalankan Sendy di HP.
Sampai ketiganya LULUS, MVP tidak dikunci.
