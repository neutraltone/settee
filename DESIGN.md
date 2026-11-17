---
name: Settee
description: A calm, comfortable IPTV player for a Fire TV Stick, seen from the sofa at ten feet.
colors:
  aubergine-dusk: "#1A1416"
  upholstery: "#281F22"
  cushion: "#362A2E"
  picture-rail: "#45373B"
  cream: "#F8EFEA"
  mushroom: "#BDABA8"
  coral-lamp: "#FF8F6B"
  plum-ink: "#2A130B"
  tally-red: "#FF5A5F"
typography:
  display:
    fontFamily: "Young Serif"
    fontSize: "54sp"
    fontWeight: 400
    lineHeight: "62sp"
    fontFeature: "lnum, tnum"
  hero:
    fontFamily: "Young Serif"
    fontSize: "32sp"
    fontWeight: 400
    lineHeight: "39sp"
    fontFeature: "lnum, tnum"
  screen-title:
    fontFamily: "Young Serif"
    fontSize: "28sp"
    fontWeight: 400
    lineHeight: "34sp"
    fontFeature: "lnum, tnum"
  section:
    fontFamily: "Young Serif"
    fontSize: "18sp"
    fontWeight: 400
    lineHeight: "23sp"
    fontFeature: "lnum, tnum"
  synopsis:
    fontFamily: "Alegreya Sans"
    fontSize: "18sp"
    fontWeight: 500
    lineHeight: "23sp"
    fontFeature: "lnum, tnum"
  row-title:
    fontFamily: "Alegreya Sans"
    fontSize: "17sp"
    fontWeight: 700
    lineHeight: "21sp"
    fontFeature: "lnum, tnum"
  card-title:
    fontFamily: "Alegreya Sans"
    fontSize: "16sp"
    fontWeight: 700
    lineHeight: "19sp"
    fontFeature: "lnum, tnum"
  body:
    fontFamily: "Alegreya Sans"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "21sp"
    fontFeature: "lnum, tnum"
rounded:
  cell: "2dp"
  thumbnail: "7dp"
  card: "10dp"
  pill: "9999dp"
spacing:
  card-gap: "12dp"
  row-gap: "14dp"
  screen-top-bottom: "27dp"
  screen-side: "48dp"
components:
  button:
    backgroundColor: "{colors.cushion}"
    textColor: "{colors.cream}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: "10dp 22dp"
  button-highlighted:
    backgroundColor: "{colors.coral-lamp}"
    textColor: "{colors.plum-ink}"
  button-disabled:
    backgroundColor: "{colors.cushion}"
    textColor: "{colors.mushroom}"
  pill:
    backgroundColor: "{colors.upholstery}"
    textColor: "{colors.mushroom}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: "8dp 20dp"
  pill-selected:
    backgroundColor: "{colors.cushion}"
    textColor: "{colors.coral-lamp}"
  pill-highlighted:
    backgroundColor: "{colors.coral-lamp}"
    textColor: "{colors.plum-ink}"
  list-row:
    backgroundColor: "{colors.upholstery}"
    textColor: "{colors.cream}"
    typography: "{typography.row-title}"
    rounded: "{rounded.card}"
    padding: "8dp 10dp"
    height: "52dp"
  list-row-highlighted:
    backgroundColor: "{colors.coral-lamp}"
    textColor: "{colors.plum-ink}"
  guide-cell-highlighted:
    backgroundColor: "{colors.coral-lamp}"
    textColor: "{colors.plum-ink}"
    rounded: "{rounded.cell}"
  art-card-portrait:
    backgroundColor: "{colors.cushion}"
    textColor: "{colors.cream}"
    typography: "{typography.card-title}"
    rounded: "{rounded.card}"
    width: "118dp"
    height: "177dp"
  art-card-landscape:
    backgroundColor: "{colors.cushion}"
    textColor: "{colors.cream}"
    typography: "{typography.card-title}"
    rounded: "{rounded.card}"
    width: "186dp"
    height: "105dp"
  art-card-large:
    backgroundColor: "{colors.cushion}"
    textColor: "{colors.cream}"
    typography: "{typography.card-title}"
    rounded: "{rounded.card}"
    width: "300dp"
    height: "169dp"
  side-panel:
    backgroundColor: "{colors.upholstery}"
    textColor: "{colors.cream}"
    padding: "27dp 24dp"
    width: "400dp"
  text-field:
    backgroundColor: "{colors.upholstery}"
    textColor: "{colors.cream}"
    typography: "{typography.body}"
    rounded: "{rounded.card}"
    padding: "4dp 16dp"
---

# Design System: Settee

`docs/LOOK.md` and the code in `app/src/main/java/app/opentv/ui/design` are the authority. This file
sums them up for anyone designing a new screen. If it disagrees with either, they win, and this file
needs refreshing with `/impeccable document`. Units are Android's: dp for size, sp for type, designed
on a 960 x 540 dp screen.

## Overview

**Creative North Star: "The Front Room"**

Settee is the British front room at night: the good settee, the lamps low, the telly on. The room is
dark and warm, nearly black with plum in it, so the picture and the artwork are the brightest things in
it. One coral light, the Highlight, always shows where the remote is. Everything else steps back. The
type is a soft, old-fashioned serif for names and a friendly humanist sans for everything you read,
both big enough to read from across the room.

There are two densities in the same room. Live TV, the guide and Home are quiet, dense and quick: rows,
rules and small cards, nothing ornamental. Movies, Shows and details pages are where the room opens up:
big backdrops, portrait covers, space around the title. Calm is how it looks, never how fast it goes.
No transition makes the viewer wait to press the next button, and anything that can't hold 60 fps on a
Fire TV Stick doesn't move at all.

It must never look like a sideloaded IPTV box, a stretched phone app or a storefront: no neon, no grid
of glossy tiles, no promotional hero, no badges shouting for attention.

**Key Characteristics:**
- Always dark, one set of colours, no light theme.
- One accent, coral, which means "the remote is here", with a red kept only for recording.
- Flat, solid surfaces layered by tone. No blur, no see-through shades, at most one glow.
- Young Serif for names and headings, Alegreya Sans for reading, nothing under 16 sp.
- Every question opens in a Side Panel down the right, never a dialog in the middle.
- Two Highlight treatments: a ring and lift on artwork, a solid coral fill on everything list-like.
  One exception: the player's previous, play or pause and next are bare icons that turn coral.

## Colors

A warm aubergine dark, lit by a single coral lamp.

### Primary
- **Coral Lamp** (coral-lamp): the Highlight, and only the Highlight or things that mean "chosen":
  the solid fill of a highlighted row, button, guide cell or pill, the ring round a highlighted card,
  the current category's text, a selected season, progress bars and switches that are on. It's 8.1:1
  on Aubergine Dusk.
- **Plum Ink** (plum-ink): text and icons on Coral Lamp, 7.9:1. It's also the plum of the mark, drawn
  on its coral tile.

### Secondary
- **Tally Red** (tally-red): something is being captured right now, and nothing else. A row in progress
  shows a red dot and the word "Recording"; the dot turns Plum Ink while the row has the Highlight.

### Neutral
- **Aubergine Dusk** (aubergine-dusk): the background of every screen, drawn solid. Also the dark edge
  inside the card ring, and the fade under titles on artwork.
- **Upholstery** (upholstery): the Side Panel, Settings rows at rest, unselected pills, text fields.
  Text on it is 14.2:1, quiet text 7.3:1.
- **Cushion** (cushion): one step up. Buttons at rest, the selected pill, the current rail item, and
  the fill behind artwork that hasn't loaded. Text 12.1:1, quiet text 6.3:1.
- **Picture Rail** (picture-rail): guide rules and a text field's resting border. Decoration only: too
  faint to carry text or the Highlight.
- **Cream** (cream): text, 16.0:1 on Aubergine Dusk. Also the aerial TV drawn while waiting.
- **Mushroom** (mushroom): quiet text, such as status lines, later programmes, values, notes and error
  detail lines. 8.3:1 on Aubergine Dusk.

### Named Rules
**The One Lamp Rule.** Coral marks where the remote is, or what's chosen. Never use it to decorate,
for a badge, or to draw the eye to something the viewer can't press. On a TV, coral reads as "press
me".

**The Tally Light Rule.** Tally Red means recording now. Failures, series links and clashes are never
red.

**The Solid Colour Rule.** Things step back by colour, never by opacity. Watched episodes use Mushroom,
not see-through Cream, and shelves are never dimmed. The only see-through colours are the fades and
dark pills that keep text readable over artwork.

## Typography

**Display font:** Young Serif 3.003, Regular only (falls back to Alegreya Sans Bold for Russian,
Ukrainian, Bulgarian, Greek and Vietnamese)
**Body font:** Alegreya Sans 2.004, Medium and Bold

**Character:** Young Serif is soft and slightly old-fashioned, like a book spine on the shelf by the
settee. Alegreya Sans is humanist and warm, and stays easy to read at 16 sp from across the room.
Both ship unmodified from Google Fonts, under the OFL.

### Hierarchy
- **Display** (Young Serif, 54 sp / 62): the biggest moments, such as "You're all set."
- **Hero** (Young Serif, 32 sp / 39): a film's or show's title over its backdrop.
- **Screen title** (Young Serif, 28 sp / 34): the name of a screen or a Settings pane.
- **Section** (Young Serif, 18 sp / 23): row headings such as "Continue watching" and panel titles.
- **Synopsis** (Alegreya Sans Medium, 18 sp / 23): what a programme or film is about.
- **Row title** (Alegreya Sans Bold, 17 sp / 21): the title on a list row: an episode, a channel, a
  setting, a recording.
- **Card title** (Alegreya Sans Bold, 16 sp / 19): the title on artwork, where there's less room.
- **Body** (Alegreya Sans Medium, 16 sp / 21): labels, values, notes, status lines, buttons.

### Named Rules
**The Sofa Floor Rule.** Nothing is smaller than 16 sp. A test on the type tokens checks it.

**The Lined-Up Figures Rule.** Every role draws lining, tabular figures (`lnum, tnum`), so times, counts
and episode numbers line up down the guide and the lists.

**The One Weight Rule.** Young Serif only has Regular. Never ask it for bold; hierarchy in headings
comes from size.

## Layout

The screen is 960 x 540 dp, inside a 48 dp margin at the sides and 27 dp at the top and bottom, clear
of a TV's overscan. A 104 dp rail on the left holds the icons for Search, Home, Live TV, Movies, Shows
and Recordings, with Profile and Settings at the foot. While the rail has the Highlight, it slides open
with labels and pushes the content along.

Content is rows. Cards sit 12 dp apart in a row, and rows sit 14 dp apart. Rows run to the screen's
right edge, so the cut-off card says there's more. Rows leave room for the lift and ring, so a
highlighted card is never clipped. Settings uses two panes, sections on the left and their rows beside
them. The Categories Sidebar (214 dp) and the Side Panel (400 dp) slide in from the left and right.

Phones and tablets have to keep working, but they get no layout of their own.

### Named Rules
**The Press Budget Rule.** A layout is judged by the presses it costs. Daily routes have limits in
`docs/LOOK.md` (one press to the first Continue Watching card, none to the next episode), and a new
layout mustn't exceed them.

**The Remembered Place Rule.** Coming back into a row lands on the card the viewer was last on there,
never on whatever is at the same position.

## Elevation & Depth

Flat and tonal. Depth comes from three steps of warm dark, Aubergine Dusk, then Upholstery, then
Cushion, and from solid panels laid over the screen, never from shadows or blur. The one exception is
the Highlight: a highlighted card lifts (scale 1.05, or 1.03 for Home's large first card) and casts a
single 14 dp coral glow. Only the highlighted item glows, and the state changes on the press's own
frame with no animation between, so moving the Highlight costs no layout.

### Shadow Vocabulary
- **Highlight glow** (14 dp elevation, coral ambient and spot colour): on the highlighted card or Live
  Preview only.

### Named Rules
**The One Glow Rule.** Never more than one glow on screen, and never blur, a dimmed shade behind a
panel, or two screens drawn see-through at once. The Side Panel sits solid over the screen with nothing
dimmed behind it.

**The Solid Floor Rule.** Every screen draws a solid background. Only where video plays (the players,
the Live Preview) is the area left clear for the picture.

## Shapes

Corners soften with size and with how much there is to look at. Dense, ruled things are nearly square:
guide cells and sidebar rows get 2 dp. Episode thumbnails get 7 dp. Cards, episode rows, Settings rows
and rail items get a gentle 10 dp. Buttons and pills are full pills. The guide is ruled and square,
with a strong rule under the time header. There are no borders on cards; a text field is the only thing
with a resting border, in Picture Rail, which turns 3 dp coral when it has the Highlight.

The mark is a settee that's also a TV, with an old TV's aerials and a tiny face, always in plum on its
coral tile. Its aerial TV comes back as the waiting animation, drawn in Cream with coral arcs.

## Components

### Buttons
Soft and tactile, like a cushion you press.
- **Shape:** full pill.
- **At rest:** Cushion fill, Cream label in Body, an optional 22 dp icon before it.
- **Highlighted:** solid Coral Lamp, Plum Ink label and icon.
- **Disabled:** Mushroom label, and the Highlight passes over it.

### Chips (Pills)
- **Style:** a row of choices such as a show's seasons. Unselected: Upholstery fill, Mushroom text.
- **Selected:** Cushion fill and Coral Lamp text, so the row still says what's chosen when the
  Highlight is elsewhere. Moving onto a pill selects it at once, with no OK.
- **Highlighted:** solid Coral Lamp, Plum Ink text.

### Cards / Containers
- **ArtCard:** the card is its artwork, cropped to fill. Portrait covers (118 x 177), landscape
  Continue Watching cards (186 x 105) and Home's large first card (300 x 169).
- **Corner style:** 10 dp, clipped.
- **Background:** Cushion behind the art, so a missing cover still reads.
- **Text on art:** a fade that darkens early, clear at the top to Aubergine Dusk at 0.72 by 40% down and
  0.92 at the foot, so a two-line title's first line has at least 0.75 behind it. Then the title and up
  to two Mushroom status lines, one per line ("S4 E3" over "22 min left").
- **Highlight:** the ring (4 dp coral with a 2 dp dark edge inside it), the lift and the glow. The 4 dp
  progress bar moves in 6 dp to clear the ring, and the text moves up clear of the bar.
- **Badge:** a title's Quality (4K, FHD, HD, CAM) top right, Cream on a dark pill, never coral.

### Inputs / Fields
- **Style:** Upholstery fill, 10 dp corners, 1 dp Picture Rail border, Mushroom label above and
  placeholder inside.
- **Focus:** the border turns 3 dp Coral Lamp. The keyboard stays shut until OK.

### Navigation
- **Rail:** icons only when closed, with the current tab resting on a Cushion fill and its icon in
  Coral Lamp. Open, every item gets its label, and the highlighted one takes the solid coral fill.
- **Categories Sidebar:** 214 dp, rows with 2 dp corners. The current category is in Coral Lamp text,
  and the highlighted row is solid coral.

### List Row (Settings)
Every setting is one row with one target, resting on Upholstery so a pane reads as a list of things to
press. The label is in Row title. At its end is a value with a chevron, a chevron alone, or a switch
(a Cushion track when off, coral when on, turning Plum Ink while highlighted so it doesn't vanish into
the fill). A Mushroom note line underneath says things like "For Sam". Highlighted, the whole row is
coral and every line of text is Plum Ink.

### Side Panel
Every question, choice and notice. 400 dp down the right, solid Upholstery, 24 dp padding, rows 2 dp
apart. Choices are rows, with the current one ticked in coral. Confirmations start on the safe choice.
Back closes it, and there's no Cancel.

### Guide
Ruled and square. The times sit in a header above a strong rule, with the day in its corner. The
current programme is in Row title, later ones in Mushroom. A highlighted cell is solid coral with Plum
Ink text, at 2 dp corners.

### Aerial TV (waiting)
The TV from the mark in Cream, with coral arcs rising from its aerials over about 1.8 s and the eyes
blinking about every 4 s. Full size (48 to 64 dp) is centred with what's loading named under it. Small
size, at text height, sits at the end of a row, drawn in Plum Ink when the row has the Highlight. It's
the one thing that keeps moving when the system's animations are off. When the work knows how far it
is, a progress bar and percentage take its place.

### Error message
A sentence in the Voice saying what happened and what to do, then a small Mushroom line with the code,
status and format. Never a provider URL, username, password or MAC.

## Do's and Don'ts

### Do:
- **Do** build from `ui/design` and Compose foundation: `HighlightSurface`, `PillButton`, `Pill`,
  `ListRow`, `ArtCard`, `SidePanel`, `ScreenFrame`, `AerialTv`, `ErrorMessage`.
- **Do** make every new element reachable and obvious with a D-pad. If it can take the Highlight, it
  draws one of the two treatments: ring and lift on artwork, solid coral fill on everything else. The
  player's previous, play or pause and next are the one exception: bare icons that turn coral.
- **Do** switch text and icons to Plum Ink on anything coral, including a switch's track and the small
  aerial TV.
- **Do** keep text 16 sp or larger, with lining, tabular figures.
- **Do** put the fade under any text that sits on artwork, and make it follow the text, not the art: at
  least 0.7 of Aubergine Dusk under words and 0.9 under quiet text, clearing only past the text.
- **Do** put every question, choice, confirmation and notice in a Side Panel.
- **Do** check any motion on a Fire TV Stick: under 16.7 ms a frame while it moves, or it doesn't move.

### Don't:
- **Don't** use Material 3 or `RoundedCornerShape` outside `ui/design` (detekt bans it). Use
  `SetteeShapes`.
- **Don't** use coral for decoration, badges or anything that can't be pressed (The One Lamp Rule).
- **Don't** use red for anything but recording now (The Tally Light Rule).
- **Don't** blur, dim behind a panel, use more than one glow, or draw two screens see-through at once
  (The One Glow Rule).
- **Don't** step things back with opacity; use Mushroom (The Solid Colour Rule).
- **Don't** add a light theme, a dialog in the middle of the screen, or a promotional hero on Home.
- **Don't** fake a bold in Young Serif.
- **Don't** make the viewer wait for a transition. Screen changes dip through the background in about
  300 ms, and presses during it still go through.
- **Don't** let it look like a sideloaded IPTV box, a stretched phone app or a storefront.
