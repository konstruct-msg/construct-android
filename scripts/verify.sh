#!/usr/bin/env bash
# One command that says whether a change to this app is ready to push.
#
#   scripts/verify.sh              static checks + compile + unit tests          (~2 min)
#   scripts/verify.sh --device     + install over existing data, launch, read logcat (~+1.5 min)
#   scripts/verify.sh --device --boot   boot the first AVD if nothing is connected
#   scripts/verify.sh --since REF  range the commit checks read (default: the upstream branch)
#   scripts/verify.sh --watch SECS how long --device reads logcat (default 45; the core's PQ
#                                  upgrade sweep fires ~15 s after launch)
#
# Why a script and not "run the tests": the ways this app has broken were mostly not test
# failures. Commits went in without a compile because checkCoreLibrary stopped Gradle first
# (vault TODO 67); a `.so` from another core build does not fail a call, it fails start-up;
# a core action with no executor compiles fine behind `else ->` and is lost at run time. Each
# check below is one of those, and the report names which one failed.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEVICE=0; BOOT=0; SINCE=""; WATCH=45
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE=1 ;;
    --boot) BOOT=1; DEVICE=1 ;;
    --since) SINCE="$2"; shift ;;
    --watch) WATCH="$2"; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

OUT="$ROOT/app/build/verify"; mkdir -p "$OUT"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
PKG=com.construct.messenger
SERVICE_DIR=app/src/main/java/com/construct/messenger/service

RESULTS=(); FAILED=0
pass() { RESULTS+=("PASS  $1"); }
fail() { RESULTS+=("FAIL  $1 — $2"); FAILED=1; }
info() { RESULTS+=("info  $1"); }
step() { printf '\n\033[1m── %s\033[0m\n' "$1"; }

# ── 1. The core libraries are the ones the lock names ──────────────────────────
step "core pairing"
want=$(grep -m1 '^CONSTRUCT_CORE_VERSION=' construct-core.lock | cut -d= -f2)
bad=""
for so in app/src/main/jniLibs/*/libconstruct_core.so; do
  [ -f "$so" ] || { bad="no .so in app/src/main/jniLibs (scripts/fetch_core.sh; the core is not in git)"; break; }
  got=$(strings -a "$so" | grep -o -m1 'CONSTRUCT_CORE_VERSION=.*' | cut -d= -f2)
  [ "$got" = "$want" ] || bad="$bad $(basename "$(dirname "$so")")=$got"
done
# The same core for this machine, which the unit tests below load (app/src/test/host/).
host=0
for lib in app/src/test/host/*/libconstruct_core.*; do
  [ -f "$lib" ] || continue
  host=$((host + 1))
  got=$(strings -a "$lib" | grep -o -m1 'CONSTRUCT_CORE_VERSION=.*' | cut -d= -f2)
  [ "$got" = "$want" ] || bad="$bad host/$(basename "$(dirname "$lib")")=$got"
done
[ "$host" -gt 0 ] || bad="$bad no host library in app/src/test/host (scripts/fetch_core.sh)"
[ -z "$bad" ] && pass "core pairing ($want)" || fail "core pairing" "lock says $want;$bad"

# ── 2. Nothing requires Google Play Services ───────────────────────────────────
step "no Play Services"
if bash scripts/check_no_play_services.sh > "$OUT/no-gms.txt" 2>&1; then pass "no Play Services"
else fail "no Play Services" "see $OUT/no-gms.txt"; fi

# ── 2b. Design-token debt does not grow ────────────────────────────────────────
# Sizes, faces and colours set by hand instead of CTFont / CTIcon / CTColor. A baseline per
# kind; a rise fails. Twin of the iOS check (vault TODO 122).
step "UI tokens"
if bash scripts/check_ui_tokens.sh > "$OUT/ui-tokens.txt" 2>&1; then pass "UI tokens"
else fail "UI tokens" "see $OUT/ui-tokens.txt"; fi

# ── 3. Every core action has an executor ───────────────────────────────────────
# The two `when`s over CfeAction must stay exhaustive: a new core action should fail to
# compile, not fall into `else ->` and vanish. A branch that only warns "not acted on" is a
# known gap, reported so it is not mistaken for wired.
step "CfeAction executors"
# The executors are MessageProcessor.executeSideEffects and CfeTimerBridge.execute; `route()`
# above them stops at the first action it recognises and is allowed an `else`.
BINDING=$(find app/src/main/java -name construct_core.kt | head -1)
variants=$(awk '/^sealed class CfeAction/,/^}/' "$BINDING" | grep -oE '^    (data )?(class|object) [A-Za-z]+' | awk '{print $NF}')
executor_mp=$(awk '/fun executeSideEffects/{f=1} f' "$SERVICE_DIR/MessageProcessor.kt")
executor_tb=$(awk '/fun execute\(/{f=1} f' "$SERVICE_DIR/CfeTimerBridge.kt")
elses=$( { echo "$executor_mp"; echo "$executor_tb"; } | grep -nE '^\s*else *->' || true)
absent=""
for v in $variants; do
  echo "$executor_mp" | grep -q "CfeAction\.$v\b" || absent="$absent MessageProcessor:$v"
  echo "$executor_tb" | grep -q "CfeAction\.$v\b" || absent="$absent CfeTimerBridge:$v"
done
nv=$(echo "$variants" | wc -w | tr -d ' ')
if [ -z "$elses" ] && [ -z "$absent" ]; then pass "every CfeAction ($nv) has a branch in both executors, no else ->"
else fail "CfeAction executors" "${elses:+else -> present; }${absent:+missing:$absent}"; fi
unwired=$(grep -rn 'not acted on' "$SERVICE_DIR" | wc -l | tr -d ' ')
[ "$unwired" = 0 ] && pass "no core action left unwired" || info "$unwired core action branch(es) still only log 'not acted on'"

# ── 4. Delivery-path commits answer the three questions ────────────────────────
step "commit messages"
if [ -z "$SINCE" ]; then SINCE=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || echo HEAD); fi
missing=""
for c in $(git rev-list "$SINCE..HEAD" 2>/dev/null); do
  touches=$(git show --name-only --format= "$c" | grep -E 'app/src/main/java/com/construct/messenger/(service|crypto|stealth|invite|data/api|domain/usecase)/|app/src/main/proto/' || true)
  [ -z "$touches" ] && continue
  git show -s --format=%B "$c" | grep -qiE 'trust boundary|boundary|границ' || missing="$missing $(git rev-parse --short "$c")"
done
n=$(git rev-list --count "$SINCE..HEAD" 2>/dev/null || echo 0)
[ -z "$missing" ] && pass "three questions in delivery-path commits ($n commit(s) since $SINCE)" \
  || fail "three questions" "no server/withhold/boundary answers in:$missing"

# ── 5. Compile and unit tests ──────────────────────────────────────────────────
# The instrumented tests are compiled too, not run (they need a device — and an emulator: on a
# phone `connectedAndroidTest` uninstalls the app afterwards). Uncompiled, they fell behind the
# core's API for weeks and nothing said so.
step "compile + unit tests"
rm -rf app/build/test-results/testDebugUnitTest
if ./gradlew -q --console=plain :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest > "$OUT/gradle.txt" 2>&1; then
  gradle_ok=1; else gradle_ok=0; fi
counts=$(find app/build/test-results/testDebugUnitTest -name '*.xml' 2>/dev/null \
  | xargs grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' 2>/dev/null \
  | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END{printf "%d %d %d %d", t,s,f,e}')
read -r t s f e <<< "${counts:-0 0 0 0}"
if [ "$gradle_ok" = 1 ] && [ "$f" = 0 ] && [ "$e" = 0 ] && [ "$t" -gt 0 ]; then
  pass "compile + unit tests ($t tests, $s skipped)"
else
  fail "compile + unit tests" "$t tests, $f failed, $e errors — see $OUT/gradle.txt"
  grep -E '^e: |FAILED|Test.*>.*FAILED' "$OUT/gradle.txt" | head -15
fi

# ── 6. On a device: install over existing data, launch, read what happens ─────
if [ "$DEVICE" = 1 ]; then
  step "device"
  serial=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
  if [ -z "$serial" ] && [ "$BOOT" = 1 ]; then
    avd=$("$SDK/emulator/emulator" -list-avds | head -1)
    echo "booting $avd …"
    nohup "$SDK/emulator/emulator" -avd "$avd" -no-snapshot-save -no-boot-anim > "$OUT/emulator.txt" 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
    serial=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
  fi
  if [ -z "$serial" ]; then
    fail "device" "no device or emulator connected (use --boot)"
  elif [ "$FAILED" = 1 ]; then
    info "device stage skipped: fix the failures above first"
  else
    A=("$ADB" -s "$serial")
    if ./gradlew -q --console=plain :app:assembleDebug >> "$OUT/gradle.txt" 2>&1 \
       && "${A[@]}" install -r app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.txt" 2>&1; then
      pass "install over existing data on $serial"
      "${A[@]}" logcat -c
      "${A[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
      echo "watching logcat for ${WATCH}s …"
      sleep "$WATCH"
      pid=$("${A[@]}" shell pidof "$PKG" | tr -d '\r')
      "${A[@]}" logcat -d > "$OUT/logcat-all.txt"
      if [ -n "$pid" ]; then grep -E "\( *$pid\)| $pid " "$OUT/logcat-all.txt" > "$OUT/logcat.txt" || true
      else cp "$OUT/logcat-all.txt" "$OUT/logcat.txt"; fi

      [ -n "$pid" ] && pass "app alive after ${WATCH}s" || fail "app alive" "process gone — see $OUT/logcat-all.txt"
      crash=$(grep -E 'FATAL EXCEPTION|UnsatisfiedLinkError|checksum mismatch|ANR in '"$PKG" "$OUT/logcat-all.txt" | head -3)
      [ -z "$crash" ] && pass "no crash, ANR or UniFFI mismatch" || fail "crash" "$crash"
      nw=$(grep -c 'not acted on' "$OUT/logcat.txt" || true)
      [ "$nw" = 0 ] && pass "no unwired core action at run time" || fail "unwired core action" "$nw 'not acted on' line(s) in logcat"
      # What happened to sessions — counted, not judged: a fresh install and an upgraded one
      # differ, and the reader knows which this was.
      for label in "sessions reopened (OpenSession):session reopen for .* done" \
                   "reopens refused or unavailable:session reopen for .* (refused|unavailable)" \
                   "PQ upgrade deferred:upgrade deferred" \
                   "decryption errors sent:DECRYPTION_ERROR" "decrypt failures:decrypt_failed" \
                   "sessions retired:SessionRetired|retired" "app errors (E/, system noise excluded):^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ E (MessageProcessor|CfeTimerBridge|SessionManager|MessagingRuntime|MessageRouter|MessageStream|ProcessorEffects|SendMessageUseCase|SessionControl|ReceivingOpen|AuthRepository|StealthSender)"; do
        name=${label%%:*}; pat=${label#*:}
        info "$name: $(grep -cE "$pat" "$OUT/logcat.txt" || true)"
      done
    else
      fail "install" "see $OUT/install.txt / $OUT/gradle.txt"
    fi
  fi
fi

# ── Report ────────────────────────────────────────────────────────────────────
printf '\n\033[1m── verify: %s\033[0m\n' "$([ "$FAILED" = 0 ] && echo READY || echo NOT READY)"
printf '%s\n' "${RESULTS[@]}"
echo "logs: $OUT"
exit "$FAILED"
