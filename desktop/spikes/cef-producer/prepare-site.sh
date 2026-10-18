#!/bin/sh
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST="${SYSTEM_DIST:-$HERE/../../../web/apps/system/dist}"
SITE="$HERE/build/site"
if [ ! -f "$DIST/launcher/index.html" ]; then
    echo "No system apps at $DIST; build them (web: pnpm build) or set SYSTEM_DIST" >&2
    exit 1
fi
rm -rf "$SITE"
mkdir -p "$SITE"
cp -R "$DIST/." "$SITE/"
cp -R "$HERE/site/spike" "$SITE/spike"
VIDEOS="$HERE/build/videos"
mkdir -p "$VIDEOS"
SOURCE="testsrc2=size=1920x1080:rate=60:duration=20"
[ -f "$VIDEOS/video-vp9.webm" ] || ffmpeg -loglevel error -f lavfi -i "$SOURCE" \
    -c:v libvpx-vp9 -b:v 8M -deadline realtime -cpu-used 8 -row-mt 1 -pix_fmt yuv420p "$VIDEOS/video-vp9.webm"
[ -f "$VIDEOS/video-av1.webm" ] || ffmpeg -loglevel error -f lavfi -i "$SOURCE" \
    -c:v libsvtav1 -preset 10 -b:v 6M -pix_fmt yuv420p "$VIDEOS/video-av1.webm"
cp "$VIDEOS"/*.webm "$SITE/spike/"
echo "$SITE"
