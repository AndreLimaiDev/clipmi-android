#!/usr/bin/env bash
# Membangun library native ClipMI untuk Android arm64 dengan NDK r30.
#
# Dijalankan dari shell MSYS2 UCRT64 (butuh make, pkgconf, meson, ninja):
#   D:/dev/msys64/usr/bin/env MSYSTEM=UCRT64 CHERE_INVOKING=1 /usr/bin/bash -l native/build-native.sh [langkah...]
# Langkah: qjs freetype fribidi harfbuzz libass ffmpeg (default: semuanya).
#
# Sumber diunduh ke $SRC (tarball, lihat native/README.md); hasil:
#   native/out/arm64-v8a/prefix   library statis + header (dipakai CMake aplikasi)
#   native/out/arm64-v8a/jniLibs  executable libffmpeg.so dan libqjs.so
#
# libqjs.so = QuickJS-ng untuk tantangan JS YouTube di yt-dlp. youtubedl-android 0.18.1 membundel QuickJS 2025-04-26
# dengan nama yang sama dan memberikannya ke yt-dlp lewat --js-runtimes; app/build.gradle.kts (pickFirsts) memakai
# milik kita karena 25% lebih cepat di HP uji.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${SRC:-/d/dev/native/src}"
BUILD="${BUILD:-/d/dev/native/build}"
OUT="$ROOT/native/out/arm64-v8a"
PREFIX="$OUT/prefix"
JNILIBS="$OUT/jniLibs/arm64-v8a"

API=29
NDK_WIN="D:/dev/android-sdk/ndk/30.0.16248370"
TC_WIN="$NDK_WIN/toolchains/llvm/prebuilt/windows-x86_64"
TC="/d/dev/android-sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64"
CMAKE_BIN="/d/dev/android-sdk/cmake/3.31.6/bin"
TARGET="aarch64-linux-android$API"

FREETYPE=freetype-2.15.0
FRIBIDI=fribidi-1.0.18
HARFBUZZ=harfbuzz-15.1.0
LIBASS=libass-0.17.5
FFMPEG=ffmpeg-8.1.3
QJS=quickjs-ng-0.18.0

# 16 KB page size (wajib untuk Android 15+ di HP dengan halaman 16 KB).
COMMON_CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections"
COMMON_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--gc-sections"

# clang NDK di (akhir) PATH mencegah meson mencari Visual Studio (vsenv); di depan PATH ia merusak gcc PC.
export PATH="$PATH:$TC/bin"

mkdir -p "$BUILD" "$PREFIX" "$JNILIBS"
export PKG_CONFIG_LIBDIR="$(cygpath -m "$PREFIX")/lib/pkgconfig"
export PKG_CONFIG_PATH=""
# Meson menolak prefix "D:/..." untuk host Android, jadi dipasang dengan prefix "/" ke DESTDIR, dan pkg-config
# menambahkan sysroot ke setiap -I/-L.
PREFIX_WIN="$(cygpath -m "$PREFIX")"
export PKG_CONFIG_SYSROOT_DIR="$PREFIX_WIN"

CROSS="$BUILD/android-arm64.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$TC_WIN/bin/clang.exe'
cpp = '$TC_WIN/bin/clang++.exe'
ar = '$TC_WIN/bin/llvm-ar.exe'
strip = '$TC_WIN/bin/llvm-strip.exe'
ranlib = '$TC_WIN/bin/llvm-ranlib.exe'
pkg-config = 'pkgconf'

[built-in options]
c_args = ['--target=$TARGET', $(printf "'%s', " $COMMON_CFLAGS)]
cpp_args = ['--target=$TARGET', $(printf "'%s', " $COMMON_CFLAGS)]
c_link_args = ['--target=$TARGET', $(printf "'%s', " $COMMON_LDFLAGS)]
cpp_link_args = ['--target=$TARGET', '-static-libstdc++', $(printf "'%s', " $COMMON_LDFLAGS)]

[host_machine]
system = 'android'
subsystem = 'android'
kernel = 'linux'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
EOF

# Compiler PC untuk program pembuat tabel saat build (FriBidi gen.tab).
NATIVE="$BUILD/build-machine.ini"
cat > "$NATIVE" <<EOF
[binaries]
c = 'D:/dev/msys64/ucrt64/bin/gcc.exe'
cpp = 'D:/dev/msys64/ucrt64/bin/g++.exe'
ar = 'D:/dev/msys64/ucrt64/bin/ar.exe'
strip = 'D:/dev/msys64/ucrt64/bin/strip.exe'
pkg-config = 'pkgconf'
EOF

unpack() { # unpack <nama>
    [ -d "$BUILD/$1" ] || tar -xf "$SRC/$1.tar.xz" -C "$BUILD"
}

meson_build() { # meson_build <nama> <opsi meson...>
    local name=$1; shift
    unpack "$name"
    rm -rf "$BUILD/$name/_android"
    # MSYS2_ARG_CONV_EXCL: jangan ubah "--prefix=/" menjadi "D:/dev/msys64/".
    MSYS2_ARG_CONV_EXCL="--prefix=" meson setup "$BUILD/$name/_android" "$BUILD/$name" --cross-file "$CROSS" \
        --native-file "$NATIVE" --prefix=/ --libdir lib --includedir include --buildtype release --default-library static \
        -Db_staticpic=true -Db_ndebug=true "$@"
    meson compile -C "$BUILD/$name/_android"
    meson install -C "$BUILD/$name/_android" --destdir "$PREFIX_WIN"
}

step_qjs() {
    [ -d "$BUILD/$QJS" ] || { mkdir -p "$BUILD/$QJS"; tar -xzf "$SRC/$QJS.tar.gz" -C "$BUILD/$QJS" --strip-components=1; }
    "$CMAKE_BIN/cmake.exe" -S "$BUILD/$QJS" -B "$BUILD/$QJS/_android" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="D:/dev/android-sdk/cmake/3.31.6/bin/ninja.exe" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_WIN/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=$API -DCMAKE_BUILD_TYPE=Release -DQJS_BUILD_EXAMPLES=OFF
    "$CMAKE_BIN/cmake.exe" --build "$BUILD/$QJS/_android" --target qjs_exe
    "$TC/bin/llvm-strip.exe" -o "$JNILIBS/libqjs.so" "$BUILD/$QJS/_android/qjs"
}

step_freetype() {
    meson_build $FREETYPE -Dbrotli=disabled -Dbzip2=disabled -Dharfbuzz=disabled -Dpng=disabled \
        -Dzlib=disabled -Dtests=disabled -Dmmap=enabled
}

step_fribidi() {
    # Lewat autotools, bukan meson: meson 1.12.1 di MSYS2 gagal mengenali gcc untuk program gen.tab, sedangkan
    # tarball rilis sudah berisi tabel hasil generate (lib/*.tab.i) sehingga gen.tab tidak perlu dijalankan.
    unpack $FRIBIDI
    local dir="$BUILD/$FRIBIDI"
    rm -rf "$dir/_android"
    (
        cd "$dir"
        make distclean >/dev/null 2>&1 || true
        MSYS2_ARG_CONV_EXCL="--prefix=" ./configure --host=aarch64-linux-android --prefix=/ \
            --enable-static --disable-shared --disable-docs --disable-deprecated \
            CC="$TC_WIN/bin/clang.exe --target=$TARGET" AR="$TC_WIN/bin/llvm-ar.exe" \
            RANLIB="$TC_WIN/bin/llvm-ranlib.exe" CFLAGS="$COMMON_CFLAGS -DNDEBUG" LDFLAGS="$COMMON_LDFLAGS"
        make -C lib
        make -C lib install DESTDIR="$PREFIX_WIN"
        make install-pkgconfigDATA DESTDIR="$PREFIX_WIN"
    )
}

step_harfbuzz() {
    meson_build $HARFBUZZ -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled \
        -Dchafa=disabled -Dpng=disabled -Dzlib=disabled -Dicu=disabled -Dgraphite=disabled \
        -Dfontations=disabled -Dgdi=disabled -Ddirectwrite=disabled -Dcoretext=disabled -Dharfrust=disabled \
        -Dkbts=disabled -Dwasm=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled -Dgpu_demo=disabled \
        -Dsubset=disabled -Dtests=disabled -Dintrospection=disabled -Ddocs=disabled -Ddoc_tests=false \
        -Dutilities=disabled -Dbenchmark=disabled
}

step_libass() {
    meson_build $LIBASS -Dfontconfig=disabled -Ddirectwrite=disabled -Dcoretext=disabled \
        -Drequire-system-font-provider=false -Dlibunibreak=disabled -Dasm=enabled \
        -Dtest=disabled -Dcompare=disabled -Dprofile=disabled -Dfuzz=disabled -Dcheckasm=disabled
}

step_ffmpeg() {
    source "$ROOT/native/ffmpeg-config.sh"
}

STEPS=("$@")
[ ${#STEPS[@]} -gt 0 ] || STEPS=(qjs freetype fribidi harfbuzz libass ffmpeg)
for s in "${STEPS[@]}"; do
    echo "=== $s ==="
    "step_$s"
done
echo "Selesai: $OUT"
