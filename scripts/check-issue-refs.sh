#!/usr/bin/env bash
# repo が追跡している Markdown の全部（git ls-files '*.md'）に、自 repo の issue・PR の番号（`#` と 1〜5 桁の数字）があると落とす。
# 他の repo の参照（owner/repo の後ろの番号）や URL のアンカーは通す。
# 番号は書かず、理由は本文に書く（決まりは docs/development.md の「issue・PR の番号」の項）。コード（.kt など）は見ない（色の 16 進と当たるため）。
# 使い方: scripts/check-issue-refs.sh [ファイル…]（repo 直下で。引数が無ければ git ls-files '*.md' の全部）
# 落ちる例と通る例のテストは scripts/test-check-issue-refs.sh
set -euo pipefail
if [ "$#" -gt 0 ]; then
  files=("$@")
else
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(git ls-files '*.md')
fi
status=0
for f in "${files[@]}"; do
  [ -f "$f" ] || continue
  out=$(awk -v file="$f" '
    # 区間 {m,n} は mawk の版によって効かないので使わない
    /(^|[^A-Za-z0-9_.\/&-])#[0-9][0-9]?[0-9]?[0-9]?[0-9]?([^0-9A-Fa-f]|$)/ {
      printf "::error file=%s,line=%d::自 repo の issue・PR の番号を書かない（理由は本文に書く）\n", file, NR
      bad = 1
    }
    END { exit bad }
  ' "$f") || status=1
  [ -z "$out" ] || echo "$out"
done
exit "$status"
