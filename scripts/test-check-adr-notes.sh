#!/usr/bin/env bash
# scripts/check-adr-notes.sh の落ちる例と通る例。使い方: scripts/test-check-adr-notes.sh（repo 直下で）
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0
# expect <通る|落ちる> <名前> <ファイルの中身>
expect() {
  rm -f "$tmp"/*.md
  printf '%s\n' "$3" > "$tmp/0001-x.md"
  printf '> 注記: x\n' > "$tmp/README.md"
  if scripts/check-adr-notes.sh "$tmp" > "$tmp/out" 2>&1; then got=通る; else got=落ちる; fi
  if [ "$got" != "$1" ]; then
    echo "NG: $2（期待 $1、実際 $got）"
    cat "$tmp/out"
    fail=1
  else
    echo "ok: $2"
  fi
}
fm=$'---\ndate: 2026-10-01\n---\n'
expect 通る "注記の無い ADR" "${fm}本文。ADR 0007 の境界に従う。"
expect 通る "本文中の status と注記の語" "${fm}status: は本文では見ない。注記という語は、> 注記 のように行頭でなければ通る"
expect 通る "引用ブロック" "${fm}> 引用の文"
expect 落ちる "行頭の注記" "${fm}本文
> 注記: 古い決定を残す"
expect 落ちる "行頭の注記（> の後ろに空白なし）" "${fm}>注記: x"
expect 落ちる "太字の注記" "${fm}> **注記**: x"
expect 落ちる "字下げした注記" "${fm}   > 注記: x"
expect 落ちる "frontmatter の status" $'---\ndate: 2026-10-01\nstatus: accepted\n---\n本文'
expect 落ちる "frontmatter の superseded" $'---\ndate: 2026-10-01\nsuperseded-by: 0015\n---\n本文'
expect 落ちる "ADR NNNN で改めた" "${fm}配布は Play だけ（ADR 0015 で改めた）。"
expect 落ちる "ADR NNNN で終えた" "${fm}- GitHub Releases の配布は ADR 0015 で終えた"
exit "$fail"
