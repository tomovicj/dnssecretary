# shellcheck shell=sh
# fixenv/reload-list.sh – reload and flush for a fix script run by "dnssecretary fix --apply"
# in the app container, which has no Docker. They only queue the work in the reload list;
# scripts/dnssecretary.sh then restarts the servers, flushes the resolver and runs verify.
#
# The fix script contract (sourced before every fix script, see ScriptGenerator):
#   reload <service>   load the changed files on that server (auth1, tld, ...)
#   flush <zone>       drop the resolver's cached answers for the zone

RELOAD_LIST=${RELOAD_LIST:-build/dnssecretary/reload}

queue() {
    mkdir -p "$(dirname "$RELOAD_LIST")"
    grep -qxF "$1" "$RELOAD_LIST" 2>/dev/null || echo "$1" >> "$RELOAD_LIST"
}

reload() {
    queue "restart $1"
    echo "reload $1: queued"
}

flush() {
    queue "flush $1"
    echo "flush $1: queued"
}
