#!/bin/sh
# Checks that an APK is signed by exactly one certificate, the one in the release keystore, and
# prints that certificate's SHA-256 digest. Given a pinned digest (as apksigner or keytool prints it), the
# certificate must also be that one.
# Usage: tools/check-apk-signer.sh <apk> <keystore> <alias> [pinned-digest], with KEYSTORE_PASSWORD exported
# APKSIGNER names the apksigner binary; by default the newest one under $ANDROID_HOME/build-tools.
# The release workflow runs this rather than trusting that a keystore was configured: only the APK
# itself proves which key will be asked to upgrade over the installed one.
set -eu

apk=$1
keystore=$2
alias=$3
pinned=${4:-}

if [ -z "${APKSIGNER:-}" ]; then
    build_tools=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/* | sort -V | tail -n 1)
    APKSIGNER="$build_tools/apksigner"
fi

# keytool prints AB:CD:..., apksigner prints abcd... A pin may be pasted in either form, and saved
# with a Windows line end that $(cat) keeps.
normalise() { tr -d ': \r' | tr 'A-F' 'a-f'; }
pinned=$(printf '%s' "$pinned" | normalise)

# release.yml passes "$(cat tools/release-cert.sha256)", and a failed substitution inside an
# argument doesn't stop the step: a missing or emptied file arrives as "", which must not read as
# "no pin" or deleting the file would switch the check off.
if [ $# -ge 4 ] && [ -z "$pinned" ]; then
    echo "The pinned digest is empty: is tools/release-cert.sha256 missing or blank?" >&2
    exit 1
fi

# -storepass:env keeps the password out of the process list. keytool lists every certificate in
# the key's chain; the first is the key's own, the one apksigner reports.
# keytool reports errors on stdout, so its output is kept to show if no digest comes out of it.
listing=$(keytool -list -v -keystore "$keystore" -alias "$alias" -storepass:env KEYSTORE_PASSWORD 2>&1) || true
expected=$(printf '%s\n' "$listing" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1 | normalise)
if [ -z "$expected" ]; then
    echo "Could not read the certificate for alias '$alias' from $keystore:" >&2
    printf '%s\n' "$listing" | sed 's/^/  /' >&2
    exit 1
fi

if ! certs=$("$APKSIGNER" verify --print-certs "$apk"); then
    echo "$apk is not validly signed" >&2
    exit 1
fi

# Older build-tools print "Signer #1 certificate SHA-256 digest: …", 37 prints "V2 Signer: certificate
# SHA-256 digest: …", once per signature scheme. The same signer may also be listed once per SDK
# range after key rotation, hence the de-duplication. A source stamp is not a signing key.
actual=$(printf '%s\n' "$certs" | sed -n '/^Source Stamp/d; s/^[^ ]* *Signer[^:]*:* *certificate SHA-256 digest: //p' |
    normalise | sort -u)

if [ "$actual" != "$expected" ]; then
    echo "$apk is not signed with the release key." >&2
    echo "  expected: $expected" >&2
    echo "  found:    $(printf '%s' "$actual" | tr '\n' ' ')" >&2
    if [ -z "$actual" ]; then
        echo "No signer digest in the output of $APKSIGNER:" >&2
        printf '%s\n' "$certs" | sed 's/^/  /' >&2
    fi
    exit 1
fi

# The keystore comes from the same secrets that signed the APK, so on its own it can't notice those
# secrets being swapped for another key. The pin is the certificate every release so far was signed
# with, committed where a change to it shows up in a PR.
if [ -n "$pinned" ] && [ "$actual" != "$pinned" ]; then
    echo "$apk is not signed with the pinned release certificate." >&2
    echo "  pinned: $pinned" >&2
    echo "  found:  $actual" >&2
    exit 1
fi

printf '%s\n' "$expected"
