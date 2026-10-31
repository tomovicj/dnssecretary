#!/bin/sh
# sandbox/run.sh – runs INSIDE the sandbox container, which scripts/simulate.sh starts:
# a copy of the lab at /lab, no network (--network none), NET_ADMIN, as root.
#
#   sh /lab/scripts/sandbox/run.sh <zone> <fix script>
#
# 1. adds the lab's addresses (10.53.0.x) to lo and starts every server on its own address
# 2. runs the fix script like everywhere else: sh -eu in /lab, ZONE set, after fixenv/sandbox.sh
# 3. runs "dnssecretary verify --json" against the sandbox's own servers and resolver
# The last line of the output is the result: SANDBOX-RESULT {"scriptExit": N, "verify": {...} | null}
#
# Exit: 0 fixed (script ok, verify ok), 1 not fixed, 3 the sandbox itself failed.

set -eu
cd /lab
ZONE=${1:?zone}
SCRIPT=${2:?fix script}
JAR=dnssecretary/target/dnssecretary.jar
CONFIG=dnssecretary/config/lab.yaml
SCRIPT_TIMEOUT=${SCRIPT_TIMEOUT:-120}

# shellcheck source=scripts/fixenv/sandbox.sh
. scripts/fixenv/sandbox.sh

# Files the servers and the script write are root's: give them back to the owner of the copy,
# so simulate.sh can delete it.
owner=$(stat -c %u:%g /lab)
done_() {
    for s in $(echo "$SANDBOX_SERVERS" | awk '{ print $1 }'); do sandbox_stop "$s"; done
    chown -R "$owner" /lab 2>/dev/null || true
}
trap done_ EXIT

mkdir -p "$SANDBOX_RUN"
echo "$SANDBOX_SERVERS" | while read -r svc ip sw; do
    ip addr add "$ip/32" dev lo
done
for svc in $(echo "$SANDBOX_SERVERS" | awk '{ print $1 }'); do
    sandbox_start "$svc" || { echo "sandbox: $svc did not start"; cat "$SANDBOX_RUN/$svc.log" 2>/dev/null; exit 3; }
done
sleep 1
echo "sandbox: lab servers up on 10.53.0.{2,3,11,12,13,53}"

echo "\$ sh -eu fix.sh   (ZONE=$ZONE)"
rc=0
ZONE=$ZONE timeout "$SCRIPT_TIMEOUT" sh -eu -c '. scripts/fixenv/sandbox.sh; . "$1"' fix "$SCRIPT" || rc=$?
echo "fix script exit: $rc"

verify=null
if [ "$rc" -eq 0 ]; then
    sleep 1
    echo "\$ dnssecretary verify $ZONE"
    out=$(java -jar "$JAR" --config "$CONFIG" verify "$ZONE" --json) || true
    verify=$(echo "$out" | tail -n 1)
    case $verify in
        "{"*) ;;
        *) echo "$out"; echo "sandbox: verify gave no result"; verify=null ;;
    esac
fi

echo "SANDBOX-RESULT {\"scriptExit\":$rc,\"verify\":$verify}"
case $verify in
    *'"ok":true'*) exit 0 ;;
    null) [ "$rc" -eq 0 ] && exit 3 || exit 1 ;;
    *) exit 1 ;;
esac
