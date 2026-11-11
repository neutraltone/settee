# Settee's look

What Settee looks like and how it behaves on a TV. Build tickets point here, so nobody has to dig through closed decision tickets, and the prototypes never merge. The reasons behind each decision are on the tickets linked at the end.

Designed for a Fire TV Stick at ten feet with a remote, on a 960 x 540 dp screen. Dark only. Phones and tablets are kept possible, but get no design of their own.

Anything that moves has to pass the performance budget in [Motion and cost](#motion-and-cost).

## Feel

Warm and calm: a comfortable room that stays out of the way. Live TV, the guide and Home are quiet, dense and quick. Movies, Shows and details pages are big on artwork and space. Calm describes the look, never the pace, so no transition ever makes the viewer wait to press the next button.

Never a sideloaded IPTV box, a stretched phone app or a storefront.

## Press limits

No design may exceed these on a Daily route.

| Route | Limit | Tier |
|---|---|---|
| Pick up the first Continue Watching card | 1 | Daily |
| Favourite show to playing | 3 plus moves along the row | Daily |
| Next episode | 0, it plays on its own | Daily |
| Channel list during live playback | 1, or 2 if the bar is showing | Occasional |
| Home to the Live tab | About 3 | Occasional |

A Live Preview adds one Down to the Home routes. It must always be obvious where the Highlight is, and watch status must show on the cards the viewer chooses from.

## Voice

A calm, capable friend, writing for the viewer as "you". Technical terms appear only in setup, with a plain line explaining them. British English, sentence case, short sentences and contractions. Exclamation marks only when really warranted, and no em dashes.

- **Terms:** "provider" for the service, "EPG source" for guide data in setup only, "Movies", and "Season" for part of a series.
- **Errors:** a friendly sentence saying what happened and what to do next, and whose end it's on when known. No apologies or jokes. A small, quiet line under it always shows the code, status and format, and never a provider URL, username, password or MAC.
- **Empty states:** what will appear here, plus the one thing to do about it if there is one.
- **Wordplay:** one or two lines at most, on the welcome and setup screens only.
- **Settee itself:** no subject where the sentence works without one, then "Settee", and "we" only where it reads warmer. Never "I".

### Example wording

Check each against what the app can actually tell.

- **Welcome:** "Welcome to Settee. Let's get you settled in."
- **Add your provider:** "Settee plays the TV you already pay for. It doesn't supply channels, so start by adding your provider."
- **Privacy:** "Your provider details stay on this device. Settee has no accounts and no server of its own."
- **Home, empty:** "What you watch and favourite will show up here."
- **Recordings, empty:** "No recordings yet. Press Record while you're watching, or pick a programme in the guide."
- **Providers, empty:** "No providers yet. Add the service you already pay for to get started."
- **Category, empty:** "No channels in this category."
- **Can't connect:** "Can't reach your provider. Check this TV is online and the address is right." Phones say "this device".
- **Timed out:** "Your provider stopped responding. It may be busy, so try again in a moment."
- **Web page instead of video:** "Your provider sent a web page instead of video. The link may have expired, so try refreshing your provider in Settings."
- **Bad stream:** "This channel's video is damaged and won't play. That's at your provider's end, so try another channel."
- **Anything else:** "This didn't play. Try again, or pick something else."

## Identity

A settee that's also a TV: its back is the screen, with an old TV's aerials on top and a tiny, cute face on the screen. It always sits in its coral tile, in plum on coral, including on Settee's own dark background.

- **App icon:** the mark on coral, for rounded, round and square launchers. API 24-25 has no adaptive icons, so it needs a PNG fallback. On a Fire TV this is the tile people see: Fire TV Home draws a sideloaded app's icon on a grey tile and ignores its banner (checked on the Stick against TiviMate; Appstore apps such as Downloader get full tiles from Amazon's listing).
- **TV banner:** 320 x 180 on coral, the mark beside "Settee" in Young Serif. It must carry the name. It's a PNG, for the Android TV and Google TV launchers that show banners.
- **With the name on a dark screen:** the tile beside the name in cream.
- **Artwork:** `tools/launcher-art/mark.py` writes every copy of the mark from [the prototype's SVG](https://github.com/neutraltone/settee/blob/prototype/153-identity/prototype-screenshots/153-identity/settee-icon.svg), and "Settee" is drawn as shapes, not live text (`outline_wordmark.py`). Change the artwork there and run `tools/render-launcher-art.sh`.

## Tokens

Plain constants in `ui/design`.

### Colour

| Role | Value | Notes |
|---|---|---|
| Background | `#1A1416` | |
| Surface | `#281F22` | Text 14.2:1, quiet text 7.3:1 |
| Raised | `#362A2E` | Text 12.1:1, quiet text 6.3:1 |
| Rule | `#45373B` | Decoration only |
| Text | `#F8EFEA` | 16.0:1 on Background |
| Quiet text | `#BDABA8` | 8.3:1 on Background |
| Accent | `#FF8F6B` | 8.1:1 on Background |
| On accent | `#2A130B` | 7.9:1 on Accent |

Watched episodes step back by colour, never alpha. On-video colours for the players are set with the player tickets.

### Type

| Role | Face | Size |
|---|---|---|
| Display | Young Serif | 54 sp |
| Hero | Young Serif | 32 sp |
| Screen title | Young Serif | 28 sp |
| Section | Young Serif | 18 sp |
| Synopsis | Alegreya Sans Medium | 18 sp |
| Title on a card or row | Alegreya Sans Bold | 16 to 17 sp |
| Body and labels | Alegreya Sans Medium | 16 sp |

- **Size floor:** 16 sp for all text, checked by a test on the type tokens.
- **Figures:** lining, with `fontFeatureSettings = "lnum, tnum"`, wherever a number appears.
- **Shipping the fonts:** the exact Google Fonts files, Young Serif 3.003 and Alegreya Sans 2.004, unmodified, not subset and never turned into code. Young Serif on upstream `master` has been redrawn much thinner, so pin 3.003. Ship Young Serif Regular and Alegreya Sans Medium and Bold.
- **Licences:** rely on the fonts being an aggregate alongside the app, not on the OFL being compatible with the GPL. Ship each upstream `OFL.txt` in the APK and the repo with its version, source commit and hash, and add a row on the About screen.
- **Other languages:** Young Serif doesn't cover Russian, Ukrainian, Bulgarian, Greek or Vietnamese, so titles in those locales use Alegreya Sans Bold, chosen by locale. Arabic, Persian, Hindi, Thai, Chinese, Japanese and Korean stay on the system fallback.

### Shape and space

| Role | Value |
|---|---|
| Guide cells, sidebar rows | 2 dp corners |
| Episode thumbnails | 7 dp corners |
| Cards, episode rows, rail items | 10 dp corners |
| Buttons | Pill |
| Screen margin | 48 dp at the sides, 27 dp top and bottom |
| Gap between cards | 12 dp |
| Gap between rows | 14 dp |

## Highlight

Two treatments, because they suit different surfaces.

- **Cards and the Live Preview:** a 4 dp coral ring with a 2 dp dark edge inside it, plus a lift. The dark edge keeps the ring visible on warm, white and printed artwork.
- **Lift:** 1.05, or 1.03 on Home's large first card. Anything bigger cuts the ring off at the row's edge. Leave room for it in the row's padding.
- **Glow:** one, 14 dp, on the highlighted item only.
- **Progress on a highlighted card:** inset 6 dp, inside the dark edge, with the text moved up clear of it.
- **Guide cells, sidebar rows, rail items, buttons and episode rows:** a solid coral fill with dark text.
- **Text on artwork:** a gradient across the lower part of the card.

## Rail

Home, Live TV, Movies, Shows and Recordings, with Search at the top, and the Profile and Settings at the foot. Home is always where the app opens.

- **Closed:** icons only, 48 dp from the screen's edge with no panel behind them, on a 104 dp rail. The current tab stays marked.
- **Open:** when the rail has the Highlight, the panel appears with a label for every item, and pushes the content along. The icons stay where they were. It closes when the Highlight leaves.
- **Keys:** Left enters on the current tab, Right returns to the item left, OK on a tab moves the Highlight into it, and Back is unchanged.
- **Slides open and shut.** The panel slides while it draws, and the content beside it changes size only once, never on every frame. Animating the rail's width made the slide stutter on a Stick. Drawn this way, it ran at 8-12 ms a frame.
- **Already open when a screen appears with the Highlight on it,** however it got there. Only the viewer's own move into the rail slides it open.

## Home

From the top: Live Preview only when live TV was the Profile's last playback, Continue Watching, Recently added shows, Recently added movies, Favourite shows, Favourite movies, Favourite channels. No hero, and no Favourites item on the rail. Letting the viewer reorder the rows is for after 1.0 ([#212](https://github.com/neutraltone/settee/issues/212)).

- **Continue Watching:** landscape cards, 186 dp wide, with the first 300 dp wide when there's no Live Preview. Home opens on the first card. The picture is a frame from the title where the provider has one: the episode's own still, else the show's wide backdrop; a movie's wide backdrop, else its cover. The title keeps to one line, cut off at rest, and scrolls while the card has the Highlight.
- **Recently Added:** two rows, shows then movies, each the 20 the providers added most recently, newest first. A title the provider never dated is left out. Release dates aren't used because providers fill them unreliably, and the rows aren't called New, so they can't be confused with a New Episode.
- **Covers:** Recently Added and Favourites use portrait covers, 118 dp wide, with the title on a dark fade at the foot of the art, and the title's Quality in the top corner when its name has one. On Recently Added, a quiet status says where the Profile is: `S4 E3` over `22 min left`, `22 min left`, `S4 E4` (Up Next), `New episode` or `Watched`, with a thin progress bar along the bottom edge for anything part-watched. An unstarted title shows only its name.
- **Favourites:** two rows, shows then movies, each the most recently favourited first. A Favourite shows only its title: where the Profile is with it is Continue Watching's job. A show opens its series page, and a movie its movie page.
- **Favourite channels:** their own row of 118 dp square tiles, the same width as the covers and square like Live TV's logos. The logo is fitted with a margin on a plain surface, with no text over it, since logos are busy and often white. The channel's name and what's on now sit underneath, and the second line is left out when the guide has nothing. Channels aren't mixed into Favourites, so they add no moves on the way to a favourite show.
- **Live Preview:** one selectable block, with one Down to the first Continue Watching card. It takes about 140 dp, which pushes the rows below further down the screen, so check this.
- **Moving between rows:** Up and Down always leave the row, and coming into a row land on the card the viewer was last on there, or on its first card if they haven't been there yet. It never goes by position, which took Down from the large first card to the second Favourite.

## Shows and Movies

A backdrop with the highlighted title's name, status and synopsis over it, then rows of portrait covers.

- **Opens:** on the first cover of the first row, which is where OK on the tab in the rail lands. There's no search bar, because Search is on the rail.
- **Backdrop:** changes once the Highlight has rested on a title for about 400 ms, not on each press, so moving quickly along a row doesn't load a backdrop for every title. Changing on each press cost a few frames on a Stick. Loading the new backdrop still costs something, and can land on the next press, so measure it again when it's built, including a press just after it changes.
- **Gradient:** must move with the text. With the sidebar open, text today lands on the bright part of the backdrop and drops as low as 1.5:1, so this is a required fix.
- **Rows:** "For you" keeps the tab's rows. There's no Popular row, because the app has no popularity data.
- **Grids:** any other category opens its whole grid, with the same covers as the rows. A grid reads only what its cards show, not each title's full details, so a category of thousands opens quickly and stays light on a 1 GB Stick.
- **No blank covers while scrolling:** covers are fetched about four rows ahead in the direction the viewer is moving, so a normal pace of scrolling never shows a blank card. Holding Down as fast as the remote repeats may still outrun it.
- **Measure** the largest category on a real provider on a Stick: frame times while scrolling, and memory. If it misses the budget, stop before merging and decide with the numbers whether to page from the database now or in a follow-up. Don't fall back to not animating.

## Categories Sidebar

On Live TV, Movies and Shows. Tucked away until Left from the leftmost item opens it on the current category, so it adds no press on the way to a card. It slides open and pushes the content (on the guide, it may slide over it instead: see Sliding), and Right closes it.

- **Rows:** For you first, which is the tab's rows and where the sidebar opens when no category is picked, then the provider's categories in their own order. Categories hidden on this device are left out.
- **Picking a category** leaves the Highlight on it. Right then moves into the content and closes the sidebar.
- **Sliding:** each tab has to pass the budget when it's built. Movies and Shows are built like Home, so a push should pass. Today's guide can't: moving it costs about 25 ms a frame. If the rebuilt guide still can't, the sidebar slides over the guide there instead of pushing it.
- **Size:** 214 dp, with solid coral rows when highlighted and the current category in coral otherwise.
- **Find a category:** a text field at the top. Up from the first row reaches it, and Down returns to the list. Like every text box, the keyboard stays shut until OK. Typing a few letters narrows the list, and it shows how many of how many are showing, counting only categories the viewer can see.
- **To fix:** Right lands on the nearest row, not the one the viewer left.

## Details

A hero with a full backdrop, title, status, synopsis, Resume and Favourite.

- **A film's page** is the same hero, with a credits line of the first three billed and the director, and More like this beneath it. The row's title and the tops of its covers show under the hero, saying there's more. Down reaches the row and scrolls the hero up; Up brings the whole hero back. While the row is being picked it shows as For you's placeholder row, and the hero leaves its room whether or not any films turn up, so nothing moves. The row is films sharing the film's genres: its own provider's first, then those sharing the most genres, then by For you's score, where a rating of 9.5 or more counts as average and a recent addition gets a lift. A film with no genre gets others from its category.

- **Opens on Resume.** One Down reaches the episode in progress. The list opens already scrolled to that episode, so the Down moves the Highlight onto it without the list jumping.
- **Season pills:** a row under the hero. Up from the episodes reaches them, and Down from the buttons passes them. Moving the Highlight onto a pill selects that season at once, with no OK. The row scrolls sideways to keep the highlighted or selected pill in view, with a cut-off pill at the edge showing there are more, and follows when Down carries on into another season. Options on a pill marks the season watched or unwatched.
- **Specials:** Season 0 is labelled "Specials" and comes after the last season.
- **One season:** a show with only one season has no pill row, and Up from its first episode reaches the buttons.
- **Episodes:** a vertical list of one season at a time, with the thumbnail on the left, showing status. Going into the list moves the hero away in one step, leaving the title and the pills, and going back up past the pills returns it. Down from a season's last episode carries on to the next season's first, which shows under the last faded out downwards, out of the Highlight's reach, so the list says where Down goes; Up from a first episode never goes back a season, which is Up to the pills, Left, then Down.
- **Back** from anywhere in the episodes moves the Highlight to the selected season's pill. Back from the pills or the hero leaves the page.
- **Season, never "series"** for part of a show. That includes "2 seasons" on cards.
- **Artwork fades in** when it loads, rather than popping in, as long as that passes the budget.

## Player

Movies, episodes and live TV share one layout.

- **First press:** the title and one row: a pause or play icon, the seek bar, then Subtitles and Audio as icon buttons. A close button sits at the top right. The Highlight starts on the seek bar.
- **On the seek bar:** Left and Right move through the video, and OK plays and pauses. They can't also move along the row, so the play icon only shows the state, and is a target for touch.
- **Down** moves the Highlight to Subtitles and brings up the less-used buttons as pills. Right reaches Audio, and Down again reaches the pills. **Up** reaches close.
- **Pills:** on movies and episodes, Mark as watched, Sleep timer and Options. On live TV, Record, Quality, Aspect ratio and Sleep timer. Check these against today's options.
- **Subtitles and audio:** a panel on the right, with the current choice ticked.
- **Up Next:** a card in the bottom right with a still, the episode, a countdown, Play now and Watch credits.
- **Still watching:** centred over a dimmed picture, with Keep watching and Stop.

This focus design has never been tried with a remote, so try it first and change it if it doesn't work.

## Live TV

- **Changing channel:** a small card in the corner with the logo, channel, programme, progress and a line for what's next. It can't take the Highlight, so the remote carries on as it would with the picture alone.
- **Channel list:** a left panel with the last channel pinned at the top, and rows with the logo, name and what's on.
- **Guide:** ruled and square, with the times in a header above a strong rule. The current programme is bold, and later ones are quiet. Left from the first column opens the Categories Sidebar.

Up and Down still zap, with no number entry.

## Settings

Two panes: sections on the left, their settings beside them, so nothing has to be opened to see what's inside.

Sections are Providers, TV guide, Channels, Display and playback, Recording, Parental lock, Your devices, Add-ons, Manage on phone or laptop, and About. There's no Light theme.

## Onboarding

One page: the welcome and the choice of provider together.

- **Set up from your phone comes first,** where the Highlight starts. The three ways of typing it on the TV sit under it.
- **The QR screen** keeps today's flow: the code, six digits, and an address for when the camera won't scan.
- **The page on the phone** isn't designed yet. Give it the same colours and type.

## Recordings

One list, with Coming up, Recorded and Series links as headings. Recordings have a channel logo but no artwork, so they're rows, not cards.

## Motion and cost

Set from measurements on a Fire TV Stick (`AFTSS`) in [#156](https://github.com/neutraltone/settee/issues/156). The Stick is the device the budget is set on. Slower Android TV boxes aren't a target.

- **While anything moves,** its frames take under 16.7 ms, so 60 fps, on a release build.
- **The first two frames after a press** may each take up to 50 ms: that's where the app reacts, such as the Highlight moving or a panel's labels appearing.
- **One frame where the layout changes size** may also take up to 50 ms, such as the content resizing once at the end of the rail's slide.
- **How to measure a movement:** time it frame by frame with `dumpsys gfxinfo <package> framestats`, one movement at a time, from the press until the last frame. Leave out the first two frames and the one resize frame, and the rest must all be under 16.7 ms. The app-wide summary in [PERFORMANCE.md](PERFORMANCE.md) mixes in press responses and idle frames, so it isn't the test. The rail passes this way: its slide frames took 8-12 ms.
- **A new screen's first frame** comes within 300 ms.
- **Anything that can't pass doesn't animate.** It goes straight to where it ends up. There's no slower animation as a middle ground.
- **No limit on how many things move at once.** The budget applies to the whole screen while they move.
- **Screen changes** dip through the background in about 300 ms: the old screen darkens, the screen swaps, and the new one comes up. Only one screen is drawn at a time, so it should cost about what drawing that screen does, but it hasn't been measured: measure it when it's built. Today's 700 ms crossfade drew both screens see-through and cost 21-45 ms a frame on the way back to Home.
- **Presses during a dip go through.** They act on the new screen as soon as it's there, and are never dropped, so the dip doesn't make the viewer wait.
- **Every screen draws a solid background,** so nothing behind it shows through. The one exception is where video plays (the players, the Live Preview): the picture sits behind the screen, so that area stays clear.
- **Never:** blur, alpha-dimmed shelves, more than one glow, or two screens drawn see-through at once.
- **Images:** decoded at tile size.
- **The gradient on every card** costs nothing measurable.

## Known risks

- **Printed titles:** real covers often carry their own title, which runs into ours. Nothing here fixes it yet, so try it with real artwork when `ArtCard` is built.
- **Rows at the edge:** rows run to the screen's edge, so partly hidden text sits outside the margin.
- **Colour:** everything so far was judged on a monitor. Check colour and legibility on a real TV.
- **Back to Home is slow:** rebuilding Home takes 330-510 ms on a Stick, over the 300 ms budget for a new screen.

## What done means

Every screen ticket is done when:

- It's fully usable with a remote, with focus tests for the routes that have press limits.
- It has a screenshot of the screen with its first Highlight, plus any existing states.
- Its copy follows [Voice](#voice).
- Its old code is gone, along with its entries in the detekt baseline.
- It has been tried on a Stick before merging.

## Decisions and prototypes

| Decision | Prototype |
|---|---|
| [What Compose for TV gives over Material 3](https://github.com/neutraltone/settee/issues/85) | |
| [What Fire TV requires](https://github.com/neutraltone/settee/issues/86) | |
| [What the viewer relies on](https://github.com/neutraltone/settee/issues/87) | |
| [What Settee should feel like](https://github.com/neutraltone/settee/issues/88) | |
| [The visual direction](https://github.com/neutraltone/settee/issues/89) | [`prototype/89-directions`](https://github.com/neutraltone/settee/tree/prototype/89-directions) |
| [The left-hand navigation](https://github.com/neutraltone/settee/issues/90) | [`prototype/90-left-rail`](https://github.com/neutraltone/settee/tree/prototype/90-left-rail) |
| [How Settee talks](https://github.com/neutraltone/settee/issues/145) | |
| [How the design system is built](https://github.com/neutraltone/settee/issues/147) | |
| [The typefaces](https://github.com/neutraltone/settee/issues/150) | [`research/150-typefaces`](https://github.com/neutraltone/settee/tree/research/150-typefaces) |
| [The highlight and tokens](https://github.com/neutraltone/settee/issues/151) | [`prototype/151-highlight`](https://github.com/neutraltone/settee/tree/prototype/151-highlight) |
| [The remaining screens](https://github.com/neutraltone/settee/issues/152) | [`prototype/152-remaining`](https://github.com/neutraltone/settee/tree/prototype/152-remaining) |
| [The logo](https://github.com/neutraltone/settee/issues/153) | [`prototype/153-identity`](https://github.com/neutraltone/settee/tree/prototype/153-identity) |
| [How the build splits](https://github.com/neutraltone/settee/issues/155) | |
| [The performance budget](https://github.com/neutraltone/settee/issues/156) | [`prototype/90-left-rail`](https://github.com/neutraltone/settee/tree/prototype/90-left-rail) |
