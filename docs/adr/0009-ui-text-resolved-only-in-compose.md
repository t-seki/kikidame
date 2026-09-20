---
status: accepted
date: 2026-09-20
---
UI の文言は `res/values*/strings.xml` に置き、**文字列に解決するのは Compose だけ**にする（#104）。Composable の外で文言を作る場所（`LibraryRefresher` のスナックバー、`NowPlaying.messages`、ViewModel の `uiState.error`、`EpisodeDetails` の行ラベル）は `String` ではなく `UiText`（`Res(@StringRes id, args)` / `Plural(@PluralsRes id, quantity, args)` / `Plain(text)` / `Joined(parts, separator)` の sealed interface）を返し、表示する Composable が `resolve()` する。`resolve(Context)` は `ui/` の Composable から呼ぶ非 Composable なラムダ（`LaunchedEffect` でスナックバーに渡すとき）のためのもので、`ui/` の外では使わない。`Context.getString` は `ui/` の外に書かない。例外は `playback/PlaybackService.kt` だけで、通知のボタン名と Android Auto のブラウズツリー（#96）はプロセスの外（システム UI・車載機）へ `String` を渡すしかないので、Service が `getString` する。

- 代替は `@ApplicationContext Context` を ViewModel・`LibraryRefresher`・`PlaybackService` に注入して `getString` した `String` を流すこと。既存の `Flow<String>` の型を変えずに済むが、(1) テストが文言の比較になり Robolectric が要る（`UiText` なら `Res(R.string.x, 12)` の比較で「どのメッセージがどの引数で選ばれたか」を検証できてプレーン JVM で済む。並び・省略の規則を見るテスト — 行の補足 2 つと各回の詳細 — だけは Robolectric の `@Config(qualifiers = "ja")` で日本語に解決した文字列で比べる）、(2) 保持されている `uiState.error` が言語切替に追従しない、(3) プロセス内で Service・Worker・UI が共有する `NowPlaying`（ADR 0006）に Android の `Context` が入る、の 3 点で見送った。作る側 5 つ・出す側 5 箇所で経路は小さい
- 既定ロケールは英語（`values/` = en、`values-ja/` = ja）。日英以外の端末には既定が出るので、IzzyOnDroid / F-Droid の英語の説明文を読んで入れた人に日本語を出さない。文字列を足す PR は両方に足し、`MissingTranslation` を lint のエラーにして片方だけを防ぐ。`values/strings.xml` には、英語だけでは文脈が分からないキー（書式引数の意味、どの画面のどこか、CONTEXT.md の用語）に翻訳者向けのコメントを付け、書式引数は `%1$s` のように番号付きにする
- 言語の切替は OS の「アプリの言語」（Android 13+、`generateLocaleConfig = true`）だけ。アプリ内の切替は AppCompat を足すことになるので置かない。Android 12 はシステム言語に従う
- 翻訳の受け入れ体制（Weblate 等）は需要が見えてから。`values-<lang>/strings.xml` が唯一の入口である形だけ守る
- 日付・時刻・尺・容量の書式（`2026-08-02`、`08-02`、`1:02:03`、`12.3 MB`）はロケールに依らず固定。資源化するのは日本語の句読点と単位（「、」区切り、「（123 回）」）だけで、数+単位は `<plurals>` にする
