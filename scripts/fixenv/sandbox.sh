# shellcheck shell=sh
# fixenv/sandbox.sh – reload and flush for a fix script that runs in the sandbox
# (scripts/sandbox/run.sh). The sandbox is ONE container without a network (--network none):
# every lab server runs in it as its own process, on the lab's IP address added to lo,
# so zone files, glue, root hints and the trust anchor work unchanged.
#
# The fix script contract (sourced before every fix script, see ScriptGenerator):
#   reload <service>   load the changed files on that server (auth1, tld, ...)
#   flush <zone>       drop the resolver's cached answers for the zone
#
# Also used by run.sh to start the servers: sandbox_start / sandbox_stop <service>.

SANDBOX_RUN=${SANDBOX_RUN:-/tmp/sandbox}   # generated configs, pid files, logs

# service, address, software – the same addresses as in compose.yaml
SANDBOX_SERVERS="root 10.53.0.2 nsd
tld 10.53.0.3 nsd
auth1 10.53.0.11 nsd
auth2 10.53.0.12 nsd
auth3 10.53.0.13 unbound
resolver 10.53.0.53 unbound"

sandbox_server() {
    echo "$SANDBOX_SERVERS" | awk -v s="$1" '$1 == s { print $2, $3 }'
}

# The server's own config, bound to its address only, with its files under /lab/<service>
# and its pid file and logs in $SANDBOX_RUN (so several servers fit in one container).
sandbox_config() {
    svc=$1; ip=$2; sw=$3; conf=$SANDBOX_RUN/$svc.conf
    if [ "$sw" = nsd ]; then
        awk -v ip="$ip" -v svc="$svc" -v run="$SANDBOX_RUN" '
            /^[[:space:]]*ip-address:/ { print "    ip-address: " ip; next }
            /^[[:space:]]*zonesdir:/   { print "    zonesdir: \"/lab/" svc "\""; next }
            { gsub("/etc/nsd/", "/lab/" svc "/"); print }
            /^server:/ { print "    pidfile: \"" run "/" svc ".pid\""
                         print "    xfrdfile: \"" run "/" svc ".xfrd\""
                         print "    zonelistfile: \"" run "/" svc ".zonelist\""
                         print "    database: \"\""
                         print "    username: \"\""
                         print "    logfile: \"" run "/" svc ".log\"" }' "/lab/$svc/nsd.conf" > "$conf"
    else
        awk -v ip="$ip" -v svc="$svc" -v run="$SANDBOX_RUN" '
            /^[[:space:]]*interface:/ { print "    interface: " ip; next }
            /^[[:space:]]*logfile:/   { print "    logfile: \"" run "/" svc ".log\""; next }
            { gsub("/etc/unbound/", "/lab/" svc "/"); print }
            /^server:/ { print "    pidfile: \"" run "/" svc ".pid\""
                         print "    username: \"\""
                         print "    chroot: \"\""
                         print "    use-syslog: no"
                         print "    access-control: 127.0.0.0/8 allow" }' "/lab/$svc/unbound.conf" > "$conf"
    fi
    echo "$conf"
}

sandbox_start() {
    # "<address> <software>" split into $2 and $3 on purpose
    # shellcheck disable=SC2046
    set -- "$1" $(sandbox_server "$1")
    [ $# -eq 3 ] || { echo "sandbox: unknown service $1" >&2; return 1; }
    conf=$(sandbox_config "$1" "$2" "$3")
    if [ "$3" = nsd ]; then nsd -c "$conf"; else unbound -c "$conf"; fi
}

sandbox_stop() {
    pidfile=$SANDBOX_RUN/$1.pid
    [ -f "$pidfile" ] || return 0
    pid=$(cat "$pidfile")
    kill "$pid" 2>/dev/null || true
    i=0
    while kill -0 "$pid" 2>/dev/null && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
    rm -f "$pidfile"
}

reload() {
    sandbox_stop "$1"
    sandbox_start "$1"
    echo "reload $1: restarted"
}

flush() {
    unbound-control -c "$SANDBOX_RUN/resolver.conf" flush_zone "$1" >/dev/null
    unbound-control -c "$SANDBOX_RUN/resolver.conf" flush_bogus >/dev/null
    echo "flush $1: done"
}
