# Library native (arm64-v8a, NDK r30)

Hasil build ada di `native/out/` (tidak di-commit). Bangun ulang setelah clone, atau setelah menaikkan versi:

```bash
D:/dev/msys64/usr/bin/env MSYSTEM=UCRT64 CHERE_INVOKING=1 D:/dev/msys64/usr/bin/bash.exe -l native/build-native.sh
```

| Komponen | Versi | Sumber tarball (`D:/dev/native/src`) | Build |
|---|---|---|---|
| FreeType | 2.15.0 | download.savannah.gnu.org/releases/freetype | meson |
| FriBidi | 1.0.18 | github.com/fribidi/fribidi/releases | autotools (tabel sudah ada di tarball) |
| HarfBuzz | 15.1.0 | github.com/harfbuzz/harfbuzz/releases | meson |
| libass | 0.17.5 | github.com/libass/libass/releases | meson, asm aarch64 aktif |
| FFmpeg | 8.1.3 (sama dengan desktop) | ffmpeg.org/releases | configure, LGPL, lihat `ffmpeg-config.sh` |
| QuickJS-ng | 0.18.0 | github.com/quickjs-ng/quickjs (tag v0.18.0, .tar.gz) | CMake → `jniLibs/libqjs.so`, menggantikan QuickJS bawaan youtubedl-android |

Keluaran:
- `out/arm64-v8a/prefix/lib/*.a` + header: library statis untuk `app/src/main/cpp` (JNI `libclipmi_native.so`).
- `out/arm64-v8a/jniLibs/arm64-v8a/libffmpeg.so` dan `libqjs.so`: executable FFmpeg dan QuickJS-ng. Android 10+ hanya mengizinkan menjalankan file
  dari `nativeLibraryDir`, jadi executable dibundel sebagai "library" dan diekstrak saat pasang (`extractNativeLibs`).

Alat (sekali pasang): MSYS2 di `D:/dev/msys64` dengan paket `make pkgconf autoconf automake libtool patch diffutils
mingw-w64-ucrt-x86_64-{meson,ninja,gcc}`.

Catatan lingkungan Windows yang sudah ditangani skrip:
- Meson menolak prefix `D:/...` untuk host Android: dipasang dengan `--prefix=/` ke DESTDIR, pkg-config memakai
  `PKG_CONFIG_SYSROOT_DIR`; `MSYS2_ARG_CONV_EXCL` mencegah MSYS2 mengubah `/` menjadi `D:/dev/msys64/`.
- clang NDK harus di akhir PATH: di depan PATH ia merusak gcc PC (configure FFmpeg gagal "Host compiler lacks C11").
- Meson 1.12.1 gagal mengenali gcc sebagai compiler build machine saat cross-compile, jadi FriBidi lewat autotools.
- `boxblur` dan `eq` FFmpeg berlisensi GPL; build ini LGPL, latar blur memakai `avgblur` dua kali.
