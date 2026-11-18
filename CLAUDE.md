# ClipMI Android

Aplikasi Android berdiri sendiri untuk clipping kajian/ceramah menjadi klip vertikal 9:16: semua data di HP pengguna, tanpa server, tanpa login. Rencana lengkapnya di `prd-clipmi-android.md` (fase 0–5). Pengguna menulis dalam bahasa Indonesia; balas dalam bahasa Indonesia.

## Hubungan dengan ClipMI desktop
- Repo desktop: `D:\laragon\www\clip-mi` (Laravel 13 + NativePHP/Electron + Livewire 4 + Svelte 5). PRD-nya `prd-clipmi.md`.
- Aturan yang harus sama dengan desktop: prompt analisis dan format JSON (`app/Analysis/PromptBuilder.php`, `ClipImporter.php`), perapian transkrip (`app/Transcript`), tata letak layar (`app/Render/Composition.php`), subtitle ASS (`app/Render/AssWriter.php`, `SubtitleBuilder.php`), gaya subtitle/bingkai, dan format Paket Standar Tim (`app/Settings/TeamPack.php`).
- Padanan Kotlin dikunci dengan tes perbandingan output dari fixture yang diekspor dari repo desktop (input → output identik), seperti refactor `Composition` desktop yang diperiksa dengan 6.144 kombinasi.
  - Ekspor fixture dengan PHP desktop terpasang (NativePHP, PHP 8.3.36: `C:\Users\BWA - IT\AppData\Local\Programs\clipmi\resources\build\php\php.exe`), bukan PHP Laragon, karena `round()`/mbstring harus sama. Contoh: `tools/export-transcript-fixtures.php`, `tools/export-analysis-fixtures.php`, `tools/export-render-fixtures.php` (render: filtergraph dan ASS harus identik byte demi byte; `boxblur` GPL diganti `RenderGraph.lgpl()` hanya saat dijalankan).
  - Fungsi PHP yang ditiru ada di `core/.../php` (`Php.round` dengan pre-rounding PHP 8.3, `trim`, `strip_tags`, `HtmlEntities`).
  - Regex di `core` juga jalan di Android (ICU): jangan pakai flag `(?U)`; tulis kelas Unicode eksplisit (`\p{Z}`, `\p{L}`).
  - Fixture dari video asli (konten pihak lain) hanya lokal di `.../input/real` dan `.../expected/real` (di-gitignore).
- Jangan mengubah repo desktop dari sesi ini tanpa diminta; baca saja sebagai rujukan.

## Lingkungan build (PC ini, Windows 11)
- Semua alat ada di `D:\dev` (drive C sempit):
  - JDK: `D:\dev\jdk-21` (Microsoft OpenJDK 21.0.13)
  - Android SDK: `D:\dev\android-sdk` (platform `android-37.0`, build-tools 37.0.0, platform-tools 37.0.1, NDK r30 `30.0.16248370`, CMake 3.31.6)
  - Gradle 9.9.1: `D:\dev\gradle-9.9.1`, cache di `D:\dev\gradle-home`
- Versi stabil yang dipakai (dicek 17 November 2026): AGP 9.5.1, Kotlin 2.4.21 (plugin `org.jetbrains.kotlin.plugin.compose`), Compose BOM 2026.09.00, activity-compose 1.13.0, Media3 1.11.1. Cek versi terbaru dari maven-metadata Google/Maven Central sebelum menaikkan.
- **Jalankan Gradle selalu lewat `scripts/gradle.sh` (Bash) atau `scripts/gradle.ps1`.** Skrip itu mengatur `JAVA_HOME`, `GRADLE_USER_HOME`, dan `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/dev/tmp`.
  - Alasannya: nama pengguna Windows "BWA - IT" membuat TEMP menjadi path pendek `C:\Users\BWA-IT~1\...`, dan Selector Java gagal membuat socket Unix di sana ("Unable to establish loopback connection"), sehingga klien Gradle tidak bisa tersambung ke daemon.
  - Memasang flag itu di `org.gradle.jvmargs` tidak cukup (diterapkan terlambat).
- `local.properties` memakai garis miring biasa: `sdk.dir=D\:/dev/android-sdk` (garis miring terbalik di-escape oleh format properties).
- SDK lama di `%LOCALAPPDATA%\Android\Sdk` (NDK 26, 28-beta) tidak dipakai.
- Proyek uji `D:\dev\hello-android` (Compose + Media3 + library C lewat NDK) sudah terbukti bisa dibuild; boleh dijadikan rujukan atau dihapus.
- Tidak ada emulator: image yang terpasang arm64 sedangkan PC Intel x64, dan Windows Hypervisor Platform belum tentu aktif (pengaturan sistem, bukan wewenang Claude). Uji di HP Android nyata lewat USB/wireless debugging (`D:\dev\android-sdk\platform-tools\adb.exe`).
  - HP uji: Xiaomi Mi 8 Lite (Android 10/MIUI). MIUI butuh "Install via USB" di Opsi pengembang; bila `adb install` ditolak (`INSTALL_FAILED_USER_RESTRICTED`), pengguna mengetuk Install di HP.
  - Dari Git Bash, path HP (`/sdcard/...`) diubah MSYS menjadi path Windows: pakai `MSYS_NO_PATHCONV=1` untuk perintah adb.
- Library native (libass dkk., FFmpeg, QuickJS-ng) dibangun dengan `native/build-native.sh` di MSYS2 (`D:\dev\msys64`), hasilnya di `native/out/` (tidak di-commit); harus ada sebelum `assembleDebug`. Lihat `native/README.md`.
- Uji Fase 0 (Pengaturan → Uji Fase 0) bisa dipicu dari PC: `adb shell am start -S -n id.clipmi.android/.MainActivity --es lab <device|ytdlp|ytdlp_update|ass|render_media3|render_ffmpeg|audio_ffmpeg> [--es kunci nilai]`; log dan hasil di `/sdcard/Android/data/id.clipmi.android/files/lab/`. Hasil dan keputusan Fase 0: `docs/fase0-hasil.md`.

## Aturan kerja
- Commit hanya bila pengguna meminta; push hanya dengan "Commit & push" (atau yang setara). Pesan commit berbahasa Indonesia dan diakhiri `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Remote GitHub belum ada; PC ini tidak punya GitHub CLI, jadi repo dibuat pengguna sendiri. Rilis APK diunggah manual oleh pengguna; setelah dipublikasikan, cocokkan sha256-nya.
- Jangan mengubah pengaturan sistem atau keamanan Windows/HP. Tanyakan dulu sebelum mengunduh berkas besar (sebutkan nama, sumber, ukuran) dan sebelum memasang alat baru.
- Jangan pernah menulis atau menampilkan API key asli pengguna.
- Cek dokumentasi terbaru (Context7 atau situs resmi Android) sebelum menebak API Media3, Compose, WorkManager, atau NDK.
- Pesan penutup: teks biasa yang diakhiri satu pertanyaan beserta pilihannya (pengguna kadang memantau lewat HP).
