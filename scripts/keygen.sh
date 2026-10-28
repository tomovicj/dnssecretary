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
# Tab "Prvo (MVP)":
ZONES="$ZONES a1-expired.test. a3-nosig.test. a4-bogus.test. a6-expiring.test."
ZONES="$ZONES b1-ds-stale.test. b2-ds-digest.test. b4-ds-unsigned.test."
ZONES="$ZONES d1-nsec-broken.test. d4-nsec3-iter.test. e1-rollover.test."
# Tab "Kasnije":
ZONES="$ZONES a2-notyet.test. a5-unknown-key.test. a7-ttl-long.test."
ZONES="$ZONES b3-ds-alg.test. b5-dnskey-zsk.test. b6-island.test. b7-ds-sha1.test. b8-ds-extra.test."
ZONES="$ZONES c1-rsasha1.test. c2-two-alg.test. c3-rsa1024.test."
ZONES="$ZONES d2-bitmap.test. d3-nsec-nosig.test. d5-nsec3param.test. d6-nsec-mixed.test."
ZONES="$ZONES e2-ksk-ds-early.test. e3-rollover.test. e4-cds.test."
ZONES="$ZONES f1-stale-secondary.test. f2-tcp-edns.test."

mkdir -p "$KEYDIR"

# Algoritam za zonu. Skoro sve zone koriste 13, osim grešaka C1 i C3.
alg_for() {
    case $1 in
        c1-rsasha1.test.) echo "RSASHA1" ;;              # algoritam 5 (zastareo)
        c3-rsa1024.test.) echo "RSASHA256 -b 1024" ;;    # algoritam 8, slab ključ
        *)                echo "$ALG" ;;
    esac
}

# Da li za zonu već postoji bar jedan ključ (u folderu $2, default $KEYDIR)?
# Fajlovi izgledaju ovako: K<zona>+<algoritam>+<keytag>.key
# npr.  K.+013+12345.key   Ktest.+013+23456.key   Kc1-rsasha1.test.+005+34567.key
has_keys() {
    ls "${2:-$KEYDIR}"/K"$1"+*.key >/dev/null 2>&1
}

for zone in $ZONES; do
    if has_keys "$zone"; then
        echo ">> $zone: ključevi već postoje, preskačem"
        continue
    fi

    alg=$(alg_for "$zone")
    echo ">> $zone: pravim KSK i ZSK ($alg)"
    # $alg namerno bez navodnika: može da sadrži i "-b 1024".
    # -f KSK  -> flags 257 (SEP bit). Na ovaj ključ pokazuje DS u roditelju.
    ksk=$(dnssec-keygen -q -K "$KEYDIR" -a $alg -f KSK "$zone")
    # bez -f  -> flags 256. Ovaj ključ potpisuje ostatak zone.
    zsk=$(dnssec-keygen -q -K "$KEYDIR" -a $alg "$zone")

    echo "   KSK: $ksk"
    echo "   ZSK: $zsk"
done

# Dodatni ključ koji zona NE koristi za potpisivanje (zato je u posebnom folderu,
# da ga dnssec-signzone -S ne nađe). Pravi se samo ako već ne postoji.
#   $1 = folder u $KEYDIR, $2 = zona, ostalo = opcije za dnssec-keygen
extra_key() {
    dir=$KEYDIR/$1; zone=$2; shift 2
    mkdir -p "$dir"
    if has_keys "$zone" "$dir"; then
        echo ">> $zone: dodatni ključ u $dir/ već postoji, preskačem"
    else
        echo ">> $zone: pravim dodatni ključ u $dir/"
        dnssec-keygen -q -K "$dir" "$@" "$zone" >/dev/null
    fi
}

# B1, B8: "stari" KSK. Njegov DS ostaje u test. zoni posle promene ključa.
extra_key old  b1-ds-stale.test.     -a "$ALG" -f KSK
extra_key old  b8-ds-extra.test.     -a "$ALG" -f KSK
# E2: "novi" KSK čiji DS ode kod roditelja pre nego što je ključ objavljen.
extra_key next e2-ksk-ds-early.test. -a "$ALG" -f KSK
# A5: drugi ZSK. Njime se potpisuju zapisi, a u DNSKEY skupu ga nema.
extra_key k2   a5-unknown-key.test.  -a "$ALG"
# C2: KSK algoritma 8. Objavljen je u DNSKEY skupu (i DS ga ima), ali ništa ne potpisuje.
extra_key alg8 c2-two-alg.test.      -a RSASHA256 -b 2048 -f KSK

echo
echo "Gotovo. Ključevi su u $KEYDIR/ (.key = javni deo, .private = TAJNI deo)."
