#!/bin/sh
# check.sh – pita resolver za svaku pokvarenu zonu i pokaže šta se desilo.
# Kolona "očekivano" je ono što je provereno sa Unbound 1.20 (lab resolver).
# Gde se razlikuje od kataloga grešaka, piše "(katalog: ...)".
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
echo
echo "=== Prvo (MVP) ==="
row A1  www.a1-expired.test           A      "SERVFAIL, EDE 7 (Signature Expired)"
row A3  www.a3-nosig.test             A      "SERVFAIL, EDE 10 (RRSIGs Missing)"
row A4  www.a4-bogus.test             A      "SERVFAIL, EDE 6 (DNSSEC Bogus)"
row A6  www.a6-expiring.test          A      "NOERROR AD (radi, ali potpis ističe za 2 dana)"
row B1  www.b1-ds-stale.test          A      "SERVFAIL, EDE 6 (katalog: 9)"
row B2  www.b2-ds-digest.test         A      "SERVFAIL, EDE 6 ili 9"
row B4  www.b4-ds-unsigned.test       A      "SERVFAIL, EDE 9 ili 10"
row D1  www.d1-nsec-broken.test       A      "NOERROR AD (postojeće ime radi)"
row D1  missing.d1-nsec-broken.test   A      "SERVFAIL, EDE 6"
row D4  www.d4-nsec3-iter.test        A      "NOERROR AD (postojeće ime radi)"
row D4  missing.d4-nsec3-iter.test    A      "NXDOMAIN bez AD, bez EDE (iteracije > 150 -> insecure)"
row E1  www.e1-rollover.test          A      "NOERROR AD (pre e1-rollover.sh)"
echo
echo "=== Kasnije ==="
row A2  www.a2-notyet.test            A      "SERVFAIL, EDE 8 (Signature Not Yet Valid)"
row A5  www.a5-unknown-key.test       A      "SERVFAIL, EDE 9"
row A7  www.a7-ttl-long.test          A      "NOERROR AD (TTL 30 dana, potpis 7)"
row B3  www.b3-ds-alg.test            A      "SERVFAIL, EDE 9 ili 6"
row B5  www.b5-dnskey-zsk.test        A      "SERVFAIL, EDE 6 ili 10"
row B6  www.b6-island.test            A      "NOERROR bez AD"
row B7  www.b7-ds-sha1.test           A      "NOERROR AD (EDE 2 ako resolver ne podržava SHA-1)"
row B8  www.b8-ds-extra.test          A      "NOERROR AD"
row C1  www.c1-rsasha1.test           A      "NOERROR AD ili bez AD (EDE 1)"
row C2  www.c2-two-alg.test           A      "NOERROR AD (Unbound je blag; strožiji resolver: SERVFAIL)"
row C3  www.c3-rsa1024.test           A      "NOERROR AD"
row D2  www.d2-bitmap.test            TXT    "SERVFAIL, EDE 6"
row D3  missing.d3-nsec-nosig.test    A      "SERVFAIL, EDE 12 (NSEC Missing)"
row D5  missing.d5-nsec3param.test    A      "SERVFAIL, EDE 12"
row D6  missing.d6-nsec-mixed.test    A      "NXDOMAIN AD (NSD šalje samo NSEC3; katalog: SERVFAIL)"
row E2  www.e2-ksk-ds-early.test      A      "SERVFAIL, EDE 9 ili 6"
row E3  www.e3-rollover.test          A      "NOERROR AD (pre e3-rollover.sh)"
row E4  e4-cds.test                   CDS    "NOERROR AD (CDS sa pogrešnim digest-om)"
row F1  www.f1-stale-secondary.test   A      "NOERROR AD (Unbound ponovi upit na auth1; auth2 direktno: istekli potpisi)"
row F2  www.f2-tcp-edns.test          A      "NOERROR AD (mali odgovor radi)"
row F2  big.f2-tcp-edns.test          TXT    "SERVFAIL, bez EDE (katalog: 22 ili 23)"

# A6: koliko još važi potpis
exp=$(docker compose exec -T client dig @10.53.0.53 +dnssec +short www.a6-expiring.test A 2>/dev/null |
      awk '$1 == "A" { print $5; exit }')
[ -n "$exp" ] && echo && echo "A6: RRSIG za www.a6-expiring.test ističe $exp (UTC)"
exit 0
