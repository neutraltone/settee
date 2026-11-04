# Can Settee ship Young Serif and Alegreya Sans?

Research for [#150](https://github.com/neutraltone/settee/issues/150), checked on 2026-11-04. Direction E is on
[#89](https://github.com/neutraltone/settee/issues/89) and the Stick's limits are on
[#86](https://github.com/neutraltone/settee/issues/86). This isn't legal advice, and the author isn't a lawyer.

## Answer

**Yes, ship them**, as unmodified files with their licence text in the APK and the repo. Rely on the fonts being
an aggregate alongside the program, not on the OFL being compatible with the GPL, because it probably isn't.
Three things should change from the prototype: use lining figures for numbers, raise the body floor above 14 sp for
this font, and use Alegreya Sans for titles in languages Young Serif can't write.

## What the prototype uses

Read from `prototype/89-directions` at `489e8383`.

- **Files:** `proto_young_serif.ttf` (Regular only), and `proto_alegreya_regular`, `_medium` and `_bold`. All four
  are byte-identical to the `ofl/` copies in [google/fonts](https://github.com/google/fonts/tree/main/ofl) (SHA-256 compared).
- **Versions:** Young Serif 3.003 and Alegreya Sans 2.004. Upstream `master` has moved on to 6.002 and 2.008.
- **Use in `DirectionCombined.kt`:** Young Serif only at 18 sp and up (18, 28, 32, 54). Alegreya Sans at 14 to
  18 sp, with Medium and Bold at seven call sites each and Regular as the default. The 14 sp text is Regular in
  the `Soft` colour (category counts and "11 of 214").

**Trap:** Young Serif on `master` isn't the font E was judged with. It has been redrawn with new weights and italics.
Its Regular has a 0.080 em stem against 0.137 em in 3.003, and its x-height is 0.440 em against 0.500 em, so swapping
it in makes E's titles look much thinner. Even its Bold (0.120 em) is lighter than the 3.003 Regular.

## 1. Licence

### Are they really OFL?

| | Young Serif | Alegreya Sans |
|---|---|---|
| Upstream repo | [noirblancrouge/YoungSerif](https://github.com/noirblancrouge/YoungSerif) | [huertatipografica/Alegreya-Sans](https://github.com/huertatipografica/Alegreya-Sans) |
| Licence, per GitHub and `OFL.txt` | OFL-1.1 | OFL-1.1 |
| Copyright line in `OFL.txt` and the font's name table | 2023 The Young Serif Project Authors | 2013 The Alegreya Sans Project Authors |
| Reserved Font Name in `OFL.txt` or the font | None | None, but see below |
| `fsType` (embedding bits) | 0, unrestricted | 0, unrestricted |

**Alegreya Sans has a conflict upstream.** `OFL.txt` (changed in June 2017, "Copyright string updated") reserves no
names, and neither do the shipped fonts' name tables or Google Fonts' `OFL.txt`. The older
[`LICENSE.md`](https://github.com/huertatipografica/Alegreya-Sans/blob/master/LICENSE.md) (January 2014) still says
"with Reserved Font Names 'Alegreya Sans'". [OFL FAQ 5.7](https://openfontlicense.org/ofl-faq/) says no names are
reserved unless stated after the copyright statement, and the 2017 change reads as the newer intent, but nothing
upstream says the 2014 file is withdrawn. This doesn't matter while the files stay unmodified, because a Reserved
Font Name only limits Modified Versions. Treat the name as reserved if the font is ever changed.

### Is the OFL compatible with the GPL?

Nobody with authority says it is, so assume it isn't.

- **FSF:** the [licence list](https://www.gnu.org/licenses/license-list.html#SILOFL) calls the OFL a free copyleft
  licence for fonts and doesn't mention the GPL. The neighbouring Arphic and IPA font licences are explicitly marked
  incompatible, so the silence isn't a clear statement either way. The entry sits with the font licences, not under the GPL-compatible software licences.
- **The licence terms:** OFL condition 5 requires the font to be distributed only under the OFL, and condition 1
  forbids selling it alone. GPLv3 requires a covered work to be licensed as a whole under the GPL with no further
  restrictions. Those can't both hold for a single work.
- **A licensing lawyer:** Richard Fontana, on the [Linux kernel list in 2023](https://lkml.iu.edu/hypermail/linux/kernel/2311.0/01472.html):
  "I think it should be beyond dispute that OFL-1.1 is incompatible with the GPL." That's one expert's opinion on a
  mailing list, not a ruling. The [patch he was answering](https://lkml.iu.edu/hypermail/linux/kernel/2311.0/01381.html) was less sure.
- **SIL:** the [OFL FAQ](https://openfontlicense.org/ofl-faq/) (1.1-update7, November 2023) has no entry on GPL
  compatibility. Its only mention of the GPL is about upgrade clauses (8.8).

### Is a font in an APK a combined work or an aggregate?

Compatibility only matters if the two form one work. The evidence is that they don't.

- **GPLv3 section 5** (in this repo's `LICENSE`): separate works that aren't extensions of the covered work, and
  aren't combined with it into a larger program, form an aggregate, and the GPL doesn't reach the other parts.
- **[FSF FAQ, MereAggregation](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation):** an aggregate may include
  GPL-incompatible parts. Where the line falls is a question for judges, and the FSF's test looks at the mechanism and
  the meaning of the communication. Being in the same executable file definitely combines modules, and that's the one
  phrase to watch, since an APK is a single file. But an APK is a zip container, and the code is in `classes.dex` while
  the font is a separate entry.
- **FSF FAQ on [artwork](https://www.gnu.org/licenses/gpl-faq.html#WhatCaseIsOutputGPL):** it accepts in passing that
  a GPL game can have art and audio under a separate licence. The
  [font exception](https://www.gnu.org/licenses/gpl-faq.html#FontException) is about embedding GPL fonts in documents,
  so it doesn't apply here.
- **OFL FAQ:** fonts are typically aggregated with software, not merged into it, so incompatibility is of little
  concern (1.2). Only the parts based on the font need the OFL (1.3). Bundling with a mobile app is addressed directly (1.20).
- **Precedent, not authority:** [Signal-Android](https://github.com/signalapp/Signal-Android) is AGPL-3.0 and ships
  `assets/fonts/Inter-Medium.otf`; [Inter](https://github.com/rsms/inter) is OFL.
- **Measured:** the font survives the release build intact. In a real `assembleRelease` the TTFs were renamed by the
  resource shrinker (`res/Eu.ttf`) and deflated, and each extracted file had the original SHA-256. The code refers to
  it by resource ID, and the platform reads it as data. OFL FAQ 5.9 treats a compressed wrapper as no modification.

### How settled is this?

| Claim | Status |
|---|---|
| Both fonts may be bundled in an app under the OFL | **Settled**: the licence and FAQ 1.20 say so |
| The OFL is GPL-compatible | **Don't rely on it**: FSF silent, and an expert says no |
| A separate font file in an APK is an aggregate | **Widely assumed, untested**: the OFL's authors say so and GPLv3's text supports it, but the FSF hasn't ruled on fonts in an app package and no court decision was found |

The practical risk looks low. The people able to enforce the GPL here are Settee's and OpenTV's copyright holders,
and the font authors' licence explicitly allows this. A GPLv3 section 7 exception isn't a practical extra safeguard,
because it would need the abandoned OpenTV project's contributors to agree.

### The safe course

1. Ship the upstream files byte for byte in `res/font`. Giving the file a lowercase resource name is fine: it doesn't
   change the font's own name.
2. Never turn a font into code, such as a Kotlin byte array, and never merge it with other data in a build step.
3. Say plainly that the fonts are under the OFL, not the GPL. Don't describe the whole APK as GPL-only.
4. Don't sell the fonts on their own, and don't use the authors' names to promote a modified version.

### What must ship

The minimum is the copyright statement, the licence notice and the licence text (OFL condition 2 and FAQ 1.20).
Metadata can legally carry this, but the name tables only hold a short notice and a link, which FAQ 1.10 strongly
advises against relying on.

| Where | What |
|---|---|
| **APK** | `app/src/main/assets/licences/young-serif-OFL.txt` and `alegreya-sans-OFL.txt`, each the upstream `OFL.txt` unedited. Use `assets`, since it's outside the resource shrinker |
| **About screen** | A row showing those texts and each font's name, with a link to its source. FAQ 1.20 calls this good practice. `AboutScreen.kt` already has a licence section, and the text needs to be reachable with a remote |
| **Repo** | The same two files, plus a short note of each font's version, source commit and SHA-256. `README.md` says the fonts are OFL |
| **Font files** | Name tables left as they are, which already hold the copyright and licence notice |

The whole font package isn't needed, only the fonts used and their copyright and licence (FAQ 1.20). No font source
has to ship: the OFL doesn't require it, and the GPL's Corresponding Source doesn't reach the other parts of an aggregate.

## 2. Size

### Measured

Real `assembleRelease` from `origin/main` at `9a2567f`, with and without the four files in `res/font`. They were kept
with a temporary `tools:keep`, since nothing referenced them. They were stored with Deflate, not left uncompressed.

| File | TTF bytes | Bytes in the APK |
|---|---:|---:|
| Alegreya Sans Regular | 263,804 | 125,283 |
| Alegreya Sans Medium | 264,612 | 125,854 |
| Alegreya Sans Bold | 266,856 | 126,272 |
| Young Serif Regular | 106,608 | 50,034 |
| **All four** | **901,880** | **427,443** |

The APK went from 6,293,900 to 6,722,012 bytes: **+428,112 bytes (+6.8%)**.

### Smaller sets

Computed by adding the measured entries above, not separately built, so the APK grows a few hundred bytes more than this.

| Set | Bytes in the APK | Share of the APK | Trade-off |
|---|---:|---:|---|
| Regular, Medium, Bold, Young Serif | 427,443 | 6.8% | As prototyped |
| **Medium, Bold, Young Serif** | **302,160** | **4.8%** | **Recommended**: see section 3 |
| Regular, Bold, Young Serif | 301,589 | 4.8% | Compose's matcher sends Medium to Regular, and 14 sp text stays thin |
| Medium, Young Serif | 175,888 | 2.8% | Bold is faked, which is a poor way to show the selected item |

Compose's [`FontMatcher`](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/font/FontMatcher.kt)
follows CSS matching, so a request for Medium with no Medium file picks Regular, and a request for Bold with no Bold
file picks the next weight down and fakes the rest. Young Serif 3.003 has only one weight, so no choice arises there.

#86 sets no limit on APK size. The number it gives for memory is under 300 MB PSS
during 1080p playback. **Estimated, not measured:** at most about 1 MB of font data in memory for four files, or
around 0.3% of that. Size doesn't decide this.

### Subsetting

**Allowed, but not worth it.** Removing glyphs or features makes a Modified Version (OFL FAQ 2.5 and 2.6). That's
permitted, but it must stay under the OFL, and it would have to drop the name "Alegreya Sans" because of the conflict
above. Young Serif has no reserved name.

Measured with fontTools and Deflate at level 9, which reproduced the APK's entry sizes exactly. Not built into an APK.
Medium, Bold and Young Serif start at 302,160 bytes.

| Subset | Bytes | Saved | Saved as share of the APK |
|---|---:|---:|---:|
| Same characters, only the OpenType features E would use | 201,296 | 100,864 | 1.6% |
| Latin, Greek and Cyrillic, same features | 175,158 | 127,002 | 2.0% |
| The same with hinting removed | 129,566 | 172,594 | 2.7% |

Saving 2% means renaming a font, adding a repeatable build step, testing each language again, and giving up
byte-identical files, which are the cleanest position for question 1.

## 3. Readability

### What the fonts' metrics establish

Measured from the outlines. At Fire TV's xhdpi density, 14 sp is 28 px on the 1080p canvas, the conversion
[Amazon gives](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html). Roboto and
Atkinson Hyperlegible are there for comparison. Amazon's page names Helvetica Neue as the system font, which wasn't measured.

| Font | x-height (em) | x-height at 14 sp | Capital at 14 sp | Stem at 14 sp | Thin stroke at 14 sp | Thick to thin |
|---|---:|---:|---:|---:|---:|---:|
| Alegreya Sans Regular | 0.461 | 12.9 px | 17.9 px | 2.00 px | 1.57 px | 1.34 |
| Alegreya Sans Medium | 0.464 | 13.0 px | 18.0 px | 2.59 px | 1.96 px | 1.36 |
| Alegreya Sans Bold | 0.468 | 13.1 px | 18.0 px | 3.46 px | 2.49 px | 1.40 |
| Young Serif 3.003 | 0.500 | 14.0 px | 21.0 px | 3.84 px | 2.46 px | 1.48 |
| Young Serif 6.002 Regular | 0.440 | 12.3 px | 18.5 px | 2.24 px | 1.41 px | 1.61 |
| Atkinson Hyperlegible | 0.496 | 13.9 px | 18.7 px | 2.35 px | 2.07 px | 1.22 |
| Roboto 3.015 | 0.528 | 14.8 px | 19.9 px | 2.55 px | 2.07 px | 1.23 |

- **Alegreya Sans is small for its size.** Its x-height is 13% below Roboto's, so 14 sp reads about like Roboto at
  12 sp. It takes **16 sp to match Roboto's 14 sp**. It passes Amazon's 14 sp rule on paper without giving the
  size that rule assumes.
- **Regular is thin at 14 sp.** Its stem is 2.00 px, against 2.55 px for Roboto. Medium, at 2.59 px and 1.96 px, is the
  closest match. Stroke contrast is low for all three weights, so it isn't the problem: the size and the Regular weight are.
- **Young Serif is fine as used.** It's sturdy, and E uses it only from 18 sp (36 px), where the stem is about 4.9 px and the `o`'s thin stroke about 3.2 px.
- **Colour contrast passes easily.** Against E's backgrounds, `Soft` is 6.25:1 to 8.27:1, `Ink` on `Bg` is 16:1, and
  `OnCoral` on `Coral` is 7.85:1. These are WCAG ratios worked out from the hex values, and a TV's real contrast will differ.

### Angular size

The [Legge and Bigelow 2011](https://pmc.ncbi.nlm.nih.gov/articles/PMC3428264/) review of reading gives a consensus
critical print size of 0.2° of x-height, or 12 arcminutes, typically varying from 0.15° to 0.3° (9 to 18 arcminutes).
That's for reading fast, not recognising a short label, so it's a guide and not a pass mark. Calculated at 10 feet (3.05 m):

| TV | Alegreya 14 sp | Alegreya 16 sp | Alegreya 18 sp | Roboto 14 sp | Alegreya size for 12 arcminutes |
|---|---:|---:|---:|---:|---:|
| 32 inch | 5.4′ | 6.1′ | 6.9′ | 6.2′ | 31 sp |
| 43 inch | 7.2′ | 8.2′ | 9.3′ | 8.3′ | 23 sp |
| 55 inch | 9.2′ | 10.6′ | 11.9′ | 10.6′ | 18 sp |
| 65 inch | 10.9′ | 12.5′ | 14.0′ | 12.5′ | 15 sp |

On most TVs, 14 sp is under the critical size in any font, and Roboto is too. Short labels survive that better than
sentences do. Alegreya at 14 sp falls below even the 9 arcminute end at 43 inches, so synopses should be 18 sp or more.

### A finding the ticket didn't ask about: figures

**Both fonts default to old-style figures.** In Alegreya Sans, the default 1 is only 502 units tall, close to the
x-height, and looks like a small capital I. `E11` reads like `EII`, and 3, 4, 5, 7 and 9 drop below the baseline.
Rendering confirmed this. Episode numbers, times and counts are all over E's screens, so this affects sofa legibility
more than the choice of weight does.

Both fonts include the fix: `lnum` gives lining figures (594 units in Alegreya Sans Regular, against 641 for capitals)
and `tnum` then makes them equal width (475 units for every Medium figure), which stops times and counts shifting. In
Compose that's `fontFeatureSettings = "lnum, tnum"` on the text style. Rendering both at 28 px on E's background made
a clear difference.

The prototype sets no font features, and its guide screenshot shows old-style figures ("48,212 channels", "Now 20:10"),
so **that's what the owner and viewer approved**. Lining figures are a visible change to E, and less characterful, so
this is the owner's call. The evidence favours changing them at least for times, episode numbers and counts.

### What can't be settled without a person and a real TV

- Whether 14 sp and 16 sp Alegreya Sans are comfortable for the viewer from her actual seat, on her actual TV.
- Whether Medium looks too heavy compared with the Regular she approved, particularly in 18 sp synopses.
- What the TV's sharpening, scaling and overscan do to strokes of about 2 px, and the look of warm near-blacks. #89 lists this as still to check.
- How the Stick draws these fonts. The test renders used Pillow's FreeType on a Mac, and the Stick's hinting and anti-aliasing weren't observed.
- Load time on first use. Bundled fonts load blocking the first time they're needed, and this wasn't measured on 1 GB hardware. It fits with #84's baseline.

## 4. Language coverage

Each font's real `cmap` was compared, using fontTools, with two sets for each of the 30 `values-*` folders and the
base file: every character in that `strings.xml`, and the [CLDR](https://github.com/unicode-org/cldr) exemplar
characters in both cases, since provider titles aren't limited to the app's own strings. CLDR's `nb` is an alias, so
Norwegian used `no`. All three Alegreya Sans weights have the same `cmap`.

| Languages | Young Serif 3.003 | Alegreya Sans 2.004 |
|---|---|---|
| en, cs, da, de, es, fi, fr, hr, hu, id, it, nb, nl, pl, pt, ro, sk, sv, tr | Covered | Covered |
| vi | **Fallback**: 43 of the letters in its strings and 78 exemplars missing | Covered |
| ru, uk, bg | **Fallback**: no Cyrillic | Covered |
| el | **Fallback**: no Greek | Covered |
| ar, fa | **Fallback** | **Fallback** |
| hi | **Fallback** | **Fallback** |
| th | **Fallback** | **Fallback** |
| ja, ko, zh | **Fallback** | **Fallback** |

Young Serif 6.002 adds Vietnamese but still has no Cyrillic or Greek. For the reason above, that isn't a reason to switch.

**Symbols, in every language.** Existing strings use `●`, `★`, `📺`, `🔍` and `🔔`, which Alegreya Sans doesn't have.
Young Serif 3.003 lacks `→`, `★` and the emoji. These already depend on system fonts, so nothing changes, though the
symbols may not match their surroundings.

### What Android does by default

- **Bundled fonts fall back to the system automatically.** The [platform docs](https://developer.android.com/reference/android/graphics/Typeface.CustomFallbackBuilder)
  say system fallback happens when no font in the custom chain has the character. AndroidX reaches it on every path
  the Stick uses: `CustomFallbackBuilder` on API 29 and up, and `createFromFamiliesWithDefault` with `sans-serif` below.
  Nothing should show as a missing glyph box as long as the system has the script.
- **In AOSP**, [`fonts.xml`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/data/fonts/fonts.xml)
  maps these to Noto Naskh Arabic, Noto Sans Devanagari, Noto Sans Thai, Noto Sans CJK and Noto Color Emoji.
  **Fire OS is Amazon's fork, and its font list wasn't checked**, because no Stick was available.
- **Arabic, Persian, Hindi, Thai, Chinese, Japanese and Korean are no worse off.** They already rely on those same
  system fonts today, since the current system sans doesn't have those scripts either.
- **Russian, Ukrainian, Bulgarian, Greek and Vietnamese titles are the new problem.** They'd fall back letter by letter
  from Young Serif to a plain system sans, while digits and punctuation stay in Young Serif. Vietnamese is the worst:
  the fallback happens mid-word, with `ơ` and `ư` in one typeface and the letters around them in another.

### The fallback to use

**Choose the title font by locale.** For `ru`, `uk`, `bg`, `el` and `vi`, draw titles in Alegreya Sans Bold, which
covers them, so E's hierarchy holds with no extra files. Leave the other languages to the system.

A bundled per-character chain isn't available everywhere. Compose's `FontFamily` chain is documented for a font that
fails to load, not for a missing glyph, and `CustomFallbackBuilder` needs API 29, which Fire OS 6 and 7 Sticks (API 25
to 28) don't have. So a Cyrillic title from a provider, on a device set to English, will still show the mixed look. It
stays readable, so it's a cosmetic cost.

Bundling Noto for the remaining scripts isn't worthwhile, as the system should already cover them. For scale, Google
Fonts' variable Noto Sans JP is 9.6 MB and Noto Sans KR is 10.4 MB (file sizes before compression), against a 6.3 MB APK.

## Recommendation

Ship Young Serif and Alegreya Sans, using the exact Google Fonts files E was judged with (Young Serif 3.003, Alegreya
Sans 2.004), unmodified and not subset, relying on bundling as an aggregate rather than GPL compatibility, with each
upstream `OFL.txt` in the APK and repo and a row on the About screen. Ship three files, Young Serif Regular, Alegreya
Sans Medium and Alegreya Sans Bold, for about 302 KB (+4.8%), making Medium the body weight in place of Regular unless
a look at a real TV says it's too heavy, in which case add Regular back for 125 KB. Turn on `lnum, tnum` wherever
numbers matter, subject to the owner seeing the change, since E was approved with old-style figures. Set the body floor at 16 sp in Alegreya Sans, with 14 sp only for secondary labels and 18 sp for
synopses. For fallback, use Alegreya Sans Bold for titles in Russian, Ukrainian, Bulgarian, Greek and Vietnamese, and
leave Arabic, Persian, Hindi, Thai, Chinese, Japanese and Korean to Android's system fallback, checking those on a Stick
when one is available.

## Not verified

- **Legal position:** no lawyer reviewed this, no court decision was found, and no FSF statement on the OFL and the GPL was found beyond the list entry.
- **FSF pages:** rate-limited here. The `#SILOFL` entry was read live and also, like the FAQ text, in a 2025 Internet Archive copy. The live FAQ's aggregate wording matched as far as it was returned.
- **Fedora:** Fontana also says Fedora doesn't class the OFL as a FOSS licence for code. Fedora's own page blocked automated access, so that's second-hand and isn't relied on.
- **Fire OS:** its actual system fonts and fallback, whether emoji draw in colour, and every behaviour on a Stick, as none was to hand.
- **Older Android paths:** for API 24 and 26, the fallback call was confirmed by name in the AndroidX source, not run.
- **Subsets:** sizes are measured, but none was built into an APK or tested per language.
- **Line height:** the prototype's one-line text uses 1.15 times the font size, while Young Serif's own line spans 1.41 em. Clipping with fallback scripts wasn't tested.
- **Size with fewer files:** the smaller sets add up measured entries and weren't built on their own.

## Sources

- SIL: [OFL FAQ 1.1-update7](https://openfontlicense.org/ofl-faq/), and the OFL text in each font's repo.
- FSF: [licence list](https://www.gnu.org/licenses/license-list.html#SILOFL), [GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html), and GPLv3 in this repo's `LICENSE`.
- Fonts: [YoungSerif](https://github.com/noirblancrouge/YoungSerif), [Alegreya-Sans](https://github.com/huertatipografica/Alegreya-Sans), [google/fonts `youngserif`](https://github.com/google/fonts/tree/main/ofl/youngserif), [google/fonts `alegreyasans`](https://github.com/google/fonts/tree/main/ofl/alegreyasans).
- Android: [`CustomFallbackBuilder`](https://developer.android.com/reference/android/graphics/Typeface.CustomFallbackBuilder), [Compose fonts](https://developer.android.com/develop/ui/compose/text/fonts), [androidx source](https://github.com/androidx/androidx), [AOSP `fonts.xml`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/data/fonts/fonts.xml).
- Amazon: [Fire TV design guidelines](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html).
- Reading: Legge and Bigelow, [Does print size matter for reading?](https://pmc.ncbi.nlm.nih.gov/articles/PMC3428264/), Journal of Vision, 2011.
- Languages: [Unicode CLDR](https://github.com/unicode-org/cldr).
- Commentary: [Fontana on the Linux kernel list](https://lkml.iu.edu/hypermail/linux/kernel/2311.0/01472.html), and the [patch he answered](https://lkml.iu.edu/hypermail/linux/kernel/2311.0/01381.html).
