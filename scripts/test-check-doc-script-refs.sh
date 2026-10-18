#!/usr/bin/env bash
# scripts/check-doc-script-refs.sh の落ちる例と通る例（#328）。使い方: scripts/test-check-doc-script-refs.sh（repo 直下で）
set -euo pipefail
root=$PWD
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0
# expect <通る|落ちる> <名前> <Markdown の中身>。作業フォルダに scripts/exists.sh と docs/sibling.sh を用意して流す
expect() {
  rm -rf "$tmp/w"
  mkdir -p "$tmp/w/scripts" "$tmp/w/docs"
  : > "$tmp/w/scripts/exists.sh"
  : > "$tmp/w/docs/sibling.sh"
  printf '%s\n' "$3" > "$tmp/w/docs/a.md"
  if (cd "$tmp/w" && "$root/scripts/check-doc-script-refs.sh" docs/a.md) > "$tmp/out" 2>&1; then got=通る; else got=落ちる; fi
  if [ "$got" != "$1" ]; then
    echo "NG: $2（期待 $1、実際 $got）"
    cat "$tmp/out"
    fail=1
  else
    echo "ok: $2"
  fi
}
expect 通る "repo にあるスクリプトのパス" '`scripts/exists.sh` を流す'
expect 通る "Markdown と同じフォルダのスクリプト" '`sibling.sh` を流す'
expect 通る "リンクの文字列の中のインラインコード" '[`worker.sh`](https://example.com/worker.sh) を流す'
expect 通る "リンクの URL の中" '[worker](https://example.com/`worker.sh`) を流す'
expect 通る "コードブロックの中" "$(printf '```bash\n`nowhere.sh`\n```')"
expect 通る "インラインコードでない .sh" 'nowhere.sh を流す'
expect 通る ".sh 以外のファイル" '`nowhere.kts` を見る'
expect 通る "コマンド形で、スクリプトが repo にある" '`bash scripts/exists.sh --flag` を流す'
expect 落ちる "repo に無いスクリプトの名前だけ" '`worker-pr-status.sh` を流す'
expect 落ちる "repo に無いパス" '`scripts/nowhere.sh` を流す'
expect 落ちる "コマンド形で、スクリプトが repo に無い" '`bash nowhere.sh --flag` を流す'
expect 落ちる "リンクの外にある 2 つ目" '[`worker.sh`](https://example.com/worker.sh) と `other.sh`'
expect 落ちる "コードブロックの後" "$(printf '```bash\nx\n```\n`nowhere.sh`')"
exit "$fail"
