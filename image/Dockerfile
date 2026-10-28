# Versions are pinned: the lab's behaviour (EDE codes, clock-skew tolerance, NSEC3 limit)
# depends on the Unbound and BIND versions. The expected results in scripts/check.sh
# were verified with exactly these versions.
#
# "~X.Y.Z" accepts only Alpine security patches (-r0, -r1, ...), not a new version.
# If the build breaks because Alpine no longer ships that version: bump it here,
# run scripts/check.sh and fix the expected results if they changed.
FROM alpine:3.20.10
RUN apk add --no-cache \
        nsd~4.9.1 \
        unbound~1.20.0 \
        bind-tools~9.18.49 \
        bind-dnssec-tools~9.18.49 \
        ldns-tools~1.8.3
