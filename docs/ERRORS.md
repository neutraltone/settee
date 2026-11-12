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

## Playback

Movies and Shows' error panel, the live player, the Home Live Preview, the guide's preview, and a
recording that can't start. Strings are in `res/values/strings.xml`.

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
| `ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE` | `error_web_page` | Your provider sent a web page instead of video. The link may have expired, so try refreshing your provider in Settings. | code · format | Provider's: expired link or captive page |
| `ERROR_CODE_PARSING_CONTAINER_MALFORMED`, `ERROR_CODE_PARSING_MANIFEST_MALFORMED` | `error_damaged` | This video is damaged and won't play. That's at your provider's end, so try something else. | code · format | Provider's: broken stream |
| `ERROR_CODE_BEHIND_LIVE_WINDOW` | `error_behind_live` | This channel fell too far behind live. Try again to catch up. | code · format | Neither: a slow connection or a short live window |
| Decoder failed with a video format | `error_video_format_tv` / `_device` | This TV can't play this video format. | code · format | This hardware's decoders |
| Format unsupported or too much for the decoder (video) | `error_video_format_tv` / `_device` + `playback_try_other_quality` | This TV can't play this video format. A different quality may work. | code · format | This hardware |
| Decoder failed with an audio format | `error_audio_format_tv` / `_device` | This TV can't play this audio format. | code · format | This hardware |
| Decoder failed, format unknown or neither | `error_format_tv` / `_device` | This TV can't play this format. | code · format | This hardware |
| `OutOfMemoryError` anywhere in the cause chain | `error_out_of_memory_tv` / `_device` + `playback_try_other_quality` | This stream needs more memory than this TV has. A different quality may work. | code · format | This hardware: stream too big |
| `DecoderList` refuses the video track | `error_too_big_tv` / `_device` | This video is too big for this TV to play. | format | This hardware |
| No first frame within the limit | `error_no_first_frame_tv` / `_device` | This TV couldn't show this video. Try again, or pick something else. | format | This hardware, or a broken stream |
| Any other Media3 code | `error_anything_else` | This didn't play. Try again, or pick something else. | code · format | Unknown |
| `Playable.Failure.LINK_REFUSED` | `playback_link_refused` | Your provider didn't give a link for this channel. Try refreshing your provider in Settings. | LinkRefused | Provider's portal |
| `Playable.Failure.NO_CATCHUP` | `playback_no_catchup` | Your provider doesn't offer catch-up, so past programmes can't be played. | NoCatchup | Provider's: playlists and portals have no catch-up |
| `Playable.Failure.GONE` | `error_gone` | This isn't on your provider any more. Try refreshing your provider in Settings. | Gone | Provider's: removed from the catalogue |
| Recording: storage missing (`RecordingStorage.Unavailable`) | `rec_error_usb_no_folder`, `rec_error_usb_unavailable` | There's no folder to record to. Pick one on your USB drive in Recording settings. / Can't find your USB drive. Plug it back in, then pick its folder again in Recording settings. | StorageUnavailable | Viewer's: drive or folder |
| Recording: any other exception | `rec_error_failed` | This recording stopped. Try recording it again. | exception type | Unknown |
| Recording: nothing came | `rec_error_nothing` | Nothing was recorded. The channel may have stopped, so try recording it again. | none | Provider's stream, usually |

The external player's toast can't hold `ErrorMessage`, so it shows the sentence with the detail on the
line under it.

## Provider connection

A failed Catalogue Refresh as the guide shows it (`Outcome.Refreshed.failure`), adding a provider
(`Outcome.AddFailed`, also on the phone's channel manager page) and the provider's Test button.
`ErrorWording.ofProvider` words them.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| `UnknownHostException`, `ConnectException`, `NoRouteToHostException` | `error_cant_reach_tv` / `_device` | Can't reach your provider. Check this TV is online and the address is right. | exception type | Viewer's network, or a wrong address |
| `InterruptedIOException` (timeouts) | `error_timed_out` | Your provider stopped responding. It may be busy, so try again in a moment. | exception type | Provider's |
| Xtream `auth: 0` (`Problem.LOGIN_REJECTED`), HTTP 401 or 403 | `error_login` | Your provider didn't accept your login. Check your provider's details in Settings. | LoginRejected, or HTTP 401/403 | Viewer's login |
| Xtream account not Active (`Problem.ACCOUNT_INACTIVE`) | `error_account_inactive` | Your provider says this account isn't active. Check with them, then try again. | AccountInactive | Provider's: expired or disabled |
| Not a valid address (`Problem.BAD_ADDRESS`) | `error_bad_address` | That address doesn't look right. Check your provider's details in Settings. | BadAddress | Viewer's setup |
| Portal refused the MAC, or none set (`Problem.DEVICE_REFUSED`) | `error_device_refused` | Your provider didn't accept these details. Check your provider's details in Settings. | DeviceRefused | Viewer's setup, or the portal |
| HTTP 429 | `error_http_429` | Your provider is getting too many requests from this device. Wait a minute, then try again. | HTTP 429 | Provider's rate limit |
| HTTP 5xx | `error_http_5xx` | Your provider is having problems. That's at their end, so try again later. | HTTP 5xx | Provider's server |
| Provider answered with no channels | `error_no_channels` | Your provider sent no channels. Your account may not have any yet, so check with them. | NoChannels | Provider's: no package |
| Playlist with no channels | `error_playlist_empty` | There are no channels at that address. Check it's the link your provider gave you. | NoChannels | Viewer's setup |
| Anything else while downloading | `error_catalogue` | Couldn't load your provider's channels. Try again in a moment. | exception type, or HTTP status | Unknown |
| Test button, anything else | `error_test_failed` | Couldn't connect to your provider with these details. Check them and try again. | exception type | Unknown |
| The guide with no channels and no failure known this session | `guide_load_failed_desc` | Try again in a moment, or check your provider's details in Settings. | none | Unknown |

## Guides

A guide feed's failure is stored on its row (`ViewerError.stored`) and shown in Guide settings.

| Trigger | String key | Sentence | Detail | Whose end, likely cause |
|---|---|---|---|---|
| Downloaded, no programmes | `error_guide_empty` | This guide downloaded, but had no programmes in it. Check it's the right link. | NoProgrammes | The feed's |
| Any exception | `error_guide` | Couldn't download this guide. Try again in a moment. | exception type · HTTP status | The feed's server, or the network |

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
| Update check couldn't reach a release (`Outcome.CouldNotCheck`) | `about_check_failed` | Couldn't check for updates. Try again later. | HTTP status, EmptyReply, NoApk, or exception type | GitHub, the network, or a release missing its APK |
| Update download failed | `update_failed_tv` / `_device` | Couldn't download the update. Check this TV is online, then try again. | exception type | The network |
| TMDB Key turned down (TMDB answers HTTP 401), in Settings › TMDB, the Onboarding TMDB step and on the phone | `error_tmdb_key_rejected` | TMDB didn't accept that key. Check you copied the whole API Key, then try again. | KeyRejected · HTTP 401 | Viewer's: a mistyped or partly copied key |
| TMDB Key check got no answer (`TmdbCheck.Unreachable` with no status) | `error_tmdb_unreachable_tv` / `_device` | Can't reach TMDB. Check this TV is online, then try again. | exception type | Viewer's network |
| TMDB Key check answered with any other status | `error_tmdb_trouble` | TMDB is having problems. That's at their end, so try again later. | HTTP status | TMDB's server |
| Settings › TMDB or the Onboarding TMDB step: phone server no network, wouldn't start, timed out | `pairing_*` (as pairing) | As pairing | exception type when one was behind it | As pairing |
