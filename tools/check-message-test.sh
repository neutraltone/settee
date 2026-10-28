#!/bin/sh
# Table test for the commit convention: tools/check-message.sh (PR titles) and .githooks/commit-msg
# (commit messages, given a message file the way git passes it). POSIX sh, so it runs anywhere the
# hook does. Usage: tools/check-message-test.sh
cd "$(dirname "$0")/.." || exit 1
failures=0
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

# hook <expected: pass|fail> <message file contents>
hook() {
    printf '%s\n' "$2" > "$tmp"
    if sh .githooks/commit-msg "$tmp" 2>/dev/null; then got=pass; else got=fail; fi
    if [ "$got" != "$1" ]; then
        echo "FAIL hook: expected $1, got $got for: $2"
        failures=$((failures + 1))
    fi
}

# title <expected: pass|fail> <PR title>
title() {
    if tools/check-message.sh "$2" 2>/dev/null; then got=pass; else got=fail; fi
    if [ "$got" != "$1" ]; then
        echo "FAIL title: expected $1, got $got for: $2"
        failures=$((failures + 1))
    fi
}

hook pass 'feat(#12): add series rules'
hook fail 'add series rules'

# git strips comments and leading blank lines after the hook runs, so the hook must too.
hook pass '
# Please enter the commit message for your changes.
fix(#7): keep favourites across a refresh

# On branch fix/7'
hook fail '# feat(#12): a commented-out subject
keep favourites'

# The rule itself, per #6.
for type in feat fix refactor perf; do
    title pass "$type(#12): do the thing"
    title fail "$type: do the thing"
done
for type in docs chore ci test build; do
    title pass "$type(#12): do the thing"
    title pass "$type: do the thing"
done
title pass 'feat(#12)!: drop Android 5'
title pass 'chore(main): release 0.2.0'
title fail 'chore(main): release notes'
title fail 'feature(#12): do the thing'
title fail 'feat(12): do the thing'
title fail 'feat(#12):'
title fail 'Feat(#12): do the thing'
# Only the first line counts, so a good body can't rescue a bad subject.
title fail 'do the thing
feat(#12): do the thing'

# Messages git writes itself pass the hook, but never as a PR title: that becomes the commit on main.
hook pass "Merge branch 'main' into feat/12-series-rules"
hook pass 'Revert "feat(#12): add series rules"'
hook pass 'fixup! feat(#12): add series rules'
hook pass 'squash! feat(#12): add series rules'
title fail "Merge branch 'main' into feat/12-series-rules"
title fail 'Revert "feat(#12): add series rules"'
title fail 'fixup! feat(#12): add series rules'

if [ "$failures" -gt 0 ]; then
    echo "$failures failure(s)"
    exit 1
fi
echo "All message checks pass"
