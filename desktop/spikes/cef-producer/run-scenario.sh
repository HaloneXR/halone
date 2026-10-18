#!/bin/sh
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
SITE="http://127.0.0.1:8765"
SCENARIO="${1:?usage: run-scenario.sh <scenario> [seconds] [margin] [screenshot.png]}"
SECONDS_TO_RUN="${2:-20}"
MARGIN="${3:-10}"
SHOT="${4:-}"
LAUNCHER="1280x720@$SITE/launcher/"
SETTINGS="1280x720@$SITE/settings/"
TEST="1280x720@$SITE/spike/test.html"
VP9="1920x1080@$SITE/spike/video.html?src=video-vp9.webm"
AV1="1920x1080@$SITE/spike/video.html?src=video-av1.webm"

case "$SCENARIO" in
    test) set -- --panel "$TEST" ;;
    launcher) set -- --panel "$LAUNCHER" ;;
    video-vp9) set -- --panel "$VP9" ;;
    video-av1) set -- --panel "$AV1" ;;
    three-vp9) set -- --panel "$LAUNCHER" --panel "$TEST" --panel "$VP9" ;;
    three-av1) set -- --panel "$LAUNCHER" --panel "$TEST" --panel "$AV1" ;;
    four) set -- --panel "$LAUNCHER" --panel "$SETTINGS" --panel "$TEST" --panel "$VP9" ;;
    *) echo "unknown scenario $SCENARIO" >&2; exit 2 ;;
esac

LOG="$HERE/build/$SCENARIO-m$MARGIN.log"
"$HERE/build/Release/consumer" "$@" --margin "$MARGIN" --seconds "$SECONDS_TO_RUN" > "$LOG" 2>&1 &
CONSUMER=$!
if [ -n "$SHOT" ]; then
    sleep 12
    WINDOW="$(grep -m1 'consumer: window' "$LOG" | sed 's/.* //')"
    screencapture -x -o -l"$WINDOW" "$SHOT"
fi
wait "$CONSUMER"
python3 -I "$HERE/summarize.py" "$LOG"
