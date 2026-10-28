#!/usr/bin/env python3
# Compares two directories of screenshots and writes the PR description section and comment for the
# Screenshots workflow (.github/workflows/screenshots.yml, #44). publish.sh does the uploading.
#
#   screenshots.py section BASE HEAD OUT          OUT/section.md, plus the images it references
#   screenshots.py comments PREVIOUS HEAD OUT SHA OUT/comment-N/comment.md plus its images, per comment
#   screenshots.py splice BODY SECTION            prints BODY with SECTION spliced in
import argparse
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops


@dataclass(frozen=True)
class Change:
    name: str
    before: Path | None
    after: Path | None


# Both sides render on the same runner, so differences are rare, but anti-aliasing can still move by
# a few levels or flip a stray edge pixel. A pixel counts as changed when a channel moves by more than
# CHANNEL_TOLERANCE, and a screen when more than PIXEL_TOLERANCE of its pixels did: at 1080p that is
# about 200 pixels, less than one changed character of body text.
CHANNEL_TOLERANCE = 8
PIXEL_TOLERANCE = 0.0001


def differs(a: Path, b: Path) -> bool:
    with Image.open(a) as left, Image.open(b) as right:
        left, right = left.convert("RGB"), right.convert("RGB")
        if left.size != right.size:
            return True
        # Per pixel, the largest channel difference, then 255 where it is over tolerance and 0 below.
        worst = ImageChops.difference(left, right).split()
        delta = ImageChops.lighter(ImageChops.lighter(worst[0], worst[1]), worst[2])
        changed = delta.point(lambda level: 255 if level > CHANNEL_TOLERANCE else 0).histogram()[255]
        return changed > PIXEL_TOLERANCE * left.width * left.height


def compare(base: Path, head: Path) -> list[Change]:
    """Every screen that is new, removed or changed between base and head, by name. Either may be missing."""
    befores = {p.stem: p for p in Path(base).glob("*.png")}
    afters = {p.stem: p for p in Path(head).glob("*.png")}
    changes = []
    for name in sorted(befores.keys() | afters.keys()):
        before, after = befores.get(name), afters.get(name)
        if before is None or after is None or differs(before, after):
            changes.append(Change(name, before=before, after=after))
    return changes


START = "<!-- screenshots:start -->"
END = "<!-- screenshots:end -->"


def _image(change: Change, side: str) -> str:
    return f"![{change.name} {side}](./{change.name}.{side}.png)"


def table(changes: list[Change]) -> str:
    """A before | after row per screen. References are relative so gh --attach rewrites them to uploads."""
    rows = ["| Screen | Before | After |", "| --- | --- | --- |"]
    for change in changes:
        before = _image(change, "before") if change.before else "_New_"
        after = _image(change, "after") if change.after else "_Removed_"
        rows.append(f"| {change.name} | {before} | {after} |")
    return "\n".join(rows)


def section(changes: list[Change]) -> str:
    """The description's Screenshots section, between the markers splice() looks for."""
    body = table(changes) if changes else "No visual changes."
    return "\n".join(
        [
            START,
            "## Screenshots",
            "",
            body,
            "",
            "<sub>Rendered by the Screenshots workflow from this PR's base and head. "
            "It rewrites this section on every push.</sub>",
            END,
        ]
    )


def attachments(changes: list[Change]) -> list[tuple[Path, str]]:
    """Each image the section or comment references, as (where it was rendered, the name it is uploaded as)."""
    files = []
    for change in changes:
        if change.before:
            files.append((change.before, f"{change.name}.before.png"))
        if change.after:
            files.append((change.after, f"{change.name}.after.png"))
    return files


def splice(body: str, new_section: str) -> str:
    """The description with its marked section replaced, or the section appended if it has none yet."""
    start = body.find(START)
    end = body.find(END, start)
    if start != -1 and end != -1:
        return body[:start] + new_section + body[end + len(END) :]
    if not body.strip():
        return new_section + "\n"
    return body.rstrip("\r\n") + "\n\n" + new_section + "\n"


# gh uploads at most this many files per command.
MAX_ATTACHMENTS = 50


def comments(changes: list[Change], since: str) -> list[tuple[str, list[tuple[Path, str]]]]:
    """
    The comments showing what changed since the previous push, as (markdown, attachments) pairs.

    A comment can't take more uploads later the way the description can be re-read and edited, so
    a long list is split into several comments that each fit one gh command.
    """
    posted, batch = [], []
    for change in changes:
        if batch and len(attachments(batch + [change])) > MAX_ATTACHMENTS:
            posted.append(batch)
            batch = []
        batch.append(change)
    if batch:
        posted.append(batch)
    heading = f"### Screenshots changed since {since[:7]}"
    return [
        (f"{heading}{' (continued)' if i else ''}\n\n{table(batch)}", attachments(batch))
        for i, batch in enumerate(posted)
    ]


def _copy(files: list[tuple[Path, str]], out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for source, name in files:
        shutil.copyfile(source, out / name)


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(prog="screenshots.py")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("section", "comments"):
        command = commands.add_parser(name)
        command.add_argument("before", type=Path)
        command.add_argument("after", type=Path)
        command.add_argument("out", type=Path)
        if name == "comments":
            command.add_argument("since")
    command = commands.add_parser("splice")
    command.add_argument("body", type=Path)
    command.add_argument("section", type=Path)
    args = parser.parse_args(argv)

    if args.command == "section":
        changes = compare(args.before, args.after)
        _copy(attachments(changes), args.out)
        (args.out / "section.md").write_text(section(changes) + "\n")
        print(f"{len(changes)} changed screen(s)")
    elif args.command == "comments":
        posted = comments(compare(args.before, args.after), args.since)
        args.out.mkdir(parents=True, exist_ok=True)
        for i, (markdown, files) in enumerate(posted, start=1):
            _copy(files, args.out / f"comment-{i}")
            (args.out / f"comment-{i}" / "comment.md").write_text(markdown + "\n")
        print(f"{len(posted)} comment(s)")
    else:
        sys.stdout.write(splice(args.body.read_text(), args.section.read_text().rstrip("\n")))


if __name__ == "__main__":
    main(sys.argv[1:])
