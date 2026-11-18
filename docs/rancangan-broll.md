# Rancangan B-roll (ditunda)

Status: rancangan, belum dikerjakan (19 November 2026). Disusun dari pembahasan dengan pengguna dan dokumentasi
Pexels/Pixabay saat itu.

## Konsep

Potongan video stok pendek (2–4 detik) menutupi **area video pembicara** pada kalimat tertentu. Suara pembicara tetap
jalan; subtitle, hook, logo, watermark, dan bingkai tetap di atasnya. Suara video stok dibuang.

## Sumber video

**Pexels API** dengan key gratis milik pengguna (bukan key aplikasi).

- `GET https://api.pexels.com/v1/videos/search`, header `Authorization: <key>`.
- Parameter: `query`, `orientation=portrait`, `size` (`small` = HD cukup), `locale`, `per_page` (≤ 80), `page`.
- Respons: `videos[]` dengan `duration`, `user.name`, `user.url`, `url` (halaman Pexels), `video_files[]`
  (`quality` hd/sd, `width`, `height`, `link`), `video_pictures[]` (gambar kecil).
- Batas: 200 permintaan/jam, 20.000/bulan (header `X-Ratelimit-*`).
- Syarat: tautan "Video dari Pexels" setiap kali hasil tampil; sebut videografer bila memungkinkan; jangan meniru
  fungsi inti Pexels; jangan mengakali batas.
- Key disimpan terenkripsi (Android Keystore) seperti key lain; isian baru di Pengaturan › AI & transkrip.

**Pixabay** (sumber kedua, nanti): `GET https://pixabay.com/api/videos/`, `lang=id` didukung, `safesearch`, tetapi
tidak ada filter orientasi video, hasil wajib di-cache 24 jam, batas 100 permintaan/60 detik, wajib menampilkan
sumber saat hasil tampil.

**Video buatan AI**: belum ada yang benar-benar gratis untuk pemakaian rutin; tidak dipakai.

## Alur di editor (panel Potong, teks berjalan)

1. Tahan satu kalimat → "Sisipkan B-roll" → lembar pilih video dengan kata kunci terisi.
2. Kata kunci dari AI analisis yang sudah diatur (bahasa Inggris, karena pencarian Pexels paling bagus); bisa diubah.
3. Kisi hasil video tegak; ketuk = pratinjau, "Pakai" = unduh berkas terkecil yang ≥ 720 px lebar (±2–8 MB) ke
   `workspace/projects/<id>/broll/`.
4. Kalimat diberi tanda 🎞 dan durasinya; ketuk tanda untuk ganti video, geser titik mulai di video stok, ubah durasi,
   atau hapus.
5. "Saran B-roll": AI menandai 2–4 kalimat yang cocok beserta kata kuncinya; video tetap dipilih pengguna.

## Aturan konten kajian (bawaan, ikut prompt AI)

- Boleh: alam, langit, kota, jalan, rumah, kitab, tangan, makanan, aktivitas sehari-hari.
- Hindari: wajah menonjol, simbol agama lain, musik/tarian, pakaian terbuka, alkohol, kekerasan.
- Pexels tidak bisa disaring seketat itu, jadi video selalu dipilih pengguna; AI hanya memberi kata kunci.

## Batasan

- Total B-roll ±30% durasi klip; saran otomatis tidak di 3 detik pertama (waktu hook), sisipan manual boleh.
- Transisi potong biasa atau pudar 0,2 dtk (pilihan).
- Data di kolom baru `clips.broll` (JSON, khusus Android) → skema 10. Desktop belum mengenal B-roll; klip ber-B-roll
  hanya bisa dirender di HP.
- Pencarian/unduhan butuh internet; render tetap luring setelah video terunduh.

## Data (usulan)

```json
{"items": [{"at": 12.4, "length": 3.0, "file": "projects/6/broll/pexels-123.mp4", "offset": 0.0,
            "source": "pexels", "id": 123, "author": "Nama", "author_url": "https://…", "page_url": "https://…"}],
 "transition": "cut"}
```

`at` = detik di video sumber (seperti `cuts`), sehingga ikut bergeser bila klip dipotong; item di luar rentang klip
diabaikan.

## Render (usulan)

Setelah komposisi bingkai: tiap item B-roll menjadi input tambahan (`-ss offset -t length`), di-scale/crop ke
persegi area video (`Composition.video.rect`, radius sudut sama), lalu `overlay` dengan
`enable='between(t,a,b)'` (waktu hasil, setelah bagian yang dibuang). Subtitle dan lapisan atas tetap digambar
sesudahnya. Filter yang dibutuhkan (`scale`, `crop`, `overlay`, `fade`, `setpts`) sudah ada di build FFmpeg.

## Pengujian

Tanpa key Pexels: render dengan video lokal sebagai B-roll. Dengan key: pencarian, unduh, dan render di HP.
