#!/bin/sh
# e1-rollover.sh – greška E1 uživo: ZSK promenjen prebrzo.
#
# 1. Resolver upita DNSKEY za e1-rollover.test i stavi ga u keš (TTL 3600 s).
# 2. Zamenimo ZSK BEZ prethodnog objavljivanja: novi odmah potpisuje, stari odmah nestaje.
# 3. auth1 služi novu zonu, a resolver i dalje ima STARI DNSKEY skup u kešu
#    -> potpis novim ZSK ne može da se proveri -> SERVFAIL, dok keš ne istekne.
#
# Pokretanje sa hosta (lab mora da radi: docker compose up -d):
#   sh scripts/e1-rollover.sh
#
# Za ponovni pokušaj: sign.sh + docker compose restart root tld auth1 resolver.

set -eu
cd "$(dirname "$0")/.."

Z=e1-rollover.test

q() {
    docker compose exec -T client dig @10.53.0.53 +dnssec +tries=1 +time=5 "$@" 2>/dev/null |
    awk '/^;; ->>HEADER<<-/ { sub(/,.*/, "", $6); s = $6 }
         /^;; flags:/       { ad = ($0 ~ / ad[ ;]/) ? "AD" : "--" }
         /^; EDE:/          { sub(/^; EDE: /, ""); sub(/\):.*/, ")"); e = " EDE " $0 }
         END { print s, ad e }'
}

echo ">> 1. čistim keš za $Z i punim ga DNSKEY skupom (stari ZSK)"
# Samo DNSKEY, ne www: inače bi resolver keširao i ispravan odgovor za www.
docker compose exec -T resolver unbound-control flush_zone $Z >/dev/null
echo "   DNSKEY $Z: $(q $Z DNSKEY)"

echo ">> 2. menjam ZSK bez prethodnog objavljivanja"
docker compose run --rm --user "$(id -u):$(id -g)" signer sh scripts/break.sh e1-rollover 2>/dev/null

echo ">> 3. auth1 učitava novu zonu (resolver NE restartujemo – keš ostaje)"
docker compose restart auth1 >/dev/null 2>&1
sleep 2

echo "   www.$Z A: $(q www.$Z A)"
echo
echo "Očekivano: SERVFAIL, EDE 9 ili 6. Resolver vidi potpis ključem kog nema u kešu."
echo "Popravka: vrati stari ZSK u DNSKEY skup i sačekaj TTL (vidi katalog, E1)."
