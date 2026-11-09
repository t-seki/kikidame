#!/usr/bin/env bash
# docs/adr/*.md（README.md を除く）に、注記の形の行があると落とす。ADR は今の決定だけを書き、経緯は git の履歴に任せる（docs/development.md の「docs と ADR の決まり」）。
# 見る形（行ごと）:
#   - 引用の「> 注記」（字下げ・太字の「> **注記**」も）
#   - frontmatter（先頭の --- と --- の間）の status: と superseded を含むキー
#   - 「ADR NNNN で改めた」「ADR NNNN で終えた」の経緯の書き方
# 使い方: scripts/check-adr-notes.sh [ADR のフォルダ]（repo 直下で。省略時は docs/adr）
set -euo pipefail
dir=${1:-docs/adr}
bad=0
for f in "$dir"/*.md; do
  [ "$(basename "$f")" = README.md ] && continue
  hits=$(awk '
    FNR == 1 && $0 == "---" { fm = 1; next }
    fm && $0 == "---" { fm = 0; next }
    fm && /^(status|[A-Za-z_-]*superseded[A-Za-z_-]*)[ \t]*:/ { print FNR ": frontmatter の " $0; next }
    /^[ \t]*>[ \t]*[*_]*注記/ { print FNR ": " $0; next }
    /ADR [0-9]+ で(改め|終え)た/ { print FNR ": " $0 }
  ' "$f")
  if [ -n "$hits" ]; then
    while IFS= read -r line; do
      echo "::error file=$f::注記の形の行がある（本文を今の決定に書き換え、経緯は git の履歴に任せる）: $line"
    done <<< "$hits"
    bad=1
  fi
done
exit "$bad"
