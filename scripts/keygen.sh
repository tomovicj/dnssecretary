#!/bin/sh
# keygen.sh – makes a KSK and a ZSK for every zone in the lab.
# Run it ONCE. If a zone already has keys, it is skipped.
# (A key lives long, a signature short – that's why this is separate from sign.sh.)
#
# Run from the host:
#   docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/keygen.sh

set -eu
cd "$(dirname "$0")/.."      # work from the lab root, wherever it was called from

KEYDIR=keys
ALG=ECDSAP256SHA256          # algorithm 13
ZONES=". test. good.test."   # names WITH the trailing dot, same as in the file names

# Broken zones from the error catalogue (see scripts/break.sh)
# MVP tab:
ZONES="$ZONES a1-expired.test. a3-nosig.test. a4-bogus.test. a6-expiring.test."
ZONES="$ZONES b1-ds-stale.test. b2-ds-digest.test. b4-ds-unsigned.test."
ZONES="$ZONES d1-nsec-broken.test. d4-nsec3-iter.test. e1-rollover.test."
# Later tab:
ZONES="$ZONES a2-notyet.test. a5-unknown-key.test. a7-ttl-long.test."
ZONES="$ZONES b3-ds-alg.test. b5-dnskey-zsk.test. b6-island.test. b7-ds-sha1.test. b8-ds-extra.test."
ZONES="$ZONES c1-rsasha1.test. c2-two-alg.test. c3-rsa1024.test."
ZONES="$ZONES d2-bitmap.test. d3-nsec-nosig.test. d5-nsec3param.test. d6-nsec-mixed.test."
ZONES="$ZONES e2-ksk-ds-early.test. e3-rollover.test. e4-cds.test."
ZONES="$ZONES f1-stale-secondary.test. f2-tcp-edns.test."

mkdir -p "$KEYDIR"

# Algorithm for a zone. Almost all zones use 13, except errors C1 and C3.
alg_for() {
    case $1 in
        c1-rsasha1.test.) echo "RSASHA1" ;;              # algorithm 5 (deprecated)
        c3-rsa1024.test.) echo "RSASHA256 -b 1024" ;;    # algorithm 8, weak key
        *)                echo "$ALG" ;;
    esac
}

# Does the zone already have at least one key (in directory $2, default $KEYDIR)?
# The files look like this: K<zone>+<algorithm>+<keytag>.key
# e.g.  K.+013+12345.key   Ktest.+013+23456.key   Kc1-rsasha1.test.+005+34567.key
has_keys() {
    ls "${2:-$KEYDIR}"/K"$1"+*.key >/dev/null 2>&1
}

for zone in $ZONES; do
    if has_keys "$zone"; then
        echo ">> $zone: keys already exist, skipping"
        continue
    fi

    alg=$(alg_for "$zone")
    echo ">> $zone: making KSK and ZSK ($alg)"
    # $alg unquoted on purpose: it can also contain "-b 1024".
    # shellcheck disable=SC2086
    # -f KSK  -> flags 257 (SEP bit). The DS in the parent points to this key.
    ksk=$(dnssec-keygen -q -K "$KEYDIR" -a $alg -f KSK "$zone")
    # no -f   -> flags 256. This key signs the rest of the zone.
    # shellcheck disable=SC2086
    zsk=$(dnssec-keygen -q -K "$KEYDIR" -a $alg "$zone")

    echo "   KSK: $ksk"
    echo "   ZSK: $zsk"
done

# An extra key the zone does NOT sign with (that's why it lives in a separate directory,
# so dnssec-signzone -S doesn't find it). Made only if it doesn't exist yet.
#   $1 = directory in $KEYDIR, $2 = zone, rest = options for dnssec-keygen
extra_key() {
    dir=$KEYDIR/$1; zone=$2; shift 2
    mkdir -p "$dir"
    if has_keys "$zone" "$dir"; then
        echo ">> $zone: extra key in $dir/ already exists, skipping"
    else
        echo ">> $zone: making an extra key in $dir/"
        dnssec-keygen -q -K "$dir" "$@" "$zone" >/dev/null
    fi
}

# B1, B8: the "old" KSK. Its DS stays in the test. zone after the key change.
extra_key old  b1-ds-stale.test.     -a "$ALG" -f KSK
extra_key old  b8-ds-extra.test.     -a "$ALG" -f KSK
# E2: the "new" KSK whose DS reaches the parent before the key is published.
extra_key next e2-ksk-ds-early.test. -a "$ALG" -f KSK
# A5: a second ZSK. It signs the records, but it is not in the DNSKEY set.
extra_key k2   a5-unknown-key.test.  -a "$ALG"
# C2: an algorithm-8 KSK. It is published in the DNSKEY set (and has a DS), but signs nothing.
extra_key alg8 c2-two-alg.test.      -a RSASHA256 -b 2048 -f KSK

echo
echo "Done. The keys are in $KEYDIR/ (.key = public part, .private = SECRET part)."
