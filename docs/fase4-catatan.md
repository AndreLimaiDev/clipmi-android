# Fase 4: Jadwal, Unggah & Statistik — catatan (18 November 2026)

## Logika inti di `core` (selesai)

Paket `id.clipmi.core.schedule`, padanan kode Jadwal desktop, dikunci tes `ScheduleParityTest` dari
`tools/export-schedule-fixtures.php` (kode desktop dijalankan apa adanya dengan PHP desktop 8.3.36 dan SQLite di
memori; Isi otomatis dan pengingat memakai model dan query desktop sungguhan):

| Bagian | Padanan desktop | Kasus uji | Hasil |
|---|---|---|---|
| `Slots.normalize/summary/times` | `normalizeSlots` halaman Jadwal, `SocialAccount::slotSummary/slotTimes` | 400 slot acak (jam kembar digabung, rentang waktu acak) | identik |
| `QueueFiller.plan/fill/banner` | `QueueFiller::fill` + pesan `autoFill()` | 200 skenario: 1–4 akun, slot terisi, klip seri (bagian tertahan), file hasil hilang, klip belum dirender; 602 jadwal | identik |
| `Schedules.rescheduleAt` | `reschedule()` halaman Jadwal | 120 jadwal terlewat (sebagian akun sudah terbit/dilewati, slot bersama, tanpa slot) | identik, termasuk pesan |
| `Reminders.notes` | `ScheduleReminder::send` | 120 skenario, 86 notifikasi (tepat waktu, banyak sekaligus, terlewat) | identik |
| `PostStats.statAt` | `ScheduleAccount::statAt` | 300 postingan × hari 1/7/30 | identik |
| `PostStats.isDue` | `StatsRefresher::due` | 400 baris (umur 0–40 hari, jarak 3/12/48 jam) | identik |
| `PostStats.summary/ratio/number/rate` | ringkasan tampilan Statistik, `PostStat::engagementRate`, `number_format` | 300 | identik |
| `PostStats.parseYtDlp/explain/parseYouTubeApi` | `YtDlp::parseStats`, `PostStatsFetcher` | 8 + 6 + kasus tes desktop | identik |
| `IdDate` | `translatedFormat` Carbon locale id | lewat pesan di atas | identik |

## Aplikasi

- **Database v6**: tabel `social_accounts`, `schedules`, `schedule_accounts`, `post_stats` (kolom sama dengan desktop;
  waktu dalam epoch milidetik, ditampilkan sebagai jam dinding HP). Migrasi 5 → 6 hanya membuat tabel baru.
- **Akun sosmed**: nama, platform, handle, warna (warna berikutnya yang belum dipakai), slot rutin (jam + hari).
- **Tab Jadwal**: jadwal terlewat (Unggah sekarang / Jadwalkan ulang), Minggu (per hari, tombol + untuk memilih klip),
  Bulan (kisi dengan titik warna akun), Antrean (Isi otomatis, klip siap dijadwalkan, klip terpilih yang belum
  dirender dengan tombol Render), filter akun. "Jadwalkan" juga ada di layar Hasil.
- **Form jadwal**: tanggal, jam, akun tujuan, caption, hashtag dengan penghitung batas tiap platform (hanya ditandai),
  catatan; caption/hashtag yang sama dengan klip disimpan kosong.
- **Pengingat**: satu alarm tepat waktu (`setExactAndAllowWhileIdle`; Android 13+ `USE_EXACT_ALARM`, Android 12
  `SCHEDULE_EXACT_ALARM`) untuk jadwal berikutnya; saat berbunyi semua jadwal yang tiba diingatkan sekaligus, lalu
  alarm berikutnya dipasang. Dipasang ulang saat aplikasi dibuka, setelah HP menyala ulang, aplikasi diperbarui, atau
  jam/zona waktu berubah. Notifikasi (kanal "Pengingat posting", penting) dengan tombol Unggah dan Nanti 30 menit.
- **Siap Unggah**: teks unggahan dan tombol salin, putar video, peringatan file hilang / klip diubah setelah render
  (Render ulang), per akun: Bagikan ke aplikasi platformnya (teks disalin dulu), Tandai terbit, Lewati, link postingan
  (menandai terbit dan mengambil statistik), Ambil statistik, Isi manual, Kembalikan ke Menunggu.
- **Statistik**: `StatsWorker` (WorkManager, butuh internet) mengambil lewat YouTube Data API bila key diisi
  (Pengaturan → Statistik, Keystore, key lewat header) atau yt-dlp; berkala tiap jam untuk postingan yang sudah
  waktunya, sampai hari ke-31. Tab Statistik: ringkasan per akun, kartu postingan (tayangan + "×" median, suka,
  komentar, share, engagement, hari 1/7/30), Perbarui semua dengan progres.

## Diuji di HP (18 November 2026, Xiaomi Mi 8 Lite)

- Migrasi 5 → 6: 38 klip utuh, tanpa crash (cadangan database v5 di `D:\dev\tmp\dbbackup`).
- Akun: dua akun uji, pemilih jam, hari slot; ringkasan "07.00 akhir pekan · 08.00 setiap hari" dan JSON slot sama
  dengan desktop.
- Form jadwal: bawaan besok 19.00 ke semua akun, penghitung Instagram/TikTok, pemilih tanggal dan jam.
- Pengingat: jadwal 09.35 memasang alarm tepat; notifikasi "Waktunya posting" muncul 09.35.00 saat aplikasi di latar;
  tombol Unggah membuka Siap Unggah jadwal itu.
- Jadwalkan ulang (diketuk pengguna): pindah ke slot bersama berikutnya (Kamis 08.00).
- Isi otomatis: 3 klip ke Jumat 08.00 (dua akun), Sabtu 07.00 (Instagram), Sabtu 08.00 (dua akun), melewati Kamis
  08.00 yang terisi; pesan "3 klip dijadwalkan, 20 Nov – 21 Nov."
- Bulan: titik akun di tanggal berjadwal, daftar hari terpilih.
- Bagikan ke TikTok: membuka TikTok Lite (lewat pemilih sistem karena TikTok Lite punya beberapa tujuan berbagi);
  dibatalkan tanpa posting.
- Tempel link: akun ditandai terbit, statistik diambil yt-dlp dalam ±25 detik (video publik YouTube sebagai contoh).
- Tab Statistik: ringkasan per akun ("1 postingan (0 terukur) · median – · terbaik –" sebelum ada angka), kartu
  postingan, engagement (6,7%; 4,8% = 120/2.500), Perbarui semua dengan progres lalu "Statistik diperbarui."
- Isi manual (Instagram): tanpa angka ditolak "Isi minimal satu angka."; 2.500 tayangan + 120 suka tersimpan sebagai
  `manual`.
- YouTube Data API: key palsu disimpan (tersamar, tidak ditampilkan lagi), YouTube API sungguhan menolak dan pesan
  "API key YouTube ditolak. Periksa key di Pengaturan." tampil di kartu; key lalu dihapus.

### Perbaikan dari uji itu

- Kartu antrean: tombol di bawah judul (judul tidak terpotong).
- Baris hari yang sudah lewat setinggi hari lain.
- Pesan Isi otomatis/jadwal ulang: daftar digulir ke atas supaya terbaca.
- Kolom link/form tertutup keyboard: `imePadding` dipasang sebelum `verticalScroll`.
- Angka tanpa titik ribuan di HP (`Locale.ROOT` di Android/ICU tanpa pemisah): titik ribuan ditulis sendiri.
- "Mengambil statistik…" tidak berhenti: pemantau membaca data lama dan membandingkan dengan milidetik.
- Angka jutaan terpotong di kartu Statistik: ukuran huruf mengecil sendiri (`TextAutoSize`).

### Belum diuji di HP

- Pengingat setelah HP menyala ulang dan tombol "Nanti 30 menit".
- Pembaruan statistik berkala di latar (WorkManager tiap jam).
- MIUI "Mulai otomatis" (bisa menahan alarm/worker aplikasi yang tidak diizinkan).
