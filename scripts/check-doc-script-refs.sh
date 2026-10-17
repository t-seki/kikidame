#!/usr/bin/env bash
# docs/ と README の Markdown で、インラインコードに書かれた `*.sh` のファイル名が、repo にもリンクにも無いものを落とす（#328）。
# 落とすもの: インラインコード（`…`）の中の空白で区切った語のうち、`.sh` で終わり、次の 2 つを両方満たすもの
#  1. repo 直下からの相対パス、またはその Markdown のあるフォルダからの相対パスで、ファイルが無い
#  2. リンク（`[…](…)`）の中に無い（リンクの文字列・URL に含まれるものは見ない）
#    （`~/…`・`$HOME/…`・glob（`scripts/*.sh`）のようなパスの形でない語も、ファイルが無いとして落とす）
# 見ないもの: コードブロック（```）の中、`.sh` 以外のファイル。実装の限界として次も見ない・除かない
#  - フェンスは行頭（前の空白は可）の ``` だけで切り替える。`~~~` のフェンスは見ない
#  - インラインコードは一重のバッククォートの span だけを見る。二重バッククォートの span は見ない
#  - リンクは `[…](…)` の形だけを除く。参照式のリンクは除かない
# 直し方: repo の中のスクリプトは `scripts/xxx.sh` のようにパス付きで書く。ほかの repo のものは https://… のリンクにする。
# 使い方: scripts/check-doc-script-refs.sh [Markdown のファイル…]（repo 直下で。既定は docs/**/*.md と README*.md）
# 落ちる例と通る例のテストは scripts/test-check-doc-script-refs.sh
set -euo pipefail
if [ "$#" -gt 0 ]; then
  files=("$@")
else
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(find docs -name '*.md' | sort; ls README*.md 2>/dev/null)
fi
status=0
for f in "${files[@]}"; do
  dir=$(dirname "$f")
  # 出力: 行番号<TAB>語（コードブロックとリンクを除いた行のインラインコードから）
  refs=$(awk '
    /^[[:space:]]*```/ { fence = !fence; next }
    fence { next }
    {
      line = $0
      gsub(/\[[^]]*\]\([^)]*\)/, "", line)
      while (match(line, /`[^`]+`/)) {
        span = substr(line, RSTART + 1, RLENGTH - 2)
        line = substr(line, RSTART + RLENGTH)
        n = split(span, w, /[[:space:]]+/)
        for (i = 1; i <= n; i++) if (w[i] ~ /\.sh$/) printf "%d\t%s\n", NR, w[i]
      }
    }
  ' "$f")
  [ -z "$refs" ] && continue
  while IFS=$'\t' read -r no ref; do
    if [ -e "$ref" ] || [ -e "$dir/$ref" ]; then continue; fi
    echo "::error file=$f,line=$no::\`$ref\` が repo に無く、リンクにもなっていない（repo の中なら scripts/xxx.sh のようにパス付きで、ほかの repo なら https://… のリンクで書く）"
    status=1
  done <<< "$refs"
done
exit "$status"
