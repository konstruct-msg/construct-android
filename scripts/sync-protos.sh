#!/usr/bin/env bash
# Sync vendored .proto files from a local construct-protos checkout.
#
# construct-protos is the client-facing mirror: construct-server/shared/proto is the
# source of truth, and construct-protos/scripts/sync-from-server.sh copies it there.
# Android vendors its own copy under app/src/main/proto because the Gradle protobuf
# plugin needs the files inside the module, so that copy has to be refreshed by hand
# whenever the schema changes — this script is that step.
#
# Usage: ./scripts/sync-protos.sh [/path/to/construct-protos]
#        ./scripts/sync-protos.sh --check      # verify in sync, change nothing
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
DEST="$REPO_ROOT/app/src/main/proto"

CHECK_ONLY=0
if [[ "${1:-}" == "--check" ]]; then
  CHECK_ONLY=1
  shift
fi

PROTOS_DIR="${1:-${PROTOS_DIR:-$HOME/Code/construct-protos}}"

if [[ ! -d "$PROTOS_DIR" ]]; then
  echo "Error: construct-protos not found at $PROTOS_DIR" >&2
  echo "Pass the path explicitly: $0 /path/to/construct-protos" >&2
  exit 1
fi

DIRS=(core messaging services signaling)
drift=0

for dir in "${DIRS[@]}"; do
  src="$PROTOS_DIR/$dir"
  [[ -d "$src" ]] || continue

  if [[ $CHECK_ONLY -eq 1 ]]; then
    # Compare file by file rather than `diff -rq --include`: BSD diff (macOS) has no
    # --include, and silently treats it as an operand, which reports drift for every
    # directory whether or not anything differs.
    for proto in "$src"/*.proto; do
      name="$(basename "$proto")"
      if [[ ! -f "$DEST/$dir/$name" ]]; then
        echo "  MISSING: $dir/$name"
        drift=1
      elif ! diff -q "$proto" "$DEST/$dir/$name" >/dev/null; then
        echo "  DRIFT: $dir/$name"
        drift=1
      fi
    done
  else
    mkdir -p "$DEST/$dir"
    cp "$src/"*.proto "$DEST/$dir/"
    echo "  synced: $dir/"
  fi
done

if [[ $CHECK_ONLY -eq 1 ]]; then
  if [[ $drift -eq 1 ]]; then
    echo "✗ Vendored protos differ from $PROTOS_DIR — run: $0" >&2
    exit 1
  fi
  echo "✓ Vendored protos match $PROTOS_DIR"
  exit 0
fi

echo "✅ Sync complete. Review changes with: git diff app/src/main/proto"
