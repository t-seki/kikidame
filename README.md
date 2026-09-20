# Kikidame

日本語: [README.ja.md](./README.ja.md)

An Android app for **episodic audio** (radio recordings and podcasts) kept in a Jellyfin music library. It downloads and deletes episodes automatically by per-program rules, and keeps playing when the server is unreachable. Kikidame is an unofficial client with no affiliation to the Jellyfin project.

The name comes from the Japanese *kiki-dame* ("listen to what you have saved up"): record the episodes as they air, then listen to them on your phone when you have time.

## Why

Existing Jellyfin clients (the official app, Findroid, Finamp) are built around video or music. Two things are weak there: "keep episodes on the device, program by program, and remove them automatically" and "work fully even when the server is unreachable". Kikidame is built for exactly those two, for audio where episodes pile up.

- **Offline first** — playback always uses the files on the device. Playback position and played state live on the device only and are never sent to the server
- **Retention rules** — per program, choose "Keep latest N" and "Delete after played"; sync then downloads what is missing and removes what is no longer needed

It assumes one server, one user, one library, and that this app is the only place you listen. Sharing with other devices or the web player is not a goal.

## What it does

- **Program list** — sections split by the Starred mark, search by program or publisher name, filter by publisher. Each row shows whether sync is enabled for the program and whether it is no longer on the server (Gone)
- **Episode list** — download, pin, and mark played. Each row shows whether the episode is on the device, downloading, or failed
- **Sync** — per program, choose whether sync is enabled for it and its retention rule. WorkManager syncs every 6 hours, with a "Wi-Fi only" option. Episodes you download by hand are Pinned and never removed by the rules
- **Player** — background playback with notification and lock-screen controls, skip 10 seconds back and forward, speed 1.0x to 2.0x, sleep timer (by time or until the end of the episode). The playback position is saved so the next play resumes where you left off; listening near the end marks the episode Played, and finishing it advances to the next episode of the same program. After the app process has ended, the ▶ on a Bluetooth headset or Android Auto resumes the last episode from where it stopped
- **Android Auto** — browse Continue / Starred / Programs on the car screen and play. Only episodes on the device are shown; playback position and played state follow the same rules as on the phone (Auto runs on the phone, so nothing needs syncing)
- **Mini player** and three themes (system / dark / light)
- **English and Japanese UI** — switch with the OS "App languages" setting (Android 13 and later). The default is English. To add a language, open a PR with `app/src/main/res/values-<lang>/strings.xml` (see "文言の足し方" in [docs/ui.md](./docs/ui.md), Japanese)

The vocabulary (episodic audio, program, episode, publisher, published at, performers, pinned, starred, on hold, and so on) is defined in [CONTEXT.md](./CONTEXT.md) (Japanese, with the English term for each entry).

## Install

Not distributed on Google Play ([ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md), Japanese). Two ways to install:

1. **GitHub Releases** — download `kikidame-<version>.apk` from [Releases](https://github.com/t-seki/kikidame/releases)
2. **Obtainium** — for automatic updates, add `https://github.com/t-seki/kikidame` to [Obtainium](https://github.com/ImranR98/Obtainium). It notifies you of new releases; the APK carries the same signature, so it installs over the existing app and keeps your episodes and playback positions

**Not submitted to IzzyOnDroid or the main F-Droid repository.** Two reasons. (1) Both reject apps whose code was written with generative AI ([IzzyOnDroid App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/): "We are strongly opposed to apps which are fully or in part created by generative AI tools"), and most of Kikidame's code is written with Claude Code. The submission form requires a declaration of AI use; an honest answer would be rejected, and we will not make a false declaration. (2) The main F-Droid repository rebuilds from source and signs with its own key, so its APK cannot update to or from the Releases build (reinstalling would lose your episodes and playback positions). A self-hosted F-Droid repository, which needs no third-party review, is being considered for people who prefer the F-Droid client ([#123](https://github.com/t-seki/kikidame/issues/123)).

Requires Android 12 (API 31) or later. Store descriptions and screenshots live in [fastlane/metadata/android/](./fastlane/metadata/android/) (Obtainium does not read them, but `fdroidserver` for the self-hosted repository mentioned above reads exactly this layout, so they are kept).

## Server-side prerequisites

- Jellyfin **10.10 or later** (10.10.7 and 12.0.0 verified with containers and the integration test, 10.11 on a real server; see `scripts/jellyfin-testserver.sh` and "テスト用 Jellyfin サーバ" in `docs/development.md`, Japanese. Legacy authentication is not used)
- The audio must be in a **music library**. The app maps it like this:

  | Jellyfin | In the app |
  | --- | --- |
  | MusicAlbum | Program |
  | Audio | Episode |
  | AlbumArtist | Publisher |
  | Artists of the Audio | Performers (display only) |
  | PremiereDate (DateCreated if missing) | Published At (ordering and the basis of "Keep latest N") |

- In other words, the file tags need `album` = program, `albumartist` = publisher, the date (`©day` in M4A) = published at, and `artist` = performers. Jellyfin builds the structure above from them. Whether MP3 date tags yield a published date is unverified
  - For radio recordings, the output of [radirec-tool](https://github.com/t-seki/radirec-tool) has this shape (`albumartist` = station, `©day` = broadcast date)
  - For podcasts, tag the files you fetched from the feed with `album` = program name, `albumartist` = the publisher or network, and the date = release date, then put them in the music library. One folder per program helps Jellyfin group them into a MusicAlbum

## How it works

- **The server is the source of truth for which episodes exist; the device is a cache.** Sync deletes only when it has obtained the server's full listing ([ADR 0004](./docs/adr/0004-sync-deletes-only-from-full-listing.md), Japanese). If it cannot, the program is On Hold: nothing is downloaded and nothing is deleted
- **Playback position and played state are owned by the device.** They are never sent to the server, and the server's values are never used ([ADR 0002](./docs/adr/0002-playback-position-local-authority.md), [ADR 0007](./docs/adr/0007-playback-state-stays-local.md), Japanese)
- **Identity in the app does not depend on server IDs.** If the library is rebuilt on the server and IDs change, programs and episodes are matched again by publisher, program name, and title, keeping files and playback positions ([ADR 0001](./docs/adr/0001-local-surrogate-key.md), [ADR 0005](./docs/adr/0005-rematch-unlinked-rows-before-sync-deletes.md), Japanese)
- **Retention rules apply only at sync time.** Nothing is deleted the moment you mark an episode played or change a setting

## Build and run

You need JDK 21 and the Android SDK (platform 37). Put `local.properties` (not tracked by git) at the repository root:

```
sdk.dir=/home/<you>/Android/Sdk
```

```bash
export JAVA_HOME=~/.local/jdk/current   # JDK 21
./gradlew test                          # unit tests (no emulator needed; same as CI)
./gradlew :app:assembleDebug            # APK
./gradlew :app:installDebug             # install on the connected device
```

Setting up the toolchain, wireless debugging from WSL2, the device checklist, and reconciling the DB with the files on disk are covered in [docs/development.md](./docs/development.md) (Japanese).

## Modules

```
:core:domain   Pure Kotlin. Types for programs, episodes, retention rules, and played state; pure functions for sync and played detection
:core:data     Room entities / DAOs / repositories. Mapping between entities and domain types stays inside this module
:app           Compose UI, the Media3 MediaLibraryService (playback and the Android Auto browse tree), WorkManager workers, Hilt
```

The stack is Kotlin / Jetpack Compose (Material 3) / Media3 / Room / WorkManager / Hilt / jellyfin-sdk-kotlin. Downloads do not use Media3's DownloadManager; the app writes straight to a file with OkHttp using the URL and headers the SDK builds ([ADR 0003](./docs/adr/0003-download-without-media3-downloadmanager.md), Japanese).

## Non-goals

- Video, transcoding, casting
- Multiple servers, users, or libraries
- Syncing playback position or played state to the server, or sharing them with other devices
- Server administration
- Audiobooks (you listen from the oldest chapter, so "Keep latest N" does not fit; supporting them would need a "direction" per program)
- Distribution and payments on Google Play (if demand appears, the option of selling a paid build on Play while Releases stay free is kept open; [ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md), Japanese)

The full list is under "やらないこと" in [docs/claude-code-handoff.md](./docs/claude-code-handoff.md) (Japanese).

## Documentation

The documentation below is in Japanese.

- Glossary: [CONTEXT.md](./CONTEXT.md) (each term carries its English name)
- Architecture decision records: [docs/adr/](./docs/adr/)
- Implementation handoff (overall design, milestones): [docs/claude-code-handoff.md](./docs/claude-code-handoff.md)
- Development guide (toolchain, tests, device checks, parallel work): [docs/development.md](./docs/development.md)
- UI guidelines (what matters, colors, the role of each screen): [docs/ui.md](./docs/ui.md)

## License

[MPL-2.0](./LICENSE) ([ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md), Japanese). Kikidame is an unofficial client with no affiliation to the Jellyfin project; the Jellyfin name and logo belong to the Jellyfin project.

Communication with the server uses [jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin) (LGPL-3.0). The list of bundled dependencies and their licenses is shown in the app under "Settings > Open source licenses". Its source data is `app/src/main/res/raw/aboutlibraries.json`; after changing dependencies, regenerate it with `./gradlew :app:exportLibraryDefinitions` and commit the result (it is not generated at build time, so offline builds produce the same list).
