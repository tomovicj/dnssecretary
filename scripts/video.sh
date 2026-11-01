#!/bin/sh
# video.sh – records the web app demo as a video, without anyone clicking: demo/out/dnssecretary-demo.webm
#
#   sh scripts/video.sh            about 4 minutes; the lab and the web app must be running (sh scripts/web.sh)
#   PACE=0.3 sh scripts/video.sh   shorter pauses, for a dry run
#
# It resets the hosting (demo.sh reset), then a browser in the "tour" container (Playwright,
# demo/tour.mjs) goes through the web app while three customer zones break, one by one, with a
# caption for every step: detected, verified in the sandbox, explained, fixed on the hosting.
# The hosting is left healthy (the three start zones still at risk). The browser is 1440x900;
# convert with e.g. ffmpeg -i demo/out/dnssecretary-demo.webm demo.mp4 if you need an MP4.

set -eu
cd "$(dirname "$0")/.."

curl -sf -o /dev/null http://localhost:8080/api/zones || { echo "The web app doesn't answer on :8080 – run: sh scripts/web.sh"; exit 1; }

LAB_UID=$(id -u)
LAB_GID=$(id -g)
export LAB_UID LAB_GID
mkdir -p demo/out
docker compose --profile demo run --rm tour
echo "Video: demo/out/dnssecretary-demo.webm"
