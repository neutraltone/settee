# Settee

**A personal IPTV player for Android TV, Fire TV, phones and tablets, built for a Fire TV Stick.**

No account. No subscription. No server between you and your provider.

[![Build](https://github.com/neutraltone/settee/actions/workflows/build.yml/badge.svg)](https://github.com/neutraltone/settee/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

---

Settee is based on [OpenTV](https://github.com/opentvproject/opentv), a GPL-licensed, clean-room
IPTV player whose original project has been abandoned. Settee carries it on as its own app with
its own name, application ID and update channel, and installs alongside OpenTV rather than over
it. The licence and the per-file copyright of the OpenTV contributors stay as they are.

## What it does

- **Live TV** from Xtream Codes logins and plain M3U/M3U8 playlists
- **A guide that stays put** — XMLTV EPG that survives restarts, refreshes incrementally, and
  never wipes itself
- **Recording (DVR)** — record to the box, a USB drive or your NAS (SMB); schedule from the
  guide; series-link a whole show; play back in-app with full seeking
- **Catch-up / archive** where your provider offers it, plus **programme reminders**
- **Movies and series** with resume and per-profile watch history
- **Favourites, categories, hide channels, search**, picture-in-picture, aspect control, and
  hand-off to an external player
- **Free sync between your own devices** — favourites, watch history and NAS recordings, over
  your wifi or Tailscale, with no server of ours
- **Multiple languages** (fully translated to Spanish), a parental PIN, profiles, and self-updates
- **D-pad first** — designed for a remote, works with a touchscreen
- **One APK** for Android TV, Fire TV, phones and tablets

## Screenshots

The live TV guide — programme grid, favourites, and a live preview:

![Live TV guide](docs/screenshots/01-guide.png)

| Recordings &amp; reminders | Free cloud sync — your own NAS, no server |
| :---: | :---: |
| ![Recordings and reminders](docs/screenshots/02-recordings.png) | ![NAS cloud sync](docs/screenshots/03-cloud-sync.png) |
| Movies &amp; series | Settings |
| ![Movies](docs/screenshots/04-movies.png) | ![Settings](docs/screenshots/05-settings.png) |

## What it deliberately does not do

- **No servers, no web dashboard, no account.** Your provider credentials never leave your
  device, and syncing happens directly between your devices — or through your own NAS — never
  through a machine anyone else runs. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the
  reasoning.
- **No content.** Settee is a player. You bring a service you already pay for.

## Install

Grab `settee.apk` from [the latest release](https://github.com/neutraltone/settee/releases/latest).
`https://github.com/neutraltone/settee/releases/latest/download/settee.apk` always serves the
newest build. Once installed, the built-in updater offers each new release.

### Fire TV Stick

1. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. Let it install apps: **Settings → My Fire TV → Developer Options → Install unknown apps →
   Downloader → On**. If Developer Options is missing, open **Settings → My Fire TV → About** and
   press select on the device name seven times.
3. Open Downloader and type the full URL above into its address box, then **Go**. When the
   download finishes, choose **Install**, then **Delete** to free the space.

Every release is built by GitHub Actions from a tagged commit, so the APK can be checked against
the source. See [docs/RELEASING.md](docs/RELEASING.md).

## Build it yourself

```bash
git clone https://github.com/neutraltone/settee.git
cd settee
./gradlew assembleDebug
```

You need JDK 17 and the Android SDK (API 37); Android Studio provides both. The debug APK lands
in `app/build/outputs/apk/debug/` and installs as `uk.neutraltone.settee.debug`, alongside a
release build.

Run the tests:

```bash
./gradlew test
```

## Clean-room

Nothing in OpenTV or Settee was decompiled or copied from another IPTV app. The code was written
from public specifications (the Xtream Codes request format, the XMLTV and M3U formats, the Kodi
catch-up tags) and from how other apps *behave*, never from their source.

## Licence

[GPL-3.0-or-later](LICENSE), inherited from OpenTV.

## Legal

Settee is a media player, comparable to VLC. It ships with no channels, no playlists, and no
links to any. What you point it at, and whether you are entitled to, is between you and your
provider.
