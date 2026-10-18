#!/bin/sh
# Runs the compositor with the shell: the system apps from web/apps/system/dist
# (build them with `pnpm --filter @halone/system-apps build` in web/) in the CEF producer.
# Extra arguments go to the compositor, e.g. --glasses --seconds 30.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ "$(uname -s)" = Darwin ]; then
    PRODUCER="$HERE/../spikes/cef-producer/build/Release/Halone CEF Spike.app/Contents/MacOS/Halone CEF Spike"
    export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}"
else
    PRODUCER="$HERE/../spikes/cef-producer/build/Release/halone-cef-producer"
fi
APPS="$HERE/../../web/apps/system/dist"
LOG="${PRODUCER_LOG:-$HERE/build/producer.log}"
exec "$HERE/build/install/halone-desktop/bin/halone-desktop" \
    --producer "$PRODUCER" --producer-log "$LOG" --shell "$APPS" \
    "$@"
