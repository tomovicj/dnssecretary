#!/bin/sh
# sign.sh – potpisuje sve zone ODOZDO NAGORE i pravi trust anchor.
# Pokreni svaki put kad promeniš zonu ili kad potpisi isteknu.
#
# Pokretanje sa hosta:
#   docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh
# Posle toga:
#   docker compose restart root tld auth1 resolver
#
# Važenje potpisa možeš da promeniš preko env promenljivih (trebaće u fazi 2):
#   SIG_START=now-3600  SIG_END=now+2592000   (default: od pre 1h do za 30 dana)

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+2592000}

mkdir -p "$BUILD" client

# ---------- pomoćne funkcije ----------

# Potpiši jednu zonu i odmah proveri rezultat.
#   $1 = origin (npr. good.test ili .)
#   $2 = ulazni (nepotpisani) fajl
#   $3 = izlazni (potpisani) fajl
sign_zone() {
    origin=$1; input=$2; output=$3
    echo ">> potpisujem: $origin"

    # -S          sam nađe ključeve u $KEYDIR i ubaci DNSKEY zapise u zonu
    # -d $BUILD   gde da upiše dsset-<zona>. (DS zapis za roditelja)
    # -s / -e     početak i kraj važenja potpisa (inception / expiration)
    # -N unixtime SOA serial = trenutno vreme, pa raste pri svakom potpisivanju
    dnssec-signzone -S -K "$KEYDIR" -d "$BUILD" \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o "$origin" -f "$output" "$input" >/dev/null

    # Nezavisna provera. Ako ovo padne, set -e prekida skriptu
    # i pokvarena zona NE stiže do servera.
    dnssec-verify -o "$origin" "$output"
}

# Spoji izvornu zonu deteta-roditelja sa DS zapisom deteta.
#   $1 = izvorni fajl roditelja, $2 = dsset fajl deteta, $3 = izlaz
with_ds() {
    { cat "$1"; echo; cat "$2"; } > "$3"
}

# Nađi KSK fajl za zonu (onaj sa flags 257).
ksk_file() {
    grep -l 'DNSKEY 257' "$KEYDIR"/K"$1"+013+*.key | head -n 1
}

# ---------- provere pre početka ----------

for f in root.zone test.zone good.test.zone; do
    [ -f "$SRC/$f" ] || { echo "Nema $SRC/$f"; exit 1; }
done

# DS zapise ubacuje ova skripta. Ako su ostali $INCLUDE ili ručni DS u izvoru,
# dobićeš duple ili zastarele DS zapise.
if grep -qiE '^\$INCLUDE|[[:space:]]DS[[:space:]]' "$SRC/test.zone" "$SRC/root.zone"; then
    echo "UPOZORENJE: zones/test.zone ili zones/root.zone već sadrže \$INCLUDE ili DS."
    echo "            Obriši ih – sign.sh sam dodaje DS iz build/dsset-*."
    exit 1
fi

# ---------- 1. good.test (list stabla) ----------
sign_zone good.test "$SRC/good.test.zone" "$BUILD/good.test.zone.signed"
#   -> nastaje $BUILD/dsset-good.test.

# ---------- 2. test. (roditelj od good.test) ----------
with_ds "$SRC/test.zone" "$BUILD/dsset-good.test." "$BUILD/test.zone.in"
sign_zone test "$BUILD/test.zone.in" "$BUILD/test.zone.signed"
#   -> nastaje $BUILD/dsset-test.

# ---------- 3. root (roditelj od test.) ----------
with_ds "$SRC/root.zone" "$BUILD/dsset-test." "$BUILD/root.zone.in"
sign_zone . "$BUILD/root.zone.in" "$BUILD/root.zone.signed"

# ---------- 4. trust anchor ----------
# Resolver ne može da pita "koji je root ključ?" – mora da ga zna unapred.
# Pravimo DS (SHA-256 hash) od root KSK.
root_ksk=$(ksk_file .)
[ -n "$root_ksk" ] || { echo "Nema root KSK – pokreni prvo keygen.sh"; exit 1; }

dnssec-dsfromkey -2 "$root_ksk" > resolver/root.key      # format za Unbound
echo ">> trust anchor: $(cat resolver/root.key)"

# Isti anchor u BIND formatu, za delv u client kontejneru.
awk '{ for (i = 1; i <= NF; i++) if ($i == "DS") {
         printf "trust-anchors {\n    . static-ds %s %s %s \"%s\";\n};\n",
                $(i+1), $(i+2), $(i+3), $(i+4); exit } }' \
    resolver/root.key > client/delv-anchor.conf

# ---------- 5. isporuka serverima ----------
cp "$BUILD/root.zone.signed"      root/
cp "$BUILD/test.zone.signed"      tld/
cp "$BUILD/good.test.zone.signed" auth1/

echo
echo "Gotovo. Sad pokreni na hostu:"
echo "  docker compose restart root tld auth1 resolver"
