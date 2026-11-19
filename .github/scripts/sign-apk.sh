#!/usr/bin/env bash
# Usage: sign-apk.sh <unsigned.apk> <output.apk>. Keep passwords out of argv.
set -euo pipefail
umask 077
input=${1:?missing unsigned APK}
apk=${2:?missing output APK}
: "${KEYSTORE_B64:?}" "${STORE_PASSWORD:?}" "${KEY_ALIAS:?}" "${KEY_PASSWORD:?}"
: "${VERSION_NAME:?}" "${VERSION_CODE:?}"
tools="$ANDROID_HOME/build-tools/35.0.0"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '%s' "$KEYSTORE_B64" | base64 --decode > "$tmp/signing.jks"
unset KEYSTORE_B64

# Compare the APK signer with the certificate of the requested alias, not just
# with an arbitrary valid signature. keytool exports the certificate as DER.
cert=$(keytool -exportcert -keystore "$tmp/signing.jks" -storepass:env STORE_PASSWORD -alias "$KEY_ALIAS" | sha256sum)
"$tools/zipalign" -P 16 -f 4 "$input" "$tmp/aligned.apk"
"$tools/apksigner" sign --ks "$tmp/signing.jks" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:STORE_PASSWORD --key-pass env:KEY_PASSWORD --out "$apk" "$tmp/aligned.apk"
"$tools/apksigner" verify --verbose --print-certs "$apk" | tee "$tmp/certificates"
grep -Fx "Signer #1 certificate SHA-256 digest: ${cert%% *}" "$tmp/certificates"
"$tools/zipalign" -c -P 16 4 "$apk"
"$tools/aapt" dump badging "$apk" > "$tmp/badging"
grep -F "package: name='com.example.chessmentor' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" "$tmp/badging"
sha256sum "$apk" > "$apk.sha256"
sha256sum --check "$apk.sha256"
