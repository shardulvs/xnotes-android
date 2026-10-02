#!/usr/bin/env bash
# Regenerates the vendored PDFium tree (app/src/main/cpp/pdfium), its CMake lists
# (app/src/main/cpp/pdfium.cmake) and its THIRD_PARTY entries from one pinned
# PDFium commit.
#
# Dev machine only. The Gradle and F-Droid builds compile the committed tree and
# never run this. Needs curl, tar, unzip, python3 and the SDK's pinned NDK and
# CMake.
#
#   tools/pdfium-sync.sh            fetch the pinned sources, then regenerate
#   tools/pdfium-sync.sh --offline  regenerate from the sources already fetched
#
# 1. Fetch: gitiles tarballs at the commits PDFium's DEPS pins (much faster than
#    gclient's git fetches, and no depot_tools), plus GN from CIPD.
# 2. GN: the build graph of //:pdfium for each ABI, as project.json.
# 3. Probe: compile that graph with the pinned NDK and CMake, and link it rooted
#    at the public API (saving excluded). The link map shows which sources reach
#    the library.
# 4. Vendor: copy those sources, every file their compiles read, and the
#    licenses; write pdfium.cmake and THIRD_PARTY.
# 5. Verify: link the probe again from the vendored tree and require the same
#    symbols.
#
# PDFIUM_WORK overrides the work dir (default ~/.cache/xnotes-pdfium).
set -euo pipefail

# chromium/8076 branch head (2026-09-29).
PDFIUM_BRANCH=chromium/8076
PDFIUM_REV=8ca5b735df4263f43c830479b78213854a392c22
PDFIUM_GIT=https://pdfium.googlesource.com/pdfium
# The DEPS entries that the build graph of //:pdfium loads or compiles.
DEPS_NEEDED=(
    build
    buildtools
    third_party/abseil-cpp
    third_party/cpu_features/src
    third_party/dragonbox/src
    third_party/fast_float/src
    third_party/freetype/src
    third_party/harfbuzz/src
    third_party/icu
    third_party/libjpeg_turbo
    third_party/nasm
    third_party/zlib
)

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=$ROOT/tools
WORK=${PDFIUM_WORK:-$HOME/.cache/xnotes-pdfium}
SRC=$WORK/src
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
NDK_VERSION=$(sed -n 's/^ *ndkVersion = "\(.*\)"$/\1/p' "$ROOT/app/build.gradle.kts")
NDK=$SDK/ndk/$NDK_VERSION
MIN_SDK=$(sed -n 's/^ *minSdk = \([0-9]*\)$/\1/p' "$ROOT/app/build.gradle.kts")
# Android ABI name, GN cpu name.
ABIS=("arm64-v8a arm64" "armeabi-v7a arm" "x86_64 x64")

offline=0
case "${1:-}" in
    --offline) offline=1 ;;
    "") ;;
    *) echo "usage: $0 [--offline]" >&2; exit 2 ;;
esac
[ -d "$NDK" ] || { echo "NDK $NDK_VERSION not found at $NDK" >&2; exit 1; }

# Extracts <repo> at <rev> into <dir>, skipping it when already there.
fetch_tree() {
    local repo=${1%.git} rev=$2 dir=$3
    [ "$(cat "$dir/.xnotes-rev" 2>/dev/null)" = "$rev" ] && return
    rm -rf "$dir.part" && mkdir -p "$dir.part"
    curl -fsSL --retry 3 "$repo/+archive/$rev.tar.gz" | tar -xz -C "$dir.part"
    echo "$rev" > "$dir.part/.xnotes-rev"
    rm -rf "$dir" && mv "$dir.part" "$dir"
    echo "fetched $dir"
}

if [ "$offline" = 0 ]; then
    mkdir -p "$WORK"
    if [ "$(cat "$SRC/.xnotes-rev" 2>/dev/null)" != "$PDFIUM_REV" ]; then
        rm -rf "$SRC"
        fetch_tree "$PDFIUM_GIT" "$PDFIUM_REV" "$SRC"
    fi
    pids=()
    while read -r path repo rev; do
        fetch_tree "$repo" "$rev" "$SRC/$path" & pids+=($!)
    done < <(python3 "$TOOLS/pdfium_vendor.py" deps "$SRC/DEPS" "${DEPS_NEEDED[@]}")
    for pid in "${pids[@]}"; do wait "$pid"; done

    gn_version=$(python3 "$TOOLS/pdfium_vendor.py" var "$SRC/DEPS" gn_version)
    if [ "$(cat "$WORK/gn/.xnotes-rev" 2>/dev/null)" != "$gn_version" ]; then
        rm -rf "$WORK/gn" && mkdir -p "$WORK/gn"
        curl -fsSL --retry 3 -o "$WORK/gn/gn.zip" \
            "https://chrome-infra-packages.appspot.com/dl/gn/gn/linux-amd64/+/$gn_version"
        unzip -q "$WORK/gn/gn.zip" gn -d "$WORK/gn" && rm "$WORK/gn/gn.zip"
        echo "$gn_version" > "$WORK/gn/.xnotes-rev"
    fi
fi

got=$(cat "$SRC/.xnotes-rev" 2>/dev/null || true)
[ "$got" = "$PDFIUM_REV" ] || { echo "no sources for $PDFIUM_REV; run without --offline" >&2; exit 1; }

# gclient would write this from DEPS' gclient_gn_args.
cat > "$SRC/build/config/gclient_args.gni" <<EOF
build_with_chromium = false
checkout_android = true
checkout_libpng = true
checkout_skia = false
android_ndk_version = "$(python3 "$TOOLS/pdfium_vendor.py" var "$SRC/DEPS" android_ndk_version)"
EOF

# GN describes the NDK's clang and libc++ (the compilers we build with), zeroed
# stack variables (Chromium's production hardening), and no host sysroot, since
# no host tool is ever built.
for spec in "${ABIS[@]}"; do
    read -r abi cpu <<< "$spec"
    (cd "$SRC" && "$WORK/gn/gn" gen "out/$abi" --root-pattern=//:pdfium --ide=json \
        --script-executable=python3 --args="
        target_os=\"android\"
        target_cpu=\"$cpu\"
        clang_base_path=\"$NDK/toolchains/llvm/prebuilt/linux-x86_64\"
        default_min_sdk_version=$MIN_SDK
        is_debug=false
        is_component_build=false
        pdf_is_standalone=true
        pdf_enable_v8=false
        pdf_enable_xfa=false
        pdf_use_skia=false
        pdf_use_partition_alloc=false
        use_custom_libcxx=false
        clang_use_chrome_plugins=false
        treat_warnings_as_errors=false
        init_stack_vars_zero=true
        use_sysroot=false
        symbol_level=0")
done

# Probe: build every reachable source once per ABI with the pinned NDK and
# CMake, rooted at the whole public API, keeping the link maps.
CMAKE_DIR=$SDK/cmake/$(sed -n 's/^ *version = "\(3\.[0-9.]*\)"$/\1/p' "$ROOT/app/build.gradle.kts")/bin
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
DEST=$ROOT/app/src/main/cpp

probe_build() {  # <build dir> <pdfium dir> <pdfium.cmake> <abi>
    "$CMAKE_DIR/cmake" -G Ninja -S "$WORK/probe" -B "$1" \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$4" -DANDROID_PLATFORM="android-$MIN_SDK" \
        -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DPDFIUM_DIR="$2" -DPDFIUM_CMAKE="$3" > /dev/null
    "$CMAKE_DIR/cmake" --build "$1" -- -k 0 > "$1.log" 2>&1 \
        || { echo "probe build failed, see $1.log" >&2; exit 1; }
}

jsons=()
for spec in "${ABIS[@]}"; do
    read -r abi cpu <<< "$spec"
    jsons+=("$abi=$SRC/out/$abi/project.json")
done
python3 "$TOOLS/pdfium_vendor.py" probe "$SRC" "$WORK" "$LLVM/clang" "$PDFIUM_REV" "${jsons[@]}"
for spec in "${ABIS[@]}"; do
    read -r abi cpu <<< "$spec"
    probe_build "$WORK/probe-build/$abi" "$SRC" "$WORK/probe/pdfium.cmake" "$abi"
done

# Vendor the sources that reach a link plus the files they include, then link
# the probe again from the vendored tree (another directory) and check that it
# defines exactly the same symbols.
python3 "$TOOLS/pdfium_vendor.py" vendor "$SRC" "$WORK" "$DEST" "$CMAKE_DIR/ninja" \
    "$PDFIUM_REV" "$PDFIUM_BRANCH"
# A vendored file that git ignores would be missing from every clone.
ignored=$(git -C "$ROOT" ls-files --others --ignored --exclude-standard "$DEST/pdfium")
[ -z "$ignored" ] || { printf "git ignores vendored files:\n%s\n" "$ignored" >&2; exit 1; }
for spec in "${ABIS[@]}"; do
    read -r abi cpu <<< "$spec"
    probe_build "$WORK/verify-build/$abi" "$DEST/pdfium" "$DEST/pdfium.cmake" "$abi"
    printf "%s: " "$abi"
    python3 "$TOOLS/pdfium_vendor.py" same-symbols "$LLVM/llvm-nm" \
        "$WORK/probe-build/$abi/libpdfium_probe.so" "$WORK/verify-build/$abi/libpdfium_probe.so"
done
