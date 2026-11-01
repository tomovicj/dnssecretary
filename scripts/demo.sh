#!/bin/sh
# demo.sh – puts the lab into a state for the web app demo (the demo panel calls it).
#
#   sh scripts/demo.sh heal                   every zone correct (C1, C3 stay "at risk")
#   sh scripts/demo.sh break <zone> <error>   a hosting zone (zones/hosting.list) broken as <error>:
#                                             a1 … e4 as break.sh "zone" mode lists them, e1 = live rollover
#   sh scripts/demo.sh break <zone>           a catalogue zone as the catalogue breaks it; E1/E3: live rollover
#   sh scripts/demo.sh reset                  the lab as after sign.sh: the hosting at its start
#                                             (3 zones at risk), all 30 catalogue zones broken
#   sh scripts/demo.sh save [-f]              remember keys/ and auth3/unbound.conf (heal and break do it once)
#
# Needs only the Docker CLI: signing runs in a throw-away lab container (like the signer
# service), restarts go through scripts/fixenv/prod.sh. Run from the host or from a container
# with the Docker CLI and socket (the web app); inside a container set HOST_LAB_DIR.
#
# Why save: fixes for C1/C3, E1/E3 and F2 change keys/ and auth3/unbound.conf, and sign.sh
# doesn't put them back. reset restores them from build/demo/ before it runs sign.sh.

set -eu
cd "$(dirname "$0")/.."

HOST_LAB_DIR=${HOST_LAB_DIR:-$PWD}
IMAGE=${LAB_IMAGE:-dnslab}
SAVED=build/demo
BROKEN=build/broken

# shellcheck source=scripts/fixenv/prod.sh
. scripts/fixenv/prod.sh

usage() { sed -n '2,10p' "$0"; exit 2; }

# A lab script with the BIND tools, as the owner of the lab (the files stay theirs).
lab_sh() {
    docker run --rm --network none --user "$(stat -c %u:%g .)" -v "$HOST_LAB_DIR:/lab" -w /lab "$IMAGE" sh "$@"
}

# A query through the resolver from the client container (fills the resolver's cache).
ask() {
    docker exec "$(lab_container client)" dig @10.53.0.53 +dnssec +tries=1 +time=5 "$@" >/dev/null 2>&1 || true
}

save() {
    if [ -d "$SAVED/keys" ] && [ "${1:-}" != -f ]; then return 0; fi
    rm -rf "$SAVED"
    mkdir -p "$SAVED"
    cp -a keys "$SAVED/keys"
    cp auth3/unbound.conf "$SAVED/unbound.conf"
    echo "saved keys/ and auth3/unbound.conf in $SAVED/"
}

reload_all() {
    for s in root tld auth1 auth2 auth3 resolver; do reload "$s"; done
}

reset() {
    if [ -d "$SAVED/keys" ]; then
        rm -rf keys/*
        cp -a "$SAVED/keys/." keys/
        cp "$SAVED/unbound.conf" auth3/unbound.conf
        echo "restored keys/ and auth3/unbound.conf from $SAVED/"
    fi
    lab_sh scripts/sign.sh >/dev/null
    reload_all
}

heal() {
    save
    lab_sh scripts/heal.sh
    reload_all
}

break_zone() {
    z=${1%.}
    save
    case $z in
        e1-rollover.test)
            # the resolver caches the DNSKEY set with the old ZSK, then the ZSK is replaced at once
            flush "$z"
            ask "$z" DNSKEY
            lab_sh scripts/break.sh rollover "$z"
            reload auth1 ;;
        e3-rollover.test)
            # the resolver caches www (signed with the old ZSK); the old key is removed at once
            flush "$z"
            ask "www.$z" A
            lab_sh scripts/break.sh rollover "$z"
            reload auth1
            docker exec "$(lab_container resolver)" unbound-control flush_type "$z" DNSKEY >/dev/null ;;
        *)
            found=0
            for dir in auth1 auth2 auth3; do
                case $dir in auth1) src=$BROKEN/$z.zone.signed ;; *) src=$BROKEN/$dir/$z.zone.signed ;; esac
                if [ -f "$src" ]; then
                    cp "$src" "$dir/"
                    reload "$dir"
                    found=1
                fi
            done
            [ "$found" = 1 ] || { echo "No broken version of $z in $BROKEN/ – is it a catalogue zone? Run sign.sh."; exit 1; }
            if [ "$z" = f2-tcp-edns.test ]; then
                cp "$SAVED/unbound.conf" auth3/unbound.conf
                reload auth3
            fi
            # the parent's DS as the catalogue has it (none for B6)
            ds=build/parent-ds/$z.ds
            if [ -f "$BROKEN/parent-ds/$z.ds" ]; then
                cmp -s "$BROKEN/parent-ds/$z.ds" "$ds" || { cp "$BROKEN/parent-ds/$z.ds" "$ds"; changed=1; }
            elif [ -f "$ds" ]; then
                rm "$ds"; changed=1
            fi
            if [ "${changed:-0}" = 1 ]; then
                lab_sh scripts/sign-tld.sh >/dev/null
                reload tld
            fi
            flush "$z" ;;
    esac
    echo "broke $z"
}

# A hosting zone broken live as one catalogue error ($2, e.g. a1 or A1).
break_hosting() {
    z=${1%.}; e=$(echo "$2" | tr '[:upper:]' '[:lower:]')
    save
    if [ "$e" = e1 ]; then
        # as for e1-rollover.test: the resolver caches the DNSKEY set, then the ZSK is replaced at once
        flush "$z"
        ask "$z" DNSKEY
        lab_sh scripts/break.sh rollover "$z"
        reload auth1
    else
        ds=build/parent-ds/$z.ds
        before=$(cat "$ds" 2>/dev/null || true)
        lab_sh scripts/break.sh zone "$z" "$e"
        cp "build/hosting/$z.zone.signed" auth1/
        reload auth1
        if [ "$(cat "$ds" 2>/dev/null || true)" != "$before" ]; then
            lab_sh scripts/sign-tld.sh >/dev/null
            reload tld
        fi
        flush "$z"
    fi
    echo "broke $z ($e)"
}

case ${1:-} in
    heal)  heal ;;
    reset) reset ;;
    save)  save "${2:-}" ;;
    break) case $# in
               2) break_zone "$2" ;;
               3) break_hosting "$2" "$3" ;;
               *) usage ;;
           esac ;;
    *)     usage ;;
esac
