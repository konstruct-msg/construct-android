#!/usr/bin/env bash
# Builds libconstruct_whisper.so — whisper.cpp behind native/whisper/whisper_jni.c — for every ABI
# the app ships, into app/src/main/jniLibs/<abi>/ (not in git, like the core; AGENTS.md). TODO 116.
#
#   scripts/build_whisper.sh
#
# The source is whisper.cpp at the tag below, checked by the archive's SHA-256 before anything is
# built: a different archive under the same name stops here. Needs the Android NDK (ANDROID_NDK_HOME,
# or the newest under the SDK), cmake and ninja.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.9.5
SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
ABIS=(arm64-v8a armeabi-v7a x86_64)
MIN_SDK=26

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
ndk=${ANDROID_NDK_HOME:-$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)}
[ -f "$ndk/build/cmake/android.toolchain.cmake" ] || { echo "no Android NDK (set ANDROID_NDK_HOME)" >&2; exit 1; }

work=build/whisper
mkdir -p "$work"
archive="$work/whisper.cpp-$VERSION.tar.gz"
if [ ! -f "$archive" ]; then
  echo "fetching whisper.cpp v$VERSION"
  curl -fsSL "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$VERSION.tar.gz" -o "$archive.part"
  mv "$archive.part" "$archive"
fi
got=$( (sha256sum "$archive" 2>/dev/null || shasum -a 256 "$archive") | cut -d' ' -f1)
[ "$got" = "$SHA256" ] || { echo "whisper.cpp v$VERSION: SHA-256 $got, expected $SHA256" >&2; rm -f "$archive"; exit 1; }
src="$work/whisper.cpp-$VERSION"
[ -d "$src" ] || tar -xzf "$archive" -C "$work"

# Ninja when there is one (faster); Make otherwise — every runner has that.
generator=$(command -v ninja >/dev/null && echo Ninja || echo "Unix Makefiles")
for abi in "${ABIS[@]}"; do
  out="$work/out-$abi"
  cmake -S native/whisper -B "$out" -G "$generator" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$MIN_SDK" \
    -DCMAKE_BUILD_TYPE=Release -DWHISPER_SRC="$PWD/$src" > "$work/cmake-$abi.log"
  cmake --build "$out" --target construct_whisper > "$work/build-$abi.log"
  mkdir -p "app/src/main/jniLibs/$abi"
  # The APK carries the code, not the symbols.
  "$ndk"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded -o "app/src/main/jniLibs/$abi/libconstruct_whisper.so" "$out/libconstruct_whisper.so"
  echo "$abi: $(du -h "app/src/main/jniLibs/$abi/libconstruct_whisper.so" | cut -f1)"
done
