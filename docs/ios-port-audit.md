# Audit port iOS (Fase 0)

Status: 22 November 2026, branch `ios-port`. Dasar: kode `main` setelah rilis 0.1.2 (commit 930b0eb) dan hasil
[uji coba YouTube iOS](ios-spike-youtube.md) yang berhasil. Rencana induk:
[Rencana Port ClipMI ke iOS (Sideload)](Rencana%20Port%20ClipMI%20ke%20iOS%20(Sideload).md).

## Ringkasan

| Bagian | Ukuran | Ke iOS |
|---|---|---|
| `core` (Kotlin murni, 36 file) | 6.185 baris + 1.678 baris tes (fixture 27 MB) | Hampir utuh ke `commonMain`; 9 file perlu pengganti API JVM |
| `app/data` (Room) | 1.334 baris | Room KMP (sudah dipakai versi 2.8.5); migrasi dan transaksi perlu disesuaikan |
| `app/domain` | 4.031 baris | Logika dipisah dari API Android; 8 file sangat terikat Android |
| `app/work` (WorkManager) | 1.060 baris | Diganti antrean di dalam aplikasi + unduhan latar iOS; tidak dipindah |
| `app/ui` (Compose) | 12.125 baris | Compose Multiplatform; sebagian besar bisa dipakai ulang |
| `app/src/main/cpp` (C) | 257 baris | C portabel; hanya lapisan JNI yang diganti |
| `app/lab` | 899 baris | Tidak diport (alat uji Fase 0 Android) |

Kesimpulan: **layak**. Risiko terbesar (YouTube) sudah terbukti jalan. Risiko berikutnya adalah FFmpeg di iOS
(dijalankan di dalam proses, bukan sebagai program) dan pratinjau editor (AVPlayer + libass).

## 1. Modul dan dependency sekarang

- Gradle: `:app` (Android, AGP 9.5.1) dan `:core` (Kotlin/JVM murni, hanya `kotlinx-serialization-json`).
- Kotlin 2.4.21, Compose BOM 2026.09.00, Room 2.8.5 (KSP), WorkManager 2.12.0, Media3 1.11.1, OkHttp 5.5.1,
  LiteRT 2.3.2, youtubedl-android 0.18.1, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.13.0.

| Dependency Android | Pengganti di iOS |
|---|---|
| Compose (material3, navigation, lifecycle) | Compose Multiplatform (JetBrains), navigation dan lifecycle versi KMP |
| Room + KSP | Room KMP + `BundledSQLiteDriver` |
| kotlinx-coroutines/serialization | Sama (sudah multiplatform) |
| OkHttp | Ktor Client (engine Darwin di iOS, OkHttp di Android) |
| WorkManager | Antrean di dalam aplikasi + `URLSession` background + `BGTaskScheduler` (terbatas) |
| Media3 ExoPlayer (pratinjau) | AVPlayer di `UIKitView` |
| Media3 muxer / MediaExtractor | AVFoundation (gabung video+audio passthrough) |
| youtubedl-android (Python + QuickJS) | Python 3.14 BeeWare + yt-dlp + `yt-dlp-apple-webkit-jsi` (terbukti di spike) |
| FFmpeg executable (`libffmpeg.so`) | FFmpeg library statis, fungsi `ffmpeg` dipanggil di dalam proses |
| LiteRT + model MediaPipe (deteksi wajah) | Apple Vision (lihat keputusan §5) |
| Android Keystore (`SecretStore`) | Keychain iOS |
| NotificationCompat, AlarmManager | `UNUserNotificationCenter` (notifikasi lokal terjadwal) |
| MediaStore (simpan ke galeri) | PhotoKit (album "ClipMI") |
| ActivityResult pickers | `PHPickerViewController`, `UIDocumentPickerViewController` |
| Pembaruan APK dalam aplikasi | Tidak ada di iOS; pembaruan lewat iloader/SideStore |

## 2. `core` ke `commonMain`

Isi per paket: analysis 642, php 265, render 2.913, schedule 1.208, settings 379, transcript 633, youtube 145 baris.
Tidak ada impor Android. API JVM yang dipakai dan penggantinya:

| File | API JVM | Pengganti multiplatform |
|---|---|---|
| schedule/Accounts, Performance, PostStats, QueueFiller, Schedules | `java.time` (LocalDateTime, LocalTime, Duration, ChronoUnit, DateTimeFormatter) | `kotlinx-datetime` |
| schedule/PostStats | `java.net.URI`, `java.math.BigDecimal` (HALF_UP) | Parser URL kecil; pembulatan sendiri (atau `Php.round`) |
| settings/TeamPack | `java.util.Base64` | `kotlin.io.encoding.Base64` |
| transcript/Parsers | `java.nio.charset` (deteksi encoding subtitle) | `expect/actual` (Charset JVM / `NSString` encoding iOS) |
| render/AssWriter, CoverTitle, php/Php, + 7 file lain | `String.format(Locale.ROOT, …)` (17 tempat), `Locale` | Fungsi format angka sendiri di `php` (hasil harus identik byte demi byte) |
| analysis/ChatPaste, php/HtmlEntities, php/Str | `Character.toChars`, `isValidCodePoint` | Helper codepoint sendiri |
| render/SpeakerAnalysis, TemplateFrame, schedule/Performance, settings/TeamPack | `Math.*` | `kotlin.math` |

**Risiko regex**: `core` memakai 48 `Regex(...)`. Di JVM/Android memakai `java.util.regex`/ICU, di iOS memakai mesin
regex Kotlin/Native yang berbeda. Tes kesetaraan desktop (fixture PHP) harus dijalankan juga di target iOS
(`iosSimulatorArm64Test` di runner macOS) sebelum `core` dipakai di iOS.

**Tes**: 15 kelas tes (parity desktop) membaca fixture dari `src/test/resources` (27 MB) lewat `java.io.File`. Di
KMP: tetap `jvmTest`, plus pembaca fixture `expect/actual` supaya bisa jalan di `iosTest`.

## 3. `app` per bagian

### data (Room)
`AppDatabase` (migrasi 1→10 memakai `SupportSQLiteDatabase`), `Daos`, `Entities`, `Seeds`. Room KMP mendukung
entity/DAO/Flow yang sama; yang perlu diubah: migrasi dan seed memakai `SQLiteConnection`, `withTransaction` menjadi
`useWriterConnection`/`immediateTransaction`. Skema dan nama kolom tetap, jadi file cadangan bisa dipulihkan lintas
platform.

### domain (berdasarkan keterikatan Android)

| Tingkat | File |
|---|---|
| Hampir murni (pindah ke common setelah `Context`/`File` diabstraksi) | Analysis, Scheduling, TeamPacks, Transcripts, PerformanceReports, Settings (kecuali SecretStore), Workspace, UserFonts |
| HTTP (OkHttp → Ktor) | AiClient (233), Transcriber (190, multipart), StatsWorker bagian HTTP |
| Terikat platform (dibuat `actual` iOS) | Renderer (544: alur FFmpeg + MediaStore), Media (FFmpeg/MediaExtractor/Bitmap), MediaLibrary (decode gambar/video), FaceModels + SpeakerDetector (LiteRT), YtDlp (youtubedl-android), Backup (MediaStore, WorkManager), ProjectActions (Intent, WorkManager), Updates/AppUpdater (APK; tidak ada di iOS) |

### work
Delapan worker (YouTube, transkripsi, analisis, render, statistik, performa, pembaruan, foreground). Logikanya tipis
dan memanggil `domain`. Di iOS: antrean tugas di dalam aplikasi (berjalan saat aplikasi terbuka, layar dicegah
terkunci saat render), unduhan file besar lewat `URLSession` background, statistik harian lewat `BGAppRefreshTask`
(dijadwalkan iOS, tidak pasti).

### ui (Compose)
34 file. Bagian yang tidak langsung jalan di Compose Multiplatform:
- `AndroidView`: `EditorPreview` (TextureView + ExoPlayer, crop/zoom/offset, lapisan libass) dan
  `ProjectDetailScreen` (PlayerView). Di iOS: AVPlayerLayer di `UIKitView`; lapisan libass tetap bitmap.
- Pemilih file/galeri (`rememberLauncherForActivityResult`) di 8 file: dibungkus `expect/actual`.
- `android.graphics.Bitmap` di 11 file UI (gambar sampul, thumbnail, pratinjau gaya): `ImageBitmap` + decoder
  per platform.
- `LocalContext` di 22 file UI: hampir semuanya untuk `container`, notifier, atau intent berbagi; diganti
  CompositionLocal milik ClipMI.
- Font dari file (`Font(File)`): di iOS memakai `Font(bytes)`.

## 4. Kode native

- `app/src/main/cpp`: `ass_jni.c` (154 baris, renderer libass ke bitmap) dan `text_measure.c` (84 baris, lebar teks
  FreeType untuk kotak hook). Keduanya C biasa; hanya fungsi `Java_*`/`JNIEnv` yang diganti. Di iOS dipanggil lewat
  **cinterop** Kotlin/Native (tanpa JNI).
- `native/build-native.sh` (MSYS2, NDK): FreeType 2.15.0, FriBidi 1.0.18, HarfBuzz 15.1.0, libass 0.17.5, FFmpeg
  8.1.3 (LGPL, filter terpilih, `h264_mediacodec`), QuickJS-ng 0.18.0. Untuk iOS dibangun ulang dengan toolchain
  Xcode di runner macOS dan disimpan sebagai `.xcframework` (dibangun sekali, di-cache).
  - QuickJS tidak diperlukan di iOS (diganti WebKit).
  - Encoder video: `h264_videotoolbox` (bawaan Apple) menggantikan `h264_mediacodec`.
- **FFmpeg di iOS**: Android menjalankan `libffmpeg.so` sebagai program (`ProcessBuilder`, progres lewat
  `-progress pipe:1`, batal dengan `destroy()`). iOS tidak mengizinkan itu, jadi `fftools/ffmpeg.c` dikompilasi
  sebagai fungsi di dalam aplikasi (pola FFmpegKit): `exit()` diganti kembali dari fungsi, progres lewat callback,
  batal lewat `received_sigterm`, satu render pada satu waktu. Argumen dan filtergraph (`RenderGraph` di `core`)
  tetap sama, jadi hasil render tetap mengikuti tes kesetaraan desktop.

## 5. Keputusan

1. **Crop ikuti wajah: Apple Vision**, bukan LiteRT/MediaPipe. Vision sudah ada di iOS (tanpa framework dan model
   tambahan, memakai Neural Engine) dan memberi kotak wajah serta landmark bibir. Analisis pembicara
   (`core/render/SpeakerAnalysis`) tetap sama karena hanya menerima kotak/landmark; tesnya tidak bergantung pada
   detektor. Konsekuensi: hasil deteksi iOS bisa sedikit berbeda dari Android.
2. **Struktur repo**: `core` diubah menjadi modul KMP (`commonMain` + `jvmMain`/`androidMain` + `iosMain`); `app`
   tetap aplikasi Android. Modul baru `shared` (domain + data yang bisa dibagi) dan `composeApp` (UI) dibuat
   bertahap, bukan sekaligus. Ini lebih kecil dari struktur di rencana induk tetapi tujuannya sama.
3. **YouTube**: pakai hasil spike (Python 3.14 BeeWare + yt-dlp + `yt-dlp-apple-webkit-jsi`), dipanggil dari
   Kotlin/Native lewat jembatan C kecil seperti `PythonBridge.m` di spike. Video dan audio diambil dalam satu
   ekstraksi; file besar diunduh lewat `URLSession` background.
4. **Build**: per tag rilis iOS di repo publik terpisah atau repo ini; library native (FFmpeg, libass, Python)
   dibangun sekali dan di-cache. Pemasangan lewat iloader (SideStore menyusul bila login Apple sudah bisa).

## 6. Urutan kerja berikutnya (usulan)

1. `core` → KMP: ganti API JVM (§2), jalankan semua tes kesetaraan di JVM dan iOS simulator (CI macOS).
   Android tetap memakai `core` yang sama; rilis Android tidak terganggu.
2. Kerangka iOS (Compose Multiplatform "Hello ClipMI") + jembatan Python dari spike: impor link YouTube → unduh →
   gabung video+audio → tampil di daftar project.
3. Room KMP + domain bersama: project, transkrip (subtitle YouTube), analisis (tempel JSON / API via Ktor), daftar
   klip.
4. FFmpeg iOS (`.xcframework`, `ffmpeg` di dalam proses, VideoToolbox) + libass: render satu klip ke Photos.
5. Editor dengan pratinjau AVPlayer + libass; sampul, gaya, hook.
6. Jadwal/notifikasi lokal, statistik, cadangkan/pulihkan lintas platform, crop wajah (Vision).

## 7. Belum diverifikasi

- Perilaku regex Kotlin/Native terhadap fixture desktop (diuji di langkah 1).
- Build FFmpeg 8.1.3 + libass untuk iOS di runner macOS, ukuran `.ipa` akhir.
- Compose Multiplatform untuk pratinjau video (UIKitView di dalam layout Compose) di iPhone.
- Batas memori iPhone saat render 1080×1920.
