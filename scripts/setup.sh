#!/bin/sh
# setup.sh – the whole project from a fresh clone to the web app on http://localhost:8080,
# with nothing but Docker on the host (Java and Node run in containers when they are missing).
#
#   sh scripts/setup.sh          build, keys, sign, start the lab, build the tool and the front end, start the web app
#   sh scripts/setup.sh --check  the same, then check.sh (every zone answers as expected)
#
# Safe to run again: keys that exist are kept, the zones are re-signed (fresh 30-day signatures),
# the demo hosting goes back to its start. See docs/getting-started.md for the steps one by one.

set -eu
cd "$(dirname "$0")/.."

UIDGID="$(id -u):$(id -g)"
step() { printf '\n==> %s\n' "$*"; }

step "Images (lab, signer, app, web)"
docker compose build
docker compose --profile app --profile web build app web

step "Keys (once per zone; existing keys are kept)"
docker compose run --rm --user "$UIDGID" signer sh scripts/keygen.sh >/dev/null

step "Signing every zone (sign.sh)"
docker compose run --rm --user "$UIDGID" signer sh scripts/sign.sh >/dev/null

step "Starting the lab"
docker compose up -d
docker compose restart root tld auth1 auth2 auth3 resolver >/dev/null

step "DNSSECretary jar (dnssecretary/target/dnssecretary.jar)"
if command -v mvn >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "2[1-9]'; then
    mvn -q -B -f dnssecretary/pom.xml package -DskipTests
else
    mkdir -p "$HOME/.m2"
    docker run --rm -u "$UIDGID" -v "$PWD":/src -v "$HOME/.m2":/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 \
        -w /src/dnssecretary maven:3.9-eclipse-temurin-21 mvn -q -B -Duser.home=/var/maven package -DskipTests
fi

step "Front end (webapp/dist)"
if command -v npm >/dev/null 2>&1; then
    (cd webapp && npm ci --no-audit --no-fund --loglevel=error && npm run build >/dev/null)
else
    docker run --rm -u "$UIDGID" -e HOME=/tmp -v "$PWD/webapp":/app -w /app node:22-alpine \
        sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm run build >/dev/null'
fi

step "Web app"
sh scripts/web.sh >/dev/null
i=0
until curl -sf -o /dev/null http://localhost:8080/api/zones; do
    i=$((i + 1)); [ $i -lt 60 ] || { echo "The web app didn't start: sh scripts/web.sh logs"; exit 1; }
    sleep 1
done

if [ "${1:-}" = --check ]; then
    step "check.sh"
    sh scripts/check.sh | tail -n 1
fi

echo
echo "Ready: http://localhost:8080  (Demo panel: top right; video: sh scripts/video.sh)"
