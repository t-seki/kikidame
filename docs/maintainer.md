# メンテナの手元の環境

作者（メンテナ）の手元の環境と運用の記録。署名鍵の置き場、テスト用の音声の置き場、複数の PC で作業するときの注意をまとめる。
ここに書いたパスと機器は作者のもので、コントリビューターは読まなくてよい。ビルド・テスト・リリースの手順そのものは [development.md](./development.md)、実機の手順は [device-testing.md](./device-testing.md) にあり、手元の環境によらない書き方をしている（複数の PC で作業するときに当たる `adb pair` と debug 署名の違いの説明も device-testing.md にある）。

## 署名鍵の置き場

[development.md](./development.md) の「リリース」節の「署名鍵」に出てくる `<keystore のパス>` は、作者の手元では次の場所にある。

- upload 鍵は `~/.android-keys/kikidame-upload.jks`（alias `kikidame`）。repo の外に置く
- 環境変数の `export`（`KIKIDAME_KEYSTORE` など）は `~/.android-keys/kikidame.env`（chmod 600、git 管理外）に書き、`source` して使う。値を表示するコマンド（`cat` / `grep`）は打たない。`grep -c` でも一致行を出す環境があるので、存在確認は `test -r` で行う

  ```bash
  source ~/.android-keys/kikidame.env
  ```

- 鍵の控え（keystore ファイルとパスフレーズ）は、パスワードマネージャとオフラインの 2 箇所に置く

## release ビルドを手元のビルドで上書きする

普段使いの release ビルド（`dev.tseki.kikidame`）を、データを残したまま手元のビルドで更新するときは、release 署名で上書きする。手順は [device-testing.md](./device-testing.md) の「落とし穴」の最後の項に書かれている。作者の手元では、その手順の `source <env ファイル>` を、上の `kikidame.env` にする。

## テスト用の音声の置き場

[device-testing.md](./device-testing.md) の「端末のフォルダ」の準備で使う、タグの付いた m4a と、タグの無い mp3 は、作者の手元では `~/kikidame-test-audio` に置いている（`tagged.m4a` と `untagged.mp3`）。

```bash
SRC=~/kikidame-test-audio
```

## 複数の PC で作業するとき

作者は 1 台の端末を複数の PC から使う。そのため PC を替えるたびに、[device-testing.md](./device-testing.md) の「接続」にある `adb pair` のやり直しと、「別の PC でビルドした debug ビルドが入っているとき」の `uninstall` が要る。説明は device-testing.md にあり、ここには書かない。
