# ClipMI Android

Versi Android ClipMI: aplikasi berdiri sendiri untuk memotong video kajian dan ceramah menjadi klip vertikal 9:16 siap unggah, dengan semua data tersimpan di HP pengguna.

- Rencana dan fitur: [prd-clipmi-android.md](prd-clipmi-android.md)
- Hasil uji Fase 0 dan keputusan teknis: [docs/fase0-hasil.md](docs/fase0-hasil.md)
- Lingkungan build dan aturan kerja: [CLAUDE.md](CLAUDE.md)
- Library native (libass, FFmpeg, QuickJS-ng): [native/README.md](native/README.md)

Build:
1. Library native (sekali, atau setelah menaikkan versi): `native/build-native.sh` dari MSYS2 UCRT64.
2. Aplikasi: `scripts/gradle.sh assembleDebug` (Bash) atau `powershell -ExecutionPolicy Bypass -File scripts\gradle.ps1 assembleDebug`.
