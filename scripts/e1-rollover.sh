#!/bin/sh
# e1-rollover.sh – error E1 live: ZSK replaced too quickly.
#
# 1. The resolver queries the DNSKEY for e1-rollover.test and caches it (TTL 3600 s).
# 2. We replace the ZSK WITHOUT pre-publishing: the new one signs at once, the old one vanishes at once.
# 3. auth1 serves the new zone, while the resolver still has the OLD DNSKEY set in its cache
#    -> the signature by the new ZSK can't be verified -> SERVFAIL until the cache expires.
#
# Run from the host (the lab must be running: docker compose up -d):
#   sh scripts/e1-rollover.sh
#
# To try again: sign.sh + docker compose restart root tld auth1 auth2 auth3 resolver.
# Exit: 0 if the error appeared, 1 if it didn't (used by CI).

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
# Only DNSKEY, not www: otherwise the resolver would also cache a valid answer for www.
docker compose exec -T resolver unbound-control flush_zone $Z >/dev/null
echo "   DNSKEY $Z: $(q $Z DNSKEY)"

echo ">> 2. menjam ZSK bez prethodnog objavljivanja"
docker compose run --rm --user "$(id -u):$(id -g)" signer sh scripts/break.sh rollover $Z 2>/dev/null

echo ">> 3. auth1 učitava novu zonu (resolver NE restartujemo – keš ostaje)"
docker compose restart auth1 >/dev/null 2>&1
sleep 2

got=$(q www.$Z A)
echo "   www.$Z A: $got"
echo
echo "Očekivano: SERVFAIL, EDE 9 ili 6. Resolver vidi potpis ključem kog nema u kešu."
echo "Popravka: vrati stari ZSK u DNSKEY skup i sačekaj TTL (vidi katalog, E1)."

# Exit for CI: 0 only if the error really appeared.
case $got in
    SERVFAIL*) echo "E1: ok" ;;
    *)         echo "E1: GREŠKA – očekivan SERVFAIL"; exit 1 ;;
esac
