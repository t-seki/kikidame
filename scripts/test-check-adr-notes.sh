#!/usr/bin/env bash
# scripts/check-adr-notes.sh の落ちる例と通る例。使い方: scripts/test-check-adr-notes.sh（repo 直下で）
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
hash='#'
expect 通る "箇条書きの項目（句点なし、途中に句点）" "$(printf '本文。\n\n- 2026-10-10: 一つ目。二つ目')"
expect 通る "新しい ADR の注記の項目" "$(printf '本文\n\n- 2026-10-10 ADR 0007: 改めた')"
expect 通る "改名の注記の段落" "$(printf '本文\n\n改名の注記（2026-10-10）: 上の `X` は `Y` に改めた。')"
expect 通る "移動の注記の段落" "$(printf '本文\n\n移動の注記（2026-10-10）: 移した。')"
expect 通る "行頭が - の行は段落ではない" "$(printf -- '- 決定の注記（2026-10-10）: 本文の箇条書き')"
expect 通る "注記（日付）の後に（が続く本文の行（コロンではない）" "$(printf '決定の注記（2026-10-10）（補足）本文')"
expect 通る "行の途中に注記（と ）: がある本文の行" "$(printf '本文の途中で 注記（x と書き、別の括弧（x）: と続ける')"
expect 通る "他の repo の参照" "$(printf '本文 t-seki/rajikura%s68 を参照' "$hash")"
expect 落ちる "全角コロンの決定の注記の段落" "$(printf '決定の注記（2026-10-10）：本文')"
expect 落ちる "決定の注記を段落で書く" "$(printf '本文\n\n決定の注記（2026-10-10）: 変えない。')"
expect 落ちる "項目の末尾が句点" "$(printf -- '- 2026-10-10: 句点で終わる。')"
expect 落ちる "日付の後が空白の項目の末尾が句点" "$(printf -- '- 2026-10-10 ADR 0007: 句点で終わる。')"
expect 落ちる "日付の後が全角コロンの項目の末尾が句点" "$(printf -- '- 2026-10-10：句点で終わる。')"
expect 通る "日付の後が全角コロンの項目の末尾が句点でない" "$(printf -- '- 2026-10-10：一つ目。二つ目')"
expect 落ちる "入れ子の子項目の末尾が句点" "$(printf -- '- 2026-10-10: 親\n  - 子。')"
expect 落ちる "複数行の項目の最後の行が句点" "$(printf -- '- 2026-10-10: 親\n  続き。')"
expect 通る "複数行の項目の最後の行が句点でない" "$(printf -- '- 2026-10-10: 親。\n  続き')"
expect 落ちる "本文に自 repo の番号" "$(printf '本文 %s12 を参照' "$hash")"
expect 落ちる "注記に自 repo の番号" "$(printf '本文\n\n- 2026-10-10: %s12 で改めた' "$hash")"
exit "$fail"
