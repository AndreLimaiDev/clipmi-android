# Fase 3: Editor & Render — catatan (17 November 2026)

## 3a. Port render inti ke `core` (selesai)

Paket `id.clipmi.core.render`, padanan langsung kode render desktop (`app/Render`, `FrameStyle`, `CaptionStyle`,
`ClipCuts`), dikunci tes `RenderParityTest` dari `tools/export-render-fixtures.php` (kode desktop dijalankan apa adanya
dengan PHP desktop 8.3.36 dan SQLite di memori):

| Bagian | Padanan desktop | Kasus uji | Hasil |
|---|---|---|---|
| `FrameStyle`, `Composition` | geometri bingkai, `Composition::make/forClip`, `anchorExpression`, `summary()` | 800 gaya bingkai acak (4 rasio × 4 mode × latar × bingkai video × logo × watermark × bilah judul × ukuran sumber × crop/zoom) | identik |
| `RenderGraph.filter` | `ClipRenderer::filter/frame/background` | 800 filtergraph (sama dengan di atas, + input logo/latar/template, + potongan) | identik byte demi byte |
| `AssWriter` | `AssWriter::build` | 500 file ASS acak: 6 font, efek, karaoke warna/kotak, pop/reveal/fade, kotak baris, kata kunci, bilah judul, 4 rasio, hook 4 model × 3 animasi × posisi × waktu, 79 kotak hook bersudut membulat | identik byte demi byte |
| `TextWrap` | `TextMeasure::wrap` (pembungkus seimbang) | lewat kasus hook membulat di atas, lebar teks dari GD desktop | identik |
| `HookLayout` | `resolve`, `merge` | 400 + 5 | identik |
| `SubtitleBuilder` | `linesFor`, `applyEdits`, `editLine` | 70 klip pada 80 segmen sungguhan (26 dikoreksi, sebagian lalu digeser rentangnya) | identik |
| `ClipRender` | `relativeLines`, `previewTracks`, `Clip::cutRanges/outputDuration/hookLayout/leadIn` | 70 klip (21 dengan kata/jeda dibuang/disembunyikan) | identik |
| `ClipCuts` | `normalize/add/subtract/kept/removed/map/covers/selectExpression` | 300 | identik |
| `Keywords` | `mark`, `toggle` | 6 + 6 | identik |
| `RenderGraph.joinBumpers/opener/audioFilter` | intro/outro, kartu pembuka, audio | 4 + 4 + 2 | identik |

Catatan:

- FFmpeg di HP dibangun LGPL, jadi `boxblur=R:2` desktop diganti `RenderGraph.lgpl()` menjadi `avgblur` dua kali tepat
  sebelum dijalankan (setara secara visual, uji Fase 0: SSIM 0,993). Filtergraph yang diuji tetap teks desktop asli.
- Lebar teks kotak hook bersudut membulat di desktop diukur GD (FreeType). Di tes, lebar diambil dari ekspor desktop;
  di HP perlu pengukur FreeType (JNI, library yang sama dengan libass) — dikerjakan di 3b dan dibandingkan dengan angka
  yang sama.
- "Ikuti penceramah" (jalur crop dari deteksi wajah) belum ada di HP: posisi crop selalu tetap (`keyframes` null).
- Intro/outro dari pustaka belum ada; kartu pembuka hook sudah dihitung (`ClipSpec.leadSeconds`).

## 3b. Render di HP (selesai, diuji di Xiaomi Mi 8 Lite)

- **Database v4**: tabel `caption_styles` dan `frame_styles` (kolom dan gaya bawaan sama dengan desktop: Bawaan,
  Karaoke Kuning, Kotak Biru; Crop penuh, Video utuh latar blur/hitam, Video di atas latar gelap), kolom editor/render
  klip (`caption_style_id`, `frame_style_id`, crop, `cuts`, `subtitle_lines`, `hook_layout`, `render_*`,
  `output_path`, `rendered_at`, `edited_at`), dan `projects.crop_position`. Migrasi 3 → 4 di HP: data lama utuh.
- **`Renderer`** (padanan `ClipRenderer::render`): komposisi, filtergraph, dan ASS dari core; folder kerja berisi font,
  `subs.ass`, `watermark.txt`, dan gambar bingkai; kartu pembuka hook (frame pertama dikaburkan + transisi) dengan hook
  di subtitle terpisah; encoder `h264_mediacodec` (NDK, nv12 → yuv420p → JNI); 6 Mbps untuk 1080×1920; audio
  dirapikan (bisa dimatikan di Pengaturan → Render); hasil ke Movies/ClipMI/<judul project>/<nomor> - <judul>.mp4
  lewat MediaStore (hasil lama diganti saat render ulang).
- **Pengukur teks** kotak hook membulat: FreeType lewat JNI (`cpp/text_measure.c`) meniru `imagettfbbox()` GD — **400
  dari 400 lebar teks identik** dengan desktop (Paint Android meleset rata-rata 10 px, maksimal 58 px).
- **`RenderWorker`** di antrean render: progres di notifikasi dan di kartu klip, batal, pemulihan setelah proses
  dimatikan sistem (`RenderQueue.resumeInterrupted`).
- **UI**: kartu klip terpilih punya baris render (Render klip → antre/progres + Batalkan → Bagikan [video terlampir,
  caption & hashtag disalin], Putar, Render ulang; tanda "Hasil lama" bila judul/hook diubah setelah render); bilah
  "N klip terpilih · Render semua" di tab Klip.
- **FFmpeg dibangun ulang**: filter `aselect` (bagian klip yang dibuang, untuk audio) ternyata belum aktif di build
  Fase 0.

| Uji di HP | Hasil |
|---|---|
| Klip 36,5 dtk, Crop penuh, gaya Bawaan, hook kotak gelap | 24 dtk (1,5× lebih cepat dari durasi), 1080×1920, 6,0 Mbps, AAC 128k 48 kHz |
| Klip 54 dtk, Video utuh latar blur (avgblur), Karaoke Kuning, hook "Kotak warna" sudut 24 + kartu pembuka | durasi 56,0 dtk (2 dtk pembuka + isi), hook membulat di atas pembuka yang dikaburkan |
| Klip 56,3 dtk, Video di atas latar gelap, Kotak Biru (karaoke kotak + warna kata berikutnya + kata kunci), kata 1,5 dtk dan jeda 0,8 dtk dibuang, 1 kata disembunyikan | durasi 54,0 dtk (tepat), −13,9 LUFS |

Belum: layar Hasil tersendiri (sementara hasil dikelola dari kartu klip), jeda otomatis saat baterai rendah/HP panas,
sampul, intro/outro dari pustaka, dan gambar logo/latar/template (butuh pengelola gaya, 3d).

## 3c. Editor sentuh (selesai, diuji di Xiaomi Mi 8 Lite)

- **Logika editor di core** (`ClipEditor`, `ClipSilence`): padanan `App\Analysis\ClipEditor` desktop — geser
  mulai/selesai, tempel ke batas kata, perpanjang satu segmen, atur rentang dari pegangan, buang/kembalikan kata,
  sembunyikan kata dari subtitle, ubah teks kata (waktu per kata tetap bila jumlahnya sama), jeda (deteksi, buang satu,
  buang semua ≥ n detik). Dikunci `EditorParityTest`: **40 urutan × 12 operasi acak** dari desktop
  (`tools/export-editor-fixtures.php`), termasuk penolakan dan pesan galat, keadaan klip + daftar kata + jeda identik
  setiap langkah.
- **Layar editor** (selalu gelap, dibuka dengan mengetuk kartu klip): bilah atas (tutup, urungkan, ulangi, Render),
  pratinjau, panel tab Potong · Teks · Crop · Gaya · Hook. Setiap perubahan langsung disimpan (`edited_at` ikut
  diperbarui, jadi hasil render lama ditandai); urungkan/ulangi 50 langkah; satu gerakan seret = satu langkah.
- **Pratinjau**: ExoPlayer di TextureView yang ditata sesuai `Composition` (cover/contain, crop X/Y, zoom, area video,
  sudut membulat), latar blur/warna/gradasi, dan subtitle + hook digambar libass dari **ASS yang sama dengan render**
  (dua lapisan: subtitle dengan waktu video hasil, hook dengan waktu sejak awal pembuka). Bagian yang dibuang dilompati
  saat diputar.
- **Potong**: timeline ±10 detik (kata dari transkrip, bagian dibuang, penanda putar), pegangan mulai/selesai yang
  menempel ke batas kata dengan getaran halus, tombol ±0,1/±0,5 detik, tempel ke kata, + kalimat sebelum/sesudah.
- **Teks**: kata per baris subtitle — ketuk = buang/kembalikan, tahan = ubah teks/sembunyikan/kata kunci/putar dari
  sini; jeda terdeteksi tampil sebagai chip (ketuk = buang) dan tombol "Buang jeda ≥ 0,6 dtk".
- **Crop**: geser dan cubit di pratinjau, atau penggeser posisi kiri–kanan, atas–bawah, zoom 100–250%.
- **Gaya**: pilih gaya subtitle (contoh huruf dari font yang dibundel) dan bingkai.
- **Hook**: teks, tampil/sembunyi, model, animasi, ukuran, sudut kotak, waktu tampil, kartu pembuka, posisi (seret di
  pratinjau) atau posisi bawaan.
- FFmpeg dibangun ulang lagi dengan `volumedetect` dan `silencedetect` (deteksi jeda).
- Uji di HP (klip 26): kelima tab tampil; buang kata langsung terlihat di subtitle pratinjau; tarik pegangan mulai
  menempel ke batas kata; seret hook memindahkan posisinya; urungkan mengembalikan data persis. Diperbaiki saat uji:
  video pratinjau ikut dipusatkan otomatis oleh Compose (crop tampil di sisi yang salah), geser crop terlalu peka dan
  hilang saat pelan (kini dihitung dari awal gerakan, video tepat mengikuti jari), dan crash saat jari diangkat dari
  pratinjau di tab Hook (titik tengah NaN).
- Catatan: bila kartu pembuka hook aktif, hook hanya tampil selama pembuka (sama dengan desktop), jadi saat video
  diputar di pratinjau hook tidak terlihat; di tab Hook hook selalu digambar.

## 3d. Pengelola gaya, sampul, intro/outro, Hasil (selesai, diuji di Xiaomi Mi 8 Lite)

- **Database v5**: tabel `bumpers` (pustaka intro/outro), kolom sampul klip (`cover_time`, `cover_path`, `cover_title`,
  `cover_in_video`), intro/outro per klip dan bawaan project (`intro_id`, `outro_id`; klip baru ikut bawaan project).
  Migrasi 4 → 5 disimulasikan di PC (skema v4 + SQL migrasi dibandingkan dengan skema v5: kolom, nilai bawaan, foreign
  key, dan indeks identik).
- **Gaya Subtitle** (Pengaturan › Tampilan klip): daftar, buat, ubah, duplikat, jadikan bawaan, hapus (gaya bawaan tidak
  bisa dihapus; klip pemakainya kembali ke bawaan). Pratinjau = kalimat contoh `CaptionPreview` desktop, ditulis
  AssWriter yang sama dan diputar libass berulang di atas contoh frame.
- **Gaya Bingkai**: semua isian `FrameStyleForm` desktop (rasio, tata letak, letak/tinggi video, zoom, sudut, garis tepi,
  latar blur/warna/gradasi/gambar, logo dengan 9 titik jangkar, watermark teks, bilah judul, posisi subtitle).
  Pratinjau = satu frame dirender FFmpeg dengan filtergraph render yang sama. Gambar dari galeri disimpan ulang sebagai
  PNG/JPEG (FFmpeg di HP tanpa decoder WebP/HEIC).
- **Template sendiri**: area transparan dicari dari kanal alpha (`TemplateFrame` di core), dikunci
  `TemplateFrameParityTest` dengan 40 gambar acak dari desktop (`tools/export-template-fixtures.php`): 39 identik, 1
  bergeser satu sel kisi (0,5% layar) di tepi setengah transparan karena pembulatan penskala FFmpeg.
- **Intro & outro** (Pengaturan › Intro & outro): video MP4/MOV (H.264/HEVC) maksimal 30 detik, gambar kecil dari
  FFmpeg. Dipilih per klip di editor (tab Sampul) atau dijadikan bawaan project; disambung saat render dengan
  `joinBumpers` (hook tampil di atas intro seperti desktop; intro menggantikan kartu pembuka).
- **Sampul** (tab Sampul di editor): frame saat ini atau gambar galeri, judul besar opsional, ditempel 0,1 detik di awal
  video (bawaan). Dibuat seperti `ClipRenderer::cover` desktop dan ikut disimpan ke Pictures/ClipMI/<project> (galeri
  tidak menerima gambar di folder Movies).
- **Hasil render** (tombol film di daftar project, atau Pengaturan › Render › Hasil render): render yang berjalan, hasil
  dengan durasi, resolusi, ukuran, dan gambar kecil; bagikan langsung ke TikTok/Instagram/YouTube yang terpasang
  (caption tersalin); tanda "Hasil lama" dan "File hilang" dengan tombol Render ulang.
- **Aman baterai & suhu**: render menunggu sampai baterai tidak lemah (WorkManager `requiresBatteryNotLow`: ±15% atau
  sedang dicas); saat HP terlalu panas (status termal parah) render ditunda dan dicoba tiap menit. Alasannya tampil di
  kartu klip dan layar Hasil.

Diuji di HP (18 November 2026):
- Migrasi 4 → 5 pada database asli (38 klip utuh, kolom dan indeks baru ada).
- Pratinjau gaya subtitle (libass) dan gaya bingkai (FFmpeg).
- Template dari PNG dengan area transparan; video masuk tepat ke area itu. Gambar yang batal disimpan ikut terhapus.
- Impor intro/outro dari galeri.
- Sampul dari frame dengan judul.
- Render klip dengan sampul + intro 4 dtk + outro 3 dtk, hasil 42 dtk. Urutan frame: sampul di 0,03 dtk → intro dengan
  hook di atasnya → klip dengan subtitle → outro. Sampul tersimpan di Pictures/ClipMI.
- Layar Hasil, dan lembar bagikan "Lainnya…".

Perbaikan dari uji itu:
- Thumbnail YouTube berformat WebP membuat pratinjau gaya bingkai gagal → contoh frame selain JPEG/PNG diubah dulu ke
  JPEG di cache.
- Pilihan berupa chip di dalam kartu terpotong di tepi kanan → dibungkus ke baris berikutnya (`FlowRow`).
- Keluar dari halaman ubah gaya membuang perubahan tanpa bertanya → konfirmasi "Buang perubahan?".
- ClipMI sendiri muncul sebagai tujuan di lembar bagikan → dikecualikan.
- Nama berkas di pratinjau lembar bagikan MIUI tetap nomor MediaStore; MIUI tidak memakai judul yang dikirim.

Belum diuji di HP: tunda render saat baterai lemah/HP panas (butuh mengubah status baterai sistem); tombol bagikan
langsung ke TikTok/YouTube (tidak dikirim sungguhan).

Belum dibuat: seret logo/watermark langsung di pratinjau (sekarang lewat 9 titik jangkar); timeline filmstrip/waveform.
