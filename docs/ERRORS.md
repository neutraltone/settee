# Errors

Every failure the app can show, what triggers it and what the viewer reads. The wording follows
[Voice](LOOK.md#voice): one sentence saying what happened and what to do, whose end it's on only when
the app knows. Under each sentence, `ErrorMessage` (`ui/design`) draws a quiet detail line of up to
three parts joined by " · ", each left out when unknown:

- **code**: Media3's error name made readable (`ERROR_CODE_IO_BAD_HTTP_STATUS` → `IO bad HTTP
  status`), or for failures outside playback the exception's type without "Exception" (`UnknownHost`,
  `SocketTimeout`) or the problem the app found (`LoginRejected`).
- **status**: `HTTP 403`.
- **format**: the stream's format and size, as the player names them (`HEVC 10-bit, 3840x2160`).

The detail line never shows a URL, host, username, password, MAC or any exception's own message, and
nor does the sentence: both come from the failure's type, never its text (see
`PlaybackErrorsTest`, `CatalogueRefreshTest`).

Sentences that mention the hardware have a `_tv` and a `_device` string: "this TV" when the app runs on
a television (`isRunningOnTelevision`), "this device" on phones and tablets.

Who builds them: `PlaybackErrors` for playback, `ErrorWording` (`core/`) for everything else, through
`ServiceLocator.errorWording`. Change a mapping here and in the code together.

## Starting up

Worded by `ErrorWording.ofDatabase`, drawn by `ui/DatabaseGate` in place of every screen. The app can't
run without its database, so the way out is a release that fixes it or clearing the app's data. The
screen looks for a release straight away, and the sentence follows what that found. Check again (See
the update once one is found) looks again and takes the highlight, unless the update panel is up
(`docs/RELEASING.md`, "When an update can't open the database").

The trigger for all four: the database wouldn't open at launch (`OpenTvDatabase.wontOpen`: Room's
`IllegalStateException`, SQLite's `SQLException`). Whose end: Settee's, from a database left by a newer
build after a downgrade, or a migration that doesn't match its schema. The detail is the exception type.

| Update check | String key | Sentence |
|---|---|---|
| Still checking | `error_database_checking_tv` / `_device` | Settee can't read what it saved on this TV. Checking for an update that fixes it. |
| Found one (the update panel opens over it) | `error_database_update_tv` / `_device` | Settee can't read what it saved on this TV, and there's an update that fixes it. |
| Up to date | `error_database_no_update_tv` / `_device` | Settee can't read what it saved on this TV, and there's no update to fix it yet. Clearing its data in this TV's settings for apps starts it afresh, but loses your providers, Favourites and watch history. |
| Couldn't check | `error_database_offline_tv` / `_device` | Settee can't read what it saved on this TV, and couldn't check for an update. Connect this TV to the internet and check again, or clear its data in this TV's settings for apps, which loses your providers, Favourites and watch history. |

## Playback

Movies and Shows' error panel, the live player, the Home Live Preview, the guide's preview, and a
recording that can't start. Strings are in `res/values/strings.xml`. Where trying again could fix it,
both players put the highlight on Try again and OK presses it; in the live player Up and Down still
change channel.

A recording that stops by itself is Failed when it captured nothing, and Cut short when it captured
something: still Recorded, playable, with the reason under it as a Failed row has (#473). Each cause's
sentence reads right on either, and a stop by hand is a plain finish with no reason.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| HTTP 401 | `error_http_401` | Your provider didn't accept your login. Check the username and password in Settings. | code · HTTP 401 · format | Viewer's: wrong or expired login |
| HTTP 403 | `error_http_403` | Your provider refused this. Your account may be in use on another device, so stop it there and try again. | code · HTTP 403 · format | Provider's: account streaming elsewhere, or blocking this app (the User-Agent hint stays in the provider's advanced settings) |
| HTTP 404 | `error_gone` | This isn't on your provider any more. Try refreshing your provider in Settings. | code · HTTP 404 · format | Provider's: the stream was removed |
| HTTP 405, any other status | `error_anything_else` | This didn't play. Try again, or pick something else. | code · HTTP 405 · format | Usually setup: Xtream versus playlist (that advice is `onboarding_xtream_or_playlist_help`, under the Xtream login in provider setup) |
| HTTP 429 | `error_http_429` | Your provider is getting too many requests from this device. Wait a minute, then try again. | code · HTTP 429 · format | Provider's rate limit |
| HTTP 451 | `error_http_451` | Your provider can't show this where you are. | code · HTTP 451 · format | Provider's: blocked in this region |
| HTTP 5xx | `error_http_5xx` | Your provider is having problems. That's at their end, so try again later. | code · HTTP 5xx · format | Provider's server |
| `UnknownHostException`, `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` | `error_cant_reach_tv` / `_device` | Can't reach your provider. Check this TV is online and the address is right. | code · format | Viewer's network, or a wrong address |
| `SocketTimeoutException`, `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` | `error_timed_out` | Your provider stopped responding. It may be busy, so try again in a moment. | code · format | Provider's: overloaded |
| `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`, `ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED`, `ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE` (not raised today: the player checks no content type) | `error_web_page` | Your provider sent a web page instead of video. The link may have expired, so try refreshing your provider in Settings. | code · format | Provider's: an expired account or blocked channel answering 200 with an HTML page, which won't parse as a TS container or HLS manifest; or a captive page |
| `ERROR_CODE_PARSING_CONTAINER_MALFORMED`, `ERROR_CODE_PARSING_MANIFEST_MALFORMED` | `error_damaged` | This video is damaged and won't play. That's at your provider's end, so try something else. | code · format | Provider's: broken stream |
| `ERROR_CODE_BEHIND_LIVE_WINDOW` | `error_behind_live` | This channel fell too far behind live. Try again to catch up. | code · format | Neither: a slow connection or a short live window |
| Decoder failed with a video format | `error_video_format_tv` / `_device` | This TV can't play this video format. | code · format | This hardware's decoders |
| Format unsupported or too much for the decoder (video) | `error_video_format_tv` / `_device` + `playback_try_other_quality` | This TV can't play this video format. A different quality may work. | code · format | This hardware |
| Decoder failed with an audio format | `error_audio_format_tv` / `_device` | This TV can't play this audio format. | code · format | This hardware |
| Decoder failed, format unknown or neither | `error_format_tv` / `_device` | This TV can't play this format. | code · format | This hardware |
| `OutOfMemoryError` anywhere in the cause chain | `error_out_of_memory_tv` / `_device` + `playback_try_other_quality` | This stream needs more memory than this TV has. A different quality may work. | code · format | This hardware: stream too big |
| `DecoderList` refuses the video track | `error_too_big_tv` / `_device` | This video is too big for this TV to play. | format | This hardware |
| No first frame within the limit | `error_no_first_frame_tv` / `_device` | This TV couldn't show this video. Try again, or pick something else. | format | This hardware, or a broken stream |
| Nothing played within the start-up limit (`PlaybackErrors.Stopped.START_UP_LIMIT`): 45 s from a stream being handed to the player, with a full 45 s again on coming back from another app; not for a recording still being written; no step down to another Quality, and no restart by itself (#463) | `error_timed_out` | Your provider stopped responding. It may be busy, so try again in a moment. | StartUpLimit | Provider's: took the connection and sent nothing |
| A live channel's stream ended (the provider closed the connection cleanly, or an HLS playlist gained `EXT-X-ENDLIST`) and ended again after two restarts by itself, 1.5 s apart (`PlaybackErrors.Stopped.STREAM_ENDED`). Playing again in between doesn't reset the count; picking any channel, the same one included, Try again, coming back from another app, or ten minutes between endings does (#472). Not for films, episodes or recordings, where the end is the end | `error_live_ended` | This channel stopped. Try again, or pick another channel. | StreamEnded | Provider's: another device took the connection, a session limit, or the channel went off air |
| Any other Media3 code | `error_anything_else` | This didn't play. Try again, or pick something else. | code · format | Unknown |
| `Playable.Failure.LINK_REFUSED` | `playback_link_refused` | Your provider didn't give a link for this channel. Try refreshing your provider in Settings. | LinkRefused | Provider's portal |
| `Playable.Failure.NO_CATCHUP` | `playback_no_catchup` | Your provider doesn't offer catch-up, so past programmes can't be played. | NoCatchup | Provider's: playlists and portals have no catch-up |
| `Playable.Failure.GONE` | `error_gone` | This isn't on your provider any more. Try refreshing your provider in Settings. | Gone | Provider's: removed from the catalogue |
| `Playable.Failure.BAD_ADDRESS`: a catch-up whose provider address no longer parses, so no archive URL can be built (#497) | `error_bad_address` | That address doesn't look right. Check your provider's details in Settings. | BadAddress | Viewer's setup |
| Recording: storage missing (`RecordingStorage.Unavailable`) | `rec_error_usb_no_folder`, `rec_error_usb_unavailable` | There's no folder to record to. Pick one on your USB drive in Recording settings. / Can't find your USB drive. Plug it back in, then pick its folder again in Recording settings. | StorageUnavailable | Viewer's: drive or folder |
| Recording: no free file name (`… (999).ts` taken too, #354) | `rec_error_failed` | This recording stopped. Try recording it again. | IOException | Recording folder full of one name |
| Recording: any other exception | `rec_error_failed` | This recording stopped. Try recording it again. | exception type | Unknown |
| Recording: its whole window passed while Settee wasn't running (`RecordingEngine.rearmScheduled`, on launch, after a reboot or after an update) | `rec_error_missed` | This was missed because Settee wasn't running while it was on. | Missed | Device's: off, unplugged or force-stopped |
| Recording: its alarm came after its window ended (`RecordingService`) | `rec_error_missed_late` | This was missed because Android started it after it had ended. | MissedLate | Device's: Android delayed the alarm, usually without the alarms permission |
| Recording: still in progress when Settee starts again (`RecordingRepository.failInterrupted`, on a cold start, #474) | `rec_error_interrupted` | This recording stopped because Settee was closed while it was on. | Interrupted | Device's: the process was killed by an update installed anyway, low memory or a power cut |
| Recording, Failed or Cut short: this device's storage full (`StreamCapture.Ending.StorageFull`: a write failing with ENOSPC; no reconnecting, #473) | `rec_error_full_tv` / `_device` | This TV ran out of room for recordings. Delete some you've watched to make space. | StorageFull | Viewer's: storage full |
| Recording, Failed or Cut short: the USB drive full (as above, writing to the USB drive) | `rec_error_full_usb` | Your USB drive ran out of room. Delete some recordings to make space. | StorageFull | Viewer's: drive full |
| Recording, Failed or Cut short: the NAS full (as above, the share failing a write with STATUS_DISK_FULL) | `rec_error_full_nas` | Your NAS ran out of room. Delete some recordings or other files on it to make space. | StorageFull | Viewer's: NAS full |
| Recording, Failed or Cut short: the USB drive gone (`StreamCapture.Ending.StorageLost`: any other failed write to the USB drive; no reconnecting) | `rec_error_usb_unavailable` | Can't find your USB drive. Plug it back in, then pick its folder again in Recording settings. | StorageUnavailable | Viewer's: drive unplugged |
| Recording, Failed or Cut short: the NAS unreachable (as above, writing to the NAS) | `rec_error_nas_unreachable` | Couldn't reach your NAS. Check it's on and connected to your network. | NasUnreachable | The NAS, or the network |
| Recording, Failed or Cut short: this device's storage failing a write for anything but room | `rec_error_failed` | This recording stopped. Try recording it again. | exception type | The device's storage |
| Recording, Failed or Cut short: refused, five connections in a row getting nothing and one of them refused with HTTP 401, 403, 404, 429, 451 or 5xx, the last such status counting (`StreamCapture.Ending.Refused`); worded as playback words the status (`PlaybackErrors.describeHttpStatus`) | `error_http_401`, `error_http_403`, `error_gone`, `error_http_429`, `error_http_451`, `error_http_5xx`, `error_anything_else` | As the HTTP rows above | HTTP n | As the HTTP rows above |
| Recording, Failed or Cut short: no link, the last of five connections getting nothing having had none to connect to (`StreamCapture.Ending.NoLink`) | As the `Playable.Failure` rows above | As the `Playable.Failure` rows above | LinkRefused, Gone | As the `Playable.Failure` rows above |
| Recording, Failed or Cut short: the channel stopped, five connections in a row answered and sent nothing (`StreamCapture.Ending.StreamStopped`), or its end came with nothing written; also one failed before reasons were stored | `rec_error_channel_stopped` | The channel stopped sending video, which is usually at your provider's end. Check it plays in Live TV. | ChannelStopped (none for one from before) | Provider's stream, usually |
| Recording, Failed: stopped by hand (or by Android stopping the service) before anything came; one stopped after something came is plain Recorded, with no reason | `rec_error_stopped_early` | This was stopped before anything was recorded. | Stopped | Viewer's: stopped it |
| Recordings: deleting a NAS recording failed (unreachable, wrong login, refused); the row stays | `rec_delete_failed_nas` | Couldn't delete this from your NAS. Check it's on, then try deleting it again. | exception type | The NAS, or the network |
| Recordings: deleting a USB or on-device recording failed; the row stays | `rec_delete_failed` | Couldn't delete this recording. Try deleting it again. | exception type; StorageUnavailable, with `rec_error_usb_unavailable`'s sentence, when the USB drive is gone | The drive |

The external player's toast can't hold `ErrorMessage`, so it shows the sentence with the detail on the
line under it.

### Before playing

A film's or show's page says so before Play is pressed, rather than as an error after (#181). These
aren't `ErrorMessage`s and have no detail line.

| Trigger | String key | Sentence | Whose end, likely cause |
|---|---|---|---|
| None of the title's Versions plays here (only 4K ones on a 1080p Stick) | `vod_cant_play_tv` / `_device` | This TV can't play 4K | This hardware: Play is off |
| Only Versions in another language play here | `vod_language_title`, `vod_language_body_tv` / `_device` | Only in German here. This TV can't play a version in your language. | This hardware, or the provider's shelves: Play asks first |

## Film and show pages

Drawn by `GoneDetail` (`ui/vod`) in place of the page, with Back highlighted (#495). A panel that's slow
with a title's details isn't an error: past `CatalogRepository.OPENING_LIMIT` the page draws from the
row as stored. A show with no episodes stored by then reads as a timeout in
[Show page episodes](#show-page-episodes).

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| A film or show page opened for a title no longer in the catalogue (`Opened.Gone`), from a Home row, the back stack or a restored app | `error_gone` | This isn't on your provider any more. Try refreshing your provider in Settings. | Gone | Provider's: a refresh removed it |

## Provider connection

A failed Catalogue Refresh, adding a provider (`Outcome.AddFailed`, also on the phone's channel
manager page) and the provider's Test button. `ErrorWording.ofProvider` words them, except a channel
list too big for memory, which is about this hardware and worded by `ErrorWording.ofHardware`.

Every refresh, whatever triggered it, saves each provider's channel-list failure on its row
(`Source.lastRefreshFailure`, as `ViewerError.stored`), and a later refresh that works clears it
(#464). Movies and shows failing don't count. A saved failure shows:

- under its provider's row in Settings › Providers, with Refresh again;
- on Home's banner, above the first shelf, for an enabled provider whose failure only the viewer can
  fix (`Problem.viewersToFix`): `LoginRejected`, `AccountInactive`, `BadAddress`, `NotAPanel`, HTTP 401 or 403. One provider shows its name
  and the sentence, several a count, never the detail line. Network drops, timeouts and 5xx don't
  raise it, since the next refresh usually clears them;
- in Live TV's empty state, titled `guide_load_failed_title_named` with the provider's name, when
  there are no channels at all.

Test connection on an M3U playlist fetches its address, with the provider's User-Agent, and reads only
its first 16 KB (#469): an HTTP or network failure is worded by the rows below, so a 404 is
`error_test_failed` with HTTP 404 on the detail line, and an answer that isn't a playlist is
`error_playlist_empty`. A test that passes says what it found, from `test_ok_*`, worded on the TV
(`TestOutcomeWording`) and sent to the phone's channel manager as the same sentence.

The refresh row in Settings › Providers reads Refreshing and then the result line, which points to
Providers (`refresh_done_with_provider_problems`), Guide settings (`refresh_done_with_problems`) or
both (`refresh_done_with_provider_and_guide_problems`), by what failed.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| `UnknownHostException`, `ConnectException`, `NoRouteToHostException` | `error_cant_reach_tv` / `_device` | Can't reach your provider. Check this TV is online and the address is right. | exception type | Viewer's network, or a wrong address |
| `InterruptedIOException` (timeouts) | `error_timed_out` | Your provider stopped responding. It may be busy, so try again in a moment. | exception type | Provider's |
| Xtream `auth: 0` (`Problem.LOGIN_REJECTED`), HTTP 401 or 403 | `error_login` | Your provider didn't accept your login. Check your provider's details in Settings. | LoginRejected, or HTTP 401/403 | Viewer's login |
| Xtream account not Active (`Problem.ACCOUNT_INACTIVE`) | `error_account_inactive` | Your provider says this account isn't active. Check with them, then try again. | AccountInactive | Provider's: expired or disabled |
| Not a valid address (`Problem.BAD_ADDRESS`) | `error_bad_address` | That address doesn't look right. Check your provider's details in Settings. | BadAddress | Viewer's setup |
| Xtream address answered with a web page, or 404 for `player_api.php` (`Problem.NOT_A_PANEL`): the provider's website, the wrong port, or a Wi-Fi sign-in page | `error_not_a_panel` | That address didn't answer like an IPTV provider. Check the address and port in your provider's details, or sign in to your Wi-Fi if it asks. | NotAPanel, or NotAPanel · HTTP 404 | Viewer's setup, or the network |
| Portal refused the MAC, or none set (`Problem.DEVICE_REFUSED`) | `error_device_refused` | Your provider didn't accept these details. Check your provider's details in Settings. | DeviceRefused | Viewer's setup, or the portal |
| HTTP 429 | `error_http_429` | Your provider is getting too many requests from this device. Wait a minute, then try again. | HTTP 429 | Provider's rate limit |
| HTTP 5xx | `error_http_5xx` | Your provider is having problems. That's at their end, so try again later. | HTTP 5xx | Provider's server |
| Provider answered with no channels | `error_no_channels` | Your provider sent no channels. Your account may not have any yet, so check with them. | NoChannels | Provider's: no package |
| Channel list too big for memory (`OutOfMemoryError` while syncing) | `error_channel_list_memory_tv` / `_device` | Your provider's channel list needs more memory than this TV has. | OutOfMemory | This hardware: list too big |
| Playlist with no channels | `error_playlist_empty` | There are no channels at that address. Check it's the link your provider gave you. | NoChannels | Viewer's setup |
| Test finding no `#EXTM3U` or `#EXTINF` at the start of the address's answer (`Problem.NOT_A_PLAYLIST`) | `error_playlist_empty` | There are no channels at that address. Check it's the link your provider gave you. | NotAPlaylist | Viewer's setup: a web page or the wrong link |
| Anything else while downloading | `error_catalogue` | Couldn't load your provider's channels. Try again in a moment. | exception type, or HTTP status | Unknown |
| Test button, anything else, including a playlist address answering 404 | `error_test_failed` | Couldn't connect to your provider with these details. Check them and try again. | exception type, or HTTP status | Unknown, or a wrong playlist link |
| The guide with no channels and no failure saved on a provider | `guide_load_failed_desc` | Try again in a moment, or check your provider's details in Settings. | none | Unknown |

## Show page episodes

A show's page asks each of its Versions' panels for `get_series_info` when it opens
(`VodViewModel.openShow`). When no Version has episodes stored afterwards, it says so in the
episodes' place, as an error only when every Version's fetch failed: one that answered, even with
nothing in it, means there's no error to show. Stored episodes always show instead of either. `ErrorWording.ofProvider` words
the failure of the opened Version, or of the first other one, so a timeout, a rejected login, a 429 or
a 5xx read as in [Provider connection](#provider-connection). Under it, Try again asks every panel again.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| Every fetch failed with nothing more specific (an empty or broken reply, any other HTTP status) | `vod_episodes_failed` | Couldn't load the episodes of this series. Try again in a moment. | exception type, or HTTP status | Provider's |
| No episodes stored once `CatalogRepository.OPENING_LIMIT` has passed, fetches still out (#495); episodes they store later replace it | `error_timed_out` | Your provider stopped responding. It may be busy, so try again in a moment. | SocketTimeout | Provider's: slow |
| A fetch answered, but no Version has episodes (a plain line, not an error, and no Try again) | `vod_no_episodes` | Your provider hasn't added any episodes of this series. | none | Provider's: none listed yet |

## Movies and Shows lists

An Xtream provider's movies and its shows are separate downloads, and either can fail while the other
arrives. Whatever fetched it (opening the tab, Setup's Movies or Shows step, a periodic or manual
refresh), a failed list is kept per provider in `CatalogueRefresh.State.vodFailures` until a later
download of that list works. When nothing of that kind is stored from any provider, the tab shows the
first enabled provider's failure under "Couldn't load your movies" (`vod_movies_load_failed`) or
"Couldn't load your shows" (`vod_shows_load_failed`), with Try again, which downloads again however
often it has this run, and Provider setup, which opens that provider's form. Stored titles always show
instead, with no error. `ErrorWording.ofProvider` words the failure, so a timeout, a rejected login, a
429 or a 5xx read as in [Provider connection](#provider-connection).

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| Movies' list or categories failed with nothing more specific (a reply that won't parse, any other HTTP status) | `error_movies_list` | Your provider's list of movies didn't come through. Try again in a moment. | exception type, or HTTP status | Provider's |
| Shows' list or categories failed with nothing more specific | `error_shows_list` | Your provider's list of shows didn't come through. Try again in a moment. | exception type, or HTTP status | Provider's |
| The list downloaded with nothing in it (a plain line, not an error, and no Try again) | `vod_no_movies_provider` / `vod_no_shows_provider` | Your provider hasn't sent any movies yet. | none | Provider's: none in the account |

## Guides

A guide feed's failure is stored on its row (`ViewerError.stored`) and shown in Guide settings, never
in Home's banner. A guide the viewer added has no provider behind it, so its sentences never say "your
provider"; the provider's own guide (`xmltv.php`) is worded as the provider is (see Providers above), with
`error_guide` where that table falls back.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| Own guide answers 404, 410, 401 or 403 | `error_not_a_guide` | That address isn't a guide. Check it's the right link, or sign in to your Wi-Fi if it asks. | HTTP status | The viewer's: a wrong or dead link |
| Any guide answers 200 with something other than XMLTV (a web page, plain text, JSON) | `error_not_a_guide` | (as above) | NotAGuide | The viewer's: an expired share link, a host's landing page, a Wi-Fi sign-in page |
| Own guide saved with an address that isn't a web address (before entry checked it) | `error_not_a_guide` | (as above) | GuideAddress | The viewer's: a mistyped address |
| Own guide answers any other status (429, 5xx, …) | `error_guide` | Couldn't download this guide. Try again in a moment. | HTTP status | The feed's server |
| Provider's guide answers 401/403, 429, 5xx | `error_login`, `error_http_429`, `error_http_5xx` | (as in Providers) | HTTP status | The provider's |
| Downloaded, no programmes | `error_guide_empty` | This guide downloaded, but had no programmes in it. Check it's the right link. | NoProgrammes | The feed's |
| Any other exception | `error_guide` | Couldn't download this guide. Try again in a moment. | exception type · HTTP status | The feed's server, or the network |

Guide settings' address entry adds `http://` to an address typed without a scheme, and won't save one
that still isn't a web address: the entry window stays open with `settings_guide_bad_address` ("That
isn't a web address. Check it's typed as the guide's link shows it, such as example.com/guide.xml.").

## Settings

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| Device sync: wrong code (HTTP 401) | `sync_wrong_code` | That code doesn't match the one on the other device. Check it and try again. | Refused · HTTP 401 | Viewer's typing |
| Device sync: anything else | `sync_receive_failed` | Couldn't get anything from the other device. Check it's still sharing, then try again. | exception type · HTTP status | The other device, or the network |
| Sharing: no network (`ServerProblem.NO_NETWORK`) | `sync_share_no_network_tv` / `_device` | This TV isn't on a network, so it can't share. Connect it, then try again. | none | Viewer's network |
| Sharing: socket wouldn't open | `sync_share_failed_tv` / `_device` | Couldn't start sharing on this TV. Try again in a moment. | exception type | This hardware |
| Sharing: timed out | `sync_share_timed_out` | Sharing stopped after waiting a while. Start again when you're ready. | none | Nobody connected |
| Sharing: too many wrong codes | `sync_share_too_many_codes` | Sharing stopped after too many wrong codes. Start again to get a new one. | none | The other device's typing |
| NAS sync: not set up | `sync_nas_needs_setup` | Set up your NAS under Settings → Recording first, then come back here. | none | Viewer's setup |
| NAS sync: any exception | `sync_nas_failed` | Couldn't reach your NAS. Check it's on and the details in Recording settings are right. | exception type | The NAS, or the network |
| Recording settings' Test connection (also the channel manager page's) | `recset_status_failed` | Couldn't connect to your NAS. Check it's on and these details are right. | exception type | The NAS, or its details |
| Pairing, the channel manager: no network, wouldn't start, timed out, too many codes | `pairing_*`, `manager_*` (`_no_network_tv`/`_device`, `_failed_tv`/`_device`, `_timed_out`, `_too_many_codes`) | As sharing, for pairing or the channel manager | exception type when one was behind it | As sharing |
| Phone's setup page opened from outside the TV's local network (HTTP 403, plain text) | `pairing_phone_local_only` | Only a phone on the same network as your TV can open this page. | none | Phone on mobile data, or another network |
| Phone's setup page: sent without an address | `pairing_phone_missing_address` | Add your provider's address, then send it again. | none | Viewer's typing |
| Phone's setup page: wrong code | `pairing_phone_wrong_code` (plurals) | That code doesn't match the one on your TV. 4 tries left. | none | Viewer's typing |
| Phone's setup page: fifth wrong code | `pairing_phone_too_many_title`, `pairing_phone_too_many_body` | Too many wrong codes. Start setting up from your phone again on the TV to get a new code. | none | Viewer's typing, or someone guessing |
| Phone's setup page: the provider's channels didn't load | As Provider connection | The TV's sentence, with its detail line under it | As Provider connection | As Provider connection |
| Update check couldn't reach a release (`Outcome.CouldNotCheck`) | `about_check_failed` | Couldn't check for updates. Try again later. | HTTP status, EmptyReply, NoApk, or exception type | GitHub, the network, or a release missing its APK |
| Update download failed | `update_failed_tv` / `_device` | Couldn't download the update. Check this TV is online, then try again. | exception type | The network |
| Diagnostics: the database wouldn't open or answer | `diagnostics_database_failed` | Settee couldn't read its database, so these numbers are missing. | exception type | This device's storage, or a damaged database |
| Diagnostics: one table couldn't be counted (the others still are) | `diagnostics_table_failed` | Couldn't count this table, so part of the database may be damaged. | exception type | A damaged table |
| Diagnostics: storage couldn't be measured | `diagnostics_storage_failed` | Settee couldn't measure its storage. | exception type | This device's storage |
| Diagnostics: memory couldn't be measured | `diagnostics_memory_failed` | Settee couldn't measure its memory. | exception type | This device |
| Diagnostics: the list of opens or errors couldn't be read | `diagnostics_log_failed` | Settee couldn't read what it noted down, so this list is missing. | exception type | A damaged file in `filesDir/diagnostics` |
| Diagnostics: saving a report with no NAS set up | `sync_nas_needs_setup` | Set up your NAS under Settings → Recording first, then come back here. | none | Viewer's setup |
| Diagnostics: saving a report to the NAS failed | `sync_nas_failed` | Couldn't reach your NAS. Check it's on and the details in Recording settings are right. | exception type | The NAS, or the network |

## Add-ons

Worded by `ErrorWording.ofAddon` from the `AddonFailure` `StremioClient` throws. In Settings › Add-ons,
under Add an add-on, when a pasted address couldn't be added; and in a film's Add-on sources picker,
with a Try again that asks the add-ons again, only when every add-on failed or TMDB didn't answer. When at least one answered,
the picker lists its streams, or says "No streams found from your add-ons for this title." (an empty
state, not an error: no detail line), and the failing ones stay quiet. So does a film TMDB answers for but can't match
to IMDb: that's "No streams found" too. An add-on that answers 404 for a film's streams has nothing for it, not a failure.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| No answer: unknown host, refused, no route (`AddonFailure.Unreachable`) | `error_addon_cant_reach_tv` / `_device` | Can't reach the add-on. Check this TV is online and the address is right. | exception type | Viewer's network, or a mistyped host |
| Timed out (`Unreachable` from a `SocketTimeoutException` or other `InterruptedIOException`) | `error_addon_timed_out` | The add-on stopped responding. It may be busy, so try again in a moment. | exception type | The add-on's server |
| A 5xx (`AddonFailure.Http`) | `error_addon_trouble` | The add-on is having problems. That's at its end, so try again later. | HTTP status | The add-on's server |
| Any other status but 2xx (a 404 for the manifest, a 401 or 403 from a personalised address), a web page or anything else that isn't JSON, or JSON with no `id` or `name` (`AddonFailure.NotAnAddon`) | `error_not_an_addon_tv` / `_device` | That address didn't give an add-on. Check it's the manifest.json link, and that this TV's internet doesn't need you to sign in first. | NotAnAddon · HTTP 404 (status when there was one) | The address or the key in it, or Wi-Fi asking to sign in |
| Add-on sources only: TMDB, which turns the film into an IMDb id, didn't answer, so no add-on was asked (`AddonFailure.NoLookup`) | `error_addon_lookup_tv` / `_device` | Couldn't look this film up to ask your add-ons. Check this TV is online, then try again. | exception type | Viewer's network, or TMDB |

## OpenSubtitles

Worded by `ErrorWording.ofOpenSubtitles`. In the film and episode players' OpenSubtitles results (searching and picking), the note an automatic download leaves over the picture, Settings › OpenSubtitles, Setup's OpenSubtitles step and the phone's sign-in page. An automatic download that fails never stops playback: it shows only as the note. In Settings › OpenSubtitles, a signed-in account whose allowance check fails shows the failure under Signed in as, in place of the downloads left; when it's `TokenRejected`, a Sign in again row follows, which opens the username window with the saved username filled in, then the password window.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| No results, or none in the language and kind an automatic download wants (`OpenSubtitlesFailure.NoResults`) | `error_opensubtitles_none` | OpenSubtitles has no subtitles for this yet. | NoResults | Nobody's: nothing uploaded for the title |
| No answer (`Unreachable` with no status) | `error_opensubtitles_unreachable_tv` / `_device` | Can't reach OpenSubtitles. Check this TV is online, then try again. | exception type | Viewer's network |
| Any other status, 401 and 406 aside (`Unreachable` with a status) | `error_opensubtitles_trouble` | OpenSubtitles is having problems. That's at their end, so try again later. | HTTP status | OpenSubtitles' server, or its rate limit (429) |
| Daily download limit, not signed in (HTTP 406) | `error_opensubtitles_limit_anonymous` | That's today's 5 free subtitles used. Sign in to OpenSubtitles in Settings for more. | DownloadLimit · HTTP 406 | The anonymous allowance for this address |
| Daily download limit, signed in (HTTP 406) | `error_opensubtitles_limit_account` (`_unknown` when no reset time came back) | That's today's OpenSubtitles downloads used. More from 00:00. (local time of `reset_time_utc`) | DownloadLimit · HTTP 406 | The account's own allowance |
| Wrong username or password (HTTP 401 from `/login`) | `error_opensubtitles_sign_in` | OpenSubtitles didn't accept that username or password. Check them and try again. | SignInRejected · HTTP 401 | Viewer's typing |
| Token expired or invalid, and signing in again with the saved password failed | `error_opensubtitles_token_tv` / `_device` | OpenSubtitles signed this TV out. Sign in again in Settings. | TokenRejected | A changed password, or a closed account |
| Settings › OpenSubtitles or Setup's step: phone server no network, wouldn't start, timed out | `pairing_*` (as pairing) | As pairing | exception type when one was behind it | As pairing |
