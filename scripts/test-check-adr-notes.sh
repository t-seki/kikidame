#!/usr/bin/env bash
# scripts/check-adr-notes.sh の落ちる例と通る例（#324）。使い方: scripts/test-check-adr-notes.sh（repo 直下で）
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0
# expect <通る|落ちる> <名前> <ファイルの中身>
expect() {
  rm -rf "$tmp/adr"
  mkdir "$tmp/adr"
  printf '%s\n' "$3" > "$tmp/adr/0001-x.md"
  if scripts/check-adr-notes.sh "$tmp/adr" > "$tmp/out" 2>&1; then got=通る; else got=落ちる; fi
  if [ "$got" != "$1" ]; then
    echo "NG: $2（期待 $1、実際 $got）"
    cat "$tmp/out"
    fail=1
  else
    echo "ok: $2"
  fi
}
expect 通る "箇条書きの項目（句点なし、途中に句点）" "$(printf '本文。\n\n- 2026-10-10 #1: 一つ目。二つ目')"
expect 通る "改名の注記の段落" "$(printf '本文\n\n改名の注記（#239）: 上の `X` は `Y` に改めた。')"
expect 通る "移動の注記の段落" "$(printf '本文\n\n移動の注記（#305）: 移した。')"
expect 通る "行頭が - の行は段落ではない" "$(printf -- '- 決定の注記（#1）: 本文の箇条書き')"
expect 落ちる "決定の注記を段落で書く" "$(printf '本文\n\n決定 5 を変えない注記（#313、2026-10-17 の grill）: 変えない。')"
expect 落ちる "項目の末尾が句点" "$(printf -- '- 2026-10-10 #1: 句点で終わる。')"
expect 落ちる "入れ子の子項目の末尾が句点" "$(printf -- '- 2026-10-10 #1: 親\n  - 子。')"
expect 落ちる "複数行の項目の最後の行が句点" "$(printf -- '- 2026-10-10 #1: 親\n  続き。')"
expect 通る "複数行の項目の最後の行が句点でない" "$(printf -- '- 2026-10-10 #1: 親。\n  続き')"
exit "$fail"
