#!/usr/bin/env bash
# Installs the published construct-core build that construct-core.lock names: the .so files into
# app/src/main/jniLibs/, the Kotlin bindings beside them, and the same build for this machine into
# app/src/test/host/ — what the JVM unit tests load. None of it is in git (AGENTS.md).
#
#   scripts/fetch_core.sh                 the build the lock names
#   scripts/fetch_core.sh <stamp>         another build, and the lock is rewritten to it
#                                         (e.g. 0.31.0+b3fe388ebaca — a diff someone approves)
#
# Every file is checked for the stamp before anything is copied: an archive that is not the build
# it is named after must not land half-installed.
set -euo pipefail
cd "$(dirname "$0")/.."

LOCK=construct-core.lock
want=$(grep -m1 '^CONSTRUCT_CORE_VERSION=' "$LOCK" | cut -d= -f2)
stamp=${1:-$want}
url="https://github.com/konstruct-msg/construct-core/releases/download/v${stamp}/construct-core-android.tar.gz"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "fetching v${stamp}"
curl -fsSL "$url" -o "$tmp/core.tar.gz" || { echo "no published build v${stamp} ($url)" >&2; exit 1; }
tar -xzf "$tmp/core.tar.gz" -C "$tmp"
src="$tmp/android"

bad=""
for lib in "$src"/jniLibs/*/libconstruct_core.so "$src"/host/*/libconstruct_core.*; do
  [ -f "$lib" ] || continue
  got=$(strings -a "$lib" | grep -o -m1 'CONSTRUCT_CORE_VERSION=.*' | cut -d= -f2 || true)
  [ "$got" = "$stamp" ] || bad="$bad ${lib#"$src"/}=$got"
done
[ -z "$bad" ] || { echo "the archive is not v${stamp}:$bad" >&2; exit 1; }
[ -d "$src/host" ] || { echo "v${stamp} carries no host library — published before construct-core #22" >&2; exit 1; }

mkdir -p app/src/main/jniLibs app/src/test/host
cp -R "$src"/jniLibs/. app/src/main/jniLibs/
cp "$src/kotlin/uniffi/construct_core/construct_core.kt" \
   app/src/main/java/com/construct/messenger/crypto/uniffi/construct_core/construct_core.kt
rm -rf app/src/test/host/*
cp -R "$src"/host/. app/src/test/host/

if [ "$stamp" != "$want" ]; then
  # In place, so the comments in the lock survive.
  sed -i '' "s|^CONSTRUCT_CORE_VERSION=.*|CONSTRUCT_CORE_VERSION=${stamp}|" "$LOCK" 2>/dev/null \
    || sed -i "s|^CONSTRUCT_CORE_VERSION=.*|CONSTRUCT_CORE_VERSION=${stamp}|" "$LOCK"
  echo "construct-core.lock: ${want} → ${stamp} — commit it with the bindings"
fi
echo "installed v${stamp}: jniLibs, bindings, host ($(ls app/src/test/host | tr '\n' ' '))"
