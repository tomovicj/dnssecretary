#!/bin/sh
# heal.sh – signs EVERY catalogue and hosting zone correctly: a healthy hosting to start the web app demo from
# (then scripts/demo.sh break <zone> breaks zones one by one, live).
#
# Runs where the BIND tools are, like sign.sh; normally through scripts/demo.sh heal, which
# also restarts the servers. Needs a signed lab (sign.sh): build/dsset-good.test., build/broken/.
#
# C1 and C3 stay "at risk": their keys are weak by design (keygen.sh), and heal makes no new keys.
# Signatures are valid for 60 days, so they outlast the longest TTL in the sources (30 days, A7).
# F2's server gets TCP and EDNS 1232 (auth3/unbound.conf); demo.sh reset puts its broken config back.

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
OUT=$BUILD/heal
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+5184000}

[ -d "$BUILD/broken" ] || { echo "Missing $BUILD/broken – run sign.sh first"; exit 1; }
rm -rf "$OUT"
mkdir -p "$OUT" "$BUILD/parent-ds"

for f in "$SRC"/*.test.zone; do
    z=$(basename "$f" .zone)
    [ "$z" = good.test ] && continue   # sign.sh already signs it correctly

    # A zone that is NSEC3 in the lab stays NSEC3, with the RFC 9276 parameters.
    nsec3=""
    if grep -qs NSEC3PARAM "$BUILD/broken/$z.zone.signed" "$BUILD/broken/auth3/$z.zone.signed"; then
        nsec3="-3 - -H 0"
    fi
    # -S takes only the keys in keys/ itself: the helper keys in old/, next/, k2/, alg8/ stay out.
    # shellcheck disable=SC2086
    dnssec-signzone -S -K "$KEYDIR" -d "$OUT" -s "$SIG_START" -e "$SIG_END" -N unixtime $nsec3 \
        -o "$z" -f "$OUT/$z.zone.signed" "$f" >/dev/null
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    cp "$OUT/dsset-$z." "$BUILD/parent-ds/$z.ds"

    # The same servers as the broken zone (F1: auth1 and auth2, F2: auth3); hosting zones: auth1.
    if [ -f "$BUILD/broken/$z.zone.signed" ] || grep -q "^$z\([[:space:]]\|$\)" "$SRC/hosting.list"; then
        cp "$OUT/$z.zone.signed" auth1/
    fi
    [ -f "$BUILD/broken/auth2/$z.zone.signed" ] && cp "$OUT/$z.zone.signed" auth2/
    [ -f "$BUILD/broken/auth3/$z.zone.signed" ] && cp "$OUT/$z.zone.signed" auth3/
    echo ">> healed: $z"
done

sed -i -e 's/^\([[:space:]]*do-tcp:\).*/\1 yes/' -e 's/^\([[:space:]]*max-udp-size:\).*/\1 1232/' \
    auth3/unbound.conf

sh scripts/sign-tld.sh
echo
echo "Done. Restart the servers: docker compose restart tld auth1 auth2 auth3 resolver"
