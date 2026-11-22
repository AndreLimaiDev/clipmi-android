# Uji coba YouTube di iPhone (spike iOS)

## Hasil (22 November 2026): BERHASIL

Diuji di iPhone pengguna (iPhone15,4, iOS 26.1), aplikasi dipasang lewat iloader (Import IPA, Apple ID gratis).
Kode: repo publik https://github.com/AndreLimaiDev/clipmi-ios-spike (build kedua lolos, .ipa ±31 MB).

- Python 3.14.10 (BeeWare 3.14-b14) jalan; ctypes, ssl (OpenSSL 3.5.9), sqlite3, lzma, bz2, zlib ok; subprocess
  tidak tersedia (sesuai dugaan).
- yt-dlp 2026.10.26 + yt-dlp-ejs 0.8.0: penyedia tantangan JS yang dipakai `apple-webkit-jsi-0.1.1 (external)`;
  WKWebView dibuat dari thread latar tanpa masalah.
- Video kajian 9:24: info 3,6 detik; unduh video itag 137 (1080p H.264, 58 MB) dan audio itag 140 (m4a, 8,7 MB),
  total 28,8 detik (±5 MB/dtk lewat Wi-Fi, termasuk dua kali ekstraksi).

Catatan untuk port penuh:
- Unduh video+audio dalam satu ekstraksi (format dipisah koma, seperti Android) supaya tantangan JS cukup sekali.
- Penggabungan video+audio tanpa FFmpeg: AVFoundation (passthrough), padanan MediaMuxer di Android.
- Unduhan saat aplikasi di latar: URLSession background (yt-dlp cukup mencari alamat stream).
- SideStore 0.7.0-alpha belum bisa login (Apple 401/503); pemasangan sementara lewat iloader dari PC.

Status awal: rencana, 22 November 2026. Branch `ios-port`. Bagian dari [Rencana Port ClipMI ke iOS (Sideload)](Rencana%20Port%20ClipMI%20ke%20iOS%20(Sideload).md);
urutannya dibalik: risiko YouTube diuji lebih dulu, sebelum port KMP/UI.

## Tujuan

Satu aplikasi iOS kecil (SwiftUI, belum KMP) yang dipasang lewat SideStore, menerima link YouTube, lalu:

1. menjalankan Python + yt-dlp **di dalam proses aplikasi**,
2. memecahkan tantangan JavaScript YouTube tanpa program terpisah,
3. mengunduh video dan audio ke folder aplikasi, dan menampilkan ukuran/durasinya.

Berhasil = langkah 1–3 jalan di iPhone pengguna untuk 2–3 video kajian, dan aplikasi bisa di-refresh di SideStore.
Gagal di langkah 2 = YouTube iOS ditunda; port tetap mungkin dengan impor dari Files.

## Temuan riset (22 Nov 2026)

- **Python di iOS**: resmi sejak Python 3.13 (dokumen "Using Python on iOS"). Framework siap pakai dari
  BeeWare `Python-Apple-support` (Python.xcframework, rilis GitHub; pakai 3.14). Modul biner (.so) diubah menjadi
  framework oleh skrip `install_python` dari `Python.xcframework/build/utils.sh` (Run Script sebelum Embed Frameworks).
  Interpreter dijalankan dengan `PyPreConfig`/`PyConfig` (utf8_mode, buffered_stdio mati, write_bytecode mati);
  `PYTHONHOME` = `<bundle>/python`, `PYTHONPATH` = stdlib + `<bundle>/app`.
- **Batas iOS**: "An iOS app cannot use any form of subprocessing, multiprocessing, or inter-process communication."
  `subprocess`, `fork`, `execve` tidak bisa dipakai. Socket dan thread normal.
- **yt-dlp**: tantangan n/sig YouTube diselesaikan kerangka EJS. Semua runtime bawaan (deno, node, quickjs, bun)
  dijalankan sebagai program (`Popen`), jadi tidak bisa di iOS. Tetapi API **JS Challenge Provider** bersifat publik
  (`yt_dlp.extractor.youtube.jsc.provider`, kelas `JsChallengeProvider`, nama berakhiran `JCP`), dan penyedia boleh
  mengevaluasi JS di dalam proses.
- **Plugin siap pakai**: `yt-dlp-apple-webkit-jsi` (grqz, Apache-2.0, v0.1.1, Python murni lewat `ctypes`,
  `dependencies = []`). Memecahkan n/sig dengan **WKWebView** di dalam proses; mengklaim mendukung iOS/iPadOS 14+
  (belum ada bukti uji di iOS). Butuh yt-dlp ≥ 2025.11.12. Bila dipanggil dari thread lain, ia menjalankan kerjanya
  di main run loop, jadi yt-dlp dijalankan di thread latar dan UI tetap di main thread.
- **SideStore**: iOS 15+; pemasangan pertama dari PC Windows 64-bit (iTunes + iloader, LocalDevVPN, Developer
  Mode). Apple ID gratis: 3 aplikasi aktif (SideStore termasuk), 10 App ID per minggu, kedaluwarsa 7 hari, refresh di HP
  dengan LocalDevVPN menyala. File .ipa bisa diimpor ke SideStore.

## Isi spike

```
ios-spike/
  project.yml          # XcodeGen: proyek Xcode dibuat di CI (tidak ada Mac)
  Sources/             # SwiftUI: isian link, tombol Unduh, log, tombol Bagikan log
    PythonRuntime.swift  # PyPreConfig/PyConfig, jalankan spike.py di thread latar
  app/spike.py         # yt-dlp: info + unduh format H.264 ≤1080p dan audio m4a ke Documents
.github/workflows/ios-spike.yml
```

Workflow (runner macOS, dipicu manual):

1. Unduh Python.xcframework 3.14 (BeeWare), simpan di cache.
2. `pip install --target app_packages --no-deps yt-dlp yt-dlp-ejs yt-dlp-apple-webkit-jsi certifi`
   (semuanya Python murni).
3. `xcodegen`, lalu `xcodebuild archive ... CODE_SIGNING_ALLOWED=NO`.
4. Bungkus `Payload/ClipMISpike.app` menjadi `ClipMISpike.ipa` dan unggah sebagai artifact.

Karena tidak ada Xcode console, aplikasi menulis log sendiri (stdout/stderr Python) ke file dan menampilkannya di
layar, dengan tombol Bagikan supaya log bisa dikirim ke PC.

## Yang belum pasti (diuji spike)

- Plugin WebKit benar-benar jalan di iOS dari Python tertanam (belum ada bukti di iOS).
- `ctypes`, `ssl`, dan sertifikat (certifi) di Python iOS.
- Pemblokiran YouTube (403/PO token) sama seperti di Android; bukan masalah khusus iOS.
- Unduhan besar saat aplikasi di latar: untuk versi penuh, yt-dlp hanya mencari alamat stream dan file diunduh lewat
  unduhan latar iOS (URLSession). Spike cukup mengunduh saat aplikasi terbuka.
