# Torrents play through libtorrent, via FrostWire's jlibtorrent

Torrent streams from add-ons are to play inside Settee (#665), so Settee needs a BitTorrent library (#669). It has to be maintained, let a player fetch the pieces under its read position first (piece priorities or deadlines, and sequential download), run on the Android versions Settee supports, carry a licence a GPL-3.0 app can ship, and come with native libraries for the ARM ABIs Fire TV and phones use. We chose **libtorrent (rasterbar) through FrostWire's Java bindings, `com.frostwire:jlibtorrent-android` 2.1.2.1** (released 2026-11-13, on libtorrent 2.1.2). Playback depends only on the `TorrentEngine` interface (`player/torrent`); `LibtorrentEngine` is the one class that imports the library.

libtorrent is the reference implementation of the protocol, and its streaming support is first-class: `set_piece_deadline` per piece, file priorities to skip the rest of a torrent, a sequential download flag, and a POSIX disk backend that keeps no write cache of its own. jlibtorrent exposes all of these (`TorrentHandle.setPieceDeadline`, `clearPieceDeadlines`, `prioritizeFiles`, `TorrentFlags.SEQUENTIAL_DOWNLOAD`, `SessionParams.setPosixDiskIO`). It is MIT licensed; libtorrent is BSD-3-Clause, and what it links in (Boost, BSL-1.0; OpenSSL 3, Apache-2.0) is GPL-3.0 compatible. FrostWire released it 2026-10-31, 2026-11-12 and 2026-11-13, and libtorrent itself 2026-11-23.

## Alternatives rejected

- **libtorrent4j** (`org.libtorrent4j`, MIT, on Maven Central): the same libtorrent underneath, but its last release, 2.1.0-39, was 2026-02-07 and raised its native minimum to API 28. The release before it (2.1.0-38, 2025-09-14) still builds for API 24 but is over a year old, and every later one will need 28. One release in 2026 against FrostWire's three in November alone.
- **Bt** (`com.github.atomashpolskiy:bt-core`, Apache-2.0), the pure-JVM option: no native libraries at all, but its last release, 1.10, was 2021-12-05, and it has no piece deadlines for streaming.
- **A Go or Rust engine (anacrolix/torrent, librqbit) behind gomobile or JNI**: no maintained Java API for Android, so Settee would be maintaining bindings of its own.
- **TorrentStream-Android and similar wrappers**: built on an old jlibtorrent and unmaintained, and copying a player's streaming code would break the clean-room rule. The engine follows the protocol and libtorrent's own documentation only.

## Decisions that come with it

- **Android 8.0 and up for torrents.** jlibtorrent's native library is built for API 26 (its AAR asks for `minSdkVersion 26`; the ELF note in `libjlibtorrent.so` says 26, as did 2.0.15.3's). Settee stays at minSdk 24: the manifest overrides the library's minimum, and `LibtorrentEngine` never loads it below API 26, failing the torrent as `UNSUPPORTED_DEVICE` instead. The Stick (Fire OS 7, API 28) and later Fire TVs are unaffected; Fire OS 6 devices (API 25) can't play torrents.
- **Only the ARM ABIs.** Settee stays one APK carrying `armeabi-v7a` and `arm64-v8a`. The x86 and x86_64 copies are left out with `packaging.jniLibs.excludes`, not `abiFilters`, so AndroidX's own small x86 libraries stay. An x86_64 copy would add about 5.8 MB to the APK, and 16.7 MB once extracted, for the emulator alone, and the emulators used here are arm64 (Apple silicon); Robolectric never loads the library. An x86 emulator gets `UNSUPPORTED_DEVICE`.
- **Native libraries are compressed** (`useLegacyPackaging`), not AGP's default of storing them as they are. Stored, both ABIs' 31.6 MB went into the APK unshrunk (39.4 MB in all) and the box keeps the whole APK. Compressed, the APK is half that, and Android extracts only the box's own ABI at install: on the Stick 19.2 MB of APK plus 14.2 MB extracted, against 39.4 MB.
- **The AAR, not the per-ABI jars.** It's the same `.so` files, and it carries its own R8 keep rules for the SWIG layer, which C++ finds by name.
- **From FrostWire's own Maven repository** (`dl.frostwire.com/maven`, serving the files of the project's releases), not Maven Central, which doesn't carry it. `settings.gradle.kts` limits that repository to the `com.frostwire` group.
- **One session per torrent, POSIX disk I/O.** A session starts with a torrent and stops with it, so nothing of libtorrent runs or listens between plays. POSIX disk I/O maps no files into memory, which a 32-bit Stick userland has little address space for, and has no write cache, so a piece libtorrent has verified is already in the file the engine reads. UPnP, NAT-PMP and local peer discovery are off, the DHT is on, and nothing seeds after playback.
- **Limits are provisional.** `TorrentEngineConfig`'s memory cap, piece cache cap, space kept free and upload cap are first guesses until measured on the Stick (docs/PERFORMANCE.md, "Torrent engine").

## APK size

`assembleRelease` from `3fc45188`, before and with the library (compressed native libraries, as shipped):

| | Before | With jlibtorrent | Added |
|---|---:|---:|---:|
| APK | 7,739,647 B | 19,246,583 B | 11,506,936 B (11.0 MiB) |
| `lib/armeabi-v7a/libjlibtorrent.so` | | 5,344,459 B (14,189,332 B extracted) | |
| `lib/arm64-v8a/libjlibtorrent.so` | | 6,206,372 B (17,404,816 B extracted) | |
| `classes.dex` (the bindings' Java classes, after R8) | 7,547,732 B | 7,794,676 B | 246,944 B uncompressed |
| x86_64, left out | | would add about 5.8 MB (16,723,032 B extracted) | |

Stored uncompressed instead, the APK was 39,410,075 B (31.7 MB added).

## Consequences

- #670's `DataSource` and its tests use `TorrentEngine` and the test fake (`FakeTorrentEngine`), never the library.
- `UNSUPPORTED_DEVICE` needs a sentence of its own in #670, beside nobody sharing, not enough space and didn't start in time.
- Pieces aren't evicted while a torrent plays (libtorrent has no call to drop a verified piece), so the whole file must fit under the piece cache cap and the free space less what's kept back. The stream picker leaves out a torrent whose add-on says it's bigger, counting them on a line with the room there is (`TorrentCache.room`); one whose size nobody said can still be picked, and fails with `NOT_ENOUGH_SPACE` before it starts.
- Moving to another library means a new `TorrentEngine` implementation and a new ADR; nothing outside `LibtorrentEngine` changes.
