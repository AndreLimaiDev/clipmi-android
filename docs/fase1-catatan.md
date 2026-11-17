# Fase 1: Video & Transkrip — catatan (17 November 2026)

## Yang sudah jalan dan diuji di HP (Xiaomi Mi 8 Lite, Android 10)

| Fitur | Bukti |
|---|---|
| Migrasi database 1 → 2 | DB Fase 0 di HP naik ke `user_version` 2; Room menerima skema (tabel `projects`, `transcript_segments`, `correction_entries`, `app_settings`, nama kolom sama dengan desktop) |
| Bagikan link dari aplikasi YouTube | Teks "Judul https://youtu.be/…?si=…" → project dibuat; judul, durasi, thumbnail resmi, subtitle `id-orig` json3 → **226 segmen identik dengan PHP desktop untuk file yang sama** (dibandingkan dari database HP); video 1080p diunduh + digabung FFmpeg (±1,5 menit total) |
| Bagikan video dari galeri | Video disalin ke folder kerja, durasi + thumbnail (frame 10%), status transkrip "Butuh Transkrip" |
| Pemulihan pekerjaan terputus | Saat aplikasi dibuka, project YouTube berstatus "berjalan" tanpa pekerjaan aktif diantrekan ulang |
| Status gagal yang jelas | Video tanpa izin baca → "Gagal menyalin video. … has no access …" |
| Transkripsi Groq (`whisper-large-v3-turbo`) | Video lokal 3 menit: ekstraksi audio + Groq **8,8 detik**, 26 segmen bertanda baca, sumber `api`; API key diisi pengguna di Pengaturan (Keystore) |

Penulis SRT hasil transkripsi API (`Srt.write`) identik dengan `Transcriber::toSrt` desktop (fixture `srt-writer.json`).

Belum diuji di HP: impor SRT/VTT lewat pemilih file, kamus koreksi lewat layar, batas laju Groq (jeda dan lanjut
otomatis), dan transkripsi video lebih dari 20 menit (beberapa potongan).

## Padanan dengan desktop

- Modul `core` (Kotlin murni): `TranscriptParser` (json3/SRT/VTT, UTF-16/Windows-1252), `TranscriptTidier`,
  `WordTimer`, `TextCleaner`, `CorrectionApplier`, `SubtitlePicker`, `YouTubeUrl`, `Srt`, serta tiruan fungsi PHP
  (`round()` 8.3 dengan pre-rounding, `trim`, `strip_tags`, `html_entity_decode` HTML5 dengan 2.125 entitas).
- `core/src/test/.../DesktopParityTest`: fixture dari `tools/export-transcript-fixtures.php` (dijalankan dengan PHP
  NativePHP desktop 8.3.36). Identik: 7 fixture sintetis, 5 subtitle asli (4 json3 + 1 VTT bergulir, hanya lokal),
  200.000 angka `round(x, 3)`.
- **Regex di `core` harus kompatibel dengan ICU (Android)**: flag `(?U)` tidak ada di Android dan baru ketahuan di HP.
  `\s` PHP /u ditulis eksplisit sebagai `[\t\n\x0B\f\r\p{Z}]`.

## Perbedaan yang disengaja dari desktop

- yt-dlp di HP mengunduh video dan audio sebagai dua file lalu FFmpeg menggabungkannya; ekstraksi YouTube sekali per
  video (`--load-info-json`), karena tantangan JS QuickJS ±16 detik.
- Belum ada video pratinjau (proxy): ExoPlayer memutar MP4/MOV/MKV langsung.
- Playlist belum didukung (desktop: maks. 30 video dari playlist); link playlist ditolak dengan pesan.
- Project YouTube dan video lokal ada di antrean unduh yang sama (berurutan).
