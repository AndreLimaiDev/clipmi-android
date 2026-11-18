# FFmpeg minimal untuk uji mesin render Fase 0 (dipanggil oleh build-native.sh, bukan dijalankan sendiri).
#
# Hanya komponen yang dipakai ClipRenderer desktop: demux MP4/MKV, decode H.264/HEVC/AAC (software, NEON),
# encode H.264 lewat MediaCodec (encoder hardware HP, NDK API tanpa JNI) dan AAC, filter video/audio desktop,
# serta libass/FreeType/HarfBuzz/FriBidi dari prefix yang sama. Lisensi LGPL: tanpa x264 dan tanpa filter GPL
# (boxblur desktop diganti avgblur dua kali), tanpa jaringan.
# Hasilnya executable `ffmpeg` yang dibundel sebagai jniLibs/libffmpeg.so.

unpack $FFMPEG
FF_DIR="$BUILD/$FFMPEG"
FF_OUT="$FF_DIR/_android"
mkdir -p "$FF_OUT"

FILTERS=(
    # video
    scale crop overlay boxblur split setsar setdar format colorchannelmixer ass subtitles pad null copy fps
    geq drawbox drawtext tile select loop setpts trim concat xfade color nullsrc alphamerge fade tpad
    hflip vflip transpose unsharp gblur avgblur
    # audio
    anull acopy highpass afftdn loudnorm aresample aformat atrim asetpts aselect anullsrc amix afade acrossfade
    apad volume pan volumedetect silencedetect
)

(
    cd "$FF_OUT"
    # MSYS2_ARG_CONV_EXCL: jangan ubah "--prefix=/" menjadi path MSYS2.
    MSYS2_ARG_CONV_EXCL="--prefix=" "$FF_DIR/configure" \
        --prefix=/ \
        --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
        --cc="$TC_WIN/bin/clang.exe" --cxx="$TC_WIN/bin/clang++.exe" --ld="$TC_WIN/bin/clang.exe" \
        --ar="$TC_WIN/bin/llvm-ar.exe" --nm="$TC_WIN/bin/llvm-nm.exe" --ranlib="$TC_WIN/bin/llvm-ranlib.exe" \
        --strip="$TC_WIN/bin/llvm-strip.exe" \
        --pkg-config=pkgconf --pkg-config-flags=--static \
        --extra-cflags="--target=$TARGET $COMMON_CFLAGS" \
        --extra-cxxflags="--target=$TARGET $COMMON_CFLAGS" \
        --extra-ldflags="--target=$TARGET $COMMON_LDFLAGS" \
        --extra-libs="-lc++_static -lc++abi -lm" \
        --enable-pic --enable-static --disable-shared \
        --disable-debug --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages \
        --disable-autodetect --disable-network --disable-everything \
        --disable-ffplay --disable-ffprobe --enable-ffmpeg \
        --disable-avdevice \
        --enable-mediacodec --enable-jni \
        --enable-zlib \
        --enable-libass --enable-libfreetype --enable-libharfbuzz --enable-libfribidi \
        --enable-protocol=file,pipe \
        --enable-demuxer=mov,matroska,image2,ass,concat,wav,mp3,aac \
        --enable-muxer=mp4,mov,ipod,null,image2,wav \
        --enable-parser=h264,hevc,aac,png,mjpeg \
        --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata,h264_metadata \
        --enable-decoder=h264,hevc,aac,mp3,pcm_s16le,png,mjpeg,h264_mediacodec,hevc_mediacodec \
        --enable-encoder=h264_mediacodec,aac,png,mjpeg,pcm_s16le \
        --enable-filter="$(IFS=,; echo "${FILTERS[*]}")"
    make -j"$(nproc)"
    "$TC/bin/llvm-strip.exe" -o "$JNILIBS/libffmpeg.so" ffmpeg
)
ls -la "$JNILIBS/libffmpeg.so"
