#!/usr/bin/env bash
# docs/adr/*.md の各ファイル名（一覧の docs/adr/README.md 自身を除く）が docs/adr/README.md に出ているかを確かめる。
# 欠けた ADR のファイル名を出して落とす。使い方: scripts/check-adr-index.sh（repo 直下で）
set -euo pipefail
index=docs/adr/README.md
missing=0
for f in docs/adr/*.md; do
  name=$(basename "$f")
  [ "$name" = README.md ] && continue
  if ! grep -qF -- "$name" "$index"; then
    echo "::error::$index の ADR の一覧に $name が無い"
    missing=1
  fi
done
exit "$missing"
