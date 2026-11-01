#!/bin/sh
# dnssecretary.sh – runs DNSSECretary (Java) inside the lab network (compose service "app").
#   sh scripts/dnssecretary.sh check a1-expired.test
#   sh scripts/dnssecretary.sh snapshot a1-expired.test -o build/a1.json
#   sh scripts/dnssecretary.sh fix a1-expired.test        (prints the plan and the fix script)
#   sh scripts/dnssecretary.sh verify a1-expired.test
#
# The tool has no access to Docker here; fix scripts run from the web app (sh scripts/web.sh).
#
# Build the jar first (host, needs Java 21 + Maven):
#   mvn -f dnssecretary/pom.xml package
#
# Exit: the tool's exit code (0 ok, 1 risks only, 2 critical error, 3 tool error).

set -eu
cd "$(dirname "$0")/.."

JAR=dnssecretary/target/dnssecretary.jar
[ -f "$JAR" ] || { echo "Missing $JAR – run: mvn -f dnssecretary/pom.xml package"; exit 3; }

exec docker compose run --rm -T --user "$(id -u):$(id -g)" app "$@"
