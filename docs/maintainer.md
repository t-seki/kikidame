# メンテナの手元の環境

作者（メンテナ）の手元の環境と運用の記録。署名鍵の置き場、テスト用の音声の置き場、複数の PC で作業するときの注意をまとめる。
ここに書いたパスと機器は作者のもので、コントリビューターは読まなくてよい。ビルド・テスト・リリースの手順そのものは [development.md](./development.md)、実機の手順は [device-testing.md](./device-testing.md) にあり、手元の環境によらない書き方をしている。

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

- マシンごとに初回は `adb pair` が要る（ペア設定は端末とマシンの組ごと）。`adb mdns services` で端末が見つからない場合がある。そのときは、端末のワイヤレスデバッグ画面に出ている IP:ポートを見て、`adb connect` する
- 別の PC でビルドした debug ビルドが端末に入っていると、この PC のビルドの上書きインストールが `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で失敗する。debug の APK は PC ごとの `~/.android/debug.keystore` で署名されるため。`uninstall` すれば入れ直せるが、debug ビルドのデータは消える
