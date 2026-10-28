# dnssec-lab

An isolated DNSSEC lab in Docker: a miniature DNS hierarchy with its own root and trust anchor, a validating resolver, and **one deliberately broken zone for each of 30 common DNSSEC errors**.

It is the test environment for **DNSSECretary** (Team Port 53). DNSSECretary checks a zone, explains each error in plain language, and fixes it or gives the exact fix. Every error in the team's error catalogue has a zone here that reproduces it, so the detection and fix engines can be tested against real broken zones.

Everything runs on an internal Docker network with no internet access. There are no external queries, and the lab changes no records outside itself.

## Architecture

```
                    ┌──────────────────────┐
                    │ root      10.53.0.2  │  NSD, zone "."
                    └──────────┬───────────┘
                    ┌──────────┴───────────┐
                    │ tld       10.53.0.3  │  NSD, zone "test."
                    └──────────┬───────────┘
         ┌─────────────────────┼─────────────────────┐
┌────────┴─────────┐ ┌─────────┴────────┐ ┌──────────┴────────┐
│ auth1 10.53.0.11 │ │ auth2 10.53.0.12 │ │ auth3 10.53.0.13  │
│ NSD: good.test + │ │ NSD: stale copy  │ │ Unbound auth-zone │
│ 29 broken zones  │ │ of F1 zone       │ │ no TCP, UDP ≤ 512 │
└──────────────────┘ └──────────────────┘ └───────────────────┘

┌──────────────────────┐         ┌───────────────────────┐
│ client  10.53.0.100  │ ─────▶ │ resolver  10.53.0.53  │  Unbound 1.20, validating,
│ dig, delv            │         │                       │  trust anchor = lab root KSK
└──────────────────────┘         └───────────────────────┘
```

All containers use one image (`image/Dockerfile`: Alpine with NSD, Unbound, BIND tools and ldns). Each server's directory (`root/`, `tld/`, `auth1/`, …) is mounted read-only as its config directory.

## Requirements

- Docker with Compose v2
- A POSIX shell on the host

Signing tools (`dnssec-keygen`, `dnssec-signzone`, `ldns-signzone`) run inside the `signer` container, so nothing else needs to be installed.

## Quick start

```sh
docker compose build

# 1. Keys (once). Skips zones that already have keys.
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/keygen.sh

# 2. Sign all zones and build the trust anchor (after every zone change).
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh

# 3. Start the lab.
docker compose up -d

# 4. Check that every broken zone shows the expected result.
sh scripts/check.sh
```

Always pass `--user`, so the generated files are owned by you and not by root.

After re-signing, reload the servers:

```sh
docker compose restart root tld auth1 auth2 auth3 resolver
```

### Querying by hand

```sh
docker compose exec client dig @10.53.0.53 +dnssec www.good.test
docker compose exec client dig @10.53.0.53 +dnssec www.a1-expired.test   # SERVFAIL, EDE 7
docker compose exec client delv @10.53.0.53 -a /lab/delv-anchor.conf www.good.test
docker compose logs resolver                                             # why validation failed
```

Always query `@10.53.0.53`. Without it, `dig` goes through Docker's embedded DNS (127.0.0.11), which strips Extended DNS Error (EDE) codes from the answer.

## Scripts

| Script | Runs in | What it does |
|--------|---------|--------------|
| `scripts/keygen.sh` | signer | Makes a KSK + ZSK per zone, plus extra keys some errors need (`keys/old`, `next`, `k2`, `alg8`). |
| `scripts/sign.sh` | signer | Signs bottom-up: `good.test` → broken zones → `test.` → root. Then builds the trust anchor and copies zones to the servers. |
| `scripts/break.sh` | signer | Called by `sign.sh`. Builds the broken zones and the DS records their parent should have. |
| `scripts/check.sh` | host | Queries every zone and compares rcode / AD / EDE with the expected result. Exits 1 on mismatch. `STRICT_EDE=1` also fails on wrong EDE codes. |
| `scripts/e1-rollover.sh` | host | Breaks E1 live: caches the DNSKEY set, then swaps the ZSK without pre-publishing it. |
| `scripts/e3-rollover.sh` | host | Breaks E3 live: caches `www`, swaps the ZSK and removes the old one at once. `delv` fails, Unbound itself doesn't. |
| `scripts/clean.sh` | host | Removes everything generated. `-n` dry run, `-k` keep keys, `-y` don't ask. |

After running a rollover script, run `sign.sh` and restart the servers to get a clean lab again.

## Broken zones

Each zone is named `<id>-<short>.test` and is delegated from `test.`. The IDs come from the team's error catalogue (`DNSSECretary-katalog-gresaka`). "Result" is what Unbound 1.20 returns, as checked by `check.sh`.

| ID | Zone | Error | Result (Unbound 1.20) |
|----|------|-------|----------------------|
| A1 | `a1-expired.test` | Signature expired | SERVFAIL, EDE 7 |
| A2 | `a2-notyet.test` | Signature not yet valid | SERVFAIL, EDE 8 |
| A3 | `a3-nosig.test` | RRset without RRSIG | SERVFAIL, EDE 10 |
| A4 | `a4-bogus.test` | Data changed after signing | SERVFAIL, EDE 6 |
| A5 | `a5-unknown-key.test` | Signed by a key not in the DNSKEY set | SERVFAIL, EDE 9 |
| A6 | `a6-expiring.test` | Signature expires in 2 days | NOERROR, AD (risk) |
| A7 | `a7-ttl-long.test` | TTL (30 d) longer than signature validity (7 d) | NOERROR, AD (risk) |
| B1 | `b1-ds-stale.test` | DS points to a key that doesn't exist | SERVFAIL, EDE 6 |
| B2 | `b2-ds-digest.test` | DS has a wrong digest | SERVFAIL, EDE 6 |
| B3 | `b3-ds-alg.test` | DS and DNSKEY algorithms differ | SERVFAIL, EDE 6 |
| B4 | `b4-ds-unsigned.test` | DS exists, zone unsigned | SERVFAIL, EDE 9 |
| B5 | `b5-dnskey-zsk.test` | DNSKEY set not signed by the KSK | SERVFAIL, EDE 6 |
| B6 | `b6-island.test` | Signed zone, no DS in parent ("island") | NOERROR, no AD |
| B7 | `b7-ds-sha1.test` | DS uses SHA-1 | NOERROR, AD (risk) |
| B8 | `b8-ds-extra.test` | Leftover old DS next to the correct one | NOERROR, AD (risk) |
| C1 | `c1-rsasha1.test` | Deprecated algorithm (5, RSASHA1) | NOERROR, AD (risk) |
| C2 | `c2-two-alg.test` | Two algorithms, records signed with only one | NOERROR, AD (Unbound is lenient) |
| C3 | `c3-rsa1024.test` | Weak RSA key (1024 bits) | NOERROR, AD (risk) |
| D1 | `d1-nsec-broken.test` | Broken NSEC chain | SERVFAIL for `missing.`, EDE 6 |
| D2 | `d2-bitmap.test` | NSEC type bitmap lists a type that doesn't exist | SERVFAIL for `www TXT`, EDE 6 |
| D3 | `d3-nsec-nosig.test` | NSEC records without signatures | SERVFAIL for `missing.`, EDE 12 |
| D4 | `d4-nsec3-iter.test` | NSEC3 with 200 iterations + salt (RFC 9276) | NXDOMAIN without AD |
| D5 | `d5-nsec3param.test` | NSEC3PARAM doesn't match the NSEC3 chain | SERVFAIL for `missing.`, EDE 12 |
| D6 | `d6-nsec-mixed.test` | NSEC and NSEC3 mixed in one zone | NXDOMAIN, AD (risk) |
| E1 | `e1-rollover.test` | ZSK replaced without pre-publishing | SERVFAIL, EDE 9 (after `e1-rollover.sh`) |
| E2 | `e2-ksk-ds-early.test` | DS of the new KSK published too early | SERVFAIL, EDE 6 |
| E3 | `e3-rollover.test` | Old ZSK removed before TTL expired | `delv` fails, Unbound AD from cache (after `e3-rollover.sh`) |
| E4 | `e4-cds.test` | CDS doesn't match the keys | NOERROR, AD (risk) |
| F1 | `f1-stale-secondary.test` | Secondary (auth2) serves a stale zone with expired signatures | NOERROR, AD (Unbound retries auth1) |
| F2 | `f2-tcp-edns.test` | Server without TCP and a 512-byte UDP limit | SERVFAIL for `big. TXT`, no EDE |

"Risk" means the domain still resolves, but the setup is unsafe or bad practice.

Some results differ from what other resolvers would return. For example, BIND may use other EDE codes, or be stricter for C2. This is why DNSSECretary detects errors from the zone data and treats EDE only as a hint.

### Lab notes

- **Clock skew:** Unbound tolerates up to 1 day of clock skew (`val-sig-skew-max`). Time-based errors (A1, A2, F1) therefore use offsets of 7 days or more.
- **Building the broken zones:**
  - Most are signed with `dnssec-signzone -P -O full` (one record per line), then edited with `awk`.
  - A5, D5 and D6 splice in records from a second signing run.
  - D4 is signed with `ldns-signzone`, because `dnssec-signzone` refuses more than 150 NSEC3 iterations.
- **Results depend on versions.** Package versions are pinned in `image/Dockerfile`. After bumping one, run `check.sh` and update the expected results in it.

## Repository layout

```
zones/            unsigned zone sources (hand-edited; no DS records – sign.sh adds them)
scripts/          keygen, sign, break, check, rollover and clean scripts
root/ tld/        NSD configs for the root and test. servers
auth1/ auth2/     NSD configs for the authoritative servers
auth3/            Unbound auth-zone config (error F2)
resolver/         Unbound resolver config and root hints
image/Dockerfile  the shared image (pinned versions)
compose.yaml      network and services
```

Generated and gitignored: `keys/`, `build/`, `*/*.signed`, `resolver/root.key`, `client/delv-anchor.conf`. Every team member generates their own keys, so trust anchors differ between machines.

## CI

`.github/workflows/lab.yml` runs on pushes to `main` and on pull requests. It runs:

1. `shellcheck` over `scripts/`.
2. The whole lab from scratch: build → `keygen.sh` → `sign.sh` → `up -d` → `check.sh` → both rollover scripts.

To run shellcheck locally:

```sh
docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:v0.10.0 scripts/*.sh
```
