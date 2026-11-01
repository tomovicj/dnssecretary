#!/bin/sh
# break.sh – makes one BROKEN zone for every error in the catalogue
# (catalog/catalog.yaml: the MVP and later tabs of the team sheet).
#
# You normally don't run it by hand: sign.sh calls it after good.test and before
# the test. zone, because test. has to get the DS records of these zones.
#
# Output:
#   build/broken/<zone>.zone.signed         zones served by auth1
#   build/broken/auth2/<zone>.zone.signed   zones served by auth2 (F1)
#   build/broken/auth3/<zone>.zone.signed   zones served by auth3 (F2)
#   build/parent-ds/<zone>.ds               DS that goes into the test. zone (deliberately wrong for some errors)
#
# Special modes:
#   sh scripts/break.sh rollover <zone>       replace the ZSK without pre-publishing it (E1 and E3:
#                                             e1-rollover.sh, e3-rollover.sh; or a hosting zone)
#   sh scripts/break.sh zone <zone> <error>   break a hosting zone (zones/hosting.list) as <error>
#                                             breaks its catalogue zone: build/hosting/<zone>.zone.signed
#                                             and build/parent-ds/<zone>.ds (scripts/demo.sh, hosting.sh)
#
# Broken zones do NOT go through dnssec-verify – they would fail it, which is the point.

set -eu
cd "$(dirname "$0")/.."

KEYDIR=keys
SRC=zones
BUILD=build
OUT=$BUILD/broken
PDS=$BUILD/parent-ds
TMP=$BUILD/tmp-break        # helper signing runs (A5, D5, D6)
SIG_START=${SIG_START:-now-3600}
SIG_END=${SIG_END:-now+2592000}

mkdir -p "$OUT/auth2" "$OUT/auth3" "$PDS" "$TMP"

# Errors that "zone" mode can put on any hosting zone: those with "hosting: true" in the catalogue
# (they need nothing but the zone's own KSK and ZSK and the records every hosting zone has, www A
# and mail), except E1, which "rollover" does. The web app's demo panel offers the same list.
ANY_ZONE=$(awk '/^ *- id: / { id = tolower($3) }
                /^ *lab: .*hosting: true/ && id != "e1" { printf "%s%s", sep, id; sep = " " }' catalog/catalog.yaml)

# ---------- helpers ----------

# Is $1 a zone of the demo hosting (zones/hosting.list)?
is_hosting() {
    awk -v z="$1" '$1 == z { found = 1 } END { exit !found }' "$SRC/hosting.list"
}

# Sign a zone without the built-in check (-P).
#   $1 = input, $2 = output, $3 = zone (no trailing dot), $4/$5 = validity start/end,
#   $6 = SOA serial (unixtime or keep), rest = extra options for dnssec-signzone
# -O full: one record = one line, so awk can easily find and change exactly one record.
signzone() {
    in=$1; out=$2; zone=$3; start=$4; end=$5; serial=$6; shift 6
    dnssec-signzone -P -S -K "$KEYDIR" -d "$BUILD" -O full \
        -s "$start" -e "$end" -N "$serial" "$@" \
        -o "$zone" -f "$out" "$in" >/dev/null
}

# The common case: source from zones/, output for auth1.
#   $1 = zone, $2/$3 = validity start/end, rest = extra options
sign_raw() {
    zone=$1; start=$2; end=$3; shift 3
    signzone "$SRC/$zone.zone" "$OUT/$zone.zone.signed" "$zone" "$start" "$end" unixtime "$@"
}

# Correct DS for the parent (the dsset dnssec-signzone produced).
good_ds() {
    cp "$BUILD/dsset-$1." "$PDS/$1.ds"
}

# The zone's KSK file (flags 257), in $KEYDIR or another directory ($2). Any algorithm.
ksk_file() {
    grep -l 'DNSKEY 257' "${2:-$KEYDIR}"/K"$1".+*.key | head -n 1
}

# Key tag from the file name: Kzone.+013+01193.key -> 1193
key_tag() {
    basename "$1" .key | awk -F+ '{ print $3 + 0 }'
}

# The DNSKEY record from a .key file (without the leading comments).
dnskey_of() {
    grep -v '^;' "$1"
}

# Edit a signed zone in place with awk program $2 (file: $3, default the auth1 output).
edit_zone() {
    f=${3:-$OUT/$1.zone.signed}
    awk "$2" "$f" > "$f.tmp"
    mv "$f.tmp" "$f"
}

# ---------- errors: MVP tab ----------

# A1 – expired signature. Valid from 37 days ago until 7 days ago.
# (Unbound tolerates up to 1 day of clock skew – val-sig-skew-max – so "yesterday" isn't enough.)
break_a1() {
    z=${1:-a1-expired.test}
    sign_raw "$z" now-3196800 now-604800
    good_ds "$z"
}

# A3 – record without a signature: delete the RRSIG for www A.
break_a3() {
    z=${1:-a3-nosig.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    edit_zone "$z" '!($1 == "www.'"$z"'." && $4 == "RRSIG" && $5 == "A")'
    good_ds "$z"
}

# A4 – invalid signature: change www's IP after signing.
break_a4() {
    z=${1:-a4-bogus.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    edit_zone "$z" '$1 == "www.'"$z"'." && $4 == "A" { $5 = "10.53.0.99" } { print }'
    good_ds "$z"
}

# A6 – signature expires soon: valid for only 2 more days. The zone is correct.
break_a6() {
    z=${1:-a6-expiring.test}
    sign_raw "$z" "$SIG_START" now+172800
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    good_ds "$z"
}

# B1 – DS points to a key that doesn't exist: the parent has the DS of the old KSK
# (keys/old/, made by keygen.sh), but the zone is signed with the new one.
break_b1() {
    z=${1:-b1-ds-stale.test}
    old=$(ksk_file "$z" "$KEYDIR/old")
    [ -n "$old" ] || { echo "No old KSK for $z – run keygen.sh"; exit 1; }
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-dsfromkey -2 "$old" > "$PDS/$z.ds"
}

# B2 – DS has a wrong digest: change one hex character
# (the first character of the last field – dsset splits the digest into several fields).
break_b2() {
    z=${1:-b2-ds-digest.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    awk '{ c = substr($NF, 1, 1); $NF = (c == "0" ? "1" : "0") substr($NF, 2); print }' \
        "$BUILD/dsset-$z." > "$PDS/$z.ds"
}

# B4 – DS exists, but the zone is unsigned: serve the source as is.
# The keys exist only so there is something to build the DS from.
break_b4() {
    z=${1:-b4-ds-unsigned.test}
    cp "$SRC/$z.zone" "$OUT/$z.zone.signed"
    dnssec-dsfromkey -2 "$(ksk_file "$z")" > "$PDS/$z.ds"
}

# D1 – broken NSEC chain: delete the NSEC (and its RRSIG) at mail.
# That NSEC covers the range mail → ns1, e.g. the name "missing".
break_d1() {
    z=${1:-d1-nsec-broken.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    edit_zone "$z" '!($1 == "mail.'"$z"'." && ($4 == "NSEC" || ($4 == "RRSIG" && $5 == "NSEC")))'
    good_ds "$z"
}

# D4 – NSEC3 against RFC 9276: 200 iterations + salt.
# dnssec-signzone refuses > 150 iterations, hence ldns-signzone (it reads the same BIND keys).
break_d4() {
    z=${1:-d4-nsec3-iter.test}
    # shellcheck disable=SC2012  # key file names are safe (K<zone>+013+<number>)
    keys=$(ls "$KEYDIR"/K"$z".+013+*.private | sed 's/\.private$//')
    # shellcheck disable=SC2086  # $keys unquoted on purpose: several keys
    ldns-signzone -n -t 200 -s abcdef -o "$z" -f "$OUT/$z.zone.signed" \
        "$SRC/$z.zone" $keys 2>/dev/null
    dnssec-dsfromkey -2 "$(ksk_file "$z")" > "$PDS/$z.ds"
}

# E1 – the zone starts out CORRECT. Only e1-rollover.sh breaks it (live),
# because the error depends on what the resolver already has in its cache.
break_e1() {
    z=${1:-e1-rollover.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    good_ds "$z"
}

# ---------- errors: later tab ----------

# A2 – signature not yet valid: it starts only in 7 days (as if the server clock were off).
# "Tomorrow" isn't enough: Unbound tolerates up to 1 day of clock skew (val-sig-skew-max).
break_a2() {
    z=${1:-a2-notyet.test}
    sign_raw "$z" now+604800 now+3196800
    good_ds "$z"
}

# A5 – signature by a key that is not in the DNSKEY set.
# Two signing runs: (1) KSK + ZSK1 – from it we take only the DNSKEY set and its signature;
# (2) only ZSK2 (keys/k2/) – from it we take everything else. ZSK2 is never published.
break_a5() {
    z=${1:-a5-unknown-key.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-signzone -P -S -K "$KEYDIR/k2" -d "$TMP" -O full \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o "$z" -f "$TMP/$z.k2" "$SRC/$z.zone" >/dev/null
    f=$OUT/$z.zone.signed
    { awk '$4 == "DNSKEY" || ($4 == "RRSIG" && $5 == "DNSKEY")' "$f"
      awk '!($4 == "DNSKEY" || ($4 == "RRSIG" && $5 == "DNSKEY"))' "$TMP/$z.k2"; } > "$f.tmp"
    mv "$f.tmp" "$f"
    good_ds "$z"
}

# A7 – TTL longer than signature validity: TTL 30 days (in the source), signatures valid 7 days.
break_a7() {
    z=${1:-a7-ttl-long.test}
    sign_raw "$z" "$SIG_START" now+604800
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    good_ds "$z"
}

# B3 – DS and DNSKEY have different algorithms: 13 → 8 in the DS.
break_b3() {
    z=${1:-b3-ds-alg.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    # dsfromkey: "zone. IN DS <tag> <alg> <digest type> <digest>"
    dnssec-dsfromkey -2 "$(ksk_file "$z")" | awk '{ $5 = 8; print }' > "$PDS/$z.ds"
}

# B5 – DNSKEY set not signed by the KSK: delete the KSK's signature over the DNSKEY set.
# (dnssec-signzone signs the DNSKEY set with both keys, so only the ZSK's signature remains.)
break_b5() {
    z=${1:-b5-dnskey-zsk.test}
    tag=$(key_tag "$(ksk_file "$z")")
    sign_raw "$z" "$SIG_START" "$SIG_END"
    # RRSIG fields: $5 type, ..., $11 key tag
    edit_zone "$z" '!($4 == "RRSIG" && $5 == "DNSKEY" && $11 == '"$tag"')'
    good_ds "$z"
}

# B6 – "island": the zone is correctly signed, but no DS goes to the parent.
break_b6() {
    z=${1:-b6-island.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    # no $PDS/$z.ds on purpose
}

# B7 – DS with SHA-1 (digest type 1). The zone is correct.
break_b7() {
    z=${1:-b7-ds-sha1.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    dnssec-dsfromkey -1 "$(ksk_file "$z")" > "$PDS/$z.ds"
}

# B8 – leftover old DS records: the correct DS + the DS of the old KSK (keys/old/).
break_b8() {
    z=${1:-b8-ds-extra.test}
    old=$(ksk_file "$z" "$KEYDIR/old")
    [ -n "$old" ] || { echo "No old KSK for $z – run keygen.sh"; exit 1; }
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    { cat "$BUILD/dsset-$z."; dnssec-dsfromkey -2 "$old"; } > "$PDS/$z.ds"
}

# C1 – deprecated algorithm: the keys are RSASHA1 (algorithm 5), see keygen.sh.
break_c1() {
    z=${1:-c1-rsasha1.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    good_ds "$z"
}

# C2 – two algorithms in the DNSKEY set and in the DS, but records are signed only with 13.
# The algorithm-8 KSK (keys/alg8/) is only published – it's not in $KEYDIR, so it signs nothing.
break_c2() {
    z=${1:-c2-two-alg.test}
    alg8=$(ksk_file "$z" "$KEYDIR/alg8")
    [ -n "$alg8" ] || { echo "No algorithm-8 KSK for $z – run keygen.sh"; exit 1; }
    { cat "$SRC/$z.zone"; echo; dnskey_of "$alg8"; } > "$TMP/$z.in"
    signzone "$TMP/$z.in" "$OUT/$z.zone.signed" "$z" "$SIG_START" "$SIG_END" unixtime
    { dnssec-dsfromkey -2 "$(ksk_file "$z")"; dnssec-dsfromkey -2 "$alg8"; } > "$PDS/$z.ds"
}

# C3 – weak RSA key: RSASHA256 with 1024 bits, see keygen.sh. The zone is correct.
break_c3() {
    z=${1:-c3-rsa1024.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    good_ds "$z"
}

# D2 – wrong type list in the NSEC: the source has www TXT, so the NSEC for www says
# "A TXT RRSIG NSEC". After signing we delete the TXT (and its RRSIG) –
# the NSEC stays correctly signed, but lies that TXT exists.
break_d2() {
    z=${1:-d2-bitmap.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    edit_zone "$z" '!($1 == "www.'"$z"'." && ($4 == "TXT" || ($4 == "RRSIG" && $5 == "TXT")))'
    good_ds "$z"
}

# D3 – negative answer without signatures: delete all RRSIGs over NSEC records.
break_d3() {
    z=${1:-d3-nsec-nosig.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    edit_zone "$z" '!($4 == "RRSIG" && $5 == "NSEC")'
    good_ds "$z"
}

# D5 – NSEC3PARAM doesn't match the NSEC3 chain: the chain has no salt, while the NSEC3PARAM
# (taken from a second signing run with the same key, so it is correctly signed) has salt "abcd".
break_d5() {
    z=${1:-d5-nsec3param.test}
    sign_raw "$z" "$SIG_START" "$SIG_END" -3 - -H 0
    dnssec-signzone -P -S -K "$KEYDIR" -d "$TMP" -O full -3 abcd -H 0 \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o "$z" -f "$TMP/$z.salt" "$SRC/$z.zone" >/dev/null
    f=$OUT/$z.zone.signed
    { awk '!($4 == "NSEC3PARAM" || ($4 == "RRSIG" && $5 == "NSEC3PARAM"))' "$f"
      awk '$4 == "NSEC3PARAM" || ($4 == "RRSIG" && $5 == "NSEC3PARAM")' "$TMP/$z.salt"; } > "$f.tmp"
    mv "$f.tmp" "$f"
    good_ds "$z"
}

# D6 – NSEC and NSEC3 mixed: an NSEC3 zone + NSEC records (with signatures)
# from a second signing run of the same zone, as if the NSEC → NSEC3 migration never finished.
break_d6() {
    z=${1:-d6-nsec-mixed.test}
    sign_raw "$z" "$SIG_START" "$SIG_END" -3 - -H 0
    dnssec-signzone -P -S -K "$KEYDIR" -d "$TMP" -O full \
        -s "$SIG_START" -e "$SIG_END" -N unixtime \
        -o "$z" -f "$TMP/$z.nsec" "$SRC/$z.zone" >/dev/null
    awk '$4 == "NSEC" || ($4 == "RRSIG" && $5 == "NSEC")' "$TMP/$z.nsec" >> "$OUT/$z.zone.signed"
    good_ds "$z"
}

# E2 – KSK rollover, DS replaced too early: the parent already has the DS of the new KSK
# (keys/next/), while the zone still publishes and uses only the old one.
break_e2() {
    z=${1:-e2-ksk-ds-early.test}
    new=$(ksk_file "$z" "$KEYDIR/next")
    [ -n "$new" ] || { echo "No new KSK for $z – run keygen.sh"; exit 1; }
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-dsfromkey -2 "$new" > "$PDS/$z.ds"
}

# E3 – the zone starts out CORRECT. e3-rollover.sh breaks it (live): the resolver
# caches records signed with the old ZSK, and the old ZSK vanishes from the zone at once.
break_e3() {
    z=${1:-e3-rollover.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    good_ds "$z"
}

# E4 – CDS doesn't match the keys: publish a (correctly signed) CDS with a wrong digest.
break_e4() {
    z=${1:-e4-cds.test}
    { cat "$SRC/$z.zone"; echo
      dnssec-dsfromkey -2 "$(ksk_file "$z")" |
          awk '{ $3 = "CDS"; c = substr($NF, 1, 1); $NF = (c == "0" ? "1" : "0") substr($NF, 2); print }'
    } > "$TMP/$z.in"
    signzone "$TMP/$z.in" "$OUT/$z.zone.signed" "$z" "$SIG_START" "$SIG_END" unixtime
    good_ds "$z"
}

# F1 – the secondary server has a stale zone: auth1 gets the correct zone,
# auth2 a stale copy (serial from the source, signatures expired 7 days ago).
break_f1() {
    z=${1:-f1-stale-secondary.test}
    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    signzone "$SRC/$z.zone" "$OUT/auth2/$z.zone.signed" "$z" now-3196800 now-604800 keep
    good_ds "$z"
}

# F2 – the server can't handle large answers: the zone is correct, but it is served by
# auth3 (no TCP, UDP up to 512 bytes). The large TXT + RRSIG doesn't fit in 512.
break_f2() {
    z=${1:-f2-tcp-edns.test}
    signzone "$SRC/$z.zone" "$OUT/auth3/$z.zone.signed" "$z" "$SIG_START" "$SIG_END" unixtime
    dnssec-verify -q -o "$z" "$OUT/auth3/$z.zone.signed"
    good_ds "$z"
}

# ---------- E1 / E3: live ZSK replacement ----------

# The new ZSK is used right away, the old one is dropped right away.
# The old ZSK goes to keys/retired/, so dnssec-signzone -S no longer sees it.
#   $1 = zone (e1-rollover.test, e3-rollover.test or a hosting zone)
rollover() {
    z=$1
    old=$(grep -l 'DNSKEY 256' "$KEYDIR"/K"$z".+013+*.key | head -n 1)
    [ -n "$old" ] || { echo "No ZSK for $z – run keygen.sh"; exit 1; }

    mkdir -p "$KEYDIR/retired"
    mv "$old" "${old%.key}.private" "$KEYDIR/retired/"
    new=$(dnssec-keygen -q -K "$KEYDIR" -a ECDSAP256SHA256 "$z")
    echo ">> $z: old ZSK $(basename "${old%.key}") -> keys/retired/, new ZSK $new"

    sign_raw "$z" "$SIG_START" "$SIG_END"
    dnssec-verify -q -o "$z" "$OUT/$z.zone.signed"
    cp "$OUT/$z.zone.signed" auth1/
}

# ---------- main ----------

if [ "${1:-}" = "rollover" ]; then
    z=${2:-}
    case "$z" in
        e1-rollover.test|e3-rollover.test) ;;
        *) is_hosting "$z" || { echo "Usage: sh scripts/break.sh rollover e1-rollover.test|e3-rollover.test|<hosting zone>"; exit 1; }
           OUT=$BUILD/hosting; mkdir -p "$OUT" ;;
    esac
    rollover "$z"
    exit 0
fi

if [ "${1:-}" = "zone" ]; then
    z=${2:-}; e=${3:-}
    is_hosting "$z" || { echo "$z is not in $SRC/hosting.list"; exit 1; }
    case " $ANY_ZONE " in
        *" $e "*) ;;
        *) echo "Usage: sh scripts/break.sh zone <hosting zone> <error>   (error: $ANY_ZONE)"; exit 1 ;;
    esac
    OUT=$BUILD/hosting
    mkdir -p "$OUT"
    rm -f "$PDS/$z.ds"     # B6 writes none; the others write their own
    echo ">> $z: broken as $e"
    "break_$e" "$z"
    exit 0
fi

for e in a1 a3 a4 a6 b1 b2 b4 d1 d4 e1 \
         a2 a5 a7 b3 b5 b6 b7 b8 c1 c2 c3 d2 d3 d5 d6 e2 e3 e4 f1 f2; do
    echo ">> broken zone: $e"
    break_$e
done
