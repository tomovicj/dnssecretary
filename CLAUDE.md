# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A self-contained DNSSEC lab: a miniature DNS hierarchy (root → `test.` → `good.test.`) served by NSD, with a validating Unbound resolver, all running in Docker on an isolated network (`internal: true`, no internet). There is no application code, build system, or test suite. The "code" is shell scripts, zone files, and server configs.

Comments (in scripts, configs, zone files and the workflow) are in English. Script output, `check.sh` descriptions, workflow step names and the TXT records in the broken zones are in Serbian (Latin script). Keep new code in the same split.

## Commands

Run everything from the host, in the repo root. Signing tools exist only inside the image, so the scripts run in the `signer` service (profile `tools`, `network_mode: none`, repo mounted at `/lab`). Always pass `--user` so the generated files aren't owned by root:

```sh
docker compose build                                                              # build the shared "dnslab" image (alpine + nsd, unbound, bind tools, ldns)
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/keygen.sh      # once: KSK+ZSK per zone, skips zones that already have keys
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh        # after any zone edit or when signatures expire
docker compose up -d                                                              # start root, tld, auth1, resolver, client
docker compose restart root tld auth1 auth2 auth3 resolver                        # reload after re-signing
sh scripts/clean.sh [-n dry-run] [-k keep keys] [-y no prompt]                    # back to the post-clone state
sh scripts/check.sh                                                               # test: every zone vs. expected rcode/AD (exit 1 on mismatch); STRICT_EDE=1 also checks EDE
sh scripts/e1-rollover.sh && sh scripts/e3-rollover.sh                            # live rollover errors (exit 1 if the error doesn't appear); re-run sign.sh afterwards
docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:v0.10.0 scripts/*.sh  # lint (settings in .shellcheckrc)
```

CI (`.github/workflows/lab.yml`, push to `main` and PRs only) runs shellcheck and then the whole lab from scratch: build → keygen → sign → `up -d` → `check.sh` → both rollover scripts.

Package versions in `image/Dockerfile` are pinned (`alpine:3.20.10`, `unbound~1.20.0`, …), because the expected results in `check.sh` depend on them. Unbound 1.20 decides EDE codes, clock-skew tolerance and the NSEC3 iteration limit. When bumping a version, re-run `check.sh` and update the expectations.

Signature validity can be overridden with `SIG_START` / `SIG_END` (dnssec-signzone time syntax, default `now-3600` / `now+2592000`). This is meant for later lab phases, such as testing expired signatures.

Verification is done by hand from the client container (its resolver is 10.53.0.53). For example: `docker compose exec client dig @10.53.0.53 +dnssec www.good.test`, or `delv` using `/lab/delv-anchor.conf`. Unbound logs validation failure reasons (`val-log-level: 2`) and returns EDE codes. See `docker compose logs resolver`.

## Architecture

Fixed IPs on 10.53.0.0/24: root `.2`, tld `.3`, auth1 `.11`, auth2 `.12`, auth3 `.13`, resolver `.53`, client `.100`. All services use the same image. Each server's directory (`root/`, `tld/`, `auth1/`, `auth2/`, `auth3/`, `resolver/`, `client/`) is bind-mounted read-only as its config dir. auth2 (NSD) and auth3 exist only for catalogue errors F1/F2. auth3 is Unbound serving an `auth-zone`, because NSD can't disable TCP.

**Source vs. generated files:**
- `zones/*.zone` are the hand-edited, unsigned sources. They must **not** contain DS records or `$INCLUDE`. `sign.sh` adds the DS records and aborts if it finds them already present.
- `keys/`, `build/`, `*/*.signed`, `resolver/root.key`, and `client/delv-anchor.conf` are all generated and gitignored. Trust anchors differ per team member because each person generates their own keys.

**Signing pipeline (`scripts/sign.sh`), bottom-up, because each parent needs its child's DS:**
1. Sign `good.test`, which emits `build/dsset-good.test.`
2. Concatenate `zones/test.zone` with that dsset into `build/test.zone.in`, then sign it, which emits `dsset-test.`
3. Do the same for root: `zones/root.zone` + `dsset-test.` → signed root
4. Derive the trust anchor from the root KSK (flags 257): `resolver/root.key` (Unbound DS format) and `client/delv-anchor.conf` (BIND `trust-anchors` format)
5. Copy each `*.zone.signed` into its server's directory (`root/`, `tld/`, `auth1/`). The NSD configs point at the `.signed` files.

Each signed zone is checked with `dnssec-verify`, and under `set -e` a failure stops the script before any broken zone is delivered. SOA serials use `-N unixtime`. Keys are ECDSAP256SHA256 (alg 13), except C1 (RSASHA1) and C3 (RSA 1024). See `alg_for` in `keygen.sh`. `sign.sh` finds the root KSK with the `K.+013+*.key` glob.

**Adding a zone or server** means touching several places: the source zone in `zones/`, a delegation plus glue in the parent zone, the `ZONES` list in `keygen.sh`, a new sign/with_ds step and copy in `sign.sh`, a server dir with `nsd.conf`, a compose service, and `clean.sh` if new generated paths appear. Don't delete the mounted server directories; only delete files inside them. If a directory is missing, Docker recreates it as root and `sign.sh` can no longer write to it.

**Broken zones (error catalogue).** One zone per error from the team's `DNSSECretary-katalog-gresaka` sheet (tabs "Prvo (MVP)" and "Kasnije", 30 errors), named `<id>-<short>.test` (e.g. `a1-expired.test`, `b2-ds-digest.test`). All are delegated from `test.` and served by `auth1`, except F1 (its stale copy is on `auth2`) and F2 (on `auth3` only). `sign.sh` calls `scripts/break.sh` between good.test and `test.`. It writes `build/broken/{,auth2/,auth3/}*.zone.signed`, plus the DS each zone should have in its parent in `build/parent-ds/*.ds`; those DS records are deliberately wrong for B1–B3, B7, B8, C2 and E2, and missing for B6. Broken zones skip `dnssec-verify`, and `dnssec-signzone -P -O full` is used so awk can edit single records. D4 is signed with `ldns-signzone`, because dnssec-signzone refuses more than 150 NSEC3 iterations. Keys that a zone must not sign with live in subdirs of `keys/` (`old/`, `next/`, `k2/`, `alg8/`), made by `extra_key` in `keygen.sh`. Several errors splice records from a second signing run in `build/tmp-break/`. A5 does this for its DNSKEY set, D5 for NSEC3PARAM, and D6 for NSEC. E1 and E3 are correct after signing and only break live, via `sh scripts/e1-rollover.sh` / `sh scripts/e3-rollover.sh` (host). Both move the old ZSK to `keys/retired/`. E3 fails only for a validator behind the cache (`delv`), not for Unbound itself. Run `sh scripts/check.sh` (host) to see rcode/AD/EDE per zone next to the result verified with Unbound 1.20. Unbound tolerates 1 day of clock skew (`val-sig-skew-max`), so time-based errors use offsets of 7 days or more. Adding an error means adding: a `zones/` source, a delegation in `test.zone`, the zone in `keygen.sh` `ZONES`, a `break_<id>` function in `break.sh`, and a `zone:` block in `auth1/nsd.conf`.

Query the resolver as `dig @10.53.0.53` from the client. The default path goes through Docker's embedded DNS (127.0.0.11), which strips EDE from the answer.

**Resolver specifics:** `root.hints` points to the lab root (10.53.0.2). `local-zone: "test." nodefault` is needed because Unbound otherwise blocks `.test` locally. `do-ip6: no` avoids timeouts.
