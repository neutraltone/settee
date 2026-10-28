#!/bin/sh
# Checks that a commit message or PR title follows the convention in #6:
#   type(#issue): description
# The issue scope is required for feat, fix, refactor and perf, and optional for docs, chore, ci,
# test and build. release-please's own "chore(main): release x.y.z" also passes.
# Usage: tools/check-message.sh "<first line>"
# POSIX sh and grep -E only, so the commit-msg hook (#6) and CI can share it with no Node.

line=$1

if printf '%s\n' "$line" | grep -Eq '^(feat|fix|refactor|perf)\(#[0-9]+\)!?: .+' ||
    printf '%s\n' "$line" | grep -Eq '^(docs|chore|ci|test|build)(\(#[0-9]+\))?!?: .+' ||
    printf '%s\n' "$line" | grep -Eq '^chore\(main\): release [0-9]+\.[0-9]+\.[0-9]+'; then
    exit 0
fi

cat >&2 <<MSG
"$line" doesn't follow the convention: type(#issue): description

  feat, fix, refactor, perf    need the issue: feat(#12): add series rules
  docs, chore, ci, test, build issue optional: docs: fix a typo
MSG
exit 1
