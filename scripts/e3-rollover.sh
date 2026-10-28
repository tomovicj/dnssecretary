#!/bin/sh
# e3-rollover.sh – greška E3 uživo: stari ZSK obrisan pre isteka TTL-a.
#
# 1. Resolver kešira www (potpisan STARIM ZSK-om, TTL 3600 s).
# 2. Zamenimo ZSK i stari odmah izbacimo iz DNSKEY skupa (bez čekanja TTL-a).
# 3. Brišemo samo DNSKEY iz keša resolvera – kao da mu je TTL kraći od TTL-a za www.
# 4. Validator iza keša (ovde delv u client kontejneru) dobija stari potpis za www
#    iz keša i NOVI DNSKEY skup bez starog ključa -> potpis ne može da se proveri.
#    Sam Unbound i dalje odgovara sa AD (www je proverio ranije), pa greška pogađa
#    samo deo korisnika – zato je u katalogu "SERVFAIL za deo korisnika".
#
# Pokretanje sa hosta (lab mora da radi: docker compose up -d):
#   sh scripts/e3-rollover.sh
#
# Za ponovni pokušaj: sign.sh + docker compose restart root tld auth1 auth2 auth3 resolver.

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

# delv sam proverava potpise (trust anchor iz sign.sh), resolver mu služi samo kao keš.
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

echo "   resolver: www.$Z A: $(q www.$Z A)"
echo "   delv:     $(delv_www)"
echo
echo "Očekivano: resolver i dalje NOERROR AD (iz keša), a delv ne može da proveri potpis."
echo "Popravka: vrati stari ZSK u DNSKEY skup dok ne istekne TTL (vidi katalog, E3)."
