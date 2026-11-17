# Berkontribusi ke SenzSpace

Terima kasih sudah mau ikut membangun SenzSpace. Repo ini dijaga tetap sederhana dan jujur — kontribusi kecil yang rapi lebih berharga daripada perubahan besar yang sulit dilacak.

## Cara kontribusi

1. **Buka Issue dulu** untuk bug atau ide fitur — ceritakan apa yang terjadi, di versi berapa, dan langkah mengulanginya. Screenshot membantu.
2. **Fork → branch → Pull Request.** Satu PR untuk satu perubahan. Tulis apa yang diubah dan bagaimana cara mengetesnya.
3. **Ikuti gaya yang sudah ada**: slice kecil yang berurutan, Kotlin + Rust, tanpa secret di repo (lihat [Keamanan](#keamanan)).

## Aturan main

- **Baca PRD dan `docs/ADR-01.md` dulu** sebelum mengubah perilaku Space. Aturan inti (maks 10 Space, isolasi per Space, anti-salah-akun) ada di Rust core — Kotlin hanya mencerminkannya.
- **Satu perubahan, satu bukti**: tiap perbaikan disertai panduan tes (bahasa bayi, di `docs/PANDUAN-PENGEMBANG.md`) dan hasil tes di HP bila memungkinkan.
- **Jangan kurangi kejujuran app**: status login, limit, dan error platform harus tampil apa adanya — jangan disembunyikan atau dipalsukan.
- **Jangan tambahkan otomasi yang mengoperasikan akun orang lain**, melewati limit layanan, CAPTCHA, atau verifikasi.

## Rilis (untuk maintainer)

Setiap rilis **wajib** memperbarui shortcut unduhan terbaru:

1. Salin APK rilis ke `releases/SenzSpace-vX.Y.Z.apk` **dan** timpa `releases/SenzSpace-latest.apk` dengan file yang sama.
2. Perbarui tombol "Unduh versi terbaru" di `README.md` (link `SenzSpace-latest.apk` tetap — hanya nomor versi di link rilis tertentu yang berubah).
3. Buat GitHub Release dengan tag `vX.Y.Z`, isi bodinya menautkan APK terbaru dan APK versi itu.
4. Jangan hapus APK versi lama dari `releases/` — pengguna butuh jalan kembali (rollback).

## Keamanan

- **Tidak ada secret di repo**: tanpa password, token, API key, atau data sesi — di kode, file, maupun riwayat commit.
- PIN app hanya disimpan sebagai hash SHA-256 (bukan teks biasa).
- Menemukan celah keamanan? Laporkan lewat Issue (atau hubungi maintainer) — jangan dieksploitasi, jangan disebar dulu.

## Lisensi

Kontribusi yang diterima masuk di bawah lisensi MIT repo ini.
