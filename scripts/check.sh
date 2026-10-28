#!/bin/sh
# check.sh – pita resolver za svaku pokvarenu zonu i pokaže šta se desilo.
# Kolona "očekivano" je iz kataloga grešaka (tab "Prvo (MVP)").
#
# Pokretanje sa hosta (lab mora da radi: docker compose up -d):
#   sh scripts/check.sh
#
# Pitamo @10.53.0.53 direktno: Docker DNS (127.0.0.11) briše EDE iz odgovora.
#
# Resolver kešira i dobre i loše odgovore. Za čist rezultat posle sign.sh:
#   docker compose restart resolver

set -eu
cd "$(dirname "$0")/.."

# Jedan upit preko resolvera. Ispis: status, AD flag, EDE.
#   $1 = ime, $2 = tip
ask() {
    docker compose exec -T client dig @10.53.0.53 +dnssec +tries=1 +time=5 "$1" "$2" 2>/dev/null |
    awk '
        /^;; ->>HEADER<<-/ { sub(/,.*/, "", $6); status = $6 }
        /^;; flags:/       { ad = ($0 ~ / ad[ ;]/) ? "AD" : "--" }
        /^; EDE:/          { sub(/^; EDE: /, ""); sub(/\):.*/, ")"); ede = $0 }   # ceo razlog: docker compose logs resolver
        END { printf "%-8s %s  %s", status, ad, (ede == "" ? "-" : "EDE " ede) }'
}

row() {
    printf '%-3s %-34s %-6s | dobijeno: %s\n' "$1" "$2" "$3" "$(ask "$2" "$3")"
    printf '    %-41s | očekivano: %s\n' "" "$4"
}

echo "ID  upit                               tip"
echo "------------------------------------------------------------------------------"
row OK  www.good.test                 A      "NOERROR AD"
row A1  www.a1-expired.test           A      "SERVFAIL, EDE 7 (Signature Expired)"
row A3  www.a3-nosig.test             A      "SERVFAIL, EDE 10 (RRSIGs Missing)"
row A4  www.a4-bogus.test             A      "SERVFAIL, EDE 6 (DNSSEC Bogus)"
row A6  www.a6-expiring.test          A      "NOERROR AD (radi, ali potpis ističe za 2 dana)"
row B1  www.b1-ds-stale.test          A      "SERVFAIL, EDE 9 (DNSKEY Missing)"
row B2  www.b2-ds-digest.test         A      "SERVFAIL, EDE 6 ili 9"
row B4  www.b4-ds-unsigned.test       A      "SERVFAIL, EDE 9 ili 10"
row D1  www.d1-nsec-broken.test       A      "NOERROR AD (postojeće ime radi)"
row D1  missing.d1-nsec-broken.test   A      "SERVFAIL, EDE 12 (NSEC Missing) ili 6"
row D4  www.d4-nsec3-iter.test        A      "NOERROR AD (postojeće ime radi)"
row D4  missing.d4-nsec3-iter.test    A      "NXDOMAIN bez AD ili SERVFAIL, EDE 27"
row E1  www.e1-rollover.test          A      "NOERROR AD (pre e1-rollover.sh)"

# A6: koliko još važi potpis
exp=$(docker compose exec -T client dig @10.53.0.53 +dnssec +short www.a6-expiring.test A 2>/dev/null |
      awk '$1 == "A" { print $5; exit }')
[ -n "$exp" ] && echo && echo "A6: RRSIG za www.a6-expiring.test ističe $exp (UTC)"
exit 0
