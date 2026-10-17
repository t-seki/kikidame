#!/usr/bin/env bash
# docs/adr/*.md の末尾の注記の形を確かめる（#324）。決まりは docs/development.md の ADR の注記の節。
# 落とすもの:
#  1. 行頭が `- ` や空白でなく、行頭から「…注記（#N…）:」（コロンは半角・全角）と続く段落（行の途中に現れる本文の行は除く）のうち、改名の注記・移動の注記以外
#     （決定や事実の注記は `- <日付> <出どころ>: …` の箇条書きにする）
#  2. `- YYYY-MM-DD ` で始まる箇条書きの項目の末尾が句点（。）で終わるもの
#     （項目は、次の空行・行頭が空白でない行・次の日付の項目までで、入れ子の子項目の行を含む。項目の最後の行を見る）
# 使い方: scripts/check-adr-notes.sh [ADR のディレクトリ]（repo 直下で。既定は docs/adr）
# 落ちる例と通る例のテストは scripts/test-check-adr-notes.sh
set -euo pipefail
dir=${1:-docs/adr}
status=0
for f in "$dir"/*.md; do
  out=$(awk -v file="$f" '
    function flush() {
      if (item_last != "" && item_last ~ /。[[:space:]]*$/) {
        printf "::error file=%s,line=%d::末尾の注記の項目の末尾が句点で終わっている（句点を付けない）\n", file, item_line
        bad = 1
      }
      item_last = ""
    }
    # 「〜注記（#N…）:」で始まる段落か。mawk はバイト単位なので、ブラケット式に多バイト文字を入れず、
    # index と literal の選択だけで判定する（awk の種類によらず同じ結果にする）
    function is_note_paragraph(l,   p, prefix, rest, q) {
      if (l ~ /^[-[:space:]]/) return 0
      p = index(l, "注記（#")
      if (p <= 1) return 0
      prefix = substr(l, 1, p - 1)
      if (index(prefix, "（") || index(prefix, ":") || index(prefix, "：")) return 0
      rest = substr(l, p + length("注記（#"))
      if (rest !~ /^[0-9]/) return 0
      q = index(rest, "）")
      if (q == 0 || index(substr(rest, 1, q - 1), "（")) return 0
      return (substr(rest, q) ~ /^）(:|：)/)
    }
    {
      line = $0
      if (line ~ /^[[:space:]]*- [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] /) {
        flush(); in_item = 1; item_last = line; item_line = NR; next
      }
      if (in_item && line ~ /^[[:space:]]+[^[:space:]]/) { item_last = line; item_line = NR; next }
      flush(); in_item = 0
      if (line !~ /^(改名|移動)の注記（/ && is_note_paragraph(line)) {
        printf "::error file=%s,line=%d::決定や事実の注記を段落で書いている（`- <日付> <出どころ>: …` の箇条書きにする。段落は改名・移動の注記だけ）\n", file, NR
        bad = 1
      }
    }
    END { flush(); exit bad }
  ' "$f") || status=1
  [ -z "$out" ] || echo "$out"
done
exit "$status"
