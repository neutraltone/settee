#!/usr/bin/env bash
# Renders one commit's screenshots into OUT (#44): checks the commit out in a worktree and runs its
# recordRoborazziDebug, so every side of a comparison renders the same way on the same runner.
# A commit from before screenshots existed, or whose build fails, leaves OUT empty or partial rather
# than failing: the comparison then shows those screens as new.
# Usage: tools/screenshots/render.sh COMMIT OUT
set -uo pipefail

rev=$1
mkdir -p "$2"
out=$(cd "$2" && pwd)
tree=$(mktemp -d)

# A previous push that was force-pushed away is no longer on any branch; GitHub still serves it by SHA.
git cat-file -e "$rev^{commit}" 2>/dev/null || git fetch --quiet origin "$rev" || true
if ! git worktree add --quiet --detach "$tree" "$rev"; then
    echo "::warning::Can't check out $rev, so it has no screenshots."
    exit 0
fi

if ! (cd "$tree" && ./gradlew recordRoborazziDebug --continue --stacktrace); then
    echo "::warning::Rendering $rev failed; its screenshots are missing or partial."
fi
find "$tree/app/build/outputs/roborazzi" -maxdepth 1 -name '*.png' -exec cp {} "$out/" \; 2>/dev/null
echo "$rev: $(find "$out" -name '*.png' | wc -l | tr -d ' ') screenshot(s)"
git worktree remove --force "$tree"
