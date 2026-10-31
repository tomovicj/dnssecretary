# shellcheck shell=sh
# fixenv/prod.sh – reload and flush for a fix script run against the lab ("prod") from a
# container that has the Docker CLI and socket (the web service). The containers are found
# by their compose labels, so no compose file or host path is needed.
#
# The fix script contract (sourced before every fix script, see ScriptGenerator):
#   reload <service>   load the changed files on that server (auth1, tld, ...)
#   flush <zone>       drop the resolver's cached answers for the zone

LAB_PROJECT=${LAB_PROJECT:-dnssec-lab}   # compose project name of the lab

lab_container() {
    id=$(docker ps -q --filter "label=com.docker.compose.project=$LAB_PROJECT" \
                      --filter "label=com.docker.compose.service=$1")
    [ -n "$id" ] || { echo "no running container for service $1 in project $LAB_PROJECT" >&2; return 1; }
    echo "$id"
}

reload() {
    c=$(lab_container "$1")
    docker restart "$c" >/dev/null
    echo "reload $1: restarted"
}

flush() {
    r=$(lab_container resolver)
    docker exec "$r" unbound-control flush_zone "$1" >/dev/null
    docker exec "$r" unbound-control flush_bogus >/dev/null
    echo "flush $1: done"
}
