# Rilis APK dan pembaruan dalam aplikasi

Kode ada di repo privat `AndreLimaiDev/clipmi-android`. Rilis APK ditaruh di repo **publik** terpisah
`AndreLimaiDev/clipmi-android-releases` (pola yang sama dengan desktop: `clipmi-releases`), karena aplikasi membaca
rilis tanpa token dan rilis di repo privat tidak bisa dibaca.

## Membuat repo rilis (sekali)

1. Buka https://github.com/new.
2. Owner `AndreLimaiDev`, Repository name `clipmi-android-releases`, pilih **Public**.
3. Centang **Add a README file** (rilis butuh minimal satu commit untuk tag). Isi README cukup:
   "APK rilis ClipMI Android. Pasang dari rilis terbaru; pembaruan berikutnya lewat aplikasi."
4. Create repository. Tidak perlu mengunggah kode apa pun ke repo ini.

## Menerbitkan rilis di GitHub (setiap versi)

1. Di repo rilis: **Releases** › **Draft a new release**.
2. **Choose a tag**: ketik `v0.1.0` (sama dengan versi) › **Create new tag: v0.1.0 on publish** (target `main`).
3. **Release title**: `ClipMI 0.1.0`.
4. **Description**: tempel isi `dist\ClipMI-0.1.0.md` (baris SHA-256 jangan dihapus; aplikasi memakainya untuk
   memeriksa APK dan menyembunyikannya dari catatan).
5. Seret `dist\ClipMI-0.1.0.apk` ke kotak lampiran, tunggu sampai selesai terunggah.
6. Jangan centang "Set as a pre-release" (aplikasi hanya membaca rilis terbaru yang bukan pre-release/draft).
   **Publish release**.
7. Unduh APK dari halaman rilis dan cocokkan SHA-256-nya (`Get-FileHash <file> -Algorithm SHA256`).

## Sekali saja

1. **Repo rilis Android**: sudah diisi di `clipmi.updateRepo` (`gradle.properties`)
   (`AndreLimaiDev/clipmi-android-releases`); buat repo-nya seperti di atas.
2. **Kunci tanda tangan tim**: `powershell -ExecutionPolicy Bypass -File scripts\make-keystore.ps1`. Kata sandi diketik
   sendiri oleh koordinator. Simpan `clipmi-release.jks` dan kata sandinya di tempat aman (pengelola kata sandi + salinan
   offline): tanpa kunci yang sama, versi berikutnya tidak bisa dipasang sebagai pembaruan.
3. **HP yang memakai APK debug** (dibangun dari PC): APK rilis ditandatangani kunci lain, jadi tidak bisa dipasang di
   atasnya. Cadangkan dulu (Pengaturan › Data & penyimpanan › Cadangkan), copot ClipMI, pasang APK rilis, lalu
   Pulihkan. Setelah itu pembaruan berikutnya lewat aplikasi.

## Setiap rilis

1. `powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 0.1.0`
   - menaikkan `clipmi.versionName` dan `clipmi.versionCode`,
   - membangun APK rilis bertanda tangan,
   - menulis `dist\ClipMI-<versi>.apk` dan draf catatan `dist\ClipMI-<versi>.md` (SHA-256 di baris terakhir).
2. Isi bagian "Yang baru" di catatan rilis (ditampilkan di aplikasi saat ada pembaruan dan sekali setelah terpasang).
3. Di GitHub: terbitkan rilis seperti langkah "Menerbitkan rilis di GitHub" di atas.
4. Cocokkan SHA-256 APK yang terunggah dengan catatan rilis.
5. Commit perubahan `gradle.properties` (versi).

## Di aplikasi

- Cek otomatis sekali sehari lewat Wi-Fi; versi baru → notifikasi (sekali per versi) dan kartu di tab Project
  (Lihat / Nanti; Nanti melewati versi itu).
- Pengaturan › Pembaruan aplikasi: versi terpasang, waktu cek terakhir, atau "Versi x tersedia"; ketuk untuk cek sekarang.
- Unduh di latar dengan progres di notifikasi; APK diperiksa (nama paket, versi lebih baru, kunci tanda tangan sama,
  SHA-256 dari catatan rilis) sebelum tombol Pasang muncul. Izin "Pasang aplikasi tidak dikenal" dinyalakan pengguna
  sendiri; pemasang sistem yang meminta konfirmasi.
- Setelah pembaruan, catatan "Yang baru di <versi>" tampil sekali.
