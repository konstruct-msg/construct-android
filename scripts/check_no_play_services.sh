#!/usr/bin/env bash
#
# Nothing in this app may require Google Play Services on the device.
#
# Decided 2026-08-23, while it still cost nothing: there was no delivery code in the tree at all,
# so the choice had not been made yet. It gets made by default the moment someone writes push the
# shortest way, and FCM is the shortest way. After that, changing it costs the delivery path, the
# server's token migration, and re-testing everything that depends on message ordering.
#
# Why it is an invariant rather than a preference: FCM hands a third party the fact and timing of
# every message you receive, plus a stable device id — precisely the metadata sealed sender exists
# to withhold from our own server. And an app that needs no GMS *is* the GrapheneOS client, which
# is why there will not be a separate one.
#
# See ~/Code/construct-docs/decisions/android-without-play-services.md
#
# Build-time Google artifacts (Hilt, KSP, protobuf) are fine and expected: they ask nothing of the
# device. Only device-service coordinates are refused.
#
set -euo pipefail

cd "$(dirname "$0")/.."

c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_dim() { printf '\033[2m%s\033[0m\n' "$*"; }

# Coordinates and plugin ids that pull a Play-Services runtime onto the device.
# Deliberately not a bare "google" match: `com.google.dagger`, `com.google.protobuf` and
# `com.google.devtools.ksp` are build-time and allowed, and a rule that cried wolf on them would
# be turned off within a week.
FORBIDDEN=(
    "com.google.firebase"
    "com.google.android.gms"
    "com.google.gms"
    "com.google.android.play"
    "play-services"
    "google-services"
    "firebase-bom"
    "com.google.android.datatransport"
)

fail=0

# 1. Declared dependencies and plugins.
while IFS= read -r -d '' f; do
    for token in "${FORBIDDEN[@]}"; do
        if grep -qiF "$token" "$f"; then
            c_red "  ✗ $f references '$token'"
            fail=1
        fi
    done
    # The parentheses are load-bearing. Without them `-o` binds looser than the implicit `-a`,
    # so `-print0` attaches only to the last `-name` branch and `*.gradle` is never printed —
    # the first version of this script passed a planted `firebase-messaging` line for exactly
    # that reason. Found by planting one; it is the only way to know a check can fail.
    # `-type f` because `.gradle` is also the name of a cache *directory* here.
done < <(find . -type f \( -name "*.gradle" -o -name "*.gradle.kts" -o -name "*.versions.toml" \) \
    -not -path "./build/*" -not -path "./*/build/*" -not -path "./.gradle/*" -print0)

# 2. The manifest, where a service or receiver would actually be wired up.
MANIFEST="app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
    for token in "${FORBIDDEN[@]}"; do
        if grep -qiF "$token" "$MANIFEST"; then
            c_red "  ✗ $MANIFEST references '$token'"
            fail=1
        fi
    done
fi

# 3. A resolved dependency tree is the only thing that catches a *transitive* GMS pull — a
#    library that itself depends on play-services would pass steps 1 and 2. Only run when a
#    resolution already exists; this script must stay usable without a network.
LOCKS=$(find . -name "*.lockfile" -not -path "./build/*" -not -path "./*/build/*" 2>/dev/null || true)
if [ -n "$LOCKS" ]; then
    while IFS= read -r lock; do
        for token in "${FORBIDDEN[@]}"; do
            if grep -qiF "$token" "$lock"; then
                c_red "  ✗ $lock resolves '$token' transitively"
                fail=1
            fi
        done
    done <<< "$LOCKS"
else
    c_dim "no dependency lockfiles — transitive GMS pulls are unchecked here"
    c_dim "  (\`./gradlew app:dependencies\` is the manual version; enable dependency locking to close this)"
fi

if [ "$fail" -eq 0 ]; then
    c_grn "no Play Services dependency — the app runs on a device without GMS"
else
    echo
    c_red "This app must run on a device with no Google Play Services."
    c_dim "Delivery is our own MessageStream in a foreground service; UnifiedPush is an option."
    c_dim "decisions/android-without-play-services.md — read it before arguing with this check."
    exit 1
fi
