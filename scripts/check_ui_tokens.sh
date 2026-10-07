#!/usr/bin/env bash
# check_ui_tokens.sh — the design-token debt may shrink, never grow. Android twin of iOS
# scripts/check_ui_tokens.sh (vault TODO 122).
#
# AGENTS.md says UI uses tokens (CTFont, CTIcon, CTSpace, CTColor). Nothing counted, and by
# 2026-10-06 one kind of element was set in 2–5 sizes across screens, with 326 hand-picked text
# sizes and 124 hand-sized boxes and icons. Each count below has a baseline; the check fails when
# a count rises above it. When a migration lowers a count, lower its baseline in the same commit,
# so the ratchet holds what was won.
#
# The counts are coarse on purpose. A size literal is also a video preview's frame or a QR code's
# box; a colour literal is also the white behind a QR code, which is correct. They guard the trend,
# not each site. No build needed.

set -euo pipefail
cd "$(dirname "$0")/../app/src/main/java"

THEME="/ui/theme/"

count() {
  # Zero is the goal, and grep exits 1 on no match; under pipefail that would end the script.
  { grep -rnE "$1" --include='*.kt' . || true; } | { grep -v "$THEME" || true; } | wc -l | tr -d ' '
}

# name | pattern | baseline
CHECKS=(
  "size set by hand — an icon takes CTIcon, a box a CTLayout size|\.size\([0-9]+(\.[0-9]+)?\.dp\)|32"
  "text size past CTFont — use a role or CTFont.ui(size)|fontSize *= *[0-9]|5"
  "fixed-size text helper — use a CTFont role|\bct(Regular|Medium|SemiBold|Bold|Message)\(|0"
  "system face — chrome is JetBrains Mono; message text is CTFont.message|FontFamily\.(Default|Monospace|SansSerif|Serif)|1"
  "colour literal — use CTColor|Color\.(White|Black|Gray|Red|Green|Blue|Yellow|LightGray|DarkGray)\b|Color\(0x|73"
)

failed=0
for entry in "${CHECKS[@]}"; do
  name="${entry%%|*}"; rest="${entry#*|}"
  pattern="${rest%|*}"; baseline="${rest##*|}"
  n=$(count "$pattern")
  if (( n > baseline )); then
    echo "✗ $name: $n (baseline $baseline). Sites, first 20:"
    { grep -rnE "$pattern" --include='*.kt' . || true; } | { grep -v "$THEME" || true; } | head -20
    failed=1
  elif (( n < baseline )); then
    echo "✓ $name: $n — below baseline $baseline; lower it in this script"
  else
    echo "✓ $name: $n"
  fi
done
exit $failed
