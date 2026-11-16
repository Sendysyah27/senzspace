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
| Login Google di WebView | BELUM DIUJI DI HP | — |
| Sesi bertahan setelah restart | BELUM DIUJI DI HP | — |

Diisi setelah tes bahasa bayi di `README.md` dijalankan Sendy di HP.
Sampai ketiganya LULUS, MVP tidak dikunci.
