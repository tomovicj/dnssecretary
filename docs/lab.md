# The lab

A miniature internet in Docker: its own root and trust anchor, a TLD, authoritative servers, a
validating resolver and a client. It hosts the demo hosting's 40 customer zones and one broken
zone for each of the 30 catalogue errors. Nothing in it can reach the internet.

## Servers

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
│ NSD: 40 hosting  │ │ NSD: stale copy  │ │ Unbound auth-zone │
│ + 29 broken zones│ │ of F1 zone       │ │ no TCP, UDP ≤ 512 │
└──────────────────┘ └──────────────────┘ └───────────────────┘

┌──────────────────────┐         ┌───────────────────────┐
│ client  10.53.0.100  │ ─────▶ │ resolver  10.53.0.53  │  Unbound 1.20, validating,
│ dig, delv            │         │                       │  trust anchor = lab root KSK
└──────────────────────┘         └───────────────────────┘
```

| Service | IP | Software | Serves |
| --- | --- | --- | --- |
| `root` | 10.53.0.2 | NSD | `.` |
| `tld` | 10.53.0.3 | NSD | `test.` (delegations and DS records of every zone below) |
| `auth1` | 10.53.0.11 | NSD | `good.test`, the 40 hosting zones, 29 broken zones |
| `auth2` | 10.53.0.12 | NSD | a stale copy of `f1-stale-secondary.test` (error F1) |
| `auth3` | 10.53.0.13 | Unbound `auth-zone` | `f2-tcp-edns.test`, without TCP and with 512-byte UDP (error F2; NSD can't turn TCP off) |
| `resolver` | 10.53.0.53 | Unbound 1.20 | validating resolver; trust anchor = the lab's root KSK |
| `client` | 10.53.0.100 | `dig`, `delv` | for queries by hand |

All of them use one image (`image/Dockerfile`: Alpine 3.20 with NSD, Unbound, BIND tools and
ldns, every version pinned, because the expected results depend on them). Each server's directory
(`root/`, `tld/`, `auth1/`, …) is mounted read-only as its config directory.

Query by hand from the client, always naming the resolver (Docker's own DNS strips EDE codes):

```sh
docker compose exec client dig @10.53.0.53 +dnssec www.northwind-coffee.test
docker compose exec client dig @10.53.0.53 +dnssec www.a1-expired.test     # SERVFAIL, EDE 7
docker compose exec client delv @10.53.0.53 -a /lab/delv-anchor.conf www.good.test
docker compose logs resolver                                               # why validation failed
```

## Signing

Signing tools run only in the `signer` container (`network_mode: none`, repo at `/lab`); pass
`--user $(id -u):$(id -g)` so the files stay yours.

- `keygen.sh` makes a KSK and a ZSK per zone, once (ECDSA P-256, algorithm 13; C1 and C3 get weak
  RSA keys on purpose), plus extra keys some errors need (`keys/old`, `next`, `k2`, `alg8`).
- `sign.sh` signs bottom-up, because every parent needs its children's DS records:
  1. `good.test`, then the broken zones (`break.sh`) and the hosting zones (`hosting.sh`); each
     writes the DS its parent should have to `build/parent-ds/<zone>.ds`;
  2. `test.` with all those DS records and the hosting zones' delegations (`sign-tld.sh`);
  3. the root with the DS of `test.`;
  4. the trust anchor from the root KSK: `resolver/root.key` (Unbound) and `client/delv-anchor.conf` (delv);
  5. the signed zones are copied into their servers' directories.

Correct zones go through `dnssec-verify`; with `set -e`, a failure stops before anything broken is
delivered. Signatures are valid from 1 hour ago for 30 days (`SIG_START` / `SIG_END` override it),
so **re-sign at least every 30 days**, then `docker compose restart root tld auth1 auth2 auth3 resolver`.

Zone sources in `zones/` are edited by hand and must not contain DS records or `$INCLUDE`: `sign.sh`
adds the DS records and refuses to run if it finds them. Keys, `build/`, signed zones,
`auth1/hosting.conf` and the trust anchors are generated and gitignored; every machine makes its own
keys, so trust anchors differ between team members.

## The demo hosting

[`zones/hosting.list`](../zones/hosting.list) names 40 customer zones of a made-up hosting
(`northwind-coffee.test`, `lindqvist-law.test`, …), each with a hand-made source in `zones/`. They are
what the web app watches. Everything else is generated from the list: their keys (`keygen.sh`), their
delegations in `test.` with glue to auth1 (`sign-tld.sh`), signing and `auth1/hosting.conf`, which
`auth1/nsd.conf` includes (`hosting.sh`).

All are signed correctly, except three that start "at risk", so the dashboard has something to
show before anything breaks: `westgate-pharmacy.test` (A6, signatures expire in 2 days),
`granite-legal.test` (B7, a SHA-1 DS) and `oakmont-school.test` (D4, NSEC3 with 200 iterations).

`sh scripts/demo.sh break <zone> <error>` breaks a hosting zone live. `break.sh zone <zone> <error>`
reuses the same `break_<id>` function that builds the catalogue zone for that error, on the hosting
zone's source and keys; `break.sh rollover <zone>` replaces a ZSK at once (E1). Only errors marked
`hosting: true` in the catalogue are allowed: those that need nothing but the zone's own keys and
the records every hosting zone has. `sh scripts/demo.sh reset` restores the saved keys, re-signs
everything and restarts the servers.

## The broken zones (error catalogue)

Each zone is named `<id>-<short>.test` and is delegated from `test.`. The IDs come from the team's error catalogue ([`catalog/catalog.yaml`](../catalog/catalog.yaml)). "Result" is what Unbound 1.20 returns, as checked by `check.sh`.

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

## Lab notes

- **Clock skew:** Unbound tolerates up to 1 day of clock skew (`val-sig-skew-max`). Time-based errors (A1, A2, F1) therefore use offsets of 7 days or more.
- **Building the broken zones:**
  - Most are signed with `dnssec-signzone -P -O full` (one record per line), then edited with `awk`.
  - A5, D5 and D6 splice in records from a second signing run.
  - D4 is signed with `ldns-signzone`, because `dnssec-signzone` refuses more than 150 NSEC3 iterations.
- **Results depend on versions.** Package versions are pinned in `image/Dockerfile`. After bumping one, run `check.sh` and update the expected results in it.

## Scripts

| Script | Runs in | What it does |
|--------|---------|--------------|
| `scripts/keygen.sh` | signer | Makes a KSK + ZSK per zone, plus extra keys some errors need (`keys/old`, `next`, `k2`, `alg8`). |
| `scripts/sign.sh` | signer | Signs bottom-up: `good.test` → broken zones → hosting zones → `test.` → root. Then builds the trust anchor and copies zones to the servers. |
| `scripts/break.sh` | signer | Called by `sign.sh`. Builds the broken zones and the DS records their parent should have. `zone <zone> <error>` breaks a hosting zone the same way, `rollover <zone>` replaces a ZSK at once. |
| `scripts/hosting.sh` | signer | Called by `sign.sh`: signs the demo hosting's 40 customer zones (`zones/hosting.list`), three of them with an "at risk" start error, and writes `auth1/hosting.conf`. |
| `scripts/sign-tld.sh` | signer | Step 2 of `sign.sh`: re-signs only `test.` with the children's DS records and copies it to `tld/`. DNSSECretary uses it to fix a DS in the lab. |
| `scripts/check.sh` | host | Queries every zone and compares rcode / AD / EDE with the expected result. Exits 1 on mismatch. `STRICT_EDE=1` also fails on wrong EDE codes. |
| `scripts/e1-rollover.sh` | host | Breaks E1 live: caches the DNSKEY set, then swaps the ZSK without pre-publishing it. |
| `scripts/e3-rollover.sh` | host | Breaks E3 live: caches `www`, swaps the ZSK and removes the old one at once. `delv` fails, Unbound itself doesn't. |
| `scripts/clean.sh` | host | Removes everything generated. `-n` dry run, `-k` keep keys, `-y` don't ask. |
| `scripts/dnssecretary.sh` | host | Runs DNSSECretary in the `app` container on the lab network, e.g. `check a1-expired.test`. See [`dnssecretary/README.md`](../dnssecretary/README.md). |
| `scripts/simulate.sh` | host / web | Tries a fix script in a sandbox copy of the lab (one container, no network, the lab's addresses on `lo`), then `verify`. Exit 0 = verified. The lab itself is not touched. |
| `scripts/demo.sh` | host / web | Web app demo: `break <zone> <error>` (a hosting zone broken live, e.g. `a1`; `e1` = live rollover), `reset` (the lab as after `sign.sh`, keys and auth3 config restored). |
| `scripts/video.sh` | host | Records the demo as a video (`demo/out/dnssecretary-demo.webm`): a browser in the `tour` container (Playwright, `demo/tour.mjs`) goes through one incident in under a minute. The web app must be running. |
| `scripts/setup.sh` | host | Everything from a fresh clone to the running web app; `--check` also runs `check.sh`. See [Getting started](getting-started.md). |
| `scripts/web.sh` | host | Starts (or restarts, always with a fresh JVM) the web app (compose service `web`) on http://localhost:8080; `logs`, `stop`. Build the front end first (`webapp/`, `npm run build`), see [`webapp/README.md`](../webapp/README.md). |
| `scripts/snapshots.sh` | host | Records a DNSSECretary snapshot of every catalogue zone (unit test data). `ROLLOVER=1` also records E1/E3. |

After running a rollover script, run `sign.sh` and restart the servers to get a clean lab again.

After a rollover script (`e1-rollover.sh`, `e3-rollover.sh`), run `sign.sh` and restart the servers
to get a clean lab again.

## Adding a zone or an error

- **A hosting zone**: its source in `zones/<zone>.zone` and a line in `zones/hosting.list`, then
  `keygen.sh` and `sign.sh`.
- **A catalogue error**: a source in `zones/`, a delegation in `zones/test.zone`, the zone in
  `keygen.sh`'s `ZONES`, a `break_<id>` function in `break.sh`, a `zone:` block in `auth1/nsd.conf`,
  an entry in `catalog/catalog.yaml` (and `ErrorId` in Java), a row in `check.sh`, and a recorded
  snapshot. See [Development](development.md#adding-an-error).
- **A server**: a directory with its config, a compose service with a fixed IP, a delegation and glue
  in the parent. Don't delete a mounted server directory, only the files in it: Docker would recreate
  it as root, and `sign.sh` could no longer write to it.
