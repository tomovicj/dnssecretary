#!/bin/sh
# dnssecretary.sh – runs DNSSECretary (Java) inside the lab network (compose service "app").
#   sh scripts/dnssecretary.sh check a1-expired.test
#   sh scripts/dnssecretary.sh snapshot a1-expired.test -o build/a1.json
#   sh scripts/dnssecretary.sh fix --apply a1-expired.test
#
# The tool has no access to Docker. After "fix --apply" it writes a reload list,
# and this script restarts the servers, flushes the resolver cache and runs "verify".
#
# Build the jar first (host, needs Java 21 + Maven):
#   mvn -f dnssecretary/pom.xml package
#
# Exit: the tool's exit code (0 ok, 1 risks only, 2 critical error, 3 tool error).

set -eu
cd "$(dirname "$0")/.."

JAR=dnssecretary/target/dnssecretary.jar
[ -f "$JAR" ] || { echo "Nema $JAR – pokreni: mvn -f dnssecretary/pom.xml package"; exit 3; }

run() { docker compose run --rm -T --user "$(id -u):$(id -g)" app "$@"; }

RELOAD=build/dnssecretary/reload     # written by "fix --apply": "restart <service>" / "flush <zone>"
rm -f "$RELOAD"
rc=0; run "$@" || rc=$?

if [ -f "$RELOAD" ]; then
    while read -r what arg; do
        case $what in
            restart) docker compose restart "$arg" >/dev/null ;;
            flush)   docker compose exec -T resolver unbound-control flush_zone "$arg" >/dev/null
                     docker compose exec -T resolver unbound-control flush_bogus >/dev/null ;;
        esac
    done < "$RELOAD"
    sleep 2
    zone=$(awk '$1 == "flush" { print $2; exit }' "$RELOAD")
    if [ -n "$zone" ]; then
        run verify "$zone" || rc=$?
    fi
fi
exit "$rc"
