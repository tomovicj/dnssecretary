#!/bin/sh
# sign.sh – signs all zones BOTTOM-UP and builds the trust anchor.
# Run it every time you change a zone or the signatures expire.
#
# Run from the host:
#   docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh
# Then:
#   docker compose restart root tld auth1 auth2 auth3 resolver
#
# Signature validity can be changed with env variables (needed in phase 2):
#   SIG_START=now-3600  SIG_END=now+2592000   (default: from 1h ago to 30 days from now)

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+2592000}

mkdir -p "$BUILD" client

# ---------- helpers ----------

# Sign one zone and verify the result right away.
#   $1 = origin (e.g. good.test or .)
#   $2 = input (unsigned) file
#   $3 = output (signed) file
sign_zone() {
    origin=$1; input=$2; output=$3
    echo ">> signing: $origin"

    # -S          finds the keys in $KEYDIR itself and adds the DNSKEY records to the zone
    # -d $BUILD   where to write dsset-<zone>. (the DS record for the parent)
    # -s / -e     start and end of signature validity (inception / expiration)
    # -N unixtime SOA serial = current time, so it grows with every signing
    dnssec-signzone -S -K "$KEYDIR" -d "$BUILD" \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o "$origin" -f "$output" "$input" >/dev/null

    # Independent check. If this fails, set -e stops the script
    # and the broken zone does NOT reach the servers.
    dnssec-verify -o "$origin" "$output"
}

# Join the parent's source zone with the child's DS records.
#   $1 = parent source file, $2 = child dsset file, $3 = output
with_ds() {
    { cat "$1"; echo; cat "$2"; } > "$3"
}

# Find the KSK file for a zone (the one with flags 257).
ksk_file() {
    grep -l 'DNSKEY 257' "$KEYDIR"/K"$1"+013+*.key | head -n 1
}

# ---------- checks before starting ----------

for f in root.zone test.zone good.test.zone; do
    [ -f "$SRC/$f" ] || { echo "Missing $SRC/$f"; exit 1; }
done

# This script inserts the DS records. If $INCLUDE or hand-written DS records are left
# in the source, you get duplicate or stale DS records.
if grep -qiE '^\$INCLUDE|[[:space:]]DS[[:space:]]' "$SRC/test.zone" "$SRC/root.zone"; then
    echo "WARNING: zones/test.zone or zones/root.zone already contain \$INCLUDE or DS."
    echo "         Delete them – sign.sh adds the DS records from build/dsset-* itself."
    exit 1
fi

# ---------- 1. good.test (leaf of the tree) ----------
sign_zone good.test "$SRC/good.test.zone" "$BUILD/good.test.zone.signed"
#   -> produces $BUILD/dsset-good.test.

# ---------- 1b. broken zones (error catalogue) ----------
# No dnssec-verify – they are broken on purpose. See scripts/break.sh.
rm -rf "$BUILD/broken" "$BUILD/parent-ds" "$BUILD/tmp-break"
sh scripts/break.sh
#   -> produces $BUILD/broken/{,auth2/,auth3/}*.zone.signed and $BUILD/parent-ds/*.ds
# Keep the broken DS set: scripts/demo.sh break puts a zone back into its broken state.
cp -a "$BUILD/parent-ds" "$BUILD/broken/parent-ds"

# ---------- 1c. the demo hosting's customer zones (zones/hosting.list) ----------
sh scripts/hosting.sh
#   -> produces $BUILD/hosting/*.zone.signed (delivered to auth1/), their DS, auth1/hosting.conf

# ---------- 2. test. (parent of good.test, the broken zones and the hosting zones) ----------
# A separate script, because DNSSECretary re-signs only test. when it fixes a DS.
sh scripts/sign-tld.sh
#   -> produces $BUILD/dsset-test. and delivers tld/test.zone.signed

# ---------- 3. root (parent of test.) ----------
with_ds "$SRC/root.zone" "$BUILD/dsset-test." "$BUILD/root.zone.in"
sign_zone . "$BUILD/root.zone.in" "$BUILD/root.zone.signed"

# ---------- 4. trust anchor ----------
# The resolver can't ask "what is the root key?" – it has to know it in advance.
# We build a DS (SHA-256 hash) from the root KSK.
root_ksk=$(ksk_file .)
[ -n "$root_ksk" ] || { echo "No root KSK – run keygen.sh first"; exit 1; }

dnssec-dsfromkey -2 "$root_ksk" > resolver/root.key      # format for Unbound
echo ">> trust anchor: $(cat resolver/root.key)"

# The same anchor in BIND format, for delv in the client container.
awk '{ for (i = 1; i <= NF; i++) if ($i == "DS") {
         printf "trust-anchors {\n    . static-ds %s %s %s \"%s\";\n};\n",
                $(i+1), $(i+2), $(i+3), $(i+4); exit } }' \
    resolver/root.key > client/delv-anchor.conf

# ---------- 5. delivery to the servers ----------
cp "$BUILD/root.zone.signed"      root/
cp "$BUILD/good.test.zone.signed" auth1/
cp "$BUILD"/broken/*.zone.signed  auth1/
cp "$BUILD"/broken/auth2/*.signed  auth2/   # F1: stale zone on the "secondary"
cp "$BUILD"/broken/auth3/*.signed  auth3/   # F2: server without TCP

echo
echo "Done. Now run on the host:"
echo "  docker compose restart root tld auth1 auth2 auth3 resolver"
