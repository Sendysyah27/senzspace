# ADR-01 — Engine v1: WebView terisolasi + gerbang prototipe

Status: **Prototipe S0 selesai 16 Nov 2026 — lanjut S1**
Keputusan PRD: D3 (terkunci 16 Nov 2026) — engine v1 memakai WebView terisolasi,
dengan gerbang prototipe ini. Bila prototipe gagal di salah satu titik,
alternatif yang dievaluasi adalah engine browser embed alternatif, dengan
konsekuensi ukuran app dan RAM lebih besar. **Tidak ada fitur lain yang
dibangun sebelum prototipe isolasi + login ini selesai.**

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

Ketiga kriteria terpenuhi di S0. Mekanisme yang ditetapkan selama prototipe:

- **Isolasi 2 akun situs sama:** Space 1 + Space 2 login akun berbeda
  secara bersamaan, tanpa saling mengeluarkan; tutup-buka app sesi
  bertahan.
- **Login Google di WebView:** pop-up login ditangani sebagai WebView
  asli yang ditampilkan (situs utama tetap hidup di belakang sebagai
  opener, supaya callback OAuth via `window.opener.postMessage` jalan);
  `onCloseWindow` mengembalikan WebView utama. Perbaikan pendukung:
  pop-up dialihkan ke WebView utama bila perlu, cookie pihak ketiga
  diterima, UA dibersihkan dari `"; wv"`, Toast saat halaman gagal muat.
  Diagnosis yang mengarah ke mekanisme ini: memuat URL login di WebView
  utama menghilangkan opener → login 'selesai' tapi halaman blank putih.
- **Persistensi sesi:** `CookieManager.flush()` tiap `onPageFinished` +
  `onPause`, supaya cookie sesi langsung tertulis ke disk (WebView hanya
  menulis cookie ke disk secara berkala; force-close sebelum flush
  menghapus sesi yang baru login). Teardown pop-up ditunda via
  `rootLayout.post` (lepas dari parent + destroy setelah callback
  `onCloseWindow` selesai) — teardown sinkron di dalam callback memicu
  force-close.

Sampai ketiga kriteria terpenuhi, MVP tidak dikunci.
