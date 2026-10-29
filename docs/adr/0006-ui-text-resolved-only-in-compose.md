---
date: 2026-09-20
---
UI の文言は `res/values*/strings.xml` に置き、**文字列に解決するのは Compose だけ**にする。Composable の外で文言を作る場所（`LibraryRefresher` のスナックバー、`NowPlaying.messages`、ViewModel の `uiState.error`、`EpisodeDetails` の行のラベル）は `String` ではなく `UiText`（`Res(@StringRes id, args)` / `Plural(@PluralsRes id, quantity, args)` / `Plain(text)` / `Joined(parts, separator)` の sealed interface）を返し、表示する Composable が `resolve()` する。`resolve(Context)` は `ui/` の Composable から呼ぶ Composable でないラムダ（`LaunchedEffect` でスナックバーに渡すとき）のためのもので、`ui/` の外では使わない。`Context.getString` は `ui/` の外に書かない。

例外は、プロセスの外（システムの UI・車載機）へ `String` を渡すしかない次の 2 か所で、そこでは `getString` する。

- `playback/PlaybackService.kt`: 通知のボタン名と Android Auto のブラウズツリー
- `sync/SyncFailureNotifier.kt` の `AndroidSyncFailureNotifier`: 同期の失敗の通知。通知を組み立てる 1 か所で `getString` し、そこまでは `UiText` で持つ（`LibraryRefresher` と `SyncWorker` は失敗の理由を `UiText` で渡す）

## 退けた案

- **`@ApplicationContext Context` を ViewModel・`LibraryRefresher`・`PlaybackService` に注入して、`getString` した `String` を流す** — `Flow<String>` の型を変えずに済むが、下の「理由」の 3 点で見送った

## 理由

上の退けた案と比べて、`UiText` を返す形を採った理由。

1. テストが文言の比較になり Robolectric が要る。`UiText` なら `Res(R.string.x, 12)` の比較で「どのメッセージがどの引数で選ばれたか」を確かめられ、素の JVM で済む。並び・省略の規則を見るテスト（行の補足 2 つと各回の詳細）だけは、Robolectric の `@Config(qualifiers = "ja")` で日本語に解決した文字列で比べる
2. 保持されている `uiState.error` が言語の切り替えに追従しない
3. プロセスの中で Service・Worker・UI が共有する `NowPlaying`（ADR 0004）に Android の `Context` が入る

## 型の置き場

- 型の `UiText`（Compose に依存しない）は、`ui/` の外（`sync/`・`playback/`）も使うので `dev.tseki.kikidame.text` にある。`resolve()` は Compose と `Context` に依存するので `ui/UiTextResolve.kt` にある。解決するのは `ui/` だけ、という決定は型の置き場によらない

## 言語

- 既定のロケールは英語（`values/` = en、`values-ja/` = ja）。日英以外の端末には既定が出るので、英語の説明文を読んで入れた人に日本語を出さない。文字列を足すときは両方に足し、`MissingTranslation` を lint のエラーにして片方だけを防ぐ。`values/strings.xml` には、英語だけでは文脈が分からないキー（書式の引数の意味、どの画面のどこか、CONTEXT.md の用語）に翻訳者向けのコメントを付け、書式の引数は `%1$s` のように番号付きにする
- 言語の切り替えは OS の「アプリの言語」（Android 13+、`generateLocaleConfig = true`）だけ。アプリの中の切り替えは AppCompat を足すことになるので置かない。Android 12 はシステムの言語に従う
- 翻訳の受け入れの仕組み（Weblate など）は、需要が見えてから用意する。`values-<lang>/strings.xml` が唯一の入口である形だけを守る
- 日付・時刻・尺・容量の書式（`2026-08-02`、`08-02`、`1:02:03`、`12.3 MB`）はロケールによらず固定。資源にするのは日本語の句読点と単位（「、」区切り、「（123 回）」）だけで、数 + 単位は `<plurals>` にする
