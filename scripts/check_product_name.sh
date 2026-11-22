#!/usr/bin/env bash
#
# The product name is `Konstruct`, in Latin, undeclined, in every locale — `в Konstruct`,
# `Konstruct-ը`, `Konstructの`. Decided by the owner 2026-10-20
# (~/Code/construct-docs/decisions/the-product-name-is-latin-everywhere.md, vault todo 141).
#
# Why a check and not a note: #162 put every string in Latin, and four weeks later the share
# screen (#230) came in with «Поделиться в Конструкт» — nothing read the rule. Transliterations
# belong only in store keywords, which are not app resources.
#
set -euo pipefail

cd "$(dirname "$0")/.."

c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }

# ru (any case ending), hy, ja, ko. «Конструктор» is an ordinary word and is let through.
PATTERN='Конструкт|Կոնստրուկտ|コンストラクト|콘스트럭트'

hits=$(grep -rnE "$PATTERN" app/src/main/res --include='*.xml' | grep -v 'Конструктор' || true)

if [ -z "$hits" ]; then
    c_grn "the product name is Konstruct in every locale"
else
    c_red "the product name is transliterated:"
    printf '%s\n' "$hits" | sed 's/^/  ✗ /'
    echo
    c_red "Write Konstruct, in Latin and undeclined (decisions/the-product-name-is-latin-everywhere.md)."
    exit 1
fi
