#!/bin/sh
set -eu
VERSION="154.0.34+g14c5a08+chromium-154.0.8037.98"
case "$(uname -s)" in
    Darwin) PLATFORM=macosarm64; SHA1="b7e1bc1c719f1b8907b0398d8666339471a316c8" ;;
    Linux) PLATFORM=linux64; SHA1="d0a822afcf769371e961344b64f5a461066b6a0f" ;;
    *) echo "unsupported platform" >&2; exit 1 ;;
esac
NAME="cef_binary_${VERSION}_${PLATFORM}"
DIR="$(cd "$(dirname "$0")" && pwd)/third_party"
[ -d "$DIR/$NAME" ] && { echo "$DIR/$NAME"; exit 0; }
mkdir -p "$DIR/download"
ARCHIVE="$DIR/download/$NAME.tar.bz2"
[ -f "$ARCHIVE" ] || curl -fsSL "https://cef-builds.spotifycdn.com/$(printf %s "$NAME" | sed 's/+/%2B/g').tar.bz2" -o "$ARCHIVE"
if command -v sha1sum >/dev/null; then
    echo "$SHA1  $ARCHIVE" | sha1sum -c - >/dev/null
else
    echo "$SHA1  $ARCHIVE" | shasum -a 1 -c - >/dev/null
fi
tar -xjf "$ARCHIVE" -C "$DIR"
rm "$ARCHIVE"
echo "$DIR/$NAME"
