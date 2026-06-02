#!/bin/bash
# generate_grpc_kotlin.sh
# Генерирует Kotlin gRPC client code из .proto файлов для Android.
#
# ТРЕБУЕТСЯ:
#   - protoc (https://github.com/protocolbuffers/protobuf/releases)
#   - protoc-gen-grpc-kotlin (io.grpc:protoc-gen-grpc-kotlin)
#   - protoc-gen-java (io.grpc:protoc-gen-java)
#
# УСТАНОВКА:
#   brew install protobuf
#   # grpc-kotlin generator (скачать jar и положить в путь)
#   GRPC_KOTLIN_VERSION="1.4.1"
#   curl -LO "https://github.com/grpc/grpc-kotlin/releases/download/v${GRPC_KOTLIN_VERSION}/protoc-gen-grpc-kotlin-jars-${GRPC_KOTLIN_VERSION}.zip"
#   unzip -o protoc-gen-grpc-kotlin-jars-${GRPC_KOTLIN_VERSION}.zip -d /usr/local/lib/
#
# ИСПОЛЬЗОВАНИЕ:
#   ./generate_grpc_kotlin.sh         # по умолчанию
#   ./generate_grpc_kotlin.sh --clean   # очистить перед генерацией
#   ./generate_grpc_kotlin.sh --check # только проверить зависимости

set -euo pipefail

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
PROTOS_DIR="${PROTOS_DIR:-$HOME/Code/construct-protos}"
[ -d "$PROTOS_DIR" ] || PROTOS_DIR="$PROJECT_ROOT/../construct-protos"

OUTPUT_DIR="$APP_DIR/src/main/java/com/maxeliseyev/konstructmessenger/data/api/proto"
GRPC_KOTLIN_VERSION="${GRPC_KOTLIN_VERSION:-1.4.1}"

DO_CLEAN=false
CHECK_ONLY=false

# ── Аргументы ───────────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --clean)  DO_CLEAN=true ;;
    --check) CHECK_ONLY=true ;;
    -h|--help)
      echo "Использование: $0 [--clean] [--check]"
      echo "  --clean  Очистить output директорию перед генерацией"
      echo "  --check Только проверить зависимости"
      exit 0 ;;
    *) warn "Неизвестный аргумент: $arg" ;;
  esac
done

# ── Проверка зависимостей ─────────────────────────────────────────────
hdr "Проверка зависимостей"

check_dep() {
  if command -v "$1" &>/dev/null; then
    ok "$1 найден"
    return 0
  else
    fail "$1 не найден"
    return 1
  fi
}

DEPS_OK=true

check_dep "protoc" || DEPS_OK=false
# protoc-gen-grpc-kotlin может быть как команда или jar
if command -v "protoc-gen-grpc-kotlin" &>/dev/null; then
  ok "protoc-gen-grpc-kotlin найден"
elif [ -f "/usr/local/lib/protoc-gen-grpc-kotlin" ] || \
     [ -f "/usr/local/bin/protoc-gen-grpc-kotlin" ]; then
  ok "protoc-gen-grpc-kotlin найден (jar)"
else
  warn "protoc-gen-grpc-kotlin не найден - будет использовать protoc-gen-grpc-kotlin"
fi

if [ "$CHECK_ONLY" = true ]; then
  if $DEPS_OK; then
    ok "Основные зависимости установлены"
    exit 0
  else
    fail "Отсутствуют зависимости"
    exit 1
  fi
fi

# Проверка protos директории
[ -d "$PROTOS_DIR" ] || fail "construct-protos не найден: $PROTOS_DIR"

ok "Proto source: $PROTOS_DIR"

# ── Очистка (опционально) ───────────────────────────────────────────────
if $DO_CLEAN; then
  hdr "Очистка"
  rm -rf "$OUTPUT_DIR"
  ok "Директория очищена"
fi

# ── Создание output директории ───────────────────────────────────────
mkdir -p "$OUTPUT_DIR"

info "Output: $OUTPUT_DIR"

# ── Поиск .proto файлов ───────────────────────────────────────────
PROTO_FILES=$(find "$PROTOS_DIR" -name "*.proto" -not -path "*/google/*" -not -path "*/.*" | sort)
PROTO_COUNT=$(echo "$PROTO_FILES" | wc -l | tr -d ' ')
info "Найдено $PROTO_COUNT .proto файлов"

# ── Генерация ───────────────────────────────────────────────────
hdr "Генерация Kotlin gRPC"

PROTO_PATH_ARGS="--proto_path=$PROTOS_DIR"

# Проверяем что protoc доступен
command -v protoc &>/dev/null || fail "protoc не найден"

# Генерируем: Java для messages, gRPC Kotlin для services
protoc $PROTO_PATH_ARGS \
  --java_out="$OUTPUT_DIR" \
  --kotlin_out="$OUTPUT_DIR" \
  --grpc-kotlin_out="$OUTPUT_DIR" \
  $(echo "$PROTO_FILES")

# ── Результаты ─────────────────────────────────────────────────
JAVA_COUNT=$(find "$OUTPUT_DIR" -name "*.java" 2>/dev/null | wc -l | tr -d ' ')
KT_COUNT=$(find "$OUTPUT_DIR" -name "*.kt" 2>/dev/null | wc -l | tr -d ' ')
GRPC_KT_COUNT=$(find "$OUTPUT_DIR" -name "*Grpc*.kt" 2>/dev/null | wc -l | tr -d ' ')

if [ "$JAVA_COUNT" -gt 0 ]; then
  ok "Сгенерировано $JAVA_COUNT .java файлов (messages)"
fi
if [ "$KT_COUNT" -gt 0 ]; then
  ok "Сгенерировано $KT_COUNT .kt файлов (Kotlin messages)"
fi
if [ "$GRPC_KT_COUNT" -gt 0 ]; then
  ok "Сгенерировано $GRPC_KT_COUNT gRPC Kotlin файлов"
fi

# ── Обновление build.gradle ───────────────────────────────────────────
hdr "Обновление build.gradle"

GRADLE="$APP_DIR/build.gradle"

GRPC_DEPS='
// gRPC Kotlin dependencies
implementation "io.grpc:grpc-kotlin:1.4.1"
implementation "io.grpc:grpc-core:1.64.0"
implementation "io.grpc:grpc-api:1.64.0"
implementation "io.grpc:grpc-protobuf:1.64.0"
implementation "io.grpc:grpc-okhttp:1.64.0"

// Protobuf
implementation "com.google.protobuf:protobuf-kotlin:3.25.2"
implementation "com.google.protobuf:protobuf-kotlin-lite:3.25.2"
'

if ! grep -q "grpc-kotlin" "$GRADLE"; then
  echo "$GRPC_DEPS" >> "$GRADLE"
  ok "Добавлены gRPC зависимости"
else
  ok "gRPC зависимости уже есть"
fi

# ── Готово ─────────────────────────────────────────────────
echo ""
echo -e "${BOLD}Готово! Следующие шаги:${NC}"
echo "  1. ./gradlew sync"
echo "  2. Используй классы из: data.api.proto"
echo ""
echo "Пример gRPC вызова:"
echo "  val channel = ManagedChannelBuilder.forAddress(...).build()"
echo "  val stub = AuthServiceGrpc.newBlockingStub(channel)"
echo "  val response = stub.login(request)"
echo ""
echo "Структура:"
echo "  app/src/main/java/.../data/api/proto/"
echo "  ├── *.kt, *.java (messages)"
echo "  └── *Grpc.kt (gRPC stubs)"