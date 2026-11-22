# Rencana Port ClipMI ke iOS (Sideload)

Nov 21, 2026 · @Andre Panca

## Ringkasan & keputusan

ClipMI iOS dibangun dari repo **clipmi-android yang sama**, diubah menjadi proyek Kotlin Multiplatform (KMP) + Compose Multiplatform, lalu dipasang lewat sideload SideStore dengan Apple ID gratis untuk 1–3 orang.

- **Pengguna:** Andre + 1–2 anggota tim, masing-masing memakai Apple ID sendiri.
- **Distribusi:** file .ipa tanpa tanda tangan dari GitHub Actions (runner macOS), ditandatangani oleh SideStore di HP. Refresh tiap 4–5 hari dari HP.
- **Target fitur:** setara ClipMI Android v0.1.2, kecuali hal yang tidak mungkin di Apple ID gratis (push notification, kerja di latar belakang yang panjang).
- **Bukan tujuan:** rilis App Store atau TestFlight. Kalau suatu saat perlu, kode yang sama tinggal ditandatangani dengan akun berbayar.

Catatan: rencana ini disusun dari isi APK v0.1.2, bukan dari kode sumbernya. Struktur modul yang sebenarnya perlu dicek dulu di sesi Claude Code (Fase 0).

## Strategi repo: satu repo, bukan clone terpisah

Jangan clone lalu edit untuk iOS. Ubah repo yang ada menjadi multiplatform di branch `ios-port`, supaya satu perbaikan logika langsung berlaku di Android dan iOS.

**Kenapa tidak clone terpisah:** dua repo berarti setiap perbaikan (format JSON AI, aturan klip, kamus koreksi, gaya subtitle) ditulis dua kali. Dalam beberapa minggu kedua versi akan berbeda perilaku.

**Struktur target:**

```
clipmi/
  shared/            # Kotlin common: model, Room KMP, logika AI, parser transkrip, aturan klip
    commonMain/
    androidMain/     # implementasi khusus Android (FFmpeg, yt-dlp, MediaPipe, WorkManager)
    iosMain/         # implementasi khusus iOS
  composeApp/        # UI Compose Multiplatform (layar project, editor, render, statistik)
  androidApp/        # entry point Android (isi app sekarang yang tersisa)
  iosApp/            # proyek Xcode tipis: entry point SwiftUI + library native iOS
```

**Aturan branching:**

1. `main` tetap menghasilkan APK Android. Rilis Android tidak boleh berhenti selama port.
2. Kerja port di branch `ios-port`. Merge ke `main` per fase, setelah APK Android tetap lolos uji.
3. Bagian yang beda per platform ditulis sebagai `expect`/`actual` atau interface di `commonMain` dengan implementasi di `androidMain`/`iosMain`.

Repo **clipmi-android** bisa diganti nama jadi `clipmi-mobile` nanti; tidak wajib sekarang.

## Peta komponen Android → iOS

Sebagian besar logika bisa pindah ke `commonMain`. Kerja berat ada di empat komponen native: FFmpeg, yt-dlp (Python), crop wajah, dan pemutar video.

| Komponen (Android v0.1.2) | Di iOS | Tingkat kerja |
| --- | --- | --- |
| Jetpack Compose (UI) | Compose Multiplatform, sebagian besar layar dipakai ulang | Sedang |
| Room / SQLite | Room versi KMP (bundled SQLite) di `commonMain` | Rendah |
| OkHttp (panggilan API AI) | Ktor Client (engine Darwin di iOS, OkHttp di Android) | Rendah |
| Logika AI: OpenAI, Anthropic, Gemini, Groq, Whisper | Pindah utuh ke `commonMain` | Rendah |
| Media3 / ExoPlayer (preview) | AVPlayer, ditampilkan lewat `UIKitView` | Sedang |
| FFmpeg (libffmpeg.so) + libass | Build FFmpeg sendiri sebagai .xcframework (FFmpegKit sudah tidak dirawat) | Tinggi |
| libclipmi\_native.so (kode native sendiri) | Kompilasi ulang untuk iOS arm64 kalau kodenya C/C++ portabel | Cek di Fase 0 |
| Python 3.12 + yt-dlp | Python versi iOS (Python 3.13+ resmi mendukung iOS) + yt-dlp di dalam app | Tinggi |
| QuickJS (runtime JS untuk yt-dlp) | QuickJS dikompilasi untuk iOS | Sedang |
| MediaPipe face detection (LiteRT) | Opsi A: MediaPipe Tasks iOS. Opsi B: Apple Vision framework (bawaan, tanpa model tambahan) | Sedang |
| WorkManager (jadwal upload, update yt-dlp) | Notifikasi lokal `UNUserNotificationCenter`; update yt-dlp dicek saat app dibuka | Sedang |
| Simpan ke Movies/ClipMI | PhotoKit: simpan ke album "ClipMI" di Photos | Rendah |
| Impor dari galeri | PHPicker untuk video, Document Picker untuk Files | Rendah |
| Update dari dalam app (APK) | Tidak bisa di iOS. Ganti dengan **source SideStore** (JSON di repo releases) | Rendah |
| Font custom TTF/OTF, gaya .ass | Sama, disalin ke folder Documents app | Rendah |

**Batas iOS yang harus diterima:** render dan download hanya jalan saat app terbuka. Saat render, matikan auto-lock layar (`isIdleTimerDisabled`) dan tampilkan peringatan "jangan tutup aplikasi".

## Fase kerja

Ada tujuh fase berurutan. Tiap fase selesai kalau APK Android masih lolos uji **dan** kriteria iOS-nya terpenuhi. Fase 1 menguji jalur sideload sebelum ada kerja port yang besar.

1. **Fase 0 — Audit kode.** Petakan modul dan dependency repo sekarang, tandai file yang terikat Android API, dan cek apakah `libclipmi_native` portabel.
   - Selesai: daftar file "pindah ke common" vs "perlu actual iOS", dan keputusan opsi crop wajah (MediaPipe atau Vision).
2. **Fase 1 — Uji jalur sideload.** Tambahkan target iOS ke Gradle, buat `iosApp` berisi satu layar Compose "Hello ClipMI", build .ipa di GitHub Actions, lalu pasang lewat SideStore.
   - Selesai: app terpasang di iPhone Andre dan berhasil di-refresh dari HP minimal sekali dalam 7 hari.
3. **Fase 2 — Shared core.** Pindahkan model, Room, parser transkrip (SRT/VTT/subtitle YouTube), logika AI, dan aturan klip ke `commonMain`. Ganti OkHttp dengan Ktor.
   - Selesai: di iOS bisa membuat project, menempel JSON hasil ChatGPT, dan melihat daftar klip.
4. **Fase 3 — UI ke Compose Multiplatform.** Pindahkan layar project, gaya analisis, editor (tanpa preview video dulu), dan pengaturan.
   - Selesai: alur project → analisis → daftar klip bisa dipakai penuh di iPhone.
5. **Fase 4 — Video: impor, preview, render.** Impor dari galeri/Files, preview dengan AVPlayer, FFmpeg .xcframework dengan libass, simpan ke Photos.
   - Selesai: satu klip 60 detik 1080×1920 dengan subtitle .ass selesai di-render dan masuk Photos.
6. **Fase 5 — YouTube.** Python iOS + yt-dlp + QuickJS, termasuk pengecekan update yt-dlp saat app dibuka.
   - Selesai: impor dari link YouTube berhasil, termasuk ambil subtitle otomatis.
7. **Fase 6 — Fitur lanjutan.** Crop ikut wajah, cover dan safe-area overlay, jadwal upload dengan notifikasi lokal, statistik, backup/restore (file backup sama dengan Android).
   - Selesai: fitur setara v0.1.2, dan backup dari Android bisa di-restore di iOS.

Kalau Fase 5 macet (Python + yt-dlp di iOS terlalu rumit), ClipMI iOS tetap bisa dipakai dengan impor video dari Files. Videonya diunduh dulu lewat cara lain.

## Build & sideload

Mac tidak diperlukan. Build .ipa tanpa tanda tangan berjalan di runner macOS GitHub Actions, dan penandatanganan dilakukan SideStore di HP.

**Build di GitHub Actions (workflow `ios-ipa.yml`):**

1. Trigger: push tag `ios-v*` atau dijalankan manual.
2. Runner `macos-latest`. Cache Gradle dan folder `~/.konan` supaya build Kotlin/Native tidak mulai dari nol.
3. `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, lalu `xcodebuild archive` dengan `CODE_SIGNING_ALLOWED=NO`.
4. Bungkus `Payload/ClipMI.app` jadi `ClipMI-<versi>.ipa`.
5. Unggah sebagai release di repo publik (misalnya `clipmi-ios-releases`), sekaligus perbarui file `source.json` untuk SideStore.

Perhatikan kuota: repo private memakai menit GitHub Actions, dan menit runner macOS dihitung jauh lebih mahal daripada Linux. Build iOS hanya per tag rilis, jangan di setiap push.

**Pemasangan pertama (sekali per orang, butuh PC):**

1. Aktifkan Developer Mode di iPhone (Settings › Privacy & Security).
2. Pasang SideStore dari PC mengikuti panduan resmi di docs.sidestore.io, termasuk membuat file pairing.
3. Login SideStore dengan **Apple ID masing-masing**.
4. Tambahkan source ClipMI (URL `source.json`) di SideStore, lalu pasang ClipMI dari sana.

**Rutin:**

- Refresh di SideStore tiap 4–5 hari, dengan VPN lokal SideStore menyala.
- Update ClipMI muncul otomatis di SideStore setelah `source.json` diperbarui, menggantikan fitur update APK di Android.
- Backup data ClipMI sebelum update besar.

## Risiko & mitigasi

Risiko terbesar ada di Fase 4–5 (FFmpeg dan yt-dlp di iOS), jadi keduanya punya jalur cadangan.

| Risiko | Dampak | Mitigasi |
| --- | --- | --- |
| Python + yt-dlp sulit dijalankan di iOS | Impor dari YouTube tidak tersedia | Rilis dulu dengan impor dari Files; yt-dlp menyusul |
| Build FFmpeg .xcframework gagal atau terlalu besar | Render tidak jalan | Mulai dari build minimal (H.264, AAC, libass); cek ukuran .ipa per build |
| iPhone kehabisan memori saat render video panjang | App ditutup paksa iOS | Render per klip, bukan sekaligus; batasi sumber 1080p |
| Render terhenti saat layar terkunci atau pindah app | Klip gagal | Matikan auto-lock selama render, tampilkan peringatan, dan izinkan lanjut dari klip terakhir |
| Lupa refresh SideStore lebih dari 7 hari | App tidak bisa dibuka | Pengingat berulang tiap 5 hari; jangan uninstall, cukup refresh |
| Apple mengubah aturan sideload Apple ID gratis | Jalur distribusi tertutup | Kode sama bisa ditandatangani akun Developer $99/tahun lewat TestFlight |
| Kuota menit macOS GitHub Actions habis | Tidak bisa build | Build hanya per tag rilis; cache `~/.konan` |
| Port mengganggu rilis Android | Tim Android terhambat | Kerja di branch `ios-port`; merge per fase setelah APK lolos uji |

## Prompt pembuka untuk sesi Claude Code

Tempel prompt ini di sesi Claude Code repo **clipmi-android** untuk memulai Fase 0 dan Fase 1.

```
Saya ingin membuat ClipMI versi iOS dari repo ini, untuk dipakai 1-3 orang lewat sideload SideStore (Apple ID gratis, tanpa Mac, tanpa App Store).

Keputusan yang sudah diambil:
- Satu repo, bukan clone terpisah. Ubah jadi Kotlin Multiplatform + Compose Multiplatform dengan modul shared, composeApp, androidApp, iosApp.
- Kerja di branch ios-port. Build Android di main tidak boleh rusak; setiap fase harus lolos build dan uji APK.
- Build .ipa tanpa tanda tangan lewat GitHub Actions runner macOS, hanya saat push tag ios-v*.

Tolong kerjakan berurutan dan berhenti di akhir tiap fase untuk saya cek:

Fase 0 - Audit: petakan modul dan dependency, tandai file yang memakai Android API (Context, WorkManager, Media3, MediaStore, OkHttp, JNI), cek apakah kode native libclipmi_native portabel ke iOS, lalu tulis hasilnya di docs/ios-port-audit.md.

Fase 1 - Uji jalur sideload: tambahkan target iosArm64 di Gradle, buat iosApp dengan satu layar Compose "Hello ClipMI", dan buat workflow .github/workflows/ios-ipa.yml yang menghasilkan ClipMI-<versi>.ipa (CODE_SIGNING_ALLOWED=NO, cache ~/.konan) serta source.json untuk SideStore.

Jangan mulai Fase 2 sebelum saya konfirmasi app Hello ClipMI berhasil terpasang dan di-refresh di iPhone saya.
```

Setelah Fase 1 lolos, minta Claude Code lanjut fase berikutnya dengan menyebut nomor fasenya dan kriteria selesainya dari dokumen ini.
