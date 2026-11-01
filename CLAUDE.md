# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A self-contained DNSSEC lab: a miniature DNS hierarchy (root → `test.` → `good.test.`, plus 30 deliberately broken catalogue zones and 40 customer zones of a demo hosting) served by NSD, with a validating Unbound resolver, all running in Docker on an isolated network (`internal: true`, no internet). The lab itself is shell scripts, zone files, and server configs; the DNSSECretary tool that is tested against it is a Java project in `dnssecretary/`.

All text in the repo is in English: comments, script output, `check.sh` descriptions, workflow step names, the TXT records in the broken zones, the error catalogue, the tool's report and messages, and the docs. Keep new text in English too.

## Commands

Run everything from the host, in the repo root. Signing tools exist only inside the image, so the scripts run in the `signer` service (profile `tools`, `network_mode: none`, repo mounted at `/lab`). Always pass `--user` so the generated files aren't owned by root:

```sh
docker compose build                                                              # build the shared "dnslab" image (alpine + nsd, unbound, bind tools, ldns)
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/keygen.sh      # once: KSK+ZSK per zone, skips zones that already have keys
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh        # after any zone edit or when signatures expire
docker compose up -d                                                              # start root, tld, auth1, auth2, auth3, resolver, client
docker compose restart root tld auth1 auth2 auth3 resolver                        # reload after re-signing
sh scripts/clean.sh [-n dry-run] [-k keep keys] [-y no prompt]                    # back to the post-clone state
sh scripts/check.sh                                                               # test: every zone vs. expected rcode/AD (exit 1 on mismatch); STRICT_EDE=1 also checks EDE
sh scripts/e1-rollover.sh && sh scripts/e3-rollover.sh                            # live rollover errors (exit 1 if the error doesn't appear); re-run sign.sh afterwards
mvn -f dnssecretary/pom.xml verify                                                # DNSSECretary: unit tests (offline, on snapshots) + target/dnssecretary.jar (Java 21)
sh scripts/dnssecretary.sh check a1-expired.test                                  # run DNSSECretary in the "app" container on the lab network
sh scripts/simulate.sh a1-expired.test fix.sh                                    # try a fix script in a sandbox copy of the lab (exit 0 = verified)
sh scripts/web.sh [logs|stop]                                                     # web app on http://localhost:8080 (jar built, lab running)
cd webapp && npm ci && npm run build                                              # front end into webapp/dist (served by the web app); npm run dev / mock / test
sh scripts/demo.sh break <zone> <error> | reset                                  # web app demo: break a hosting zone live (a1 … e4, e1 = rollover) / back to the start
sh scripts/video.sh                                                               # record the demo video (Playwright, web app running) into demo/out/
ROLLOVER=1 sh scripts/snapshots.sh                                                # re-record test snapshots (lab running, freshly signed); re-run sign.sh afterwards
docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:v0.10.0 scripts/*.sh scripts/fixenv/*.sh scripts/sandbox/*.sh  # lint (settings in .shellcheckrc)
```

CI (`.github/workflows/lab.yml`, push to `main` and PRs only) runs shellcheck, the DNSSECretary unit tests, the front end (`webapp`: typecheck, vitest, build, plus `node --check demo/tour.mjs`), and then the whole lab from scratch: build → keygen → sign → `up -d` → `check.sh` → DNSSECretary collect of every catalogue zone → both rollover scripts → collect of E1/E3 → one hosting zone broken live (`demo.sh break northwind-coffee.test a1`) and found by `check`.

Package versions in `image/Dockerfile` are pinned (`alpine:3.20.10`, `unbound~1.20.0`, …), because the expected results in `check.sh` depend on them. Unbound 1.20 decides EDE codes, clock-skew tolerance and the NSEC3 iteration limit. When bumping a version, re-run `check.sh` and update the expectations.

Signature validity can be overridden with `SIG_START` / `SIG_END` (dnssec-signzone time syntax, default `now-3600` / `now+2592000`). This is meant for later lab phases, such as testing expired signatures.

Verification is done by hand from the client container (its resolver is 10.53.0.53). For example: `docker compose exec client dig @10.53.0.53 +dnssec www.good.test`, or `delv` using `/lab/delv-anchor.conf`. Unbound logs validation failure reasons (`val-log-level: 2`) and returns EDE codes. See `docker compose logs resolver`.

## DNSSECretary (Java tool)

Lives in `dnssecretary/` (Maven, Java 21, dnsjava 3.6, picocli, Jackson); design in `docs/java-proposal.md`, team guide in `dnssecretary/README.md`. The error catalogue is `catalog/catalog.yaml` (translated from the team sheet), loaded as a classpath resource. The jar is built on the host and runs in the compose service `app` (profile `app`, image stage `app` = lab image + JRE, repo mounted at `/lab`, dynamic IP from `10.53.0.128/25`) with `dnssecretary/config/lab.yaml`. It never uses the system resolver: every query names its server. Pipeline `collect → check → diagnose → report` works, with a check for every catalogue error (E2 comes from the `KeyDirectoryRule` diagnosis rule). `fix` writes a shell script (`fix/ScriptGenerator`) for every catalogue error; it runs with `sh -eu` in the lab root after a `scripts/fixenv/` helpers file that defines `reload` and `flush` (`prod.sh` for a container with Docker, i.e. the web app; `sandbox.sh` in the sandbox). The CLI's `fix` only prints the plan and the script. Scripts never write to `zones/`, but C1/C3, E1/E3 and F2 fixes change `keys/` and `auth3/unbound.conf`: `scripts/demo.sh reset` puts those back (saved in `build/demo/`) and runs `sign.sh`. The sandbox (`scripts/simulate.sh` → `scripts/sandbox/run.sh`) is one container with `--network none` and NET_ADMIN: the lab's addresses go on `lo` and every server runs as its own process (configs generated by `fixenv/sandbox.sh`), on a copy of the lab in `build/sandbox/<job>/`; it runs the script and `verify --json`, and its last output line is `SANDBOX-RESULT {...}`. Java: `run/SandboxRunner` and `run/ProdRunner`. The web app (`dnssecretary serve`, package `web/`, Javalin) runs in the compose service `web` (profile `web`, image stage `web` = app + Docker CLI, the Docker socket mounted, on `dnslab` plus a normal network for port 8080): it scans every zone every 20 s, simulates each new fix script in the sandbox automatically (the "verified" column), runs scripts on the lab on request, and serves REST + SSE (`/api/...`) and `webapp/dist` if built. Everything is in memory. The front end is `webapp/` (React + TypeScript + Vite, see `webapp/README.md`); it talks to the server only through `src/api/client.ts`, and `npm run mock` runs it without a backend. Checks are pure functions over `ZoneSnapshot` and are registered in `CheckRunner.defaultChecks()`. `CatalogDetectionTest` runs each catalogue ID on its recorded snapshot (`src/test/resources/snapshots/`) as soon as a check declares that ID. User-facing text lives in `messages.properties` and the catalogue, not in the code. After changing the lab or `collect`, re-record the snapshots.

## Architecture

Fixed IPs on 10.53.0.0/24: root `.2`, tld `.3`, auth1 `.11`, auth2 `.12`, auth3 `.13`, resolver `.53`, client `.100`. All lab services use the same image (Dockerfile stage `lab`; stage `app` adds a JRE for DNSSECretary, stage `web` adds the Docker CLI to that). Each server's directory (`root/`, `tld/`, `auth1/`, `auth2/`, `auth3/`, `resolver/`, `client/`) is bind-mounted read-only as its config dir. auth2 (NSD) and auth3 exist only for catalogue errors F1/F2. auth3 is Unbound serving an `auth-zone`, because NSD can't disable TCP.

**Source vs. generated files:**
- `zones/*.zone` are the hand-edited, unsigned sources. They must **not** contain DS records or `$INCLUDE`. `sign.sh` adds the DS records and aborts if it finds them already present.
- `keys/`, `build/`, `*/*.signed`, `auth1/hosting.conf`, `resolver/root.key`, and `client/delv-anchor.conf` are all generated and gitignored (so are `webapp/dist/` and `demo/out/`). Trust anchors differ per team member because each person generates their own keys.

**Signing pipeline (`scripts/sign.sh`), bottom-up, because each parent needs its child's DS:**
1. Sign `good.test`, which emits `build/dsset-good.test.`; then the broken zones (`break.sh`) and the hosting zones (`hosting.sh`), which write their parent DS records to `build/parent-ds/*.ds`
2. Concatenate `zones/test.zone`, the hosting zones' delegations (from `zones/hosting.list`) and all those DS records into `build/test.zone.in`, then sign it, which emits `dsset-test.`
3. Do the same for root: `zones/root.zone` + `dsset-test.` → signed root
4. Derive the trust anchor from the root KSK (flags 257): `resolver/root.key` (Unbound DS format) and `client/delv-anchor.conf` (BIND `trust-anchors` format)
5. Copy each `*.zone.signed` into its server's directory (`root/`, `tld/`, `auth1/`, and `auth2/`, `auth3/` for F1/F2). The NSD configs point at the `.signed` files.

Step 2 is its own script, `scripts/sign-tld.sh` (signs `test.` from `zones/test.zone`, `zones/hosting.list`, `build/dsset-good.test.` + `build/parent-ds/*.ds` and copies it to `tld/`), so DNSSECretary can fix a parent DS without touching the root.

Each signed zone is checked with `dnssec-verify`, and under `set -e` a failure stops the script before any broken zone is delivered. SOA serials use `-N unixtime`. Keys are ECDSAP256SHA256 (alg 13), except C1 (RSASHA1) and C3 (RSA 1024). See `alg_for` in `keygen.sh`. `sign.sh` finds the root KSK with the `K.+013+*.key` glob.

**Adding a zone or server** means touching several places: the source zone in `zones/`, a delegation plus glue in the parent zone, the `ZONES` list in `keygen.sh`, a new sign/with_ds step and copy in `sign.sh`, a server dir with `nsd.conf`, a compose service, and `clean.sh` if new generated paths appear. A new hosting zone is simpler: its source in `zones/` and a line in `zones/hosting.list` (everything else is generated). Don't delete the mounted server directories; only delete files inside them. If a directory is missing, Docker recreates it as root and `sign.sh` can no longer write to it.

**Demo hosting.** `zones/hosting.list` names 40 customer zones of a made-up hosting (`<zone> [<start error>]`), with hand-made sources in `zones/`; the web app watches these (`serve --zone` for others). Everything else is generated from the list: their keys (`keygen.sh`), delegations with glue to auth1 (`sign-tld.sh`), signing and `auth1/hosting.conf` (`scripts/hosting.sh`, called by `sign.sh`; `auth1/nsd.conf` includes it). Three zones start "at risk" (A6, B7, D4). `break.sh zone <zone> <error>` reuses the `break_<id>` functions on a hosting zone (only errors that need nothing but the zone's own keys: `ANY_ZONE`, same list as `WebApp.HOSTING_ERRORS` minus E1) into `build/hosting/`; `break.sh rollover <zone>` does E1. `scripts/demo.sh break <zone> <error>` delivers it and re-signs `test.` if the DS changed. `scripts/video.sh` runs `demo/tour.mjs` (Playwright, compose service `tour`, profile `demo`) against the web app and writes `demo/out/dnssecretary-demo.webm`. `keygen.sh` drops `build/demo/` (the demo's saved `keys/`) when it makes new keys, so `demo.sh reset` never restores a copy without them.

**Broken zones (error catalogue).** One zone per error from the team's error catalogue sheet (MVP tab and later tab, 30 errors; machine-readable copy in `catalog/catalog.yaml`), named `<id>-<short>.test` (e.g. `a1-expired.test`, `b2-ds-digest.test`). All are delegated from `test.` and served by `auth1`, except F1 (its stale copy is on `auth2`) and F2 (on `auth3` only). `sign.sh` calls `scripts/break.sh` between good.test and `test.`. It writes `build/broken/{,auth2/,auth3/}*.zone.signed`, plus the DS each zone should have in its parent in `build/parent-ds/*.ds`; those DS records are deliberately wrong for B1–B3, B7, B8, C2 and E2, and missing for B6. Broken zones skip `dnssec-verify`, and `dnssec-signzone -P -O full` is used so awk can edit single records. D4 is signed with `ldns-signzone`, because dnssec-signzone refuses more than 150 NSEC3 iterations. Keys that a zone must not sign with live in subdirs of `keys/` (`old/`, `next/`, `k2/`, `alg8/`), made by `extra_key` in `keygen.sh`. Several errors splice records from a second signing run in `build/tmp-break/`. A5 does this for its DNSKEY set, D5 for NSEC3PARAM, and D6 for NSEC. E1 and E3 are correct after signing and only break live, via `sh scripts/e1-rollover.sh` / `sh scripts/e3-rollover.sh` (host). Both move the old ZSK to `keys/retired/`. E3 fails only for a validator behind the cache (`delv`), not for Unbound itself. Run `sh scripts/check.sh` (host) to see rcode/AD/EDE per zone next to the result verified with Unbound 1.20. Unbound tolerates 1 day of clock skew (`val-sig-skew-max`), so time-based errors use offsets of 7 days or more. Adding an error means adding: a `zones/` source, a delegation in `test.zone`, the zone in `keygen.sh` `ZONES`, a `break_<id>` function in `break.sh`, a `zone:` block in `auth1/nsd.conf`, an entry in `catalog/catalog.yaml` (+ `ErrorId`), and a recorded snapshot.

Query the resolver as `dig @10.53.0.53` from the client. The default path goes through Docker's embedded DNS (127.0.0.11), which strips EDE from the answer.

**Resolver specifics:** `root.hints` points to the lab root (10.53.0.2). `local-zone: "test." nodefault` is needed because Unbound otherwise blocks `.test` locally. `do-ip6: no` avoids timeouts.
