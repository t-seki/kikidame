# Kikidame

日本語: [README.ja.md](./README.ja.md)

An Android app for recorded radio programs kept on a Jellyfin server, a NAS shared folder or a folder on your device (podcast files work too). It downloads and deletes episodes automatically by per-program rules, and keeps playing when the source is unreachable. Kikidame is an unofficial client with no affiliation to the Jellyfin project.

https://github.com/user-attachments/assets/346c00bd-e9a7-4568-8849-70ac8e59ad58

<p>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Continue"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="Episodes"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="Player"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Sync settings">
</p>
<p>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="480" alt="Android Auto">
</p>

Screenshots: continue, episodes, player, sync settings, and Android Auto (English UI).

The name comes from the Japanese *kiki-dame* ("listen to what you have saved up"): record the episodes as they air, then listen to them on your phone when you have time.

## Why

Existing Jellyfin clients (the official app, Findroid, Finamp) are built around video or music. Two things are weak there: "keep episodes on the device, program by program, and remove them automatically" and "work fully even when the server is unreachable". Kikidame is built for exactly those two, for audio where episodes pile up.

- **Offline first** — playback always uses the files on the device. Playback position and played state live on the device only and are never sent to the server
- **Retention rules** — per program, choose "Keep latest N" and "Delete after played"; sync then downloads what is missing and removes what is no longer needed

It assumes one source at a time (one Jellyfin server, user and library, or one NAS shared folder or folder on the device) and that this app is the only place you listen. Changing the source deletes the programs, episodes, playback positions and files on the device and starts over. Choosing another library on the same Jellyfin server with the same user does not count as changing the source: nothing on the device is deleted, and the next sync links the programs and episodes again ([ADR 0013](./docs/adr/0013-share-playback-state-via-jellyfin-book-library.md), decision 7). Sharing with other devices or the web player is not a goal.

## What it does

- **Program list** — sections split by the Starred mark, search by program or publisher name, filter by publisher. Each row shows whether sync is enabled for the program and whether it is no longer on the server (Gone). Programs not found on the source can be removed from the device all at once from Settings
- **Episode list** — download, pin, and mark played. Each row shows whether the episode is on the device, downloading, or failed
- **Sync** — per program, choose whether sync is enabled for it and its retention rule. WorkManager syncs every 3 hours, with a "Wi-Fi only" option. Android may delay background sync for an app you have not opened for a while; turning off battery optimization for Kikidame in Settings makes it less likely to be delayed. While a background sync runs, a thin bar under the top bar says so. After a sync, a snackbar shows only what changed (new episodes, queued downloads, deleted episodes); a background sync with no changes shows nothing. "Last sync" in Settings also shows when the latest sync attempt failed or was skipped because the phone was not on Wi-Fi. If background sync keeps failing for 24 hours or more, a notification appears once (when notifications are allowed). Episodes you download by hand, or pin from the episode sheet when they are already on the device, are Pinned and never removed by the rules
- **Player** — background playback with notification and lock-screen controls, skip 10 seconds back and forward, speed 1.0x to 2.0x, sleep timer (by time or until the end of the episode). The playback position is saved so the next play resumes where you left off; listening near the end marks the episode Played, and finishing it advances to the next episode of the same program. After the app process has ended, the ▶ button on a Bluetooth headset or in a car (media button) or Android Auto resumes the last episode from where it stopped. The media button goes to the app that last played audio, so if another app played more recently, the button goes to that app instead. Even when the app is closed, a Kikidame card stays in the lock-screen media controls, and its ▶ continues from where you left off. Kikidame does not start playing by itself when a Bluetooth device connects ([ADR 0011](./docs/adr/0011-no-autoplay-on-bluetooth-connect.md), Japanese)
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

## Source prerequisites

The app connects to **Jellyfin**, a **shared folder on a NAS (SMB)** or a **folder on this device**, one at a time. The SMB shared folder and the folder on this device are available from v0.4.0 (release builds up to v0.3.0 offer only Jellyfin).

### Jellyfin

- Jellyfin **10.10 or later** (10.10.7 and 12.0.0 verified with containers and the integration test, 10.11 on a real server; see `scripts/jellyfin-testserver.sh` and "テスト用 Jellyfin サーバ" in `docs/development.md`, Japanese. Legacy authentication is not used)
- The audio must be in a **books library** (content type "Books") or a **music library**. A books library is recommended: sharing the playback position and played state with other devices and Jellyfin Web through Jellyfin (playback sharing) is planned for books libraries only (#355; not in this version yet. [ADR 0013](./docs/adr/0013-share-playback-state-via-jellyfin-book-library.md)). Music libraries keep working as before. The app maps them like this:

  | Jellyfin (books library) | Jellyfin (music library) | In the app |
  | --- | --- | --- |
  | The Album tag of the episodes | MusicAlbum | Program |
  | AudioBook | Audio | Episode |
  | The AlbumArtist tag of the episodes | AlbumArtist of the MusicAlbum | Publisher |
  | Artists of the AudioBook | Artists of the Audio | Performers (display only) |
  | PremiereDate (DateCreated if missing) | PremiereDate (DateCreated if missing) | Published At (ordering and the basis of "Keep latest N") |
  | The image of the program's folder (none for a program with a single episode) | The image of the MusicAlbum | Program image |

- In other words, the file tags need `album` = program, `albumartist` = publisher, the date (`©day` in M4A) = published at, and `artist` = performers. In a books library the app groups the episodes into programs by the `album` and `albumartist` tags; in a music library Jellyfin builds the structure above. Whether MP3 date tags yield a published date is unverified
  - For radio recordings, set the tags `album` = program, `albumartist` = station, and the date (`©day` in M4A) = broadcast date, using a general-purpose tag editor such as Mp3tag
  - For podcasts, tag the files you fetched from the feed with `album` = program name, `albumartist` = the publisher or network, and the date = release date, then put them in the library. In a music library, one folder per program helps Jellyfin group them into a MusicAlbum
  - Books libraries were checked with the 10.10.7 and 12.0 containers (the integration test) and a 10.11.11 container ([#352](https://github.com/t-seki/kikidame/issues/352)): every episode is listed as an AudioBook. Moving a real server over has not been checked yet
- To move from a music library to a books library (the programs, episodes, playback positions, played state and downloaded files on the device are linked again by the sync after you choose the new library, matching on publisher, program name and title. [ADR 0005](./docs/adr/0005-rematch-unlinked-rows-before-sync-deletes.md)):
  1. Update Kikidame to a version that can use books libraries (one that includes [#354](https://github.com/t-seki/kikidame/issues/354))
  2. In the Jellyfin dashboard, remove the music library. If the same folder is in both a music and a books library, Jellyfin reuses the items and the books library still shows them as Audio and MusicAlbum ("確かめた事実" in [ADR 0013](./docs/adr/0013-share-playback-state-via-jellyfin-book-library.md), Japanese)
  3. Add a library with the content type "Books" for the same folder and wait for the scan to finish
  4. In Kikidame, tap "Library" in Settings and choose the books library, then tap "Done". The sync starts
  5. Check that the programs and episodes are still there, and that the in-progress positions and played marks are kept. Programs that could not be linked again (for example when the publisher or program name changed) stay on the device and are counted in "Remove programs not found on the source" in Settings

### Shared folder on a NAS (SMB)

- SMB2/3 only (SMB1 is not supported). Enter the host (a name or an IP address), the share name, an optional folder inside the share, and a username and password, or connect as a guest. The password is stored encrypted on the device. The app only reads the share and never writes to or deletes anything on it
- Inside the share (or the folder you entered) the layout is exactly **`<publisher>/<program>/<episode files>`**, three levels:

  | Folder level | In the app |
  | --- | --- |
  | 1st level folder | Publisher |
  | 2nd level folder | Program (even if it has no audio yet) |
  | Audio files in a 2nd level folder | Episodes (by extension: m4a, mp3, aac, ogg, opus, flac, wav) |

  Other files (non-audio files, files at other levels) are silently ignored. Folders and files whose names start with `.`, `@` or `#` (`@eaDir`, `#recycle`, `._foo.m4a` and the like, made by NAS units and operating systems) are ignored at every level. The one exception is `folder.jpg` in a program folder (2nd level folder; only that exact name), which is read as the program image
- Publisher and program come from the folder names; the tags are not used for them. For each episode the app reads the tags: title (the file name without the extension if missing), date (the file's modified time if there is no full date), duration, and artist (performers). The tags are read again only when a file's path, size or modified time changes
- The first full scan lists every folder first, then reads the tags program by program. Each program shows up in the program list as soon as its tags are read, and while tags are being read the program list shows a count such as "Reading tags 1,234 / 6,090". The first import of a share with thousands of episode files takes on the order of ten minutes or more (about 12 minutes for about 6,000 files on the developer's NAS; it depends on your NAS, network and files)
- **Keep the app open with the screen on during the first import.** When the screen turns off and the app goes to the background, Android cuts the app's network connection after a while (from a few seconds to a few minutes; it varies with the device and the situation), and the import stops. Programs already imported stay, and the next time you open the app the import continues with the programs not read yet (their tags are not read again). Whether a program has disappeared from the share is decided only by a scan that reaches the end
- If a read fails, the app reconnects and tries the same file again, and ends the sync as unreachable after three failures in a row. Whether reconnecting actually lets a scan continue (for example after a short network glitch) has not been seen on a real device yet
- Renaming an episode file makes it unlinked and it is matched again by title (then by published day and duration) within the same program. Renaming a program folder makes the program Gone (On Hold), because the publisher and program name no longer match
- Whether this works on your NAS and network (name resolution, guest access, how long the first import takes) is verified only on the developer's NAS and device; see "実機で試す（#198: SMB の共有フォルダ）" for the setup in [docs/device-testing.md](./docs/device-testing.md) and its checklists in [docs/device-checklists.md](./docs/device-checklists.md) (Japanese)

### Folder on this device

- Pick a folder in internal storage or on an SD card with Android's folder picker (Storage Access Framework). Folders from other providers (Google Drive, SMB through a documents provider, and the like) are refused; pick a folder on this device instead. The app only reads the folder and never writes to or deletes anything in it. It does not need the "Local network" permission
- The layout inside the folder is the same as for the SMB shared folder above: exactly **`<publisher>/<program>/<episode files>`**, three levels, for example `Radio/<publisher>/<program>/<episode>.m4a` where you pick `Radio`. Android does not let apps pick the top of the internal storage or the `Download` folder itself, so make a folder below them and pick that. Whether a folder inside `Download` can be picked is unverified
- Episodes are copied into the app before you listen, just like the other sources. **Each copied episode takes space twice on the device** (the file in the folder and the copy in the app). Use the per-program sync setting and retention rule to limit how many episodes are copied
- **Keep the files in the folder.** If you delete a file from the folder, the next sync treats that episode as gone from the source and deletes the app's copy too, even if it is Pinned. This is meant for recordings you sync into the folder from a PC (Syncthing, FolderSync and the like): keep them there
- Downloads and syncs from a folder on this device need no network at all, so "Wi-Fi only" does not apply to it (the copy stays inside the device)
- If the app loses access to the folder (the permission was revoked, or the folder was moved or deleted), it signs out and asks you to pick the folder again. Programs, episodes and files on the device are kept, and they link up again if the folder you pick has the same layout
- Folders in internal storage have been verified on a real device (Android 17): import, copying, playback, and re-selecting the folder after it was moved. Whether it works on SD cards and large folders is unverified. See "実機で試す（#199: 端末のフォルダ）" in [docs/device-checklists.md](./docs/device-checklists.md) (Japanese)

## How it works

- **The source (the Jellyfin server or the shared folder) is the source of truth for which episodes exist; the device is a cache.** Sync deletes only when it has obtained the source's full listing ([ADR 0004](./docs/adr/0004-sync-deletes-only-from-full-listing.md), Japanese). If it cannot, the program is On Hold: nothing is downloaded and nothing is deleted
- **Playback position and played state are owned by the device.** They are never sent to the server, and the server's values are never used ([ADR 0002](./docs/adr/0002-playback-position-local-authority.md), [ADR 0007](./docs/adr/0007-playback-state-stays-local.md), Japanese)
- **Identity in the app does not depend on source IDs** (a Jellyfin id, or the relative path inside a shared folder). If the library is rebuilt on the server and IDs change, or a file is renamed in a shared folder, programs and episodes are matched again by publisher, program name, and title, keeping files and playback positions ([ADR 0001](./docs/adr/0001-local-surrogate-key.md), [ADR 0005](./docs/adr/0005-rematch-unlinked-rows-before-sync-deletes.md), Japanese)
- **Retention rules apply only at sync time.** Nothing is deleted the moment you mark an episode played. Nothing is deleted while you change a program's sync settings either; when you close the settings, that program is synced and the rules are applied

## Build and run

You need JDK 25 and the Android SDK (platform 37). Put `local.properties` (not tracked by git) at the repository root:

```
sdk.dir=/home/<you>/Android/Sdk
```

```bash
export JAVA_HOME=~/.local/jdk/current   # JDK 25
./gradlew test                          # unit tests (no emulator needed; same as CI)
./gradlew :app:assembleDebug            # APK
./gradlew :app:installDebug             # install on the connected device
```

Setting up the toolchain is covered in [docs/development.md](./docs/development.md), wireless debugging from WSL2 and reconciling the DB with the files on disk in [docs/device-testing.md](./docs/device-testing.md), and the device checklists in [docs/device-checklists.md](./docs/device-checklists.md) (completed ones in [docs/device-checklists-archive.md](./docs/device-checklists-archive.md)) (Japanese).

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
- Development guide (toolchain, tests, release, parallel work): [docs/development.md](./docs/development.md)
- Testing on a device (wireless debugging, Android Auto, preparing test data, reconciling the DB with the files): [docs/device-testing.md](./docs/device-testing.md)
- Device checklists with unchecked items: [docs/device-checklists.md](./docs/device-checklists.md)
- Completed device checklists: [docs/device-checklists-archive.md](./docs/device-checklists-archive.md)
- UI guidelines (what matters, colors, the role of each screen): [docs/ui.md](./docs/ui.md)

## License

[MPL-2.0](./LICENSE) ([ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md), Japanese). Kikidame is an unofficial client with no affiliation to the Jellyfin project; the Jellyfin name and logo belong to the Jellyfin project.

Communication with the server uses [jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin) (LGPL-3.0). The list of bundled dependencies and their licenses is shown in the app under "Settings > Open source licenses". Its source data is `app/src/main/res/raw/aboutlibraries.json`; after changing dependencies, regenerate it with `./gradlew :app:exportLibraryDefinitions` and commit the result (it is not generated at build time, so offline builds produce the same list).
