#!/usr/bin/env bash
# docs/adr/*.md の各ファイル名が docs/architecture.md に出ているかを確かめる。
# 欠けた ADR のファイル名を出して落とす。使い方: scripts/check-adr-index.sh（repo 直下で）
set -euo pipefail
architecture=docs/architecture.md
missing=0
for f in docs/adr/*.md; do
  name=$(basename "$f")
  if ! grep -qF -- "$name" "$architecture"; then
    echo "::error::$architecture の「参照」の ADR の一覧に $name が無い"
    missing=1
  fi
done
exit "$missing"
