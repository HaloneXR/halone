#!/bin/sh
# Runs the compositor with spike 2's CEF producer and the test site (served on :8765).
# Extra arguments go to the compositor, e.g. --glasses --seconds 30.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ "$(uname -s)" = Darwin ]; then
    PRODUCER="$HERE/../spikes/cef-producer/build/Release/Halone CEF Spike.app/Contents/MacOS/Halone CEF Spike"
    export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}"
else
    PRODUCER="$HERE/../spikes/cef-producer/build/Release/halone-cef-producer"
fi
SITE="http://127.0.0.1:8765"
LOG="${PRODUCER_LOG:-$HERE/build/producer.log}"
exec "$HERE/build/install/halone-desktop/bin/halone-desktop" \
    --producer "$PRODUCER" --producer-log "$LOG" \
    --panel "1280x720@$SITE/launcher/" \
    --panel "1280x720@$SITE/spike/test.html" \
    --panel "1920x1080@$SITE/spike/video.html?src=video-vp9.webm" \
    "$@"
