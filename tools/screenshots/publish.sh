#!/usr/bin/env bash
# Writes the Screenshots section into a PR's description and posts the since-last-push comments that
# screenshots.py rendered, uploading their images with gh --attach (#44). gh rewrites each ./x.png
# reference to the uploaded file's URL, so nothing is committed anywhere.
# Needs gh 2.102 or newer (the first with --attach), GH_TOKEN, and GH_REPO (owner/name): gh runs
# from the screenshot directories, outside any checkout.
# Usage: tools/screenshots/publish.sh PR SECTION_DIR [COMMENTS_DIR]
# No -u: bash 3.2 (macOS) calls an empty array unbound.
set -eo pipefail

pr=$1
section_dir=$(cd "$2" && pwd)
comments_dir=""
if [ -d "${3:-}" ]; then
    comments_dir=$(cd "$3" && pwd)
fi
here=$(cd "$(dirname "$0")" && pwd)
batch=50 # gh's limit per command

# Sets attach to one --attach argument per PNG in the current directory, named as the references are.
read_attachments() {
    attach=()
    while IFS= read -r file; do
        attach+=("--attach=$file")
    done < <(find . -maxdepth 1 -name '*.png' | sort)
}

cd "$section_dir"
read_attachments
body=$(mktemp)
trap 'rm -f "$body" "$body.new"' EXIT

# Read just before writing, so an edit made while the screens rendered isn't lost.
gh pr view "$pr" --json body --jq .body >"$body"
python3 "$here/screenshots.py" splice "$body" section.md >"$body.new"
gh pr edit "$pr" --body-file "$body.new" "${attach[@]:0:batch}"

# More images than one command takes: the rest are still ./ references in the section, so re-read
# the description and hand it back unchanged with the next batch attached.
for ((i = batch; i < ${#attach[@]}; i += batch)); do
    gh pr view "$pr" --json body --jq .body >"$body"
    gh pr edit "$pr" --body-file "$body" "${attach[@]:i:batch}"
done

if [ -n "$comments_dir" ]; then
    for dir in "$comments_dir"/comment-*/; do
        [ -d "$dir" ] || continue
        cd "$dir"
        read_attachments
        gh pr comment "$pr" --body-file comment.md "${attach[@]}"
    done
fi
