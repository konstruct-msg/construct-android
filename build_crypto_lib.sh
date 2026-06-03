#!/bin/bash
# build_crypto_lib.sh
# Собирает Rust-библиотеку construct-core для Android,
# мёрджит ICE-символы и генерирует UniFFI Kotlin bindings.
#
# ИСПОЛЬЗОВАНИЕ:
#   ./build_crypto_lib.sh          # все таргеты (по умолчанию)
#   ./build_crypto_lib.sh --arm64  # только arm64-v8a
#   ./build_crypto_lib.sh --armv7  # только armeabi-v7a
#   ./build_crypto_lib.sh --x86   # только x86_64 (эмулятор)
#   ./build_crypto_lib.sh --all  # все три таргета
#   ./build_crypto_lib.sh --clean  # cargo clean перед сборкой
#   ./build_crypto_lib.sh --debug  # debug сборка

set -e
set -o pipefail

# ── Цвета ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'

ok()   { echo -e "${GREEN}✅${NC} $1"; }
fail() { echo -e "${RED}❌ $1${NC}"; exit 1; }
info() { echo -e "${BLUE}▸${NC} $1"; }
warn() { echo -e "${YELLOW}⚠️${NC}  $1"; }
hdr()  { echo -e "\n${BOLD}━━━  $1  ━━━${NC}"; }

# ── Пути ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
APP_DIR="$PROJECT_ROOT/app"
JNI_LIBS="$APP_DIR/src/main/jniLibs"
CRYPTO_BINDINGS="$APP_DIR/src/main/java/com/maxeliseyev/konstructmessenger/crypto"

# construct-core может быть рядом или в ~/Code
CORE_PATH="$HOME/Code/construct-core"
[ -d "$CORE_PATH" ] || CORE_PATH="$PROJECT_ROOT/../construct-core"
[ -d "$CORE_PATH" ] || fail "construct-core не найден. Ожидается ~/Code/construct-core"

# construct-protos
PROTOS_PATH="$HOME/Code/construct-protos"
[ -d "$PROTOS_PATH" ] || PROTOS_PATH="$PROJECT_ROOT/../construct-protos"
[ -d "$PROTOS_PATH" ] || warn "construct-protos не найден"

FEATURES="android,post-quantum"
BUILD_DIR="release"
CARGO_FLAGS="--release"

# ── Аргументы ───────────────────────────────────────────────────────────────
BUILD_ARM64=true
BUILD_ARMV7=true
BUILD_X86=true
DO_CLEAN=false

for arg in "$@"; do
  case "$arg" in
    --arm64)  BUILD_ARM64=true; BUILD_ARMV7=false; BUILD_X86=false ;;
    --armv7) BUILD_ARM64=false; BUILD_ARMV7=true; BUILD_X86=false ;;
    --x86)  BUILD_ARM64=false; BUILD_ARMV7=false; BUILD_X86=true ;;
    --all)  BUILD_ARM64=true; BUILD_ARMV7=true; BUILD_X86=true ;;
    --clean) DO_CLEAN=true ;;
    --debug) BUILD_DIR="debug"; CARGO_FLAGS="" ;;
    -h|--help)
      echo "Использование: $0 [--arm64] [--armv7] [--x86] [--all] [--clean] [--debug]"
      echo "  (без флагов)  все три таргета (arm64, armv7, x86_64)"
      echo "  --arm64        Только arm64-v8a"
      echo "  --armv7       Только armeabi-v7a"
      echo "  --x86         Только x86_64 (эмулятор)"
      echo "  --all         Все три таргета (явно)"
      echo "  --clean       Cargo clean перед сборкой"
      echo "  --debug       Debug сборка вместо release"
      exit 0 ;;
    *) warn "Неизвестный аргумент: $arg" ;;
  esac
done

# ── Проверка зависимостей ─────────────────────────────────────────────────────
hdr "Проверка зависимостей"
command -v cargo &>/dev/null || fail "cargo не установлен (https://rustup.rs)"
command -v uniffi-bindgen &>/dev/null || {
  warn "uniffi-bindgen не найден, будет установлен"
  cargo install uniffi_bindgen
}
ok "cargo $(cargo --version | cut -d' ' -f2)"

# Проверка Android NDK
if [ -z "$ANDROID_NDK_ROOT" ] && [ -z "$NDK_ROOT" ]; then
  # Пробуем стандартные пути (Android Studio устанавливает в ~/Library/Android/sdk/ndk)
  for p in \
    "$HOME/Library/Android/sdk/ndk/30.0.14904198" \
    "$HOME/Library/Android/sdk/ndk/26.1.10909117" \
    "$ANDROID_HOME/ndk/30.0.14904198" \
    "$ANDROID_HOME/ndk/26.1.10909117" \
    "$ANDROID_SDK_ROOT/ndk/30.0.14904198" \
    "$ANDROID_SDK_ROOT/ndk/26.1.10909117"; do
    if [ -d "$p" ]; then
      ANDROID_NDK_ROOT="$p"
      break
    fi
  done
  # Если нашли неточный путь, попробуем любую версию
  if [ -z "$ANDROID_NDK_ROOT" ] && [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    ANDROID_NDK_ROOT=$(ls -d "$HOME/Library/Android/sdk/ndk"/*/ 2>/dev/null | sort -V | tail -1)
    ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT%/}"  # убрать trailing slash
  fi
fi

if [ -n "$ANDROID_NDK_ROOT" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
  ok "Android NDK: $ANDROID_NDK_ROOT"
  NDK_TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin"
  AR="$NDK_TOOLCHAIN/llvm-ar"
  export AR
else
  fail "Android NDK не найден. Установи через Android Studio → SDK Manager → SDK Tools → NDK (Side by side)"
fi

# ── cargo clean (опционально) ─────────────────────────────────────────────────
if $DO_CLEAN; then
  hdr "Cargo clean"
  cd "$CORE_PATH"
  cargo clean
  ok "Кеш очищен"
fi

# ── Функция: сборка одного таргета ───────────────────────────────────────────
# $1 = rust target triple (e.g. aarch64-linux-android)
# $2 = clang prefix   (e.g. aarch64-linux-android24)
build_target() {
  local target="$1"
  local clang_prefix="$2"
  local toolchain="$NDK_TOOLCHAIN"
  local cc="$toolchain/${clang_prefix}-clang"
  local cxx="$toolchain/${clang_prefix}-clang++"

  info "Сборка для $target ($BUILD_DIR)…"
  cd "$CORE_PATH"

  # Проверяем что clang-wrapper существует
  [ -f "$cc" ] || fail "Clang не найден: $cc"

  # Устанавливаем целевой таргет если нужно
  if ! rustup target list --installed 2>/dev/null | grep -q "$target"; then
    info "Добавление цели $target…"
    rustup target add "$target"
  fi

  # Сборка.
  # ВАЖНО: использовать только target-specific CC_<target>=, а не generic CC=.
  # Generic CC заставит host build scripts (libsqlite3-sys etc.) собирать
  # под host-target тем же android-clang — он не знает SDK хоста и падает на
  # "stdio.h not found". Имена env vars: dashes в target triple → underscores.
  # cc-rs читает оба варианта; берём underscore-вариант как канонический.
  local target_u="${target//-/_}"
  local cc_var="CC_${target_u}"
  local cxx_var="CXX_${target_u}"
  local ar_var="AR_${target_u}"
  local rc
  set +e
  env "$cc_var=$cc" "$cxx_var=$cxx" "$ar_var=$NDK_TOOLCHAIN/llvm-ar" \
    cargo build --lib --target "$target" --features "$FEATURES" $CARGO_FLAGS 2>&1 \
    | grep -E "^error|^warning\[|Compiling|Finished"
  rc=${PIPESTATUS[0]}
  set -e
  [ "$rc" -eq 0 ] || fail "cargo build failed for $target (rc=$rc) — re-run без grep-фильтра чтоб увидеть полный лог"

  ok "Собрано: $target"
}

# ── Функция: мёрдж ICE ────────────────────────────────────────────────────────
merge_ice() {
  local target="$1"
  local arch="$2"
  local core_lib="$CORE_PATH/target/$target/$BUILD_DIR/libconstruct_core.so"

  [ -f "$core_lib" ] || fail "libconstruct_core.so не найден: $core_lib"

  # Ищем libconstruct_ice*.a в deps/
  local ice_lib
  ice_lib=$(find "$CORE_PATH/target/$target/$BUILD_DIR/deps" \
            -name "libconstruct_ice*.a" 2>/dev/null | xargs ls -t 2>/dev/null | head -1)

  if [ -n "$ice_lib" ] && [ -f "$ice_lib" ]; then
    # Мёрдрим статические библиотеки
    local tmp_obj="/tmp/construct_android_$$_$target.o"
    local final_lib="$JNI_LIBS/$arch/libconstruct_core.so"

    mkdir -p "$JNI_LIBS/$arch"

    # Просто копируем .so (ICE уже вкомпилен в crate зависимость)
    cp "$core_lib" "$final_lib"
    info "Скопировано: $arch → libconstruct_core.so"
  else
    mkdir -p "$JNI_LIBS/$arch"
    cp "$core_lib" "$JNI_LIBS/$arch/libconstruct_core.so"
    warn "ICE не найден для $target — используем без ICE"
  fi

  local size
  size=$(du -sh "$JNI_LIBS/$arch/libconstruct_core.so" | cut -f1)
  ok "libconstruct_core.so ($arch) → $size"
}

# ── Функция: генерация UniFFI bindings ─────────────────────────────────
generate_uniffi_bindings() {
  local target="$1"
  local core_lib="$CORE_PATH/target/$target/$BUILD_DIR/libconstruct_core.so"

  [ -f "$core_lib" ] || {
    warn "libconstruct_core.so не найден для $target — пропускаем UniFFI"
    return 1
  }

  # Генерируем только для arm64 — это основной таргет для Kotlin-биндингов
  [ "$target" = "aarch64-linux-android" ] || return 0

  info "Генерация UniFFI Kotlin bindings…"
  mkdir -p "$CRYPTO_BINDINGS"
  uniffi-bindgen generate \
    --library "$core_lib" \
    --language kotlin \
    --out-dir "$CRYPTO_BINDINGS"
  ok "UniFFI bindings → $CRYPTO_BINDINGS/"
}

# ── Сборка ───────────────────────────────────────────────────────────────────
hdr "Сборка библиотек для Android"

if $BUILD_ARM64; then
  build_target "aarch64-linux-android" "aarch64-linux-android24"
fi
if $BUILD_ARMV7; then
  build_target "armv7-linux-androideabi" "armv7a-linux-androideabi24"
fi
if $BUILD_X86; then
  build_target "x86_64-linux-android" "x86_64-linux-android24"
fi

# ── Мёрдж и копирование в jniLibs ─────────────────────────────────────
hdr "Копирование в jniLibs"

mkdir -p "$JNI_LIBS"

if $BUILD_ARM64; then
  merge_ice "aarch64-linux-android" "arm64-v8a"
  generate_uniffi_bindings "aarch64-linux-android"
fi
if $BUILD_ARMV7; then
  merge_ice "armv7-linux-androideabi" "armeabi-v7a"
fi
if $BUILD_X86; then
  merge_ice "x86_64-linux-android" "x86_64"
fi

# ── Добавление в build.gradle ───────────────────────────────────────────
hdr "Обновление build.gradle"

GRADLE="$APP_DIR/build.gradle"

if ! grep -q "jniLibs" "$GRADLE"; then
  cat >> "$GRADLE" << 'EOF'

// Native libs configuration
android.sourceSets.main.jniLibs.srcDirs = ['src/main/jniLibs']
EOF
  ok "Добавлен jniLibs в build.gradle"
else
  ok "jniLibs уже настроен"
fi

# ── Верификация ─────────────────────────────────────────────────────────
hdr "Верификация"

verify_lib() {
  local arch="$1"
  local lib="$JNI_LIBS/$arch/libconstruct_core.so"

  if [ -f "$lib" ]; then
    if file "$lib" | grep -q "ELF"; then
      ok "$arch: ELF $(file "$lib" | cut -d: -f2)"
    else
      warn "$arch: не ELF бинарник"
    fi
  else
    fail "$arch: libconstruct_core.so не найден"
  fi
}

if $BUILD_ARM64; then verify_lib "arm64-v8a"; fi
if $BUILD_ARMV7; then verify_lib "armeabi-v7a"; fi
if $BUILD_X86; then verify_lib "x86_64"; fi

# ── Готово ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}Готово! Следующие шаги:${NC}"
echo "  1. Запусти Android Studio и открой проект"
echo "  2. Gradle Sync (Sync Project with Gradle Files)"
echo "  3. Build → Build Bundle(s) / APK(s) → Build APK(s)"
echo ""
echo "Структура после сборки:"
echo "  app/src/main/jniLibs/arm64-v8a/libconstruct_core.so"
echo "  app/src/main/jniLibs/armeabi-v7a/libconstruct_core.so"
echo "  app/src/main/jniLibs/x86_64/libconstruct_core.so"
echo "  app/src/main/java/.../crypto/ClassicCryptoCore.kt"