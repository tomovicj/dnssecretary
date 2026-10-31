#!/bin/sh
# simulate.sh – tries a fix script in a sandbox copy of the lab; the lab itself is not touched.
# This is what fills the "verified" column of the web app (SandboxRunner calls it).
#
# Run from the host or from a container with the Docker CLI and socket (the web app):
#   sh scripts/simulate.sh a1-expired.test fix.sh
#   sh scripts/dnssecretary.sh fix a1-expired.test --script > build/a1.sh && sh scripts/simulate.sh a1-expired.test build/a1.sh
#
# 1. copies the lab's current state to build/sandbox/<job>/ (zones, keys, build, servers, scripts)
# 2. starts the sandbox: image dnslab-app, no network, the copy at /lab, scripts/sandbox/run.sh
# 3. deletes the copy
#
# Inside a container, set HOST_LAB_DIR to the lab's path on the host: Docker mounts host paths.
# The last line of the output: SANDBOX-RESULT {"scriptExit": N, "verify": {...} | null}
#
# Exit: 0 fixed (verified), 1 not fixed, 3 the sandbox failed.

set -eu
cd "$(dirname "$0")/.."

ZONE=${1:?usage: simulate.sh <zone> <fix script> [job id]}
SCRIPT=${2:?usage: simulate.sh <zone> <fix script> [job id]}
JOB=${3:-sim-$$}
HOST_LAB_DIR=${HOST_LAB_DIR:-$PWD}
IMAGE=${SANDBOX_IMAGE:-dnslab-app}

JAR=dnssecretary/target/dnssecretary.jar
[ -f "$JAR" ] || { echo "Missing $JAR – run: mvn -f dnssecretary/pom.xml package"; exit 3; }
[ -f "$SCRIPT" ] || { echo "Missing fix script $SCRIPT"; exit 3; }
case $JOB in *[!A-Za-z0-9_-]*|"") echo "Bad job id: $JOB"; exit 3 ;; esac

COPY=build/sandbox/$JOB
rm -rf "$COPY"
mkdir -p "$COPY/dnssecretary"
trap 'rm -rf "$COPY"' EXIT

# The lab's current state; build/ without the sandboxes themselves.
tar -cf - --exclude=build/sandbox --exclude=build/demo \
    zones keys build root tld auth1 auth2 auth3 resolver client scripts | tar -xf - -C "$COPY"
cp "$SCRIPT" "$COPY/fix.sh"
echo "sandbox $JOB: copy of the lab in $COPY"

rc=0
docker run --rm --network none --cap-add NET_ADMIN \
    -v "$HOST_LAB_DIR/$COPY:/lab" \
    -v "$HOST_LAB_DIR/dnssecretary:/lab/dnssecretary:ro" \
    "$IMAGE" sh /lab/scripts/sandbox/run.sh "$ZONE" /lab/fix.sh || rc=$?
exit "$rc"
