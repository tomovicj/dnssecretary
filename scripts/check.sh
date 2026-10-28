#!/bin/sh
# check.sh – asks the resolver about every broken zone and checks the result is as expected.
# "Expected" is what was verified with Unbound 1.20 (the lab resolver).
# Where that differs from the error catalogue, the description says "(katalog: ...)".
#
# Run from the host (the lab must be running: docker compose up -d):
#   sh scripts/check.sh              status and AD must match, EDE only warns
#   STRICT_EDE=1 sh scripts/check.sh EDE must match too
#
# Exit: 0 if everything is as expected, 1 if at least one query isn't (used by CI).
#
# We ask @10.53.0.53 directly: Docker DNS (127.0.0.11) strips EDE from answers.
#
# The resolver caches both good and bad answers. For a clean result after sign.sh:
#   docker compose restart resolver

set -eu
cd "$(dirname "$0")/.."

STRICT_EDE=${STRICT_EDE:-0}
FAIL=0
WARN=0

# One query through the resolver. Prints: "<status> <AD|--> <EDE code|-> <EDE text>"
#   $1 = name, $2 = type
ask() {
    docker compose exec -T client dig @10.53.0.53 +dnssec +tries=1 +time=5 "$1" "$2" 2>/dev/null |
    awk '
        /^;; ->>HEADER<<-/ { sub(/,.*/, "", $6); status = $6 }
        /^;; flags:/       { ad = ($0 ~ / ad[ ;]/) ? "AD" : "--" }
        /^; EDE:/          { code = $3; sub(/^; EDE: /, ""); sub(/\):.*/, ")"); ede = $0 }   # full reason: docker compose logs resolver
        END { printf "%s %s %s %s", (status == "" ? "NEMA-ODGOVORA" : status), (ad == "" ? "--" : ad),
                                    (code == "" ? "-" : code), ede }'
}

# One check row.
#   $1 = ID, $2 = name, $3 = type, $4 = expected status, $5 = AD or --,
#   $6 = expected EDE code or "-" (no EDE), $7 = description
row() {
    id=$1; name=$2; type=$3; want_status=$4; want_ad=$5; want_ede=$6; desc=$7
    got=$(ask "$name" "$type")
    # shellcheck disable=SC2086  # $got unquoted on purpose: split into fields
    set -- $got
    status=$1; ad=$2; ede=$3; shift 3
    shown="$status $ad ${*:--}"     # for display: the EDE text already contains the code

    if [ "$status" != "$want_status" ] || [ "$ad" != "$want_ad" ]; then
        mark="GREŠKA"; FAIL=$((FAIL + 1))
    elif [ "$ede" != "$want_ede" ]; then
        if [ "$STRICT_EDE" = 1 ]; then mark="GREŠKA"; FAIL=$((FAIL + 1)); else mark="EDE?"; WARN=$((WARN + 1)); fi
    else
        mark="ok"
    fi

    printf '%-6s %-3s %-30s %-4s | dobijeno:  %s\n' "$mark" "$id" "$name" "$type" "$shown"
    if [ "$mark" != ok ]; then
        printf '       %-40s | očekivano: %s %s %s – %s\n' "" "$want_status" "$want_ad" "$want_ede" "$desc"
    fi
}

# Wait for the lab to come up (after "up -d" / "restart" the servers take a few seconds).
i=0
until [ "$(ask www.good.test A | cut -d' ' -f1-2)" = "NOERROR AD" ]; do
    i=$((i + 1))
    [ $i -lt 30 ] || { echo "Lab ne odgovara (www.good.test nije NOERROR AD posle 30 s)."; exit 1; }
    sleep 1
done

echo "       ID  upit                           tip"
echo "------------------------------------------------------------------------------"
row OK  www.good.test                 A     NOERROR  AD -  "ispravna zona"

echo; echo "=== Prvo (MVP) ==="
row A1  www.a1-expired.test           A     SERVFAIL -- 7  "istekao potpis"
row A3  www.a3-nosig.test             A     SERVFAIL -- 10 "zapis bez potpisa"
row A4  www.a4-bogus.test             A     SERVFAIL -- 6  "neispravan potpis"
row A6  www.a6-expiring.test          A     NOERROR  AD -  "radi, ali potpis ističe za 2 dana"
row B1  www.b1-ds-stale.test          A     SERVFAIL -- 6  "DS na nepostojeći ključ (katalog: EDE 9)"
row B2  www.b2-ds-digest.test         A     SERVFAIL -- 6  "pogrešan DS digest"
row B4  www.b4-ds-unsigned.test       A     SERVFAIL -- 9  "DS postoji, zona nepotpisana"
row D1  www.d1-nsec-broken.test       A     NOERROR  AD -  "postojeće ime radi"
row D1  missing.d1-nsec-broken.test   A     SERVFAIL -- 6  "pokidan NSEC lanac"
row D4  www.d4-nsec3-iter.test        A     NOERROR  AD -  "postojeće ime radi"
row D4  missing.d4-nsec3-iter.test    A     NXDOMAIN -- -  "iteracije > 150 -> insecure, bez EDE 27"
row E1  www.e1-rollover.test          A     NOERROR  AD -  "ispravna pre e1-rollover.sh"

echo; echo "=== Kasnije ==="
row A2  www.a2-notyet.test            A     SERVFAIL -- 8  "potpis još nije važeći"
row A5  www.a5-unknown-key.test       A     SERVFAIL -- 9  "potpis ključem van DNSKEY skupa"
row A7  www.a7-ttl-long.test          A     NOERROR  AD -  "TTL 30 dana, potpis 7"
row B3  www.b3-ds-alg.test            A     SERVFAIL -- 6  "DS i DNSKEY različit algoritam"
row B5  www.b5-dnskey-zsk.test        A     SERVFAIL -- 6  "DNSKEY skup bez potpisa KSK-a"
row B6  www.b6-island.test            A     NOERROR  -- -  "ostrvo: radi bez AD"
row B7  www.b7-ds-sha1.test           A     NOERROR  AD -  "DS SHA-1 (Unbound ga podržava)"
row B8  www.b8-ds-extra.test          A     NOERROR  AD -  "višak starog DS-a"
row C1  www.c1-rsasha1.test           A     NOERROR  AD -  "algoritam 5 (Unbound ga podržava)"
row C2  www.c2-two-alg.test           A     NOERROR  AD -  "dva algoritma (Unbound je blag; katalog: SERVFAIL kod nekih)"
row C3  www.c3-rsa1024.test           A     NOERROR  AD -  "RSA 1024"
row D2  www.d2-bitmap.test            TXT   SERVFAIL -- 6  "NSEC bitmap laže za TXT"
row D3  missing.d3-nsec-nosig.test    A     SERVFAIL -- 12 "NSEC bez potpisa"
row D5  missing.d5-nsec3param.test    A     SERVFAIL -- 12 "NSEC3PARAM ne odgovara lancu"
row D6  missing.d6-nsec-mixed.test    A     NXDOMAIN AD -  "NSD šalje samo NSEC3 (katalog: SERVFAIL)"
row E2  www.e2-ksk-ds-early.test      A     SERVFAIL -- 6  "DS novog KSK-a prerano"
row E3  www.e3-rollover.test          A     NOERROR  AD -  "ispravna pre e3-rollover.sh"
row E4  e4-cds.test                   CDS   NOERROR  AD -  "CDS sa pogrešnim digest-om"
row F1  www.f1-stale-secondary.test   A     NOERROR  AD -  "Unbound ponovi upit na auth1"
row F2  www.f2-tcp-edns.test          A     NOERROR  AD -  "mali odgovor radi"
row F2  big.f2-tcp-edns.test          TXT   SERVFAIL -- -  "veliki odgovor: nema TCP-a (katalog: EDE 22/23)"

echo
echo "Grešaka: $FAIL, upozorenja za EDE: $WARN."
[ "$FAIL" -eq 0 ]
