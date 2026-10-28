#!/bin/sh
# break.sh – pravi po jednu POKVARENU zonu za svaku grešku iz kataloga
# (DNSSECretary-katalog-gresaka, tab "Prvo (MVP)").
#
# Obično je ne pokrećeš ručno: sign.sh je poziva posle good.test, a pre test.
# zone, jer test. mora da dobije DS zapise ovih zona.
#
# Izlaz:
#   build/broken/<zona>.zone.signed   zona koju služi auth1
#   build/parent-ds/<zona>.ds         DS koji ide u test. zonu (za B1/B2/B4 namerno loš)
#
# Posebni režim (samo za E1, poziva ga e1-rollover.sh):
#   sh scripts/break.sh e1-rollover   zameni ZSK bez prethodnog objavljivanja
#
# Pokvarene zone NE prolaze kroz dnssec-verify – pale bi proveru, a to i hoćemo.

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
OUT=$BUILD/broken
PDS=$BUILD/parent-ds
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+2592000}

mkdir -p "$OUT" "$PDS"

# ---------- pomoćne funkcije ----------

# Potpiši zonu bez ugrađene provere (-P).
#   $1 = zona (bez tačke), $2 = početak, $3 = kraj važenja potpisa
# -O full: jedan zapis = jedan red, pa awk lako nađe i izmeni tačno jedan zapis.
sign_raw() {
    zone=$1
    dnssec-signzone -P -S -K "$KEYDIR" -d "$BUILD" -O full \
        -s "$2" -e "$3" -N unixtime \
        -o "$zone" -f "$OUT/$zone.zone.signed" "$SRC/$zone.zone" >/dev/null
}

# Ispravan DS za roditelja (dsset koji je napravio dnssec-signzone).
good_ds() {
    cp "$BUILD/dsset-$1." "$PDS/$1.ds"
}

# KSK fajl zone (flags 257), u $KEYDIR ili u drugom folderu ($2).
ksk_file() {
    grep -l 'DNSKEY 257' "${2:-$KEYDIR}"/K"$1".+013+*.key | head -n 1
}

# Izmeni potpisanu zonu na mestu, awk programom $2.
edit_zone() {
    f=$OUT/$1.zone.signed
    awk "$2" "$f" > "$f.tmp"
    mv "$f.tmp" "$f"
}

# ---------- greške ----------

# A1 – istekao potpis. Važio od pre 30 dana do juče.
break_a1() {
    z=a1-expired.test
    sign_raw $z now-2592000 now-86400
    good_ds $z
}

# A3 – zapis bez potpisa: obriši RRSIG za www A.
break_a3() {
    z=a3-nosig.test
    sign_raw $z "$SIG_START" "$SIG_END"
    edit_zone $z '!($1 == "www.'$z'." && $4 == "RRSIG" && $5 == "A")'
    good_ds $z
}

# A4 – neispravan potpis: promeni IP za www posle potpisivanja.
break_a4() {
    z=a4-bogus.test
    sign_raw $z "$SIG_START" "$SIG_END"
    edit_zone $z '$1 == "www.'$z'." && $4 == "A" { $5 = "10.53.0.99" } { print }'
    good_ds $z
}

# A6 – potpis uskoro ističe: važi još samo 2 dana. Zona je ispravna.
break_a6() {
    z=a6-expiring.test
    sign_raw $z "$SIG_START" now+172800
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    good_ds $z
}

# B1 – DS pokazuje na ključ koji ne postoji: u roditelju je DS starog KSK
# (keys/old/, pravi ga keygen.sh), a zona je potpisana novim.
break_b1() {
    z=b1-ds-stale.test
    old=$(ksk_file $z "$KEYDIR/old")
    [ -n "$old" ] || { echo "Nema starog KSK za $z – pokreni keygen.sh"; exit 1; }
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-dsfromkey -2 "$old" > "$PDS/$z.ds"
}

# B2 – DS ima pogrešan digest: promeni jedan hex znak
# (prvi znak poslednjeg polja – dsset deli digest na više polja).
break_b2() {
    z=b2-ds-digest.test
    sign_raw $z "$SIG_START" "$SIG_END"
    awk '{ c = substr($NF, 1, 1); $NF = (c == "0" ? "1" : "0") substr($NF, 2); print }' \
        "$BUILD/dsset-$z." > "$PDS/$z.ds"
}

# B4 – DS postoji, a zona nije potpisana: služimo izvor kakav jeste.
# Ključevi postoje samo da bi DS imao od čega da se napravi.
break_b4() {
    z=b4-ds-unsigned.test
    cp "$SRC/$z.zone" "$OUT/$z.zone.signed"
    dnssec-dsfromkey -2 "$(ksk_file $z)" > "$PDS/$z.ds"
}

# D1 – pokidan NSEC lanac: obriši NSEC (i njegov RRSIG) od mail.
# Taj NSEC pokriva opseg mail → ns1, npr. ime "missing".
break_d1() {
    z=d1-nsec-broken.test
    sign_raw $z "$SIG_START" "$SIG_END"
    edit_zone $z '!($1 == "mail.'$z'." && ($4 == "NSEC" || ($4 == "RRSIG" && $5 == "NSEC")))'
    good_ds $z
}

# D4 – NSEC3 protiv RFC 9276: 200 iteracija + salt.
# dnssec-signzone odbija > 150 iteracija, zato ldns-signzone (čita iste BIND ključeve).
break_d4() {
    z=d4-nsec3-iter.test
    keys=$(ls "$KEYDIR"/K$z.+013+*.private | sed 's/\.private$//')
    # shellcheck disable=SC2086  # $keys namerno bez navodnika: više ključeva
    ldns-signzone -n -t 200 -s abcdef -o $z -f "$OUT/$z.zone.signed" \
        "$SRC/$z.zone" $keys 2>/dev/null
    dnssec-dsfromkey -2 "$(ksk_file $z)" > "$PDS/$z.ds"
}

# E1 – zona je za početak ISPRAVNA. Kvari je tek e1-rollover.sh (uživo),
# jer greška zavisi od toga šta resolver već ima u kešu.
break_e1() {
    z=e1-rollover.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    good_ds $z
}

# E1, drugi korak: novi ZSK odmah u upotrebi, stari odmah izbačen.
# Stari ZSK ide u keys/retired/, da ga dnssec-signzone -S više ne vidi.
e1_rollover() {
    z=e1-rollover.test
    old=$(grep -l 'DNSKEY 256' "$KEYDIR"/K$z.+013+*.key | head -n 1)
    [ -n "$old" ] || { echo "Nema ZSK za $z – pokreni keygen.sh"; exit 1; }

    mkdir -p "$KEYDIR/retired"
    mv "$old" "${old%.key}.private" "$KEYDIR/retired/"
    new=$(dnssec-keygen -q -K "$KEYDIR" -a ECDSAP256SHA256 $z)
    echo ">> $z: stari ZSK $(basename "${old%.key}") -> keys/retired/, novi ZSK $new"

    break_e1
    cp "$OUT/$z.zone.signed" auth1/
}

# ---------- glavni deo ----------

if [ "${1:-}" = "e1-rollover" ]; then
    e1_rollover
    exit 0
fi

for e in a1 a3 a4 a6 b1 b2 b4 d1 d4 e1; do
    echo ">> pokvarena zona: $e"
    break_$e
done
