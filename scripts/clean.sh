#!/bin/sh
# clean.sh – deletes everything keygen.sh and sign.sh produced.
# The lab goes back to its state right after "git clone".
#
# Run (from the host, from any directory):
#   sh scripts/clean.sh          asks before deleting
#   sh scripts/clean.sh -n       only shows what it would delete (dry run)
#   sh scripts/clean.sh -k       deletes everything EXCEPT the keys
#   sh scripts/clean.sh -y       doesn't ask
#
# What it does NOT delete: zones/, configs (nsd.conf, unbound.conf, root.hints),
# scripts, compose.yaml, Dockerfile – everything you write by hand.

set -eu
cd "$(dirname "$0")/.."

# Safety: if we're not in the lab root, delete nothing.
[ -f compose.yaml ] || { echo "No compose.yaml – this is not the lab root. Stopping."; exit 1; }

usage() { echo "Usage: sh scripts/clean.sh [-n] [-k] [-y]"; }

DRY=0; KEEP_KEYS=0; YES=0
while getopts nky opt; do
    case $opt in
        n) DRY=1 ;;
        k) KEEP_KEYS=1 ;;
        y) YES=1 ;;
        *) usage; exit 1 ;;
    esac
done

# Collect only paths that ACTUALLY exist.
# (If a glob matches nothing, the literal "*/*.signed" remains – [ -e ] drops it.)
TARGETS=""
add() {
    for p in "$@"; do
        [ -e "$p" ] && TARGETS="$TARGETS $p"
    done
    return 0
}

add build                    # temporary files, dsset-*, *.zone.in
add ./*/*.signed             # signed zones in root/, tld/, auth1/, auth2/, auth3/
add auth1/hosting.conf       # the hosting zones' NSD blocks (hosting.sh)
add ./dsset-* ./*.zone.in    # in case anything ever ended up in the root dir
add resolver/root.key        # trust anchor for Unbound
add client/delv-anchor.conf  # trust anchor for delv
[ "$KEEP_KEYS" -eq 1 ] || add keys

# Note: the root/, tld/, auth1/, auth2/, auth3/, resolver/, client/ directories are NOT
# deleted, only the files in them. They are mounted into containers – if a directory is
# missing, Docker creates it as root, and then sign.sh can't write to it.

if [ -z "$TARGETS" ]; then
    echo "Already clean, nothing to delete."
    exit 0
fi

echo "Will be deleted:"
for t in $TARGETS; do echo "  $t"; done

[ "$DRY" -eq 1 ] && { echo "(dry run – nothing was deleted)"; exit 0; }

if [ "$YES" -ne 1 ]; then
    printf "Continue? [y/N] "
    read -r ans
    case $ans in
        y|Y|yes|YES) ;;
        *) echo "Cancelled."; exit 0 ;;
    esac
fi

# $TARGETS unquoted on purpose: it has to split into several paths.
# shellcheck disable=SC2086
if ! rm -rf -- $TARGETS; then
    echo
    echo "Some files were not deleted – they probably belong to root"
    echo "(created when signer ran without --user). Try:"
    echo "  sudo sh scripts/clean.sh"
    exit 1
fi

echo
echo "Cleaned. To get the lab working again:"
[ "$KEEP_KEYS" -eq 1 ] || \
echo "  docker compose run --rm --user \$(id -u):\$(id -g) signer sh scripts/keygen.sh"
echo "  docker compose run --rm --user \$(id -u):\$(id -g) signer sh scripts/sign.sh"
echo "  docker compose restart root tld auth1 auth2 auth3 resolver"
