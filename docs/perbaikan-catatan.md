# Perbaikan UI/UX & fitur lanjutan — catatan (18 November 2026)

Setelah Fase 5, dari audit UI dan permintaan pengguna. Urutan tahap:

1. Pratinjau tetap terlihat dan edit gaya di editor (selesai, di bawah).
2. Ikuti pembicara (deteksi pembicara aktif di HP) dan koreksi manual per adegan — perlu izin menambah MediaPipe.
3. B-roll dari video stok (Pexels/Pixabay, key gratis milik pengguna) — perlu keputusan sumber dan aturan konten.
4. Perapian UI lain dari audit: Project, Kandidat klip, Jadwal, Statistik.

Tombol Render di editor tetap di tempatnya (permintaan pengguna).

## Tahap 1: pratinjau tetap dan edit gaya di editor (selesai)

- **Detail project**: video tetap di atas seperti YouTube; transkrip/daftar klip bergulir di bawahnya. Segmen yang
  sedang diputar ditandai dan ikut berpindah.
- **Gaya subtitle / Gaya bingkai**: pratinjau tetap di atas, isian bergulir di bawahnya, tombol Perkecil/Perbesar
  (ukuran diingat selama aplikasi terbuka).
- **Edit gaya di editor** (padanan `EditsClipStyles` desktop): di tab Gaya, ketuk kartu lain untuk memakainya; kartu
  yang terpilih menampilkan ikon pensil di tengah dan membuka edit gaya itu (usulan pengguna, menggantikan tombol teks
  "Ubah …" di judul bagian). Isian muncul di panel bawah dan pratinjau editor langsung memakai isian yang belum
  disimpan. Simpan:
  - "Simpan ke gaya ini" — klip lain yang memakai gaya itu ikut berubah (jumlahnya ditampilkan).
  - "Simpan sebagai gaya baru" — dipakai klip ini saja; nama yang tidak diganti diberi nomor ("Karaoke Kuning 2");
    gaya bingkai baru membawa salinan gambar sendiri.
  - Batal/kembali dengan perubahan: konfirmasi "Buang perubahan?".
- Isian gaya kini satu komponen bersama (`CaptionStyleFields`, `FrameStyleFields`) untuk layar Gaya dan editor.

### Diuji di HP (Xiaomi Mi 8 Lite)

- Detail project: video terus diputar dan tetap di atas saat transkrip digulir; tanda segmen berpindah 00:07 → 00:25.
- Gaya subtitle: warna garis tepi merah langsung terlihat di pratinjau yang tetap di atas; Perkecil membuat isian lebih
  lega; kembali → "Buang perubahan?" → Buang, gaya tidak berubah.
- Editor: Ubah "Karaoke Kuning" → font Anton langsung terlihat di pratinjau editor; dialog simpan menampilkan jumlah
  klip lain; "Simpan sebagai gaya baru" membuat "Karaoke Kuning 2" (Anton) untuk klip ini, gaya asli tetap Archivo
  Black; Urungkan mengembalikan klip ke gaya asli.
- Editor: Ubah "Crop penuh" → Video utuh langsung terlihat (latar blur); Batal → Buang, gaya tetap "crop".

### Perbaikan dari uji itu

- Teks jumlah klip di dialog simpan menghitung klip ini juga ("6 klip lain" padahal 5): sekarang jumlah klip lain.
- Ikon pensil di kartu terpilih (menggantikan tombol "Ubah …"): dibangun dan dipasang, belum dilihat di layar (HP
  terkunci).

### Data uji tersisa

- Gaya subtitle "Karaoke Kuning 2" (dibuat saat uji; tidak dipakai klip mana pun, bisa dihapus dari Gaya subtitle).
