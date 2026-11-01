# Kikidame

日本語: [README.ja.md](./README.ja.md)

An Android app for people who record **radio programs** and keep the recordings on a Jellyfin server, a shared folder on a NAS (SMB) or a folder on the phone (podcast files work too). It downloads and deletes episodes automatically by per-program rules, and keeps playing when the source is unreachable. Existing Jellyfin clients are built around video or music; Kikidame is built for audio where episodes pile up.

The name comes from the Japanese 聴き溜め (*kiki-dame*, "listen to what you have saved up"): record the episodes as they air, then listen to them on your phone when you have time.

https://github.com/user-attachments/assets/346c00bd-e9a7-4568-8849-70ac8e59ad58

<p>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Continue"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="Episodes"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="Player"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Sync settings">
</p>

Screenshots: continue, episodes, player, sync settings (English UI).

## Install

Requires Android 12 or later. Not distributed on Google Play.

1. **GitHub Releases** — download `kikidame-<version>.apk` from [Releases](https://github.com/t-seki/kikidame/releases)
2. **Obtainium** — for automatic updates, add `https://github.com/t-seki/kikidame` to [Obtainium](https://github.com/ImranR98/Obtainium). Updates install over the existing app and keep your episodes and playback positions

Kikidame is not on IzzyOnDroid or the main F-Droid repository, because most of its code is written with generative AI (Claude Code), which those repositories do not accept. How generative AI is used in this project is in [CONTRIBUTING.md](./CONTRIBUTING.md#generative-ai).

## Sources

The app uses one source at a time. Details and what has been tested are in [docs/sources.md](./docs/sources.md).

- **Jellyfin** — Jellyfin 10.10 or later. Put the audio in a **books library** (content type "Books") or a music library. A books library is recommended: the playback position and played state are shared through the server with Jellyfin Web and other devices signed in as the same Jellyfin user. With a music library, a NAS share or a folder on the device, they stay on this device only
- **Shared folder on a NAS (SMB)** — SMB2/3, with a user and password or as a guest. The app only reads the share
- **Folder on this device** — a folder in internal storage or on an SD card, picked with Android's folder picker. The app only reads the folder. Each copied episode takes space twice (the file in the folder and the copy in the app). If you delete a file from the folder, the app deletes its copy at the next sync, so keep the files there

Tag the files with `album` = program, `albumartist` = publisher (the station, for radio), the date = published date, and `artist` = performers. A Jellyfin library groups episodes into programs by these tags. A NAS share or a folder on the device must be laid out exactly as `<publisher>/<program>/<episode files>`; the folder names give the publisher and program, and the tags give the title, date and performers. On a NAS with thousands of files, the first import can take ten minutes or more; keep the app open with the screen on until it finishes.

To record radio programs (Japan's radiko) in this layout and with these tags, use [rajikura](https://github.com/t-seki/rajikura), a recording tool by the same author. Its Jellyfin preset saves `<station>/<program>/<program> YYYY-MM-DD.m4a` with `albumartist` = station and `album` = program, and puts a `folder.jpg` in each program folder. Kikidame reading its output as it is has not been verified yet. For other files, a general tag editor such as Mp3tag works.

## What it does

- **Program list** — search by program or publisher name, and filter by publisher. Programs you mark with ☆ (**Starred**) get their own tab. A program that is no longer on the source is marked "Not found in the source"
- **Sync** — per program, choose whether to sync it and its retention rules ("Keep latest N", "Delete after played"). Sync runs every 3 hours (optionally on Wi-Fi only), downloads what is missing and removes what is no longer needed. If a file on the source is rewritten (for example when chapters are added), sync replaces the copy on the device and keeps the playback position
- **Episode list** — download, pin and mark played. **Pinned** episodes (downloaded by hand, or pinned with "Pin" in the episode sheet) are never removed by the retention rules
- **Offline first** — playback always uses the files on the device. The retention rules delete episodes only at sync, and only after the source's full listing was read; nothing is deleted the moment you mark an episode played. When the source cannot be reached, sync downloads and deletes nothing, and playback goes on
- **Player** — background playback with notification and lock-screen controls, skip 10 seconds, speed 1.0x to 2.0x, sleep timer, chapters, and resume where you left off. Near the end (the last 2 minutes) an episode is marked **Played**, and playback moves on to the next episode of the same program. Kikidame does not start playing when a Bluetooth device connects; the Kikidame card in the lock-screen media controls resumes the last episode, even after the app was closed
- **Android Auto** — browse Continue / Starred / Programs on the car screen and play the episodes on the device
- English and Japanese UI (per-app language on Android 13 and later), and three themes

## Changing the source

Changing the source in Settings deletes the programs, episodes, playback positions and audio files on the device and starts over. Choosing another library on the same Jellyfin server and user is not a change of source: the next sync links the programs and episodes again and keeps them. To move from a Jellyfin music library to a books library (the programs, episodes, playback positions, played state and files are linked again by publisher, program name and title):

1. Update Kikidame to a version that can use books libraries
2. In the Jellyfin dashboard, remove the music library. If the same folder is in both a music and a books library, Jellyfin reuses the items and the books library still shows them as music
3. Add a library with the content type "Books" for the same folder and wait for the scan to finish
4. In Kikidame, tap "Library" in Settings, choose the books library and tap "Done". The sync starts
5. Check that the programs, episodes, in-progress positions and played marks are still there. Programs that could not be linked again stay on the device and are counted in "Remove programs not found on the source" in Settings

## Non-goals

- Video, transcoding, casting
- Multiple servers, users, or libraries at once
- Server administration
- Audiobooks (you listen from the oldest chapter, so "Keep latest N" does not fit)
- Google Play

The full list is in [docs/architecture.md](./docs/architecture.md).

## Contributing and documentation

What is accepted and how is in [CONTRIBUTING.md](./CONTRIBUTING.md). The developer documentation, starting at [docs/README.md](./docs/README.md), is in Japanese only (this includes [docs/sources.md](./docs/sources.md) and [CONTEXT.md](./CONTEXT.md), the glossary).

## License

[MPL-2.0](./LICENSE). Kikidame is an unofficial client with no affiliation to the Jellyfin project; the Jellyfin name and logo belong to the Jellyfin project.

Communication with the server uses [jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin) (LGPL-3.0). The list of bundled dependencies and their licenses is shown in the app under "Settings > Open source licenses".
