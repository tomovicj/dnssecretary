#!/bin/sh
# e3-rollover.sh – error E3 live: old ZSK removed before its TTL expired.
#
# 1. The resolver caches www (signed with the OLD ZSK, TTL 3600 s).
# 2. We replace the ZSK and drop the old one from the DNSKEY set at once (no waiting for TTL).
# 3. We flush only the DNSKEY from the resolver cache – as if its TTL were shorter than www's.
# 4. A validator behind the cache (here delv in the client container) gets the old signature
#    for www from the cache and the NEW DNSKEY set without the old key -> can't verify it.
#    Unbound itself still answers with AD (it verified www earlier), so the error hits
#    only some users – hence "SERVFAIL za deo korisnika" in the catalogue.
#
# Run from the host (the lab must be running: docker compose up -d):
#   sh scripts/e3-rollover.sh
#
# To try again: sign.sh + docker compose restart root tld auth1 auth2 auth3 resolver.
# Exit: 0 if the error appeared, 1 if it didn't (used by CI).

set -eu
cd "$(dirname "$0")/.."

Z=e3-rollover.test

q() {
    docker compose exec -T client dig @10.53.0.53 +dnssec +tries=1 +time=5 "$@" 2>/dev/null |
    awk '/^;; ->>HEADER<<-/ { sub(/,.*/, "", $6); s = $6 }
         /^;; flags:/       { ad = ($0 ~ / ad[ ;]/) ? "AD" : "--" }
         /^; EDE:/          { sub(/^; EDE: /, ""); sub(/\):.*/, ")"); e = " EDE " $0 }
         END { print s, ad e }'
}

# delv verifies signatures itself (trust anchor from sign.sh); the resolver is only its cache.
delv_www() {
    docker compose exec -T client delv @10.53.0.53 -a /lab/delv-anchor.conf +cd \
        www.$Z A 2>&1 | grep -E '^; (fully validated|unsigned)|resolution failed|validating' | head -n 2
}

echo ">> 1. čistim keš za $Z i keširam www (potpis starim ZSK-om)"
docker compose exec -T resolver unbound-control flush_zone $Z >/dev/null
echo "   resolver: www.$Z A: $(q www.$Z A)"
echo "   delv:     $(delv_www)"

echo ">> 2. menjam ZSK i odmah brišem stari"
docker compose run --rm --user "$(id -u):$(id -g)" signer sh scripts/break.sh rollover $Z 2>/dev/null
docker compose restart auth1 >/dev/null 2>&1
sleep 2

echo ">> 3. iz keša resolvera brišem samo DNSKEY (www ostaje)"
docker compose exec -T resolver unbound-control flush_type $Z DNSKEY >/dev/null

got=$(q www.$Z A)
dv=$(delv_www)
echo "   resolver: www.$Z A: $got"
echo "   delv:     $dv"
echo
echo "Očekivano: resolver i dalje NOERROR AD (iz keša), a delv ne može da proveri potpis."
echo "Popravka: vrati stari ZSK u DNSKEY skup dok ne istekne TTL (vidi katalog, E3)."

# Exit for CI: 0 only if the resolver still works and delv fails.
case $got in
    "NOERROR AD"*) ;;
    *) echo "E3: GREŠKA – resolver je trebalo da vrati NOERROR AD iz keša"; exit 1 ;;
esac
case $dv in
    *"resolution failed"*) echo "E3: ok" ;;
    *) echo "E3: GREŠKA – delv je trebalo da padne"; exit 1 ;;
esac
