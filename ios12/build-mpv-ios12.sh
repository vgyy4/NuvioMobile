#!/usr/bin/env bash
# Builds libmpv (OpenGL ES, VideoToolbox) for iOS 12 arm64 from source and packages it as
# Libmpv.xcframework for the local MPVKit package in ios12/MPVKit.
#
# Why not the prebuilt MPVKit libraries: they target iOS 14 or newer, and they render through
# MoltenVK (Vulkan on Metal), whose current releases need iOS 15. Neither works on an iPad mini 2
# (A7, last system iOS 12). This build has no Vulkan: mpv renders through OpenGL ES (its render API),
# decodes with VideoToolbox and links only libraries compiled for iOS 12.
#
# usage: build-mpv-ios12.sh WORK_DIR MPVKIT_SUBMODULE_DIR OUTPUT_XCFRAMEWORK
set -euo pipefail

work=${1:?work directory}
mpvkit=${2:?path of the MPVKit submodule (build scripts and mpv patches)}
output=${3:?output Libmpv.xcframework path}

MIN=12.0
ARCH=arm64

openssl_version=3.5.7
openssl_sha256=a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8
freetype_version=2.13.3
freetype_url=https://download.savannah.gnu.org/releases/freetype/freetype-${freetype_version}.tar.xz
fribidi_version=1.0.16
fribidi_url=https://github.com/fribidi/fribidi/releases/download/v${fribidi_version}/fribidi-${fribidi_version}.tar.xz
harfbuzz_version=10.4.0
harfbuzz_url=https://github.com/harfbuzz/harfbuzz/releases/download/${harfbuzz_version}/harfbuzz-${harfbuzz_version}.tar.xz
unibreak_version=6.1
unibreak_url=https://github.com/adah1972/libunibreak/releases/download/libunibreak_6_1/libunibreak-${unibreak_version}.tar.gz
libass_version=0.17.5
libass_url=https://github.com/libass/libass/releases/download/${libass_version}/libass-${libass_version}.tar.xz
lcms2_version=2.17
lcms2_url=https://github.com/mm2/Little-CMS/releases/download/lcms2.17/lcms2-${lcms2_version}.tar.gz
libplacebo_tag=v7.360.1
ffmpeg_tag=n8.1.2
mpv_tag=v0.41.0

mkdir -p "$work"/{downloads,src,build,prefix,logs}
work=$(cd "$work" && pwd -P)
mpvkit=$(cd "$mpvkit" && pwd -P)
prefix=$work/prefix
logs=$work/logs
jobs=$(sysctl -n hw.ncpu)

sdk_path=$(xcrun --sdk iphoneos --show-sdk-path)
clang=$(xcrun --sdk iphoneos --find clang)
clangxx=$(xcrun --sdk iphoneos --find clang++)
ar=$(xcrun --sdk iphoneos --find ar)
ranlib=$(xcrun --sdk iphoneos --find ranlib)
strip_tool=$(xcrun --sdk iphoneos --find strip)

target_flags="-arch $ARCH -isysroot $sdk_path -miphoneos-version-min=$MIN"
common_cflags="$target_flags -O2 -g0 -fPIC -I$prefix/include"
common_ldflags="$target_flags -L$prefix/lib"

export PKG_CONFIG_PATH="$prefix/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
export PATH="$prefix/bin:$PATH"

log() { echo "[mpv-ios12] $*"; }

download() { # url file [sha256]
    local url=$1 file=$2 sha=${3:-}
    if [[ ! -f "$file" ]]; then
        for attempt in 1 2 3 4 5; do
            if curl -fsSL --retry 3 --retry-all-errors -o "$file.part" "$url"; then
                mv "$file.part" "$file"
                break
            fi
            [[ $attempt == 5 ]] && { echo "download failed: $url" >&2; exit 1; }
            sleep $((attempt * 10))
        done
    fi
    if [[ -n "$sha" ]]; then
        echo "$sha  $file" | shasum -a 256 -c - >/dev/null
    fi
}

# Runs a build step once; the marker keeps finished libraries across CI cache restores.
step() { # name command...
    local name=$1; shift
    local marker="$prefix/.done-$name"
    if [[ -f "$marker" ]]; then
        log "$name: already built"
        return
    fi
    log "$name: building"
    # errexit is switched on explicitly inside the subshell; it would be ignored if the subshell ran
    # as the condition of an if.
    local status=0
    set +e
    ( set -e; "$@" ) >"$logs/$name.log" 2>&1
    status=$?
    set -e
    if [[ $status -ne 0 ]]; then
        echo "=== $name failed (exit $status); last 80 lines of $logs/$name.log ===" >&2
        tail -n 80 "$logs/$name.log" >&2
        exit 1
    fi
    touch "$marker"
    log "$name: done"
}

unpack() { # archive dir-name
    local archive=$1 name=$2
    if [[ ! -d "$work/src/$name" ]]; then
        tar -xf "$archive" -C "$work/src"
    fi
}

autotools_build() { # name archive extra-configure-args...
    local name=$1 archive=$2; shift 2
    unpack "$archive" "$name"
    rm -rf "$work/build/$name"
    mkdir -p "$work/build/$name"
    (
        cd "$work/build/$name"
        CC="$clang" CXX="$clangxx" AR="$ar" RANLIB="$ranlib" \
        CFLAGS="$common_cflags" CXXFLAGS="$common_cflags" LDFLAGS="$common_ldflags" \
            "$work/src/$name/configure" \
            --host=aarch64-apple-darwin --prefix="$prefix" \
            --enable-static --disable-shared "$@"
        make -j"$jobs"
        make install
    )
}

write_meson_cross_file() {
    cat >"$work/cross.meson" <<EOF
[binaries]
c = '$clang'
cpp = '$clangxx'
objc = '$clang'
objcpp = '$clangxx'
ar = '$ar'
strip = '$strip_tool'
pkg-config = 'pkg-config'

[host_machine]
system = 'darwin'
subsystem = 'ios'
kernel = 'xnu'
cpu_family = 'aarch64'
cpu = 'arm64'
endian = 'little'

[built-in options]
default_library = 'static'
pkg_config_path = '$prefix/lib/pkgconfig'
buildtype = 'release'
prefix = '$prefix'
c_args = [$(for f in $common_cflags; do printf "'%s'," "$f"; done)]
cpp_args = [$(for f in $common_cflags; do printf "'%s'," "$f"; done)]
objc_args = [$(for f in $common_cflags; do printf "'%s'," "$f"; done)]
objcpp_args = [$(for f in $common_cflags; do printf "'%s'," "$f"; done)]
c_link_args = [$(for f in $common_ldflags; do printf "'%s'," "$f"; done)]
cpp_link_args = [$(for f in $common_ldflags; do printf "'%s'," "$f"; done)]
objc_link_args = [$(for f in $common_ldflags; do printf "'%s'," "$f"; done)]
objcpp_link_args = [$(for f in $common_ldflags; do printf "'%s'," "$f"; done)]
EOF
}

meson_build() { # name source-dir meson-args...
    local name=$1 source=$2; shift 2
    rm -rf "$work/build/$name"
    meson setup "$work/build/$name" "$source" --cross-file="$work/cross.meson" "$@"
    meson compile -C "$work/build/$name"
    meson install -C "$work/build/$name"
}

# ---------------------------------------------------------------------------------------------
# OpenSSL (https for FFmpeg)
# ---------------------------------------------------------------------------------------------
build_openssl() {
    local archive=$work/downloads/openssl-$openssl_version.tar.gz
    download "https://github.com/openssl/openssl/releases/download/openssl-$openssl_version/openssl-$openssl_version.tar.gz" \
        "$archive" "$openssl_sha256"
    unpack "$archive" "openssl-$openssl_version"
    if ! perl -MText::Template -e 1 >/dev/null 2>&1; then
        sudo cpan -T Text::Template >/dev/null 2>&1 || true
    fi
    rm -rf "$work/build/openssl"
    mkdir -p "$work/build/openssl"
    (
        cd "$work/build/openssl"
        CC="xcrun --sdk iphoneos clang" perl "$work/src/openssl-$openssl_version/Configure" \
            ios64-xcrun -miphoneos-version-min=$MIN \
            --prefix="$prefix" --openssldir="$prefix/ssl" --libdir=lib \
            no-shared no-tests no-apps no-docs no-module no-legacy no-fips no-comp no-zlib no-zstd
        make -j"$jobs" build_libs
        make install_dev
    )
}

build_freetype() {
    local archive=$work/downloads/freetype-$freetype_version.tar.xz
    download "$freetype_url" "$archive"
    autotools_build "freetype-$freetype_version" "$archive" \
        --with-harfbuzz=no --with-png=no --with-bzip2=no --with-brotli=no --with-zlib=yes
}

build_fribidi() {
    local archive=$work/downloads/fribidi-$fribidi_version.tar.xz
    download "$fribidi_url" "$archive"
    autotools_build "fribidi-$fribidi_version" "$archive" --disable-docs
}

build_harfbuzz() {
    local archive=$work/downloads/harfbuzz-$harfbuzz_version.tar.xz
    download "$harfbuzz_url" "$archive"
    unpack "$archive" "harfbuzz-$harfbuzz_version"
    meson_build harfbuzz "$work/src/harfbuzz-$harfbuzz_version" \
        -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled \
        -Dcoretext=disabled -Dtests=disabled -Ddocs=disabled -Dbenchmark=disabled -Dutilities=disabled
}

build_unibreak() {
    local archive=$work/downloads/libunibreak-$unibreak_version.tar.gz
    download "$unibreak_url" "$archive"
    autotools_build "libunibreak-$unibreak_version" "$archive"
}

build_libass() {
    local archive=$work/downloads/libass-$libass_version.tar.xz
    download "$libass_url" "$archive"
    autotools_build "libass-$libass_version" "$archive" \
        --disable-fontconfig --disable-require-system-font-provider --enable-coretext \
        --disable-asm
}

build_lcms2() {
    local archive=$work/downloads/lcms2-$lcms2_version.tar.gz
    download "$lcms2_url" "$archive"
    autotools_build "lcms2-$lcms2_version" "$archive"
}

# libplacebo is a hard dependency of mpv (color management, tone mapping). The GPU back ends are
# off: mpv renders through its own OpenGL ES code, not through libplacebo.
build_libplacebo() {
    local source=$work/src/libplacebo
    if [[ ! -d "$source" ]]; then
        git clone --quiet --depth 1 --branch "$libplacebo_tag" --recurse-submodules --shallow-submodules \
            https://code.videolan.org/videolan/libplacebo "$source"
    fi
    meson_build libplacebo "$source" \
        -Dvulkan=disabled -Dopengl=disabled -Dd3d11=disabled -Dglslang=disabled -Dshaderc=disabled \
        -Dlcms=enabled -Ddovi=disabled -Dlibdovi=disabled -Dunwind=disabled -Ddemos=false \
        -Dtests=false -Dbench=false -Dfuzz=false
}

# pkg-config does not know the libxml2 of the iOS SDK; FFmpeg's DASH demuxer asks for it.
write_libxml2_pc() {
    mkdir -p "$prefix/lib/pkgconfig"
    cat >"$prefix/lib/pkgconfig/zlib.pc" <<EOF
Name: zlib
Description: zlib of the iOS SDK
Version: 1.2.12
Libs: -lz
Cflags:
EOF
    cat >"$prefix/lib/pkgconfig/libxml-2.0.pc" <<EOF
Name: libxml-2.0
Description: libxml2 of the iOS SDK
Version: 2.9.13
Libs: -lxml2
Cflags: -I$sdk_path/usr/include/libxml2
EOF
}

# FFmpeg: the same component list as the MPVKit build, without Vulkan, gnutls, dav1d, libplacebo.
ffmpeg_configure_args() {
    python3 - "$mpvkit/Sources/BuildScripts/XCFrameworkBuild/main.swift" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
start = text.index("private let ffmpegConfiguers = [")
end = text.index("\n    ]", start)
body = text[start:end]
body = re.sub(r"//[^\n]*", "", body)          # comments
args = re.findall(r'"(--[^"]+)"', body)
skip = ("vulkan", "--pkg-config-flags", "--enable-nonfree")
for arg in args:
    if any(word in arg for word in skip):
        continue
    print(arg)
PY
}

build_ffmpeg() {
    local source=$work/src/ffmpeg
    if [[ ! -d "$source" ]]; then
        git clone --quiet --depth 1 --branch "$ffmpeg_tag" https://github.com/FFmpeg/FFmpeg "$source"
    fi
    rm -rf "$work/build/ffmpeg"
    mkdir -p "$work/build/ffmpeg"
    local args=()
    while IFS= read -r line; do args+=("$line"); done < <(ffmpeg_configure_args)
    (
        cd "$work/build/ffmpeg"
        "$source/configure" \
            --prefix="$prefix" \
            --enable-cross-compile --arch=arm64 --target-os=darwin \
            --cc="$clang" --cxx="$clangxx" --ar="$ar" --ranlib="$ranlib" --strip="$strip_tool" \
            --extra-cflags="$common_cflags" --extra-cxxflags="$common_cflags" --extra-ldflags="$common_ldflags" \
            --enable-neon --enable-asm --disable-programs --disable-large-tests \
            --disable-debug --enable-stripping --enable-optimizations \
            --enable-openssl --enable-libass --enable-filter=ass --enable-filter=subtitles \
            --enable-videotoolbox --enable-audiotoolbox \
            --pkg-config-flags=--static \
            "${args[@]}"
        make -j"$jobs"
        make install
    )
}

build_mpv() {
    local source=$work/src/mpv
    if [[ ! -d "$source" ]]; then
        git clone --quiet --depth 1 --branch "$mpv_tag" https://github.com/mpv-player/mpv "$source"
        local patch
        for patch in "$mpvkit"/Sources/BuildScripts/patch/libmpv/*.patch; do
            log "mpv: applying $(basename "$patch")"
            (cd "$source" && patch -p1 --batch <"$patch")
        done
    fi
    meson_build mpv "$source" \
        -Dlibmpv=true -Dcplayer=false -Dgpl=false \
        -Dgl=enabled -Dplain-gl=enabled -Dios-gl=enabled \
        -Dvulkan=disabled -Dmoltenvk=disabled \
        -Dvideotoolbox-gl=disabled -Dvideotoolbox-pl=disabled \
        -Daudiounit=enabled -Davfoundation=enabled -Dswift-build=disabled \
        -Diconv=enabled -Duchardet=disabled -Dlibbluray=disabled -Dlua=disabled -Dcplugins=disabled \
        -Djavascript=disabled -Dzimg=disabled -Djpeg=disabled -Dvapoursynth=disabled -Drubberband=disabled
}

package_xcframework() {
    local merged=$work/libmpv-all.a
    local libs=(libmpv libavdevice libavcodec libavformat libavfilter libavutil libswresample libswscale
        libass libfreetype libfribidi libharfbuzz libunibreak liblcms2 libplacebo libssl libcrypto)
    local files=()
    for lib in "${libs[@]}"; do
        if [[ ! -f "$prefix/lib/$lib.a" ]]; then
            echo "missing $prefix/lib/$lib.a" >&2
            exit 1
        fi
        files+=("$prefix/lib/$lib.a")
    done
    xcrun libtool -static -o "$merged" "${files[@]}"

    local headers=$work/headers
    rm -rf "$headers"
    mkdir -p "$headers"
    cp -R "$prefix/include/mpv" "$headers/mpv"
    cat >"$headers/module.modulemap" <<'EOF'
module Libmpv [system] {
    header "mpv/client.h"
    header "mpv/render.h"
    header "mpv/render_gl.h"
    header "mpv/stream_cb.h"
    export *
}
EOF
    rm -rf "$output"
    xcodebuild -create-xcframework -library "$merged" -headers "$headers" -output "$output"
}

verify_minimum_os() {
    # Every object of the merged archive must be marked for iOS 12 or lower.
    local merged=$work/libmpv-all.a
    local scratch=$work/verify
    rm -rf "$scratch"
    mkdir -p "$scratch"
    (cd "$scratch" && xcrun ar -x "$merged")
    local bad
    bad=$(
        for object in "$scratch"/*.o; do
            xcrun vtool -show-build "$object" 2>/dev/null | awk -v o="$(basename "$object")" '
                /minos/ { if ($2 + 0 > 12.0) print o, $2 }'
        done | sort -u
    )
    if [[ -n "$bad" ]]; then
        echo "objects built for a newer iOS than 12:" >&2
        echo "$bad" | head -40 >&2
        exit 1
    fi
    log "all objects are built for iOS $MIN or lower"
}

write_meson_cross_file
write_libxml2_pc

step openssl build_openssl
step freetype build_freetype
step fribidi build_fribidi
step harfbuzz build_harfbuzz
step unibreak build_unibreak
step libass build_libass
step lcms2 build_lcms2
step libplacebo build_libplacebo
step ffmpeg build_ffmpeg
step mpv build_mpv

package_xcframework
verify_minimum_os
log "created $output"
