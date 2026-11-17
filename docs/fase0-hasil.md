# Hasil Fase 0: Fondasi & Uji Risiko

Diuji 17 November 2026 di **Xiaomi Mi 8 Lite** (Snapdragon 660, RAM 4 GB, Android 10/MIUI, API 29 = minSdk),
encoder H.264 hardware `OMX.qcom.video.encoder.avc`. PC acuan: ClipMI desktop (FFmpeg 8.1.3, yt-dlp 2026.10.26).
Data mentah ada di HP: `/sdcard/Android/data/id.clipmi.android/files/lab/results.jsonl`.

## Ringkasan keputusan

| Risiko | Hasil | Keputusan |
|---|---|---|
| Unduh YouTube di HP | **Berhasil.** Video 1080p H.264 + AAC + subtitle json3 | youtubedl-android 0.18.1 (Python 3.12, yt-dlp diperbarui dari aplikasi) + **QuickJS-ng 0.18.0** buatan sendiri |
| libass lewat NDK r30 | **Berhasil, identik dengan desktop** (SSIM 1,000000, PSNR 84–90 dB) | libass 0.17.5 + FreeType 2.15.0 + HarfBuzz 15.1.0 + FriBidi 1.0.18, statis, JNI |
| Mesin render | FFmpeg lebih mirip desktop dan lebih cepat untuk crop | **FFmpeg 8.1.3 buatan sendiri (LGPL)** untuk render; Media3 hanya untuk pratinjau (ExoPlayer) |
| Kerangka aplikasi | Build `assembleDebug`/`assembleRelease` dan terpasang di HP | Selesai (lihat bagian akhir) |

## 1. Unduh YouTube (yt-dlp)

- youtubedl-android 0.18.1 membundel Python 3.12.11, yt-dlp 2025.11.12 (beserta yt-dlp-ejs), dan `libqjs.so`
  (QuickJS 2025-04-26), lalu menambahkan sendiri `--js-runtimes quickjs:<nativeLibraryDir>/libqjs.so`.
  `updateYoutubeDL(STABLE)` menaikkan yt-dlp ke **2026.10.26** (sama dengan desktop) dalam 1,8 detik.
- Tantangan JS YouTube dipecahkan QuickJS (desktop memakai Deno). Skrip tantangan yang sama (3,2 MB) diukur langsung di HP:
  QuickJS 2025-04-26 bawaan **20,9 detik**, QuickJS-ng 0.18.0 **15,5 detik** (output identik). Karena itu
  `libqjs.so` diganti QuickJS-ng buatan sendiri (`pickFirsts` di `app/build.gradle.kts`).
- yt-dlp 2026.10.26 tidak menyimpan player hasil pra-proses ke cache untuk QuickJS (dicek juga di PC), jadi biayanya
  muncul setiap ekstraksi. Solusi: **satu ekstraksi per video**. Langkah video memakai `--load-info-json` dari langkah
  metadata, sehingga ekstraksi dan tantangan tidak diulang (langkah video turun dari ±30 detik ke ±5 detik sebelum unduhan mulai).
- youtubedl-android menambahkan `--no-cache-dir` bila `--cache-dir` tidak diberikan lewat `addOption`; `YtDlpArgs`
  selalu memberi `--cache-dir` sendiri.
- Di HP tidak ada FFmpeg untuk yt-dlp (yt-dlp menolak `libffmpeg.so` karena namanya), jadi video dan audio diunduh
  sebagai dua file (`-f "bv*[height<=1080][vcodec^=avc1],ba[ext=m4a]"`, nama dibedakan dari ekstensi) lalu digabung.
  Penggabungan dengan **FFmpeg `-c copy`: 5,3 detik**, dengan MediaMuxer Android: 58 detik (untuk 177 MB). Dipakai FFmpeg.

Video uji: kajian 29 menit 1080p (project 5 desktop, sudah dipakai tim).

| Tahap | Waktu |
|---|---|
| Menyalakan Python + yt-dlp (`--version`) | 3,5–4,9 s |
| Metadata + subtitle `id`/`id-orig` json3 + thumbnail (termasuk tantangan JS 16,5 s) | **27,2 s** |
| Unduh video 137 (1080p, 145 MB) + audio 140 (27 MB), Wi-Fi | **40,2 s** (35 Mbps) |
| Gabung (MediaMuxer, akan diganti FFmpeg 5,3 s) | 58,5 s |

## 2. libass

- Dibangun dengan NDK r30 untuk arm64 lewat `native/build-native.sh` (MSYS2: meson/autotools), asm aarch64 aktif.
- Enam file ASS dari `AssWriter`/`CaptionPreview` desktop (`tools/export-ass-fixtures.php`: Poppins, Anton glow +
  karaoke, Montserrat kotak + pop, Bebas bayangan + kotak baris, Lilita reveal, hook berkotak) digambar di HP pada 4
  waktu, lalu dibandingkan dengan filter `ass` FFmpeg desktop (`tools/compare-ass.sh`): **24/24 frame SSIM 1,000000,
  PSNR 84–90 dB** (selisih hanya pembulatan ±1).
- Kecepatan: 0,27 ms/frame rata-rata (frame yang berubah ±1–3 ms); hanya 4–46 dari 141 frame berubah per gaya,
  jadi overlay jarang perlu digambar ulang.
- Catatan warna: header ASS desktop tidak menulis `YCbCr Matrix`, sehingga FFmpeg menafsirkan warna ASS sebagai
  BT.601 TV range. Membandingkan di rgb24 menyesatkan (putih jadi 235); perbandingan dilakukan di YUV. Pratinjau
  (ExoPlayer + overlay GL) harus menerapkan tafsiran yang sama agar warna sorotan identik dengan hasil render.

## 3. Mesin render

Input 1080p 16:9 (24 fps), klip 60 detik, keluaran 1080x1920 H.264 6 Mbps + AAC 128k, subtitle libass sepanjang klip.
Acuan: render desktop (FFmpeg 8.1.3, libx264 crf 26) dengan filtergraph `ClipRenderer` yang sama.

| Varian | Kecepatan (x realtime) | SSIM Y vs desktop | Catatan |
|---|---|---|---|
| **FFmpeg, crop tengah** | **1,46** | **0,990** | audio desktop identik (−13,8 LUFS, puncak −1,3 dBFS), tag warna BT.709 benar |
| **FFmpeg, video utuh + latar blur** | **0,75** | **0,993** | `boxblur` (GPL) diganti `avgblur` dua kali |
| Media3 Transformer, crop tengah | 1,08 | 0,988 | tag warna salah (BT.601), audio tidak dirapikan, butuh `setPortraitEncodingEnabled` |
| Media3, latar blur `GaussianBlurWithFrameOverlaid` | 0,07 | – | blur di resolusi penuh terlalu berat untuk Adreno 512 |
| Media3, latar blur dua sequence (blur di 1/4 layar) | 0,88 | 0,973 | Gaussian ≠ boxblur desktop |
| Media3 video + audio FFmpeg (berurutan) | 0,69 | 0,988 | lebih lambat dari FFmpeg penuh |
| FFmpeg audio saja (`highpass,afftdn,loudnorm`) | 1,95 | – | separuh waktu render crop habis untuk audio |

Beban panjang (FFmpeg blur, klip 5 menit): lihat bagian "Suhu".

**Keputusan: FFmpeg untuk render**, alasannya:
1. Filtergraph `ClipRenderer` desktop bisa dipakai hampir apa adanya, jadi padanan `Composition` cukup menghasilkan
   string filter yang sama (mudah dikunci dengan tes perbandingan fixture).
2. Hasil paling mirip desktop (SSIM 0,990–0,993) dan audio identik; tag warna benar.
3. Lebih cepat untuk crop; untuk blur sedikit lebih lambat dari Media3 dua sequence tetapi jauh lebih mirip.
4. Tambahan APK hanya 3,75 MB (LGPL, tanpa x264).

Media3 tetap dipakai untuk **pratinjau** (ExoPlayer + overlay libass), tidak untuk render.

Hal yang perlu ditangani di Fase 3:
- Encoder: `h264_mediacodec` mode NDK (`-ndk_codec 1`) butuh `-pix_fmt nv12` di Qualcomm (yuv420p ditolak). Siapkan
  urutan cadangan seperti `runWithEncoderFallback` desktop (nv12 → yuv420p → Media3 Transformer bila MediaCodec NDK gagal).
- Bitrate: 6 Mbps = ±46 MB/menit; desktop crf 26 hanya ±1,3 Mbps. Tentukan bitrate/mode VBR yang setara.
- Latar blur 0,75x realtime di HP kelas menengah; bisa dipercepat dengan blur di resolusi lebih kecil atau satu pass.
- Selisih 1 frame di awal klip Media3 (tidak terjadi di FFmpeg).

## Suhu

HP dicolok USB (mengisi daya), suhu ruang kantor. Semua uji di atas berjalan berurutan ±30 menit tanpa jeda panjang.

- FFmpeg latar blur, **klip 5 menit**: 397 detik (0,76x realtime), kecepatan per 10% konstan 38–40 detik, jadi
  **tidak ada throttling**. Baterai 38 → 39 °C, status termal Android 0 (normal); sesudahnya CPU 43–44 °C, GPU 40 °C.
- Media3 latar blur Gaussian resolusi penuh (14 menit): baterai 37 → 39 °C.
- Belum diuji: render massal panjang tanpa pengisian daya, dan HP lain (encoder MediaTek/Exynos).

## 4. Ukuran APK

APK rilis (R8, arm64, belum ditandatangani): **28,3 MB**.

| Bagian | Di APK (terkompresi) | Terpasang |
|---|---|---|
| Python 3.12 + stdlib (`libpython.zip.so`) | 14,1 MB | 14,3 MB |
| yt-dlp bawaan (`res/raw/ytdlp`, diganti versi terbaru saat update) | 3,2 MB | 3,2 MB |
| FFmpeg (`libffmpeg.so`) | 3,75 MB | 8,2 MB |
| libass + HarfBuzz + FreeType + FriBidi + JNI (`libclipmi_native.so`) | 1,0 MB | 2,3 MB |
| QuickJS-ng (`libqjs.so`) | 0,56 MB | 1,2 MB |
| Kode aplikasi (dex) | 4,9 MB | – |
| Font yang dibundel (6, sama dengan desktop) | 0,45 MB | – |

Library native diekstrak saat pasang (`extractNativeLibs`, wajib untuk executable), jadi ruang terpasang ±55 MB.

## 5. Kerangka aplikasi

- Gradle wrapper 9.9.1, AGP 9.5.1, Kotlin 2.4.21 (built-in Kotlin AGP 9), Compose BOM 2026.09.00, KSP 2.3.14,
  minSdk 29, target/compile 37, hanya arm64-v8a, halaman 16 KB.
- Modul `core` (Kotlin murni, tanpa Android) untuk padanan kode desktop; berisi `YtDlpArgs` + tes.
- Navigasi bawah (Project, Jadwal, Statistik, Pengaturan), tema terang/gelap mengikuti sistem (Material You di Android 12+).
- Room `clipmi.db` versi 1 dengan skema diekspor ke `app/schemas` (migrasi berversi, tanpa destructive fallback).
- WorkManager: satu antrean berurutan per jenis (`queue-download`, `queue-ai`, `queue-render`) dengan foreground
  service `dataSync` / `mediaProcessing` dan notifikasi progres.
- API key: AES-256-GCM dengan kunci Android Keystore; `allowBackup=false` dan aturan ekstraksi data mengecualikan semuanya.
- Layar **Pengaturan → Uji Fase 0** (dan `adb shell am start -S -n id.clipmi.android/.MainActivity --es lab <uji>`)
  menjalankan semua uji di atas sebagai pekerjaan foreground.

## Cara mengulang

```bash
D:/dev/msys64/usr/bin/env MSYSTEM=UCRT64 CHERE_INVOKING=1 D:/dev/msys64/usr/bin/bash.exe -l native/build-native.sh
scripts/gradle.sh assembleDebug
D:/dev/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
D:/dev/android-sdk/platform-tools/adb.exe shell am start -S -n id.clipmi.android/.MainActivity --es lab ass
tools/compare-ass.sh <folder lab/ass hasil adb pull>
```
