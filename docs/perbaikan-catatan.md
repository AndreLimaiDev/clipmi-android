# Perbaikan UI/UX & fitur lanjutan — catatan (18 November 2026)

Setelah Fase 5, dari audit UI dan permintaan pengguna. Urutan tahap:

1. Pratinjau tetap terlihat dan edit gaya di editor (selesai, di bawah).
2. Ikuti pembicara (deteksi pembicara aktif di HP) dan koreksi manual per adegan (selesai, di bawah).
3. B-roll dari video stok (Pexels/Pixabay, key gratis milik pengguna) — perlu keputusan sumber dan aturan konten.
4. Perapian UI lain dari audit: Project, Kandidat klip, Jadwal, Statistik (selesai, di bawah).

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

## Tahap 2: ikuti pembicara dan koreksi manual (selesai)

Desktop memakai wajah terbesar per detik (MediaPipe di browser). Di HP dibuat lebih akurat:

- **Model**: BlazeFace jarak dekat dan landmark wajah dari MediaPipe Face Landmarker (Apache 2.0,
  `assets/models`, lisensi di `assets/licenses`), dijalankan **LiteRT 2.3.2** di CPU. MediaPipe Tasks tidak dipakai
  karena membawa `litertlm-android` (±21 MB) dan telemetri.
- **Frame**: satu perintah FFmpeg mengambil frame 640 px (2 per sampel jalur: tiap 0,25 dtk untuk klip ≤ 2 menit)
  dan lembar gambar kecil 32×18 15 fps untuk potongan kamera.
- **Wajah**: frame dibagi 3 potongan persegi yang tumpang (BlazeFace butuh persegi), NMS berbobot; bila tidak ada
  wajah dicoba potongan setengah ukuran untuk wajah kecil. Wajah < 50% wajah terbesar (jamaah) bukan calon.
- **Pembicara aktif** (`core/.../SpeakerAnalysis.kt`, `SpeakerAnalyzer`): wajah diikuti antarframe per adegan; bila
  ada beberapa calon, bukaan mulut (landmark 13–14 / 61–291) diukur dan yang paling bergerak dalam ±1 dtk dipilih
  (harus 1,5× lebih aktif dan pembicara bertahan ≥ 1,5 dtk). Pergantian pembicara menjadi potongan (crop pindah
  langsung), digeser ke batas kalimat transkrip ±1 dtk.
- **Jalur**: keyframe dibuat `SpeakerTrack.build` (padanan desktop, dikunci fixture 600 jalur + 103 filtergraph).
  Kolom `clips.speaker_track` (DB v8) = format desktop {start,end,keyframes} + `analysis` (hasil deteksi, waktu
  project) + `manual` (posisi per adegan). Klip yang dipendekkan memakai ulang hasil deteksi tanpa deteksi ulang.
- **Render**: bila perlu, deteksi dijalankan dulu di antrean render (notifikasi "Mendeteksi pembicara"); kartu
  pembuka memakai posisi di detik 0, sampul memakai posisi di detik sampul.
- **Editor, tab Crop**: sakelar "Ikuti pembicara" (mati = posisi tetap tanpa crop meloncat), deteksi otomatis saat
  tab dibuka, daftar adegan (ketuk = lompat), geser video = posisi manual adegan yang sedang tampil, "Otomatis lagi",
  "Deteksi ulang". Pratinjau mengikuti jalur saat diputar. Posisi manual masuk riwayat urungkan.

### Diuji di HP (Xiaomi Mi 8 Lite)

- Klip 30 (podcast dua orang, 61,8 dtk): deteksi 26 dtk; 247 frame, semua berwajah; 2 potongan kamera dan 2 pergantian
  pembicara → 5 adegan, wajah di tengah crop di tiap adegan (sebelumnya posisi tetap 50).
- Geser video di adegan 4 → hanya adegan itu bertanda "manual"; "Otomatis lagi" aktif.
- Render: frame 15/29 dtk pembicara pertama, 32 dtk pembicara kedua, 37 dtk posisi manual adegan 4.
- Tes core: `SpeakerTrackParityTest` (desktop) dan `SpeakerAnalyzerTest` (pembicara aktif, jamaah, potongan kamera,
  manual, klip dipendekkan) lulus.

### Belum diuji

- Klip ceramah satu penceramah dengan jamaah sering tampil, video vertikal, dan klip > 2 menit.
- Kartu pembuka dan sampul dengan jalur pembicara (kode sama dengan desktop, belum dilihat hasilnya).

## Tahap 4: perapian UI/UX dari audit (selesai)

B-roll (Tahap 3) ditunda atas permintaan pengguna.

- **Judul layar**: label "CLIPMI" dihapus dan judul tab lebih kecil, supaya isi naik ke atas. Judul di detail
  project juga dikecilkan.
- **Project**:
  - Kotak angka (project, kandidat, MB) dibuang.
  - Dua pintu masuk langsung: "Link YouTube" (panel tempel) dan "Dari galeri" (langsung membuka pemilih video).
    Tombol melayang "Project baru" yang menutupi kartu terakhir dihapus.
  - Pintasan "Hasil render · n video siap"; pencarian selalu tampil bila project lebih dari 3.
  - Kartu project memakai tanda tahap: ✓ Video · ✓ Transkrip · ✓ n klip (titik berwarna saat berjalan atau perlu
    tindakan).
- **Kandidat klip**:
  - Lima tombol menjadi Putar · Pilih/Terpilih · Buang · ⋯ (Buka editor, Sunting teks, Salin judul & caption).
  - Kartu hanya menampilkan hook dan alasan AI (2 baris); caption, hashtag, dan kata kunci ada di Sunting teks.
  - Bilah bawah "Render semua" yang sudah ada tetap dipakai.
  - Setelah render: tombol utama "Jadwalkan" (langsung ke jadwal klip ini) dan "Bagikan".
- **Tombol aksi vs filter**: aksi (Impor SRT/VTT, Transkripsi API, Terapkan kamus, Salin …, Tempel ke batas kata,
  ±detik, Putar video, bagikan ke aplikasi, bukti analisis) memakai `ActionChip` kotak berlatar; pil hanya untuk
  memilih/memfilter.
- **Jadwal**:
  - Tab Minggu/Bulan/Antrean digabung: strip 7 hari mendatar (titik warna akun), ketuk label tanggal untuk kalender
    bulan.
  - Agenda hari terpilih berisi kartu dengan gambar kecil video dan tombol "Jadwalkan".
  - Antrean di bawah agenda: Isi otomatis (atau Atur slot), klip siap dijadwalkan, dan "Terpilih, belum dirender"
    yang diringkas dalam satu kartu (4 teratas).
- **Siap unggah**:
  - Akun yang menunggu: Bagikan + Terbit, lalu kolom link. Lewati dan Isi statistik manual pindah ke menu ⋯.
  - Akun yang sudah terbit/dilewati diringkas satu baris (tayangan · suka); ketuk untuk detail.
- **Statistik**:
  - Empat kotak ringkas (Tayangan, Suka, Komentar, Share) dan satu kartu per akun (postingan, median, terbaik,
    total).
  - Kartu "Analisis performa" yang jelas, menggantikan ikon ✦.
  - Urutan Terbaru/Terbaik. Kartu postingan ringkas (tayangan dan ×median); ketuk untuk detail (angka lain,
    engagement, hari 1/7/30, Buka jadwal, Ambil ulang).
- **Pengaturan**:
  - Grup baru: AI & transkrip, Tampilan klip (termasuk Rapikan audio), Jadwal & statistik, Data (termasuk Hasil
    render), Aplikasi.
  - "Pembaruan aplikasi" hanya tampil bila build punya repo rilis; "Uji Fase 0" hanya di build debug.

### Diuji di HP

Project, detail project (tab Transkrip dan Klip), Jadwal (strip minggu, agenda Kamis dengan gambar kecil), Siap
unggah (akun terbit ringkas; akun menunggu dengan menu ⋯), Statistik (kartu postingan dibuka), dan Pengaturan.

### Belum dikerjakan dari audit

- Editor: strip gambar (filmstrip) di garis waktu dan daftar baris subtitle di bawah video. Tombol Render tetap di
  atas sesuai permintaan.
- Pola pesan (snackbar untuk konfirmasi, banner untuk yang perlu tindakan) belum diseragamkan di semua layar.
- Belum dicoba: menu ⋯ kandidat dan akun, tombol Jadwalkan setelah render, Isi otomatis dari antrean baru.
