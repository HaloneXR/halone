#!/bin/sh
set -eu
VERSION="154.0.34+g14c5a08+chromium-154.0.8037.98"
SHA1="b7e1bc1c719f1b8907b0398d8666339471a316c8"
NAME="cef_binary_${VERSION}_macosarm64"
DIR="$(cd "$(dirname "$0")" && pwd)/third_party"
[ -d "$DIR/$NAME" ] && { echo "$DIR/$NAME"; exit 0; }
mkdir -p "$DIR/download"
ARCHIVE="$DIR/download/$NAME.tar.bz2"
[ -f "$ARCHIVE" ] || curl -fsSL "https://cef-builds.spotifycdn.com/$(printf %s "$NAME" | sed 's/+/%2B/g').tar.bz2" -o "$ARCHIVE"
echo "$SHA1  $ARCHIVE" | shasum -a 1 -c - >/dev/null
tar -xjf "$ARCHIVE" -C "$DIR"
rm "$ARCHIVE"
echo "$DIR/$NAME"
