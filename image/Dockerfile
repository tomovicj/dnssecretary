# Verzije su zakucane: ponašanje laba (EDE kodovi, tolerancija sata, NSEC3 limit)
# zavisi od verzije Unbound-a i BIND alata. Rezultati u scripts/check.sh su
# provereni baš sa ovim verzijama.
#
# "~X.Y.Z" prihvata samo bezbednosne zakrpe Alpine-a (-r0, -r1, ...), ne novu verziju.
# Ako build pukne jer Alpine više nema tu verziju: podigni verziju ovde,
# pokreni scripts/check.sh i ispravi očekivane rezultate ako su se promenili.
FROM alpine:3.20.10
RUN apk add --no-cache \
        nsd~4.9.1 \
        unbound~1.20.0 \
        bind-tools~9.18.49 \
        bind-dnssec-tools~9.18.49 \
        ldns-tools~1.8.3
