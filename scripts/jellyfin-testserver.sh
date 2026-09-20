#!/usr/bin/env bash
# テスト用の Jellyfin をコンテナで立てる（#97）。
#
#   scripts/jellyfin-testserver.sh up 1010     # Jellyfin 10.10.x を http://localhost:8097 に
#   scripts/jellyfin-testserver.sh up 12       # Jellyfin 12.x    を http://localhost:8098 に
#   scripts/jellyfin-testserver.sh down        # 両方止めて消す（メディアと設定のディレクトリは残る）
#   scripts/jellyfin-testserver.sh media       # 合成ライブラリだけ作り直す
#
# KIKIDAME_JF_LIBRARY=showcase を付けると、統合テスト用の日本語ライブラリ（番組 2 × 各回 3）の代わりに、
# スクリーンショット用の英語の架空ライブラリ（Example FM / Example Public Radio の 5 番組、各 6〜10 回）を作る（#95）。
#
# up は、ffmpeg で無音の m4a を作った合成ライブラリ（番組 2 × 各回 3）をマウントし、
# 初期セットアップ（言語・ユーザー・リモートアクセス・完了）と音楽ライブラリ「radio」の作成を REST で済ませ、
# スキャンが終わるまで待つ。終わると統合テスト用の環境変数を表示する:
#
#   KIKIDAME_JELLYFIN_URL=http://localhost:8097 KIKIDAME_JELLYFIN_USER=kikidame KIKIDAME_JELLYFIN_PASSWORD=kikidame-test \
#     ./gradlew :core:data:testDebugUnitTest --tests dev.tseki.kikidame.data.jellyfin.SdkJellyfinGatewayIntegrationTest
#
# 置き場は $KIKIDAME_JF_DIR（既定 /tmp/kikidame-jf）。docker と ffmpeg と curl と python3 が要る。
set -euo pipefail

DIR="${KIKIDAME_JF_DIR:-/tmp/kikidame-jf}"
MEDIA="$DIR/media"
USER_NAME="kikidame"
PASSWORD="kikidame-test"
AUTH_HEADER='Authorization: MediaBrowser Client="kikidame-testserver", Device="script", DeviceId="kikidame-testserver", Version="1"'

usage() { sed -n '2,20p' "$0"; exit 1; }

# 番組（album）・配信元（album_artist）・出演者（artist）・公開日（date）をタグに持つ無音の m4a を 1 本作る。
# 引数: 出力パス タイトル 番組 配信元 公開日 出演者（空なら付けない）
make_track() {
  local out="$1" title="$2" album="$3" publisher="$4" date="$5" artist="$6"
  mkdir -p "$(dirname "$out")"
  if [ -n "$artist" ]; then
    ffmpeg -loglevel error -y -f lavfi -i anullsrc=r=44100:cl=mono -t 12 -c:a aac -b:a 32k \
      -metadata "title=$title" -metadata "album=$album" -metadata "album_artist=$publisher" \
      -metadata "date=$date" -metadata "artist=$artist" "$out"
  else
    ffmpeg -loglevel error -y -f lavfi -i anullsrc=r=44100:cl=mono -t 12 -c:a aac -b:a 32k \
      -metadata "title=$title" -metadata "album=$album" -metadata "album_artist=$publisher" \
      -metadata "date=$date" "$out"
  fi
}

# スクリーンショット用（#95）: 英語の架空の配信元・番組。実在の局名・番組名は誤解を招くので使わない。
# 公開日は週次、タイトルは日付のものと "Episode N: …" のものを混ぜ、出演者は 1〜2 人。
make_showcase_media() {
  local publisher="Example FM"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-09-19.m4a" "2026-09-19" "Late Night Talk" "$publisher" "2026-09-19" "Sam Rivera;Jo Tanaka"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-09-12.m4a" "2026-09-12" "Late Night Talk" "$publisher" "2026-09-12" "Sam Rivera;Jo Tanaka"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-09-05.m4a" "2026-09-05" "Late Night Talk" "$publisher" "2026-09-05" "Sam Rivera"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-08-29.m4a" "2026-08-29" "Late Night Talk" "$publisher" "2026-08-29" "Sam Rivera;Jo Tanaka"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-08-22.m4a" "2026-08-22" "Late Night Talk" "$publisher" "2026-08-22" "Sam Rivera;Jo Tanaka"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-08-15.m4a" "2026-08-15" "Late Night Talk" "$publisher" "2026-08-15" "Sam Rivera"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-08-08.m4a" "2026-08-08" "Late Night Talk" "$publisher" "2026-08-08" "Sam Rivera;Jo Tanaka"
  make_track "$MEDIA/$publisher/Late Night Talk/2026-08-01.m4a" "2026-08-01" "Late Night Talk" "$publisher" "2026-08-01" "Sam Rivera;Jo Tanaka"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-18.m4a" "2026-09-18" "Morning Commute" "$publisher" "2026-09-18" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-17.m4a" "2026-09-17" "Morning Commute" "$publisher" "2026-09-17" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-16.m4a" "2026-09-16" "Morning Commute" "$publisher" "2026-09-16" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-15.m4a" "2026-09-15" "Morning Commute" "$publisher" "2026-09-15" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-14.m4a" "2026-09-14" "Morning Commute" "$publisher" "2026-09-14" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-11.m4a" "2026-09-11" "Morning Commute" "$publisher" "2026-09-11" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-10.m4a" "2026-09-10" "Morning Commute" "$publisher" "2026-09-10" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-09.m4a" "2026-09-09" "Morning Commute" "$publisher" "2026-09-09" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-08.m4a" "2026-09-08" "Morning Commute" "$publisher" "2026-09-08" "Alex Moreau"
  make_track "$MEDIA/$publisher/Morning Commute/2026-09-07.m4a" "2026-09-07" "Morning Commute" "$publisher" "2026-09-07" "Alex Moreau"
  publisher="Example Public Radio"
  make_track "$MEDIA/$publisher/Weekend Science Hour/Episode 24 - Why Do We Dream.m4a"        "Episode 24: Why Do We Dream?"        "Weekend Science Hour" "$publisher" "2026-09-13" "Dr. Priya Nair"
  make_track "$MEDIA/$publisher/Weekend Science Hour/Episode 23 - The Deep Sea.m4a"           "Episode 23: The Deep Sea"            "Weekend Science Hour" "$publisher" "2026-09-06" "Dr. Priya Nair;Chris Okafor"
  make_track "$MEDIA/$publisher/Weekend Science Hour/Episode 22 - Bees and Cities.m4a"        "Episode 22: Bees and Cities"         "Weekend Science Hour" "$publisher" "2026-08-30" "Dr. Priya Nair"
  make_track "$MEDIA/$publisher/Weekend Science Hour/Episode 21 - How Batteries Age.m4a"      "Episode 21: How Batteries Age"       "Weekend Science Hour" "$publisher" "2026-08-23" "Dr. Priya Nair;Chris Okafor"
  make_track "$MEDIA/$publisher/Weekend Science Hour/Episode 20 - Listener Questions.m4a"     "Episode 20: Listener Questions"      "Weekend Science Hour" "$publisher" "2026-08-16" "Dr. Priya Nair"
  make_track "$MEDIA/$publisher/Weekend Science Hour/Episode 19 - Volcanoes.m4a"              "Episode 19: Volcanoes"               "Weekend Science Hour" "$publisher" "2026-08-09" "Dr. Priya Nair;Chris Okafor"
  make_track "$MEDIA/$publisher/Book Club/2026-09-14.m4a" "2026-09-14" "Book Club" "$publisher" "2026-09-14" "Maria Santos"
  make_track "$MEDIA/$publisher/Book Club/2026-09-07.m4a" "2026-09-07" "Book Club" "$publisher" "2026-09-07" "Maria Santos;Tom Whitfield"
  make_track "$MEDIA/$publisher/Book Club/2026-08-31.m4a" "2026-08-31" "Book Club" "$publisher" "2026-08-31" "Maria Santos"
  make_track "$MEDIA/$publisher/Book Club/2026-08-24.m4a" "2026-08-24" "Book Club" "$publisher" "2026-08-24" "Maria Santos;Tom Whitfield"
  make_track "$MEDIA/$publisher/Book Club/2026-08-17.m4a" "2026-08-17" "Book Club" "$publisher" "2026-08-17" "Maria Santos"
  make_track "$MEDIA/$publisher/Book Club/2026-08-10.m4a" "2026-08-10" "Book Club" "$publisher" "2026-08-10" "Maria Santos;Tom Whitfield"
  make_track "$MEDIA/$publisher/Local History/Episode 8 - The Old Mill.m4a"     "Episode 8: The Old Mill"     "Local History" "$publisher" "2026-09-10" "Grace Lindqvist"
  make_track "$MEDIA/$publisher/Local History/Episode 7 - The 1904 Flood.m4a"   "Episode 7: The 1904 Flood"   "Local History" "$publisher" "2026-08-27" "Grace Lindqvist"
  make_track "$MEDIA/$publisher/Local History/Episode 6 - Market Street.m4a"    "Episode 6: Market Street"    "Local History" "$publisher" "2026-08-13" "Grace Lindqvist"
  make_track "$MEDIA/$publisher/Local History/Episode 5 - The Tram Years.m4a"   "Episode 5: The Tram Years"   "Local History" "$publisher" "2026-07-30" "Grace Lindqvist"
  make_track "$MEDIA/$publisher/Local History/Episode 4 - The Lighthouse.m4a"   "Episode 4: The Lighthouse"   "Local History" "$publisher" "2026-07-16" "Grace Lindqvist"
  make_track "$MEDIA/$publisher/Local History/Episode 3 - Founders.m4a"         "Episode 3: Founders"         "Local History" "$publisher" "2026-07-02" "Grace Lindqvist"
}

make_media() {
  rm -rf "$MEDIA"
  if [ "${KIKIDAME_JF_LIBRARY:-}" = "showcase" ]; then
    make_showcase_media
    echo "media: $MEDIA ($(find "$MEDIA" -name '*.m4a' | wc -l) tracks, showcase)"
    return
  fi
  # 番組 A: 同じ公開日に 2 本（"(1)" 付き）、タイトルが日付でない回、出演者が複数の回
  make_track "$MEDIA/ニッポン放送/テスト番組A/2026-09-14.m4a"     "2026-09-14"     "テスト番組A" "ニッポン放送" "2026-09-14" "出演者甲;出演者乙"
  make_track "$MEDIA/ニッポン放送/テスト番組A/2026-09-14 (1).m4a" "2026-09-14 (1)" "テスト番組A" "ニッポン放送" "2026-09-14" "出演者甲"
  make_track "$MEDIA/ニッポン放送/テスト番組A/夏休みスペシャル.m4a" "夏休みスペシャル" "テスト番組A" "ニッポン放送" "2026-08-01" "出演者甲"
  # 番組 B: 出演者が無い回を含む
  make_track "$MEDIA/TBSラジオ/テスト番組B/2026-09-07.m4a" "2026-09-07" "テスト番組B" "TBSラジオ" "2026-09-07" "出演者丙"
  make_track "$MEDIA/TBSラジオ/テスト番組B/2026-08-31.m4a" "2026-08-31" "テスト番組B" "TBSラジオ" "2026-08-31" ""
  make_track "$MEDIA/TBSラジオ/テスト番組B/2026-08-24.m4a" "2026-08-24" "テスト番組B" "TBSラジオ" "2026-08-24" "出演者丙"
  echo "media: $MEDIA ($(find "$MEDIA" -name '*.m4a' | wc -l) tracks)"
}

# 引数: 版（1010 | 12）。コンテナ名・ポート・イメージを決める
resolve() {
  case "$1" in
    1010) NAME=kikidame-jf-1010; PORT=8097; IMAGE=jellyfin/jellyfin:10.10.7 ;;
    12)   NAME=kikidame-jf-12;   PORT=8098; IMAGE=jellyfin/jellyfin:12.0 ;;
    *) echo "unknown version: $1 (1010 | 12)"; exit 1 ;;
  esac
  URL="http://localhost:$PORT"
  CONFIG="$DIR/config-$1"
}

wait_for() {
  local tries=$1; shift
  local i
  for i in $(seq 1 "$tries"); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "timed out: $*"; exit 1
}

post_json() { curl -sf --show-error -X POST -H 'Content-Type: application/json' -H "$AUTH_HEADER" "$@"; }

up() {
  resolve "$1"
  [ -d "$MEDIA" ] || make_media
  mkdir -p "$CONFIG"
  if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "container $NAME already exists; run 'down' first"; exit 1
  fi
  docker run -d --name "$NAME" -p "$PORT:8096" -v "$MEDIA:/media:ro" -v "$CONFIG:/config" "$IMAGE" >/dev/null
  echo "started $NAME ($IMAGE) on $URL"
  # 12 系は本体が起動するまで SetupServer が /System/Info/Public だけ 200 で返し、他は 503 なので、公開の /Users/Public で待つ
  wait_for 90 curl -sf "$URL/Users/Public"
  # 初期セットアップ（既に済んでいる config なら Startup API は 403 を返すので飛ばす）
  if curl -sf "$URL/Startup/Configuration" >/dev/null 2>&1; then
    post_json "$URL/Startup/Configuration" -d '{"UICulture":"ja","MetadataCountryCode":"JP","PreferredMetadataLanguage":"ja"}'
    curl -sf -H "$AUTH_HEADER" "$URL/Startup/User" >/dev/null
    post_json "$URL/Startup/User" -d "{\"Name\":\"$USER_NAME\",\"Password\":\"$PASSWORD\"}"
    post_json "$URL/Startup/RemoteAccess" -d '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}'
    post_json "$URL/Startup/Complete"
    echo "setup: user $USER_NAME created"
  fi
  local auth token user_id
  auth=$(post_json "$URL/Users/AuthenticateByName" -d "{\"Username\":\"$USER_NAME\",\"Pw\":\"$PASSWORD\"}")
  token=$(printf '%s' "$auth" | python3 -c 'import json,sys; print(json.load(sys.stdin)["AccessToken"])')
  user_id=$(printf '%s' "$auth" | python3 -c 'import json,sys; print(json.load(sys.stdin)["User"]["Id"])')
  local token_header="$AUTH_HEADER, Token=\"$token\""
  # 音楽ライブラリ radio（インターネットのメタデータ取得は全部切る: タグの値だけで比べたい）
  if ! curl -sf -H "$token_header" "$URL/Library/VirtualFolders" | grep -q '"Name":"radio"'; then
    curl -sf -X POST -H 'Content-Type: application/json' -H "$token_header" \
      "$URL/Library/VirtualFolders?name=radio&collectionType=music&refreshLibrary=true" \
      -d '{"LibraryOptions":{"PathInfos":[{"Path":"/media"}],"EnableEmbeddedTitles":true,"SaveLocalMetadata":false,"MetadataSavers":[],"TypeOptions":[{"Type":"MusicAlbum","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"MusicArtist","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"Audio","MetadataFetchers":[],"ImageFetchers":[]}]}}'
    echo "library: radio created, scanning"
  fi
  local expected count scanned=0
  expected=$(find "$MEDIA" -name '*.m4a' | wc -l)
  for _ in $(seq 1 60); do
    count=$(curl -sf -H "$token_header" "$URL/Items?userId=$user_id&IncludeItemTypes=Audio&Recursive=true&Limit=0" | python3 -c 'import json,sys; print(json.load(sys.stdin)["TotalRecordCount"])' 2>/dev/null || echo 0)
    if [ "$count" -ge "$expected" ]; then scanned=1; break; fi
    sleep 2
  done
  if [ "$scanned" -ne 1 ]; then echo "timed out: scan ($count / $expected tracks)"; exit 1; fi
  echo "scan: $count / $expected tracks"
  echo
  echo "KIKIDAME_JELLYFIN_URL=$URL KIKIDAME_JELLYFIN_USER=$USER_NAME KIKIDAME_JELLYFIN_PASSWORD=$PASSWORD"
}

down() {
  local n
  for n in kikidame-jf-1010 kikidame-jf-12; do
    if docker ps -a --format '{{.Names}}' | grep -qx "$n"; then
      docker rm -f "$n" >/dev/null && echo "removed $n"
    fi
  done
}

case "${1:-}" in
  up) [ -n "${2:-}" ] || usage; up "$2" ;;
  down) down ;;
  media) make_media ;;
  *) usage ;;
esac
