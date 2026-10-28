#!/bin/sh
# break.sh – pravi po jednu POKVARENU zonu za svaku grešku iz kataloga
# (DNSSECretary-katalog-gresaka, tabovi "Prvo (MVP)" i "Kasnije").
#
# Obično je ne pokrećeš ručno: sign.sh je poziva posle good.test, a pre test.
# zone, jer test. mora da dobije DS zapise ovih zona.
#
# Izlaz:
#   build/broken/<zona>.zone.signed         zone koje služi auth1
#   build/broken/auth2/<zona>.zone.signed   zone koje služi auth2 (F1)
#   build/broken/auth3/<zona>.zone.signed   zone koje služi auth3 (F2)
#   build/parent-ds/<zona>.ds               DS koji ide u test. zonu (za neke greške namerno loš)
#
# Posebni režim (za E1 i E3, pozivaju ga e1-rollover.sh i e3-rollover.sh):
#   sh scripts/break.sh rollover <zona>     zameni ZSK bez prethodnog objavljivanja
#
# Pokvarene zone NE prolaze kroz dnssec-verify – pale bi proveru, a to i hoćemo.

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
OUT=$BUILD/broken
PDS=$BUILD/parent-ds
TMP=$BUILD/tmp-break        # pomoćna potpisivanja (A5, D5, D6)
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+2592000}

mkdir -p "$OUT/auth2" "$OUT/auth3" "$PDS" "$TMP"

# ---------- pomoćne funkcije ----------

# Potpiši zonu bez ugrađene provere (-P).
#   $1 = ulaz, $2 = izlaz, $3 = zona (bez tačke), $4/$5 = početak/kraj važenja,
#   $6 = SOA serial (unixtime ili keep), ostalo = dodatne opcije za dnssec-signzone
# -O full: jedan zapis = jedan red, pa awk lako nađe i izmeni tačno jedan zapis.
signzone() {
    in=$1; out=$2; zone=$3; start=$4; end=$5; serial=$6; shift 6
    dnssec-signzone -P -S -K "$KEYDIR" -d "$BUILD" -O full \
        -s "$start" -e "$end" -N "$serial" "$@" \
        -o "$zone" -f "$out" "$in" >/dev/null
}

# Najčešći slučaj: izvor iz zones/, izlaz za auth1.
#   $1 = zona, $2/$3 = početak/kraj važenja, ostalo = dodatne opcije
sign_raw() {
    zone=$1; start=$2; end=$3; shift 3
    signzone "$SRC/$zone.zone" "$OUT/$zone.zone.signed" "$zone" "$start" "$end" unixtime "$@"
}

# Ispravan DS za roditelja (dsset koji je napravio dnssec-signzone).
good_ds() {
    cp "$BUILD/dsset-$1." "$PDS/$1.ds"
}

# KSK fajl zone (flags 257), u $KEYDIR ili u drugom folderu ($2). Bilo koji algoritam.
ksk_file() {
    grep -l 'DNSKEY 257' "${2:-$KEYDIR}"/K"$1".+*.key | head -n 1
}

# Key tag iz imena fajla: Kzona.+013+01193.key -> 1193
key_tag() {
    basename "$1" .key | awk -F+ '{ print $3 + 0 }'
}

# DNSKEY zapis iz .key fajla (bez komentara na početku).
dnskey_of() {
    grep -v '^;' "$1"
}

# Izmeni potpisanu zonu na mestu, awk programom $2 (fajl: $3, default izlaz za auth1).
edit_zone() {
    f=${3:-$OUT/$1.zone.signed}
    awk "$2" "$f" > "$f.tmp"
    mv "$f.tmp" "$f"
}

# ---------- greške: tab "Prvo (MVP)" ----------

# A1 – istekao potpis. Važio od pre 37 dana do pre 7 dana.
# (Unbound toleriše do 1 dan razlike u satu – val-sig-skew-max – pa "juče" nije dovoljno.)
break_a1() {
    z=a1-expired.test
    sign_raw $z now-3196800 now-604800
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

# ---------- greške: tab "Kasnije" ----------

# A2 – potpis još nije važeći: počinje tek za 7 dana (kao da je sat na serveru pomeren).
# "Sutra" nije dovoljno: Unbound toleriše do 1 dan razlike u satu (val-sig-skew-max).
break_a2() {
    z=a2-notyet.test
    sign_raw $z now+604800 now+3196800
    good_ds $z
}

# A5 – potpis ključem koji nije u DNSKEY skupu.
# Dva potpisivanja: (1) KSK + ZSK1 – odatle uzimamo samo DNSKEY skup i njegov potpis;
# (2) samo ZSK2 (keys/k2/) – odatle uzimamo sve ostalo. ZSK2 se nigde ne objavljuje.
break_a5() {
    z=a5-unknown-key.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-signzone -P -S -K "$KEYDIR/k2" -d "$TMP" -O full \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o $z -f "$TMP/$z.k2" "$SRC/$z.zone" >/dev/null
    f=$OUT/$z.zone.signed
    { awk '$4 == "DNSKEY" || ($4 == "RRSIG" && $5 == "DNSKEY")' "$f"
      awk '!($4 == "DNSKEY" || ($4 == "RRSIG" && $5 == "DNSKEY"))' "$TMP/$z.k2"; } > "$f.tmp"
    mv "$f.tmp" "$f"
    good_ds $z
}

# A7 – TTL duži od važenja potpisa: TTL 30 dana (u izvoru), potpisi važe 7 dana.
break_a7() {
    z=a7-ttl-long.test
    sign_raw $z "$SIG_START" now+604800
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    good_ds $z
}

# B3 – DS i DNSKEY imaju različit algoritam: u DS-u 13 → 8.
break_b3() {
    z=b3-ds-alg.test
    sign_raw $z "$SIG_START" "$SIG_END"
    # dsfromkey: "zona. IN DS <tag> <alg> <digest tip> <digest>"
    dnssec-dsfromkey -2 "$(ksk_file $z)" | awk '{ $5 = 8; print }' > "$PDS/$z.ds"
}

# B5 – DNSKEY skup nije potpisan KSK-om: obriši potpis KSK-a nad DNSKEY skupom.
# (dnssec-signzone potpisuje DNSKEY skup sa oba ključa, pa ostaje samo potpis ZSK-a.)
break_b5() {
    z=b5-dnskey-zsk.test
    tag=$(key_tag "$(ksk_file $z)")
    sign_raw $z "$SIG_START" "$SIG_END"
    # RRSIG polja: $5 tip, ..., $11 key tag
    edit_zone $z '!($4 == "RRSIG" && $5 == "DNSKEY" && $11 == '"$tag"')'
    good_ds $z
}

# B6 – "ostrvo": zona je ispravno potpisana, ali DS ne ide u roditelja.
break_b6() {
    z=b6-island.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    # namerno nema $PDS/$z.ds
}

# B7 – DS sa SHA-1 (digest tip 1). Zona je ispravna.
break_b7() {
    z=b7-ds-sha1.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    dnssec-dsfromkey -1 "$(ksk_file $z)" > "$PDS/$z.ds"
}

# B8 – višak starih DS zapisa: ispravan DS + DS starog KSK (keys/old/).
break_b8() {
    z=b8-ds-extra.test
    old=$(ksk_file $z "$KEYDIR/old")
    [ -n "$old" ] || { echo "Nema starog KSK za $z – pokreni keygen.sh"; exit 1; }
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    { cat "$BUILD/dsset-$z."; dnssec-dsfromkey -2 "$old"; } > "$PDS/$z.ds"
}

# C1 – zastareo algoritam: ključevi su RSASHA1 (algoritam 5), vidi keygen.sh.
break_c1() {
    z=c1-rsasha1.test
    sign_raw $z "$SIG_START" "$SIG_END"
    good_ds $z
}

# C2 – dva algoritma u DNSKEY skupu i u DS-u, a zapisi su potpisani samo sa 13.
# KSK algoritma 8 (keys/alg8/) samo objavimo – nema ga u $KEYDIR, pa ništa ne potpisuje.
break_c2() {
    z=c2-two-alg.test
    alg8=$(ksk_file $z "$KEYDIR/alg8")
    [ -n "$alg8" ] || { echo "Nema KSK algoritma 8 za $z – pokreni keygen.sh"; exit 1; }
    { cat "$SRC/$z.zone"; echo; dnskey_of "$alg8"; } > "$TMP/$z.in"
    signzone "$TMP/$z.in" "$OUT/$z.zone.signed" $z "$SIG_START" "$SIG_END" unixtime
    { dnssec-dsfromkey -2 "$(ksk_file $z)"; dnssec-dsfromkey -2 "$alg8"; } > "$PDS/$z.ds"
}

# C3 – slab RSA ključ: RSASHA256 sa 1024 bita, vidi keygen.sh. Zona je ispravna.
break_c3() {
    z=c3-rsa1024.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    good_ds $z
}

# D2 – pogrešna lista tipova u NSEC: izvor ima www TXT, pa NSEC za www kaže
# "A TXT RRSIG NSEC". Posle potpisivanja obrišemo TXT (i njegov RRSIG) –
# NSEC ostaje ispravno potpisan, ali laže da TXT postoji.
break_d2() {
    z=d2-bitmap.test
    sign_raw $z "$SIG_START" "$SIG_END"
    edit_zone $z '!($1 == "www.'$z'." && ($4 == "TXT" || ($4 == "RRSIG" && $5 == "TXT")))'
    good_ds $z
}

# D3 – negativan odgovor bez potpisa: obriši sve RRSIG nad NSEC zapisima.
break_d3() {
    z=d3-nsec-nosig.test
    sign_raw $z "$SIG_START" "$SIG_END"
    edit_zone $z '!($4 == "RRSIG" && $5 == "NSEC")'
    good_ds $z
}

# D5 – NSEC3PARAM se ne slaže sa NSEC3: lanac je bez salt-a, a NSEC3PARAM
# (uzet iz drugog potpisivanja istim ključem, pa je ispravno potpisan) ima salt "abcd".
break_d5() {
    z=d5-nsec3param.test
    sign_raw $z "$SIG_START" "$SIG_END" -3 - -H 0
    dnssec-signzone -P -S -K "$KEYDIR" -d "$TMP" -O full -3 abcd -H 0 \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o $z -f "$TMP/$z.salt" "$SRC/$z.zone" >/dev/null
    f=$OUT/$z.zone.signed
    { awk '!($4 == "NSEC3PARAM" || ($4 == "RRSIG" && $5 == "NSEC3PARAM"))' "$f"
      awk '$4 == "NSEC3PARAM" || ($4 == "RRSIG" && $5 == "NSEC3PARAM")' "$TMP/$z.salt"; } > "$f.tmp"
    mv "$f.tmp" "$f"
    good_ds $z
}

# D6 – NSEC i NSEC3 pomešani: NSEC3 zona + NSEC zapisi (sa potpisima)
# iz drugog potpisivanja iste zone, kao da prelazak NSEC → NSEC3 nije završen.
break_d6() {
    z=d6-nsec-mixed.test
    sign_raw $z "$SIG_START" "$SIG_END" -3 - -H 0
    dnssec-signzone -P -S -K "$KEYDIR" -d "$TMP" -O full \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o $z -f "$TMP/$z.nsec" "$SRC/$z.zone" >/dev/null
    awk '$4 == "NSEC" || ($4 == "RRSIG" && $5 == "NSEC")' "$TMP/$z.nsec" >> "$OUT/$z.zone.signed"
    good_ds $z
}

# E2 – KSK rollover, DS zamenjen prerano: u roditelju je već DS novog KSK
# (keys/next/), a zona još uvek objavljuje i koristi samo stari.
break_e2() {
    z=e2-ksk-ds-early.test
    new=$(ksk_file $z "$KEYDIR/next")
    [ -n "$new" ] || { echo "Nema novog KSK za $z – pokreni keygen.sh"; exit 1; }
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-dsfromkey -2 "$new" > "$PDS/$z.ds"
}

# E3 – za početak ISPRAVNA zona. Kvari je e3-rollover.sh (uživo): resolver
# kešira zapise potpisane starim ZSK-om, a stari ZSK odmah nestaje iz zone.
break_e3() {
    z=e3-rollover.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    good_ds $z
}

# E4 – CDS ne odgovara ključevima: objavi (ispravno potpisan) CDS sa pogrešnim digest-om.
break_e4() {
    z=e4-cds.test
    { cat "$SRC/$z.zone"; echo
      dnssec-dsfromkey -2 "$(ksk_file $z)" |
          awk '{ $3 = "CDS"; c = substr($NF, 1, 1); $NF = (c == "0" ? "1" : "0") substr($NF, 2); print }'
    } > "$TMP/$z.in"
    signzone "$TMP/$z.in" "$OUT/$z.zone.signed" $z "$SIG_START" "$SIG_END" unixtime
    good_ds $z
}

# F1 – sekundarni server ima staru zonu: auth1 dobija ispravnu zonu,
# auth2 staru kopiju (serial iz izvora, potpisi istekli pre 7 dana).
break_f1() {
    z=f1-stale-secondary.test
    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    signzone "$SRC/$z.zone" "$OUT/auth2/$z.zone.signed" $z now-3196800 now-604800 keep
    good_ds $z
}

# F2 – server ne radi sa velikim odgovorima: zona je ispravna, ali je služi
# auth3 (bez TCP-a, UDP do 512 bajtova). Veliki TXT + RRSIG ne staje u 512.
break_f2() {
    z=f2-tcp-edns.test
    signzone "$SRC/$z.zone" "$OUT/auth3/$z.zone.signed" $z "$SIG_START" "$SIG_END" unixtime
    dnssec-verify -q -o $z "$OUT/auth3/$z.zone.signed"
    good_ds $z
}

# ---------- E1 / E3: zamena ZSK-a uživo ----------

# Novi ZSK odmah u upotrebi, stari odmah izbačen.
# Stari ZSK ide u keys/retired/, da ga dnssec-signzone -S više ne vidi.
#   $1 = zona (e1-rollover.test ili e3-rollover.test)
rollover() {
    z=$1
    old=$(grep -l 'DNSKEY 256' "$KEYDIR"/K$z.+013+*.key | head -n 1)
    [ -n "$old" ] || { echo "Nema ZSK za $z – pokreni keygen.sh"; exit 1; }

    mkdir -p "$KEYDIR/retired"
    mv "$old" "${old%.key}.private" "$KEYDIR/retired/"
    new=$(dnssec-keygen -q -K "$KEYDIR" -a ECDSAP256SHA256 $z)
    echo ">> $z: stari ZSK $(basename "${old%.key}") -> keys/retired/, novi ZSK $new"

    sign_raw $z "$SIG_START" "$SIG_END"
    dnssec-verify -q -o $z "$OUT/$z.zone.signed"
    cp "$OUT/$z.zone.signed" auth1/
}

# ---------- glavni deo ----------

if [ "${1:-}" = "rollover" ]; then
    case ${2:-} in
        e1-rollover.test|e3-rollover.test) rollover "$2" ;;
        *) echo "Upotreba: sh scripts/break.sh rollover e1-rollover.test|e3-rollover.test"; exit 1 ;;
    esac
    exit 0
fi

for e in a1 a3 a4 a6 b1 b2 b4 d1 d4 e1 \
         a2 a5 a7 b3 b5 b6 b7 b8 c1 c2 c3 d2 d3 d5 d6 e2 e3 e4 f1 f2; do
    echo ">> pokvarena zona: $e"
    break_$e
done
