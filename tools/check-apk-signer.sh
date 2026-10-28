#!/bin/sh
# Checks that an APK is signed by exactly one certificate, the one in the release keystore, and
# prints that certificate's SHA-256 digest.
# Usage: tools/check-apk-signer.sh <apk> <keystore> <alias>, with KEYSTORE_PASSWORD exported
# APKSIGNER names the apksigner binary; by default the newest one under $ANDROID_HOME/build-tools.
# The release workflow runs this rather than trusting that a keystore was configured: only the APK
# itself proves which key will be asked to upgrade over the installed one.
set -eu

apk=$1
keystore=$2
alias=$3

if [ -z "${APKSIGNER:-}" ]; then
    build_tools=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/* | sort -V | tail -n 1)
    APKSIGNER="$build_tools/apksigner"
fi

# keytool prints AB:CD:..., apksigner prints abcd...
normalise() { tr -d ': ' | tr 'A-F' 'a-f'; }

# -storepass:env keeps the password out of the process list. keytool lists every certificate in
# the key's chain; the first is the key's own, the one apksigner reports.
expected=$(keytool -list -v -keystore "$keystore" -alias "$alias" -storepass:env KEYSTORE_PASSWORD |
    sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1 | normalise)
if [ -z "$expected" ]; then
    echo "Could not read the certificate for alias '$alias' from the keystore" >&2
    exit 1
fi

if ! certs=$("$APKSIGNER" verify --print-certs "$apk"); then
    echo "$apk is not validly signed" >&2
    exit 1
fi

# With key rotation one signer is listed once per SDK range, hence the de-duplication.
actual=$(printf '%s\n' "$certs" | sed -n 's/^Signer .*certificate SHA-256 digest: //p' | normalise | sort -u)

if [ "$actual" != "$expected" ]; then
    echo "$apk is not signed with the release key." >&2
    echo "  expected: $expected" >&2
    echo "  found:    $(printf '%s' "$actual" | tr '\n' ' ')" >&2
    exit 1
fi

printf '%s\n' "$expected"
