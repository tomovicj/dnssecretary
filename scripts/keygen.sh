#!/bin/sh
# keygen.sh – pravi KSK i ZSK za svaku zonu u labu.
# Pokreni JEDNOM. Ako ključevi već postoje, zona se preskače.
# (Ključ živi dugo, potpis kratko – zato je ovo odvojeno od sign.sh.)
#
# Pokretanje sa hosta:
#   docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/keygen.sh

set -eu
cd "$(dirname "$0")/.."      # radi iz korena laba, odakle god da je pozvan

KEYDIR=keys
ALG=ECDSAP256SHA256          # algoritam 13
ZONES=". test. good.test."   # imena SA tačkom na kraju, isto kao u imenima fajlova

# Pokvarene zone iz kataloga grešaka (vidi scripts/break.sh)
ZONES="$ZONES a1-expired.test. a3-nosig.test. a4-bogus.test. a6-expiring.test."
ZONES="$ZONES b1-ds-stale.test. b2-ds-digest.test. b4-ds-unsigned.test."
ZONES="$ZONES d1-nsec-broken.test. d4-nsec3-iter.test. e1-rollover.test."

mkdir -p "$KEYDIR"

# Da li za zonu već postoji bar jedan ključ?
# Fajlovi izgledaju ovako: K<zona>+013+<keytag>.key
# npr.  K.+013+12345.key   Ktest.+013+23456.key   Kgood.test.+013+34567.key
has_keys() {
    ls "$KEYDIR"/K"$1"+013+*.key >/dev/null 2>&1
}

for zone in $ZONES; do
    if has_keys "$zone"; then
        echo ">> $zone: ključevi već postoje, preskačem"
        continue
    fi

    echo ">> $zone: pravim KSK i ZSK"
    # -f KSK  -> flags 257 (SEP bit). Na ovaj ključ pokazuje DS u roditelju.
    ksk=$(dnssec-keygen -q -K "$KEYDIR" -a "$ALG" -f KSK "$zone")
    # bez -f  -> flags 256. Ovaj ključ potpisuje ostatak zone.
    zsk=$(dnssec-keygen -q -K "$KEYDIR" -a "$ALG" "$zone")

    echo "   KSK: $ksk"
    echo "   ZSK: $zsk"
done

# B1: "stari" KSK koji zona više ne koristi. Njegov DS ide u test. zonu,
# kao da je vlasnik promenio ključ, a DS kod registra ostao isti.
OLD=$KEYDIR/old
mkdir -p "$OLD"
if ls "$OLD"/Kb1-ds-stale.test.+013+*.key >/dev/null 2>&1; then
    echo ">> b1-ds-stale.test.: stari KSK već postoji, preskačem"
else
    echo ">> b1-ds-stale.test.: pravim stari KSK u $OLD/"
    dnssec-keygen -q -K "$OLD" -a "$ALG" -f KSK b1-ds-stale.test. >/dev/null
fi

echo
echo "Gotovo. Ključevi su u $KEYDIR/ (.key = javni deo, .private = TAJNI deo)."
