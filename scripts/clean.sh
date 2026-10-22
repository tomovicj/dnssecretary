#!/bin/sh
# clean.sh – briše sve što su napravile keygen.sh i sign.sh.
# Lab se vraća u stanje posle "git clone".
#
# Pokretanje (sa hosta, iz bilo kog foldera):
#   sh scripts/clean.sh          pita pre brisanja
#   sh scripts/clean.sh -n       samo pokaže šta bi obrisao (dry run)
#   sh scripts/clean.sh -k       obriše sve OSIM ključeva
#   sh scripts/clean.sh -y       ne pita
#
# Šta NE briše: zones/, configove (nsd.conf, unbound.conf, root.hints),
# skripte, compose.yaml, Dockerfile – sve što ti pišeš ručno.

set -eu
cd "$(dirname "$0")/.."

# Zaštita: ako nismo u korenu laba, ne brišemo ništa.
[ -f compose.yaml ] || { echo "Nema compose.yaml – ovo nije koren laba. Prekidam."; exit 1; }

usage() { echo "Upotreba: sh scripts/clean.sh [-n] [-k] [-y]"; }

DRY=0; KEEP_KEYS=0; YES=0
while getopts nky opt; do
    case $opt in
        n) DRY=1 ;;
        k) KEEP_KEYS=1 ;;
        y) YES=1 ;;
        *) usage; exit 1 ;;
    esac
done

# Skupljamo samo putanje koje STVARNO postoje.
# (Ako glob ništa ne nađe, ostane doslovno "*/*.signed" – [ -e ] to izbaci.)
TARGETS=""
add() {
    for p in "$@"; do
        [ -e "$p" ] && TARGETS="$TARGETS $p"
    done
    return 0
}

add build                    # privremeni fajlovi, dsset-*, *.zone.in
add */*.signed               # potpisane zone u root/, tld/, auth1/ (i auth2/ kasnije)
add dsset-* *.zone.in        # ako je nešto ikad završilo u korenu
add resolver/root.key        # trust anchor za Unbound
add client/delv-anchor.conf  # trust anchor za delv
[ "$KEEP_KEYS" -eq 1 ] || add keys

# Napomena: foldere root/, tld/, auth1/, resolver/, client/ NE brišemo,
# samo fajlove u njima. Montirani su u kontejnere – ako folder fali,
# Docker ga sam napravi kao root, i onda sign.sh ne može da piše u njega.

if [ -z "$TARGETS" ]; then
    echo "Već je čisto, nema šta da se briše."
    exit 0
fi

echo "Biće obrisano:"
for t in $TARGETS; do echo "  $t"; done

[ "$DRY" -eq 1 ] && { echo "(dry run – ništa nije obrisano)"; exit 0; }

if [ "$YES" -ne 1 ]; then
    printf "Nastavi? [y/N] "
    read -r ans
    case $ans in
        y|Y|da|DA) ;;
        *) echo "Prekinuto."; exit 0 ;;
    esac
fi

# $TARGETS namerno bez navodnika: treba da se podeli na više putanja.
if ! rm -rf -- $TARGETS; then
    echo
    echo "Neki fajlovi nisu obrisani – verovatno pripadaju root-u"
    echo "(nastali kad je signer pokrenut bez --user). Probaj:"
    echo "  sudo sh scripts/clean.sh"
    exit 1
fi

echo
echo "Očišćeno. Da lab ponovo proradi:"
[ "$KEEP_KEYS" -eq 1 ] || \
echo "  docker compose run --rm --user \$(id -u):\$(id -g) signer sh scripts/keygen.sh"
echo "  docker compose run --rm --user \$(id -u):\$(id -g) signer sh scripts/sign.sh"
echo "  docker compose restart root tld auth1 resolver"
