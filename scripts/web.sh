#!/bin/sh
# web.sh – the web app for DNS hosting admins (compose service "web") on http://localhost:8080
#
#   sh scripts/web.sh          start it, or restart it (builds the image the first time; the lab must be running)
#   sh scripts/web.sh logs     follow its output
#   sh scripts/web.sh stop
#
# Build the jar first: mvn -f dnssecretary/pom.xml package. The front end (webapp/dist) is
# served too when it is built. The container gets the Docker socket: a demo, not for production.
#
# Run it again after every new jar: it always starts a fresh JVM. A running JVM must never see its
# jar replaced, or the classes it hasn't loaded yet fail to load (Jetty threads then spin at 100% CPU).

set -eu
cd "$(dirname "$0")/.."

HOST_LAB_DIR=$PWD
LAB_UID=$(id -u)
LAB_GID=$(id -g)
DOCKER_GID=$(stat -c %g /var/run/docker.sock)
export HOST_LAB_DIR LAB_UID LAB_GID DOCKER_GID

case ${1:-up} in
    up)
        [ -f dnssecretary/target/dnssecretary.jar ] || { echo "Missing the jar – run: mvn -f dnssecretary/pom.xml package"; exit 1; }
        docker compose --profile web up -d --build --force-recreate web
        echo "http://localhost:8080 (API: http://localhost:8080/api/zones)" ;;
    logs) docker compose --profile web logs -f web ;;
    stop) docker compose --profile web stop web ;;
    *)    sed -n '2,7p' "$0"; exit 2 ;;
esac
