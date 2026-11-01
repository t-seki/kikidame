# Contributing

Thanks for your interest in Kikidame. 日本語の要約は末尾にあります。

## What is accepted

- **Issues**: bug reports only. Please use the issue template. Writing in English or Japanese is fine.
- **Discussions**: feature requests (Ideas category) and questions (Q&A category). Before asking for a feature, read the Non-goals section of [README.md](./README.md). The maintainer opens an issue for what is decided to be done.
- **Pull requests for translations only.**

Pull requests that change code are not accepted. The maintainer designs and reviews every change in the app, and cannot take on reviewing outside code. Such a pull request may be closed without a detailed review. If you have an idea for a code change, post it in the Ideas category of Discussions instead.

## Translations

- UI text lives in `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-<language>/strings.xml` (for example `values-ja` for Japanese). Keep the keys the same as in the English `values` file.
- Store descriptions live in `fastlane/metadata/android/<language>/` (for example `ja-JP`).

## Generative AI

Most of the app's code is written with Claude Code (see [docs/adr/0005-open-source-distributed-outside-play.md](./docs/adr/0005-open-source-distributed-outside-play.md)). For translation pull requests, machine translation and generative AI are fine, but only if someone who reads that language has checked the result.

## Documentation

The developer docs are written in Japanese only. The entry point is [docs/README.md](./docs/README.md).

## License

Contributions are accepted under the Mozilla Public License 2.0 (see [LICENSE](./LICENSE)).

## 日本語の要約

- 受け付けるのは、issue（不具合の報告だけ）、Discussions（要望は Ideas、質問は Q&A）、翻訳の PR です。issue はテンプレートに沿って、日本語でも英語でも書けます。やると決めた要望は、メンテナが issue に立てます。
- コードを変える PR は受け付けません。開いても、詳しく見ずに閉じることがあります。
- 翻訳は `app/src/main/res/values-<言語>/strings.xml`（例: 日本語は `values-ja`。キーは英語の `values` に合わせる）と `fastlane/metadata/android/<言語>/`（例: `ja-JP`）です。機械翻訳や生成 AI を使ってよいのは、その言語を読める人が確かめたものに限ります。
- アプリのコードの大半は Claude Code で書いています。
- 開発者向けの docs は日本語だけです。入口は [docs/README.md](./docs/README.md) です。
- 貢献は MPL-2.0（[LICENSE](./LICENSE)）で受けます。
