#!/bin/sh
# check.sh – asks the resolver about every broken zone and checks the result is as expected.
# "Expected" is what was verified with Unbound 1.20 (the lab resolver).
# Where that differs from the error catalogue, the description says "(catalogue: ...)".
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
        END { printf "%s %s %s %s", (status == "" ? "NO-ANSWER" : status), (ad == "" ? "--" : ad),
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
        mark="ERROR"; FAIL=$((FAIL + 1))
    elif [ "$ede" != "$want_ede" ]; then
        if [ "$STRICT_EDE" = 1 ]; then mark="ERROR"; FAIL=$((FAIL + 1)); else mark="EDE?"; WARN=$((WARN + 1)); fi
    else
        mark="ok"
    fi

    printf '%-6s %-3s %-30s %-4s | got:      %s\n' "$mark" "$id" "$name" "$type" "$shown"
    if [ "$mark" != ok ]; then
        printf '       %-40s | expected: %s %s %s – %s\n' "" "$want_status" "$want_ad" "$want_ede" "$desc"
    fi
}

# Wait for the lab to come up (after "up -d" / "restart" the servers take a few seconds).
i=0
until [ "$(ask www.good.test A | cut -d' ' -f1-2)" = "NOERROR AD" ]; do
    i=$((i + 1))
    [ $i -lt 30 ] || { echo "The lab doesn't answer (www.good.test is not NOERROR AD after 30 s)."; exit 1; }
    sleep 1
done

echo "       ID  query                          type"
echo "------------------------------------------------------------------------------"
row OK  www.good.test                 A     NOERROR  AD -  "correct zone"

echo; echo "=== MVP ==="
row A1  www.a1-expired.test           A     SERVFAIL -- 7  "expired signature"
row A3  www.a3-nosig.test             A     SERVFAIL -- 10 "record without a signature"
row A4  www.a4-bogus.test             A     SERVFAIL -- 6  "invalid signature"
row A6  www.a6-expiring.test          A     NOERROR  AD -  "works, but the signature expires in 2 days"
row B1  www.b1-ds-stale.test          A     SERVFAIL -- 6  "DS for a key that doesn't exist (catalogue: EDE 9)"
row B2  www.b2-ds-digest.test         A     SERVFAIL -- 6  "wrong DS digest"
row B4  www.b4-ds-unsigned.test       A     SERVFAIL -- 9  "DS exists, zone unsigned"
row D1  www.d1-nsec-broken.test       A     NOERROR  AD -  "an existing name works"
row D1  missing.d1-nsec-broken.test   A     SERVFAIL -- 6  "broken NSEC chain"
row D4  www.d4-nsec3-iter.test        A     NOERROR  AD -  "an existing name works"
row D4  missing.d4-nsec3-iter.test    A     NXDOMAIN -- -  "iterations > 150 -> insecure, no EDE 27"
row E1  www.e1-rollover.test          A     NOERROR  AD -  "correct before e1-rollover.sh"

echo; echo "=== Later ==="
row A2  www.a2-notyet.test            A     SERVFAIL -- 8  "signature not yet valid"
row A5  www.a5-unknown-key.test       A     SERVFAIL -- 9  "signed by a key outside the DNSKEY set"
row A7  www.a7-ttl-long.test          A     NOERROR  AD -  "TTL 30 days, signature 7"
row B3  www.b3-ds-alg.test            A     SERVFAIL -- 6  "DS and DNSKEY algorithms differ"
row B5  www.b5-dnskey-zsk.test        A     SERVFAIL -- 6  "DNSKEY set without a KSK signature"
row B6  www.b6-island.test            A     NOERROR  -- -  "island: works without AD"
row B7  www.b7-ds-sha1.test           A     NOERROR  AD -  "DS SHA-1 (Unbound supports it)"
row B8  www.b8-ds-extra.test          A     NOERROR  AD -  "leftover old DS"
row C1  www.c1-rsasha1.test           A     NOERROR  AD -  "algorithm 5 (Unbound supports it)"
row C2  www.c2-two-alg.test           A     NOERROR  AD -  "two algorithms (Unbound is lenient; catalogue: SERVFAIL on some)"
row C3  www.c3-rsa1024.test           A     NOERROR  AD -  "RSA 1024"
row D2  www.d2-bitmap.test            TXT   SERVFAIL -- 6  "NSEC bitmap lies about TXT"
row D3  missing.d3-nsec-nosig.test    A     SERVFAIL -- 12 "NSEC without signatures"
row D5  missing.d5-nsec3param.test    A     SERVFAIL -- 12 "NSEC3PARAM doesn't match the chain"
row D6  missing.d6-nsec-mixed.test    A     NXDOMAIN AD -  "NSD sends only NSEC3 (catalogue: SERVFAIL)"
row E2  www.e2-ksk-ds-early.test      A     SERVFAIL -- 6  "DS of the new KSK too early"
row E3  www.e3-rollover.test          A     NOERROR  AD -  "correct before e3-rollover.sh"
row E4  e4-cds.test                   CDS   NOERROR  AD -  "CDS with a wrong digest"
row F1  www.f1-stale-secondary.test   A     NOERROR  AD -  "Unbound retries on auth1"
row F2  www.f2-tcp-edns.test          A     NOERROR  AD -  "a small answer works"
row F2  big.f2-tcp-edns.test          TXT   SERVFAIL -- -  "large answer: no TCP (catalogue: EDE 22/23)"

echo
echo "Errors: $FAIL, EDE warnings: $WARN."
[ "$FAIL" -eq 0 ]
