# Fase 5: Analisis, Standar Tim & Distribusi — catatan (18 November 2026)

## Logika inti di `core` (selesai)

Dikunci tes perbandingan dengan kode desktop yang dijalankan apa adanya (PHP desktop 8.3.36, SQLite di memori):

| Bagian | Padanan desktop | Kasus uji | Hasil |
|---|---|---|---|
| `PerformanceDigest.build` | `App\Stats\PerformanceDigest` | 60 skenario, 549 postingan (1–3 akun, statistik acak, seri, hook/model/kartu pembuka, caption/hashtag jadwal vs klip, 150+ postingan) | JSON identik |
| `PerformancePrompt.build` | `PerformancePrompt::build` | prompt tiap skenario | identik byte demi byte |
| `PerformancePrompt.parse` | `PerformancePrompt::parse` | 360 balasan (tanpa JSON, JSON rusak, koma lebih, pagar kode, bukti karangan, poin kosong) | identik, termasuk pesan `json_last_error_msg()` |
| `TeamPack.parse/preview` | `App\Settings\TeamPack` | 304 paket, 2.496 entri (tipe salah, di luar rentang, kolom lama `logo_position`, gambar rusak/bukan gambar, video terlalu lama, entri bukan objek) | identik |

Catatan:

- Aturan validasi Laravel yang dipakai paket (`required`, `sometimes`, `nullable`, `boolean`, `integer`, `between`,
  `in`, `regex`, `array:keys`) ditiru di `core/.../settings/TeamPack.kt`; boolean JSON dibedakan dari string "true"
  seperti PHP.
- `getimagesizefromstring()` diganti pemeriksaan tanda tangan PNG/JPEG/GIF/WebP.

## Aplikasi

- **Database v7**: tabel `performance_reports` (kolom sama dengan desktop). Migrasi 6 → 7 hanya membuat tabel baru.
- **Analisis Performa** (Statistik → ikon ✦): angka diolah aplikasi, lalu Mode API (antrean AI, coba ulang otomatis
  30/90 detik saat server sibuk, pemakaian dicatat di Pemakaian AI) atau chat AI (salin/bagikan prompt, tempel
  balasan). Laporan bertanggal; bukti tiap poin bisa diketuk untuk melihat postingannya.
- **Data & penyimpanan** (Pengaturan → Data):
  - Sisa ruang HP, folder kerja, video hasil di galeri, ukuran tiap project.
  - Hapus video sumber (transkrip, klip, hasil render tetap); unduh ulang (YouTube, transkrip dipertahankan) atau
    pilih ulang (galeri). Tombol yang sama di detail project.
  - Cadangkan: satu ZIP (database + gambar kecil, sampul, gambar gaya, intro/outro; opsional video hasil) lewat
    pemilih file sistem (penyimpanan HP atau Google Drive). Tidak ikut: API key (Keystore) dan video sumber.
  - Pulihkan: pratinjau isi, lalu database dan berkas diganti, aplikasi dibuka ulang; video hasil dari cadangan
    didaftarkan lagi ke galeri; project yang video sumbernya tidak ada ditandai "Video sumber dihapus".
  - Paket Standar Tim: ekspor dan impor format yang sama dengan desktop, dengan pratinjau sebelum diterapkan.
- **Distribusi**:
  - `scripts/make-keystore.ps1`: kunci tanda tangan rilis tim (dijalankan koordinator; kata sandi diketik sendiri),
    menulis `signing.properties` (di-.gitignore) yang dipakai `assembleRelease`.
  - Pembaruan dalam aplikasi (Pengaturan → Pembaruan aplikasi): rilis terbaru GitHub (`-Pclipmi.updateRepo=pemilik/nama`),
    APK diunduh dan diperiksa (nama paket, versi lebih baru, kunci tanda tangan sama, SHA-256 bila tertulis di catatan
    rilis), lalu pemasang sistem dibuka. Izin "Pasang aplikasi tidak dikenal" dinyalakan pengguna sendiri.
  - Belum aktif sampai repo GitHub dibuat dan diisi di build.

## Diuji di HP (18 November 2026, Xiaomi Mi 8 Lite)

- Migrasi 6 → 7: 38 klip utuh, tanpa crash (cadangan database v6 di `D:\dev\tmp\dbbackup`).
- Data & penyimpanan: sisa ruang 30,3 GB, folder kerja 498 MB, video hasil 146 MB, ukuran per project.
- Cadangkan ke Unduhan: `ClipMI-cadangan-2026-11-18.zip` 1,5 MB (database, 4 gambar kecil/sampul, 2 intro/outro +
  gambar kecilnya, manifest); video sumber tidak ikut; tidak ada API key di database cadangan.
- Pulihkan: pratinjau isi cadangan tampil (project, klip, jadwal, akun, statistik, gaya, kamus, laporan, berkas);
  dibatalkan (pemulihan sungguhan belum diuji, lihat di bawah).

- Ekspor Paket Standar Tim ke Unduhan (1,5 MB): dibaca `TeamPack::parse` desktop tanpa entri dilewati (3 gaya
  subtitle, 4 gaya bingkai, 2 intro/outro); pratinjau desktop = pratinjau HP.
- Impor paket yang sama: 0 baru / 3 + 4 diperbarui / 2 intro-outro; gaya tidak terduplikasi, gaya bawaan tetap,
  intro/outro tetap id 1 dan 2 dengan file baru (file lama terhapus).
- Hapus video sumber (project uji lokal 24 MB): status "Sumber dihapus", folder kerja turun 24 MB; Pilih ulang video
  dari galeri: video siap lagi, transkrip (26 segmen) utuh.
- Analisis Performa (3 postingan, 2 akun): peringatan data sedikit, perkiraan token Mode API; mode chat AI dengan
  balasan uji (koma lebih, "p3" kecil, bukti karangan "P99", bagian kosong) tersimpan dan dibaca seperti desktop; bukti
  P3 menampilkan rasio 1,44 (6.400 / median 4.450), ER 8,3%, faktor, dan hook.

### Perbaikan dari uji itu

- Ringkasan "Folder kerja" tidak ikut diperbarui setelah video sumber dihapus/dipilih ulang.
- Akun yang masih menunggu sekarang punya "Isi statistik manual" (desktop: statistik manual menandai akun terbit).
  Dibangun dan dipasang, belum dilihat di layar (HP terkunci).

### Belum diuji di HP

- Pulihkan sampai selesai (mengganti data dan menghapus video sumber di HP uji; perlu HP/data uji terpisah).
- Analisis Mode API (memakai kuota API pengguna).
- Unduh ulang video sumber YouTube.
- APK rilis bertanda tangan dan pembaruan dalam aplikasi (menunggu kunci tim dan repo GitHub).

### Untuk tim (di luar aplikasi)

- **Verifikasi developer Android**: Google mewajibkan aplikasi di HP bersertifikasi Android didaftarkan oleh developer
  terverifikasi (diumumkan mulai 2026–2027 bertahap). Koordinator mendaftarkan akun di Android Developer Console
  dengan identitas tim, lalu mendaftarkan nama paket `id.clipmi.android` dan sidik jari SHA-256 sertifikat dari
  `scripts/make-keystore.ps1`. Ini akun dan data identitas pengguna, jadi dikerjakan pengguna sendiri.
