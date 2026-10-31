#!/bin/sh
# snapshots.sh – records a ZoneSnapshot (JSON) for good.test and every zone in the
# error catalogue. The unit tests run on these files (dnssecretary/src/test/resources/snapshots).
#
# Run from the host (the lab must be running and freshly signed, jar built):
#   sh scripts/snapshots.sh                  all zones except E1/E3, into the test resources
#   sh scripts/snapshots.sh build/snapshots  somewhere else (CI: only checks that collect works)
#   ROLLOVER=1 sh scripts/snapshots.sh       also runs e1-/e3-rollover.sh and records E1/E3
#
# E1 and E3 are visible only after their rollover scripts, and those change the lab.
# Afterwards run sign.sh and restart the servers to get a clean lab again.
#
# Exit: 0 if every snapshot was recorded, otherwise the tool's exit code.

set -eu
cd "$(dirname "$0")/.."

OUT=${1:-dnssecretary/src/test/resources/snapshots}
ROLLOVER=${ROLLOVER:-0}
mkdir -p "$OUT"

# A clean resolver cache, so that the resolver view doesn't depend on earlier queries.
docker compose restart resolver >/dev/null
sleep 2

echo ">> snimam sve zone iz kataloga u $OUT/"
sh scripts/dnssecretary.sh snapshot --catalog -d "$OUT"

if [ "$ROLLOVER" = 1 ]; then
    sh scripts/e1-rollover.sh
    sh scripts/dnssecretary.sh snapshot e1-rollover.test -d "$OUT"
    sh scripts/e3-rollover.sh
    sh scripts/dnssecretary.sh snapshot e3-rollover.test -d "$OUT"
    echo
    echo "E1/E3 su pokvareni uživo. Za čist lab:"
    echo "  docker compose run --rm --user \$(id -u):\$(id -g) signer sh scripts/sign.sh"
    echo "  docker compose restart root tld auth1 auth2 auth3 resolver"
fi
