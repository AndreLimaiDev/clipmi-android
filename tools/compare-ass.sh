#!/usr/bin/env bash
# Membandingkan frame libass dari HP (uji "ass", di lab/ass) dengan filter `ass` FFmpeg desktop pada waktu yang sama.
#
#   tools/compare-ass.sh <folder png dari HP> [ffmpeg desktop]
#
# Acuan desktop: latar hitam yuv444p (seperti jalur video desktop, tanpa subsampling chroma) 1080x1920, ass=<fixture>:fontsdir=<font desktop>, frame pada 0,5/1,0/2,2/3,4 detik.
# Frame HP (RGB) dikonversi ke YUV BT.601 TV range, sama dengan cara filter ass FFmpeg menafsirkan warna ASS tanpa
# header "YCbCr Matrix" (libass YCBCR_DEFAULT). Membandingkan di rgb24 menyesatkan: di sana FFmpeg menekan warna ke 16..235.
# Hasil: PSNR dan SSIM per frame (identik = PSNR "inf", SSIM 1.000000).
set -euo pipefail

PHONE="${1:?folder PNG dari HP}"
FFMPEG="${2:-D:/laragon/www/clip-mi/resources/bin/win-x64/ffmpeg.exe}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FONTS_SRC="D:/laragon/www/clip-mi/resources/fonts"
REF="$PHONE/../ass-desktop"
# Seperti desktop: font disalin ke folder kerja dan dipanggil relatif (fontsdir=fonts).
mkdir -p "$REF/fonts" && cp "$FONTS_SRC"/*.ttf "$REF/fonts/"

printf "%-28s %6s %10s %10s\n" "fixture" "ms" "PSNR(dB)" "SSIM"
for ass in "$ROOT"/app/src/main/assets/lab/*.ass; do
    name="$(basename "$ass" .ass)"
    cp "$ass" "$REF/$name.ass"
    for ms in 500 1000 2200 3400; do
        t=$(printf "%d.%03d" $((ms / 1000)) $((ms % 1000)))
        ref="$REF/${name}_$ms.y4m"
        (cd "$REF" && "$FFMPEG" -hide_banner -loglevel error -y -f lavfi -i "color=c=black:s=1080x1920:r=100:d=5" \
            -vf "format=yuv444p,ass=$name.ass:fontsdir=fonts" -ss "$t" -frames:v 1 "$(basename "$ref")")
        phone="$PHONE/${name}_$ms.png"
        [ -f "$phone" ] || { printf "%-28s %6s %s\n" "$name" "$ms" "(PNG HP belum ada)"; continue; }
        psnr=$("$FFMPEG" -hide_banner -i "$phone" -i "$ref" -lavfi "[0:v]scale=out_color_matrix=bt601:out_range=tv,format=yuv444p[a];[1:v]format=yuv444p[b];[a][b]psnr" -f null - 2>&1 \
            | grep -oE "average:[^ ]+" | cut -d: -f2)
        ssim=$("$FFMPEG" -hide_banner -i "$phone" -i "$ref" -lavfi "[0:v]scale=out_color_matrix=bt601:out_range=tv,format=yuv444p[a];[1:v]format=yuv444p[b];[a][b]ssim" -f null - 2>&1 \
            | grep -oE "All:[0-9.]+" | cut -d: -f2)
        printf "%-28s %6s %10s %10s\n" "$name" "$ms" "$psnr" "$ssim"
    done
done
