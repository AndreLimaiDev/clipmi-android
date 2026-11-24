# Gap fitur ClipMI: Windows, Android, iOS (24 November 2026)

Dicek dari kode (kata kunci per fitur), bukan uji aplikasi satu per satu. Repo desktop: `D:\laragon\www\clip-mi`,
commit terakhir 9 November 2026 (`f2eeb18`). Sama di ketiganya: unduh YouTube/galeri, transkrip, analisis AI, editor
(potong, buang jeda, ikuti pembicara, hook, sampul, intro/outro), render, Jadwal & Statistik, analisis performa, kamus,
Paket Tim, Pemakaian AI.

## Ada di HP (Android dan iOS), belum di Windows

- Gaya analisis per project (Kajian, Podcast, Tanya-jawab, Kisah, Nasihat, buat sendiri) + panduannya.
- Model judul sampul (editorial, kapital, pop, sorot kotak), posisi X/Y judul, bilah alat sorot `*kata*`.
- Sampul dari frame tanpa bingkai, unduh sampul, garis aman TikTok/IG/Shorts.
- Kotak sorot subtitle diatur (sudut, tinggi, lebar, posisi tegak; preset Pil, Pita tengah, Stabilo, dll.).
- Template: video penuh di balik template, posisi crop bawaan gaya bingkai, slider X/Y logo.
- Pratinjau tampilan TikTok/Reels/Shorts di editor.
- Cadangkan & pulihkan (ZIP).
- Font sendiri (TTF/OTF).
- Jendela render 9:16, label Terbit di kartu klip/Hasil render, filter "Belum dijadwalkan".

Catatan: Paket Standar Tim dari HP terbaca di Windows, tetapi pengaturan khusus HP diabaikan (render bisa berbeda).

## Ada di Windows, belum di HP

- Panduan Sumber Transkrip (cara mendapatkan file SRT/VTT): belum ada di Android maupun iOS.
- Khusus PC (tidak perlu di HP): halaman dependensi, folder hasil, latar jendela.

## Selisih Android dan iOS

- iOS belum punya sakelar "Rapikan audio saat render" (fitur selalu aktif, tidak bisa dimatikan).
- Android punya "Bagikan ke TikTok/Instagram/YouTube" langsung; iOS lewat lembar bagikan biasa.
- iOS: render dan unduh harus dengan aplikasi terbuka (batasan iOS).

## Usulan urutan

1. Celah kecil HP: sakelar Rapikan audio di iOS, Panduan Sumber Transkrip di Android dan iOS.
2. Fitur HP ke Windows (repo desktop, hanya bila diminta): gaya analisis, model judul sampul, pengaturan kotak
   sorot/template supaya Paket Tim dari HP menghasilkan render yang sama di PC.
