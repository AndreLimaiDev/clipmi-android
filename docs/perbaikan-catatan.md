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

## Tahap 5: masukan pengguna untuk halaman Jadwal

- **Kalender bulan melayang**: ketuk label minggu untuk membuka kalender bulan sebagai popup di bawahnya (seperti
  menu ⋯), tanpa menggeser agenda. Bulan bisa digeser di dalam popup; ketuk tanggal untuk memilih, ketuk di luar
  untuk menutup.
- **Antrean tertutup secara bawaan**: satu baris "Antrean" dengan ringkasan jumlah (siap dijadwalkan · belum
  dirender) dan panah; ketuk untuk membuka.
- **Hierarki antrean**: subjudul kecil redup ("Siap dijadwalkan · n", "Terpilih, belum dirender · n"), tiap klip
  belum dirender jadi kartu sendiri dengan gambar kecil (seperti baris agenda), jarak antarkartu 12dp.
- **Hari kosong**: kartu lembut bergaris putus-putus (`DashedCard`) dengan "+ Jadwalkan klip"; tombol Jadwalkan di
  judul hari hanya tampil bila hari itu sudah ada jadwalnya.
- **Filter akun**: baris chip digeser sampai tepi layar (`Modifier.bleed()`), juga di Statistik. Baris chip lain
  yang masih berhenti di gutter (Performa, Hasil render, editor, gaya) menyusul saat halaman itu dibahas.
- Dicek di HP: kalender melayang dan pilih tanggal, hari kosong, antrean tertutup lalu dibuka (20 belum dirender,
  durasi kini di depan judul project). Kelompok "Siap dijadwalkan" belum terlihat karena antrean siap sedang kosong.
- Revisi: garis putus-putus 1dp; kartu hari kosong hanya "+ Jadwalkan klip" (seluruh kartu bisa diketuk); kalender
  bulan jadi lapisan di pohon yang sama (bukan `Popup` yang membuat jendela baru) dengan bayangan 6dp.

## Tahap 6: detail jadwal (Siap unggah)

- Gambar kecil video bisa diketuk untuk memutar (ikon ▶); judul project dan tombol "Putar video" dihapus.
- Lencana progres "n/m akun terbit" (atau Terlewat/Semua terbit) menggantikan "Terjadwal".
- Teks unggahan: Judul, Caption, Hashtag masing-masing dengan ikon salin, plus satu tombol "Salin semua".
- Kartu akun menunggu hanya berisi Bagikan ke <platform> dan Terbit. Terbit membuka lembar "Terbit di <akun>"
  dengan kolom link opsional (tombol Tempel dari clipboard), "Tanpa link", dan "Simpan".
- Label Bagikan selalu menyebut platform; bila aplikasinya tidak terpasang, terbuka menu bagikan biasa.
- Pesan dipersingkat ("Terlewat sejak …", "File hasil render tidak ditemukan.", "Klip diubah setelah dirender.").
- Dicek di HP: tampilan jadwal 19 Nov dan lembar Terbit (ditutup tanpa menyimpan). Simpan link, Tempel, dan kartu
  akun yang sudah terbit belum dicoba.
- Kartu akun terbit: satu baris "Terbit 18 Nov · 369 tayangan" dengan lencana "Terbit" dan menu ⋯ (Ubah/Tambah
  link, Isi statistik manual, Kembalikan ke Menunggu). Ketuk untuk empat angka kecil, link satu baris (ketuk untuk
  membuka), dan "Diperbarui … · sumber" dengan ikon ambil ulang. Kolom link, kalimat statistik panjang, dan tombol
  terpisah dihapus. Akun dilewati: lencana "Dilewati" dan menu ⋯. Dicek di HP (jadwal 2); Simpan link belum dicoba.

## Tahap 7: detail project

- Bagian atas: ikon hapus di atas video diganti menu ⋯ (Hapus project). Keterangan "YouTube · 49:59" dengan lencana
  kecil asal transkrip (Otomatis, Manual, SRT, VTT, Whisper, API) dan ikonnya; jumlah segmen dihapus.
- Transkrip: Terapkan kamus, Impor SRT/VTT, dan Transkripsi ulang di menu ⋯ samping kolom cari (tampil langsung
  hanya bila belum ada transkrip atau transkripsi berjalan). Kalimat petunjuk dihapus.
- Klip: kartu AI jadi dua tombol kecil (Analisis lagi dengan konfirmasi token, Lewat chat AI) bila klip sudah ada.
  Filter kosong disembunyikan, petunjuk geser dihapus.
- Kartu klip: skor dalam kotak membulat, judul, ringkasan (alasan AI), durasi dan lencana status. Ketuk untuk detail
  (rentang waktu, hook, aksi Putar · Editor · Render · ⋯). Tombol aksi kotak 12dp (`ActionChip`, `primary`).
  Tombol Pilih dihapus: geser kanan = Pilih/Pulihkan, kiri = Buang/Batal pilih; juga ada di menu ⋯.
- Bug geser: daftar klip tanpa kunci + status `SwipeToDismissBox` yang tertinggal di posisi yang sama membuat klip
  berikutnya ikut terpilih/terbuang. Diganti geser buatan sendiri (`draggable`) dengan `key(clip.id)`; aksi hanya
  jalan bila dilepas setelah lewat separuh lebar kartu (latar jadi warna penuh dan HP bergetar sebagai tanda).
- Dicek di HP: geser pendek tidak berbuat apa-apa, geser penuh hanya mengubah satu kartu (10 → 9 → 10 terpilih),
  kartu dibuka menampilkan hook dan aksi. Analisis lagi, Render, dan menu ⋯ kartu belum dicoba.
- Edit teks transkrip: tekan lama satu segmen → ikon pensil di bilah pilihan → lembar "Edit teks". `SegmentEditor`
  (core) menjaga waktu kata yang tidak berubah, membagi ulang hanya kata yang berubah di rentang kata lamanya, dan
  kata sisipan tanpa celah berbagi waktu dengan tetangganya. Klip yang tumpang tindih ditandai berubah (`touchRange`,
  "Hasil lama"). Tes `SegmentEditorTest` (7) lulus. Dicek di HP: tambah kata lalu hapus lagi pada segmen 00:11;
  teks dan waktu kata kembali persis seperti semula.

## Tahap 8: editor

- **Umum**: tombol putar berupa ikon putih tanpa lingkaran; bilah geser tipis untuk pindah di video hasil; petunjuk
  di samping tombol putar dan judul panel dihapus. Semua slider editor: jalur 4dp dan bulatan putih
  (`sliderColors`, `SliderThumb`). Editor menyusut saat keyboard terbuka (`imePadding`).
- **Potong + Teks digabung** (tab Teks dihapus; tab: Potong · Crop · Gaya · Hook · Sampul): timeline selebar layar
  (ketuk = lompat, tahan-geser = telusuri; ujung tidak diseret lagi) dengan tombol ◀ ▶ mulai di kiri dan selesai di
  kanan (ketuk 0,1 dtk, tahan = terus bergeser makin cepat). Chip "Mulai di sini" / "Selesai di sini" untuk
  perpindahan jauh, Buang jeda, Tempel ke batas kata, + Kalimat. Di bawahnya teks klip yang berjalan mengikuti
  video (kalimat aktif bergaris aksen, kata yang terdengar putih), dengan kartu Intro di awal dan Outro di akhir
  (lembar pilih dari pustaka; bagian intro/outro di Sampul dihapus).
- **Crop**: kartu adegan ringkas ("5 · 00:29", titik kuning bila diatur manual) dan ikut bergeser mengikuti video;
  keterangan panjang dihapus; tombol kotak.
- **Gaya**: ikon pensil kecil di pojok kartu terpilih (tidak menutupi contoh).
- **Hook**: tombol Simpan teks dihapus; tersimpan 0,8 dtk setelah berhenti mengetik dengan centang hijau sebentar.
- **Sampul**: tombol kotak, petunjuk dihapus. Judul sampul punya model (kolom baru `cover_layout`, skema 9, khusus
  Android): Klasik (sama dengan desktop, bawaan), Editorial, Kapital tebal, Pop, Sorot kotak, Tulisan tangan
  (`CoverTitle` di core, ASS di atas frame jadi). Teks sampul (Enter = baris, `*kata*` = sorotan; tanpa bintang =
  kata kunci), warna sorotan, posisi, ukuran. Huruf baru: Instrument Serif Italic (OFL) dan Permanent Marker
  (Apache 2.0) di `assets/fonts` beserta lisensinya.
- Tes: `CoverTitleTest` (5) lulus. Dicek di HP (klip 55): teks berjalan, kelima model sampul dirender (lalu
  dikembalikan ke Klasik dan judul mati), kartu adegan ikut video, hook tersimpan sendiri (lalu dikembalikan),
  kolom hook di atas keyboard. Belum dicoba: tombol ◀ ▶ ditahan, lembar Intro/Outro, Mulai/Selesai di sini.
- Rentang klip 55 berubah ke 34:03,2–35:50,5 pada 18 Nov 22.37, bukan dari pengujian ini.
- Revisi: satu komponen slider untuk seluruh aplikasi (`ClipMiSlider`, `ClipMiRangeSlider` di Components: jalur
  4dp, bulatan putih) dipakai di editor, Gaya subtitle, dan Gaya bingkai (`IntSlider`).
- Logo bingkai: pratinjau editor kini menggambar logo (posisi sama dengan render). Saat gaya bingkai diedit di
  editor, logo diseret langsung di pratinjau (posisi bebas 0–100). Kotak "Posisi logo"/"Posisi watermark" jadi
  layar mini 9:16: ketuk titik jangkar untuk menempel, seret titik hijau untuk posisi bebas (juga di Pengaturan).
  Dicek di HP: logo ikut jari, slider tipis; perubahan uji dibuang (Batal → Buang).

## Tahap 9: transisi adegan ikuti pembicara

Penyebab perpindahan adegan terasa patah: (1) pergantian pembicara di gambar kamera yang sama dibuat loncat seperti
potongan kamera; (2) potongan kamera dicari di 15 gambar/detik sehingga titik pindah crop bisa meleset 1–2 gambar;
(3) pratinjau menghitung crop dari jam pemutar yang bisa berbeda ±60 ms dari gambar yang tampil.

- `SpeakerTrackData.Analysis.switches` (baru, kosong untuk deteksi lama) menyimpan pergantian pembicara terpisah dari
  potongan kamera. `SpeakerTrack.build(..., switches)`: di pergantian pembicara crop menoleh 0,45 dtk dengan
  smoothstep (6 keyframe); potongan kamera tetap pindah langsung. Paritas desktop tidak berubah (tes lulus).
- Potongan kamera dicari di setiap gambar video (fps asli, maksimal ±30; 15 untuk klip > 5 menit); jendela pembanding
  `cameraCuts` mengikuti fps.
- Pratinjau: `ShownFrameClock` memakai waktu presentasi gambar dari `VideoFrameMetadataListener` (tanpa offset di
  Media3 1.11.1, diperiksa di HP), jatuh ke jam pemutar bila selisih > 1 dtk.
- Dicek di HP: klip 55 → 0 potongan kamera, 8 ganti pembicara (semua adegannya pergantian pembicara, kini halus);
  klip 30 → 2 potongan kamera (28,02 dan 44,40 dtk, sebelumnya 28,03/44,43) dan 2 ganti pembicara.
- Klip yang dideteksi sebelum perubahan ini perlu "Deteksi ulang" agar pergantian pembicaranya halus.

## Tahap 10: Project, Statistik, Pengaturan

- **Project**: kartu Hasil render jadi tombol ikon di kanan judul dengan angka video siap (biru bila ada yang
  dirender). Filter Semua/Siap hanya tampil bila memisahkan project. Kartu project siap: "YouTube · 18 Nov" dan
  "10 klip · 1 terpilih · 1 dirender" (`observeCounts` kini menghitung terpilih dan dirender); tahap ✓ hanya untuk
  project yang belum selesai. Ukuran MB dihapus.
- **Statistik** (mengikuti referensi desain): subjudul "31 hari setelah terbit · diambil otomatis"; filter satu akun
  (Semua + chip bertitik warna); kartu ringkasan dengan total tayangan besar, engagement di kanan atas, dan
  suka/komentar/share berikon; "Per akun" dengan batang pembanding (ketuk = filter akun itu; hanya di Semua); kartu
  Analisis performa gelap; urutan Terbaru/Terbaik bersegmen; postingan dengan gambar, angka sebaris berikon, dan
  saat dibuka: empat angka, "Tayangan per periode" (hari ke-n dari 31, kotak hari 1/7/30 bergaris putus dengan
  tanggal tersedia), sumber data, Buka jadwal / Ambil ulang. Catatan panjang di bawah dihapus.
- **Pengaturan**: lencana Siap diganti ikon lingkaran centang hijau (`ClipMiIcons.CircleCheck`). Gaya subtitle dan
  bingkai jadi satu menu "Gaya klip" dengan tab Subtitle | Bingkai (`StylesHeader`). Keterangan dipersingkat
  (Kosong, 4 pemakaian, Kurangi noise…, Key tersimpan, 6 video, Terpakai 1,4 GB, Versi … · data hanya di HP ini);
  tombol Perbarui yt-dlp kotak.
- Dicek di HP: ketiga halaman, kartu postingan dibuka, tab Gaya klip. Belum: filter satu akun di Statistik.

## Tahap 11: Hasil render, Akun sosmed, Data & penyimpanan, Intro & outro

- **Hasil render**: lokasi galeri sekali di bawah judul; filter Semua · Siap unggah · Hasil lama · Sudah
  dijadwalkan (hanya yang ada isinya). Kartu: gambar bertanda ▶ (ketuk = putar), judul, "01:47 · 78 MB", lencana
  (Siap unggah, Dijadwalkan, Hasil lama, File hilang); satu baris Jadwalkan/Lihat jadwal atau Render ulang, Bagikan,
  dan ⋯ (Bagikan ke aplikasi terpasang, Buka editor, Buka project, Render ulang). Resolusi dihapus.
- **Akun sosmed**: ikon platform (garis sederhana, bukan logo resmi) di kotak warna akun; slot sebagai chip jam;
  catatan bawah dihapus.
- **Data & penyimpanan**: ringkasan sisa ruang besar dengan batang folder kerja/video hasil; video sumber dalam satu
  kartu (judul, ukuran, ikon hapus atau unduh/pilih ulang); keterangan cadangan dan paket satu baris; tombol kotak.
- **Intro & outro**: tambah lewat ikon + di kanan atas (tombol melayang dihapus), ketuk kartu untuk memutar
  (FileProvider kini juga membagikan `workspace/bumpers/` saja), Hapus di menu ⋯. **Gaya klip**: "+ Gaya baru"
  juga pindah ke ikon + di kanan atas.
- Dicek di HP: keempat halaman dan Gaya klip. Belum dicoba: memutar intro dari kartu, menu ⋯ hasil render.
- Perbaikan: "Lihat jadwal" di Hasil render kini membuka jadwal terdekat klip itu (Siap unggah), bukan form jadwal
  baru. Dicek di HP (klip "Jin Bisa Menyamar…").
- Catatan uji: saat audit, satu ketukan mengenai "Render ulang" klip 55 (Amalan Rahasia) yang berstatus Hasil lama
  karena uji simpan otomatis hook; render ulang berjalan (isi klip sama), sempat terputus oleh pemasangan APK lalu
  dilanjutkan WorkManager.

## Tahap 12: halaman sisa

- **Jadwalkan / Ubah jadwal**: judul project di bawah judul klip diganti durasi; akun tujuan dengan ikon platform,
  lebih rendah, centang lingkaran di kanan; batas karakter per platform satu baris redup (merah hanya bila lewat
  batas); tombol Jadwalkan/Simpan selebar layar di bawah, Hapus jadwal jadi ikon tempat sampah.
- **Edit gaya subtitle/bingkai**: pratinjau kecil secara bawaan dengan ikon perbesar/perkecil; pilihan font sebagai
  kartu "Aa" dengan huruf aslinya; semua pilihan (`ChoiceRow`: efek, posisi, animasi, dll.) satu baris yang digeser
  ke samping; Simpan tombol kotak.
- **Kamus koreksi**: satu baris "allah › Allah" dengan sakelar; Terapkan ke semua di menu ⋯; tambah lewat ikon +;
  petunjuk dipindah ke kartu kosong (contoh).
- **Pemakaian AI**: ringkasan total token besar dengan perkiraan biaya; daftar per tanggal, tiap pemakaian satu baris
  berikon (analisis/transkripsi) dengan token ringkas ("12,8 rb token").
- **Analisis AI / Transkripsi API**: pembuka dan keterangan dipendekkan; daftar penyedia lebih rendah; Simpan (dan Uji
  koneksi) tetap di bawah layar.
- Dicek di HP: semua halaman di atas (form jadwal dibuka tanpa disimpan).
