#!/usr/bin/env bash
# scripts/check-issue-refs.sh の落ちる例と通る例。使い方: scripts/test-check-issue-refs.sh（repo 直下で）
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0
# expect <通る|落ちる> <名前> <ファイルの中身>
expect() {
  printf '%s\n' "$3" > "$tmp/x.md"
  if scripts/check-issue-refs.sh "$tmp/x.md" > "$tmp/out" 2>&1; then got=通る; else got=落ちる; fi
  if [ "$got" != "$1" ]; then
    echo "NG: $2（期待 $1、実際 $got）"
    cat "$tmp/out"
    fail=1
  else
    echo "ok: $2"
  fi
}
hash='#'
expect 通る "番号の無い本文" "本文。見出しの # や #N の書き方"
expect 通る "他の repo の参照" "$(printf '本文 robolectric/robolectric%s11434 を参照' "$hash")"
expect 通る "URL のアンカー" "$(printf '[x](https://example.com/page%sfoo12) と https://example.com/a%s12' "$hash" "$hash")"
expect 通る "色の 16 進" "$(printf '色 %s1A2B3C' "$hash")"
expect 落ちる "本文に自 repo の番号" "$(printf '本文 %s12 を参照' "$hash")"
expect 落ちる "行頭の番号" "$(printf '%s123 で直した' "$hash")"
expect 落ちる "箇条書きの中の番号" "$(printf -- '- 2026-10-10: %s12 で改めた' "$hash")"
expect 落ちる "括弧の中の番号" "$(printf '本文（%s5）' "$hash")"
exit "$fail"
