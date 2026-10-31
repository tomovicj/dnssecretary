# Versions are pinned: the lab's behaviour (EDE codes, clock-skew tolerance, NSEC3 limit)
# depends on the Unbound and BIND versions. The expected results in scripts/check.sh
# were verified with exactly these versions.
#
# "~X.Y.Z" accepts only Alpine security patches (-r0, -r1, ...), not a new version.
# If the build breaks because Alpine no longer ships that version: bump it here,
# run scripts/check.sh and fix the expected results if they changed.
FROM alpine:3.20.10 AS lab
RUN apk add --no-cache \
        nsd~4.9.1 \
        unbound~1.20.0 \
        bind-tools~9.18.49 \
        bind-dnssec-tools~9.18.49 \
        ldns-tools~1.8.3

# DNSSECretary: the same signing tools as the lab + a Java runtime.
# The jar itself is built on the host and mounted (compose service "app").
FROM lab AS app
RUN apk add --no-cache openjdk21-jre-headless~21.0

# The web app: DNSSECretary + the Docker CLI, to start the sandbox, restart the lab's
# servers and run scripts/demo.sh (the socket is mounted by compose, profile "web").
FROM app AS web
RUN apk add --no-cache docker-cli~26.1.5
