#!/bin/sh
# sign-tld.sh – re-signs only the test. zone with the DS records of its children
# and delivers it to tld/. sign.sh calls it as step 2; DNSSECretary calls it when it
# fixes a DS in the parent (B1–B3, B6–B8, C2, E2) in the lab.
#
# Input:  zones/test.zone, build/dsset-good.test., build/parent-ds/*.ds
# Output: build/test.zone.signed, build/dsset-test., tld/test.zone.signed
#
# The root is not touched: the KSK of test. doesn't change, so its DS in the root stays valid.
#
# Run from the host:
#   docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign-tld.sh
#   docker compose restart tld

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+2592000}

[ -f "$BUILD/dsset-good.test." ] || { echo "Nema $BUILD/dsset-good.test. – pokreni prvo sign.sh"; exit 1; }

echo ">> potpisujem: test"
cat "$BUILD/dsset-good.test." "$BUILD"/parent-ds/*.ds > "$BUILD/dsset-test-children"
{ cat "$SRC/test.zone"; echo; cat "$BUILD/dsset-test-children"; } > "$BUILD/test.zone.in"

# Same options as sign_zone in sign.sh.
dnssec-signzone -S -K "$KEYDIR" -d "$BUILD" \
    -s "$SIG_START" -e "$SIG_END" -N unixtime \
    -o test -f "$BUILD/test.zone.signed" "$BUILD/test.zone.in" >/dev/null
dnssec-verify -o test "$BUILD/test.zone.signed"
#   -> produces $BUILD/dsset-test.

cp "$BUILD/test.zone.signed" tld/
