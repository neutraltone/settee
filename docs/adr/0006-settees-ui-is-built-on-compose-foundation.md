# Settee's UI is built on Compose foundation, not a component library

Settee has its own thin design system on Compose foundation, and Material 3 goes once the screens have moved onto it. Material 3 is built for touch: its buttons have no focused colour, and it shows focus as a faint state layer that can't be seen from the sofa. That's why the screens grew about 25 hand-written highlights and a seek bar wrapped in key handling so the highlight can leave it. Keeping Material would keep those workarounds. Settee uses only about a dozen of its widgets, so replacing them with small components is cheaper than continuing to fight them.

We rejected the other libraries. `tv-material` has the right per-state focus model, but no text field, dialog, slider or progress bar, so we'd need foundation as well and have two systems. It also ties the look to Material's roles, and Google has been removing parts of it. Compose Unstyled's docs don't mention TV or D-pad, and it would add a third-party dependency for components that are short to write.

Performance isn't the reason. We found no evidence that Material itself is what makes the app slow, so don't expect removing it to make it fast.

Screens only ever use `ui/design`, so no library's types reach them. If `HighlightSurface` proves too hard to maintain, it can be rebuilt on `tv-material`'s `Surface` without touching a screen.

The limits we accepted:

- We own every component we need, including the fiddly ones: the seek bar's scrubbing, the text field's decoration around `BasicTextField`, and the screen reader roles Material used to set for us.
- Text field keyboard behaviour should match, because Material's field is built on `BasicTextField`, but that needs checking on a Fire TV Stick.
  - Checking it on the Stick (#112) showed it doesn't. Compose's fields never told the Fire TV keyboard when Settee changed the text, so it went on showing the old words after a clear or a Recent Search, and the String field opened the keyboard as soon as the Highlight landed. So `ui/design`'s `TextField` (#167) is the exception: an Android `EditText` inside Settee's own decoration, which keeps the keyboard in step and takes dictation.
- Until the migration finishes, both systems are in the app. The last migration ticket removes Material, and a detekt baseline that only shrinks stops new uses in the meantime.
