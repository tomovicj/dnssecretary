# DNSSECretary (Java)

The tool that checks a zone, explains every DNSSEC error in plain language and fixes it.
Design: [`docs/java-proposal.md`](../docs/java-proposal.md). Error catalogue: [`catalog/catalog.yaml`](../catalog/catalog.yaml).

Pipeline: `collect → check → diagnose → plan → script → run (sandbox or hosting) → verify`.
Done so far: `collect` (DNS, delegation walk, chain of trust, snapshots), the catalogue, the CLI,
the report, checks for all 30 catalogue errors (E2 via the `KeyDirectoryRule` diagnosis rule), and
`fix`/`verify` for re-signing and the parent DS.

## Fix and verify

A fix is a shell script: the tool writes it, the admin may read and edit it, and it runs on the lab
or in a sandbox copy of it.

```sh
sh scripts/dnssecretary.sh fix a1-expired.test            # the plan and the script, changes nothing
sh scripts/dnssecretary.sh fix a1-expired.test --script   # only the script
sh scripts/simulate.sh a1-expired.test fix.sh             # run a script in a sandbox copy of the lab, then verify
sh scripts/dnssecretary.sh verify a1-expired.test         # live: no critical error + the resolver answers with AD
```

`FixPlanner` maps diagnoses to actions (`fix/FixAction`), in the order of the script:

| Action | Errors | The script |
| --- | --- | --- |
| `RepublishKey` | E1, E3 | moves the retired key back from `keys/retired/`, so both keys sign until caches expire |
| `KeyRollover` | C1, C3 | moves the keys to `keys/old/`, makes new algorithm-13 keys (fast, for the lab) |
| `ResignZone` | A1–A7, B4, B5, C2, D1–D6, E4 (+ the two above) | `dnssec-signzone -S` from `zones/` like `sign.sh`, `dnssec-verify`, copy to `auth1/`; NSEC3 stays NSEC3 (RFC 9276); A7: a TTL-capped copy of the source inside the script |
| `UpdateParentDs` | B1–B3, B6–B8, C2, E2 | writes the DS of the active KSKs to `build/parent-ds/<zone>.ds`, runs `sign-tld.sh` |
| `PublishNewDs` | after `KeyRollover` | the new KSK's DS from the signing run, then `sign-tld.sh` |
| `CopyToSecondary` | F1 | copies the current zone to the stale server (`auth2/`) |
| `ServerConfig` | F2 | `do-tcp: yes`, `max-udp-size: 1232` in `auth3/unbound.conf` |
| `Manual` | anything else | the catalogue's fix text, as a comment |

With `parent.mode: instructions` the DS is only shown for the registrar, not written.

The script contract (`ScriptGenerator`, `ScriptRunner`): it runs with `sh -eu` in `fix.labDir` (`/lab`),
with `ZONE` set, after a helpers file from `scripts/fixenv/` that defines `reload <service>` and
`flush <zone>`. Paths under the lab are relative, so the same script works in a copy of it.
- `fixenv/prod.sh`: for a container with the Docker CLI and socket (the web app): `docker restart`
  and `unbound-control` on the lab's containers, found by their compose labels.
- `fixenv/sandbox.sh`: for the sandbox: restarts the server's process, `unbound-control` on the sandbox resolver.

**Sandbox** (the web app's "verified" column): `sh scripts/simulate.sh <zone> <script>` copies the lab to
`build/sandbox/<job>/` and runs `scripts/sandbox/run.sh` in a container with `--network none` and
NET_ADMIN. The lab's addresses go on `lo` and every server runs there as its own process, so zone
files, glue and the trust anchor work unchanged. The script runs, then `dnssecretary verify --json`
against the sandbox's own servers; the last output line is `SANDBOX-RESULT {"scriptExit": N, "verify": …}`.
Exit 0 = verified. About 5 s per run; the copy is deleted afterwards. From Java: `run/SandboxRunner`
(simulation) and `run/ProdRunner` (execution on the lab with `prod.sh`, then a live verify through
`verify/LiveVerifier`); both return a `RunResult` (PASS / FAIL / ERROR). Inside a container they need the
Docker CLI and socket, and `HOST_LAB_DIR` (Docker mounts host paths).

Limit: the sandbox resolver starts with an empty cache, so for E1/E3 it proves that the fixed zone
validates, not that caches holding the old key are served (in the lab they are: both keys sign).

## Web app (`dnssecretary serve`)

`sh scripts/web.sh` starts it in the compose service `web` on http://localhost:8080 (the jar must be
built and the lab running; `sh scripts/web.sh logs`, `stop`). It has the Docker socket, so it is a demo
only. It watches the demo hosting's 40 customer zones (`zones/hosting.list`), or the zones given
with `--zone`. Package `web/`:

- `Scanner`: every 20 s (and right after an execution or a demo action) collect → check → diagnose →
  plan → script for every zone, 4 at a time, into `ZoneStore` (memory only).
- When a zone gets a new fix script, `JobService` simulates it in the sandbox (`SandboxRunner`, 2 at a
  time): that is the "verified" column. Executions (`ProdRunner`) and demo actions (`scripts/demo.sh`)
  change the lab one at a time, under a write lock; scans and simulations hold the read lock.
- `WebApp`: the routes (Javalin), and `webapp/dist` with a SPA fallback when it is built.

| Method | Path | |
| --- | --- | --- |
| GET | `/api/zones` | `ZoneRow` for every zone, broken first |
| GET | `/api/zones/{zone}` | `ZoneDetail`: the row + the report (as `check --json`) + the script + the zone's jobs |
| POST | `/api/zones/{zone}/simulations` | `{"script": "…"}` (default: the generated one) → `Job`, 202 |
| POST | `/api/zones/{zone}/executions` | `{"script": "…", "simulateFirst": true}` → `Job`, 202 |
| GET | `/api/jobs`, `/api/jobs/{id}` | jobs, newest first; one job with its log |
| GET | `/api/catalog` | the 30 catalogue entries (title, cause, fix, symptom, EDE, RFCs, …) for the zone screen |
| POST | `/api/scan` | scan every zone now |
| GET | `/api/events` | SSE (`Accept: text/event-stream`): `zone`, `job`, `log` |
| GET | `/api/demo/scenarios` | the errors `break` can put on a hosting zone (`{id, title}`; `WebApp.HOSTING_ERRORS`) |
| POST | `/api/demo/break`, `/api/demo/reset` | break: `{"zone": "…", "error": "A1"}` for a hosting zone → `Job` (kind `DEMO`), 202 |

The JSON shapes are the records in `web/Api.java` (the contract in the pivot plan). Errors are
`{"error": "…"}` with 400 or 404.

**Demo:** `sh scripts/demo.sh break <zone> <error>` (a hosting zone broken live: `break.sh zone`, or a
live rollover for `e1`), `reset` (the lab as after `sign.sh`: the hosting at its start,
30 broken catalogue zones, `keys/` and `auth3/unbound.conf` restored from `build/demo/`). It needs only
the Docker CLI. `sh scripts/video.sh` records the demo video (see the main README).

`dnssec-verify` in the script stops it (`-e`) before a broken zone is copied to a server. `zones/` is
never changed, but `KeyRollover`, `RepublishKey` and `ServerConfig` change `keys/` and
`auth3/unbound.conf`; `sh scripts/demo.sh reset` puts them back (`sign.sh` alone doesn't).

## Build and run

Java 21 and Maven 3.9 on the host (IntelliJ opens `pom.xml` as is):

```sh
mvn -f dnssecretary/pom.xml verify        # unit tests + target/dnssecretary.jar
```

Without a local JDK 21, the same build in Docker:

```sh
docker run --rm -u $(id -u):$(id -g) -v "$PWD":/src -v "$HOME/.m2":/var/maven/.m2 \
    -e MAVEN_CONFIG=/var/maven/.m2 -w /src/dnssecretary maven:3.9-eclipse-temurin-21 \
    mvn -B -Duser.home=/var/maven verify
```

The tool runs **inside the lab network** (compose service `app`, image `dnslab-app` = lab image + JRE),
because the lab network has no route from the host:

```sh
sh scripts/dnssecretary.sh catalog                    # the catalogue (also: catalog b6)
sh scripts/dnssecretary.sh check a1-expired.test      # collect → check → diagnose → report
sh scripts/dnssecretary.sh check --json a1-expired.test
sh scripts/dnssecretary.sh snapshot a1-expired.test -o build/a1.json
```

Offline, from the IDE or the host, on a recorded snapshot (no lab, no config needed):

```sh
java -jar dnssecretary/target/dnssecretary.jar check --from dnssecretary/src/test/resources/snapshots/a1-expired.test.json
```

Exit codes: `0` ok, `1` only risks, `2` at least one critical error, `3` tool error.

If the lab containers were started before the `app` service existed, recreate the network once:
`docker compose down && docker compose up -d`.

## Packages

```
rs.port53.dnssecretary
├── cli/       picocli commands: check, fix, verify, catalog, snapshot, serve
├── config/    AppConfig (config/lab.yaml), Thresholds
├── catalog/   Catalog, CatalogEntry, ErrorId (A1..F2)
├── dns/       DnsClient, DelegationWalker, ChainValidator, TrustAnchor, Dnssec (DS match, verify), Records
├── collect/   Collector → ZoneSnapshot (NameserverView, ResolverView, Response, SignedRRset, ChainLink, LocalKey),
│              ZoneSource (FileZoneSource, AxfrZoneSource), KeyDirectory, SnapshotIO
├── check/     Check, CheckContext, CheckRunner   ← checks go here, in subpackages per group
├── diagnose/  Diagnoser, DiagnosisRule, Analysis  ← rules (F1 instead of A1, B1 vs E2, …) go here
├── fix/       FixPlanner → FixPlan (FixAction) → ScriptGenerator (sh script), ScriptRunner
├── run/       SandboxRunner (simulate.sh), ProdRunner (prod.sh + live verify), RunResult
├── web/       WebApp (Javalin routes), Scanner, ZoneStore, JobService, Events (SSE), Api (JSON shapes)
├── verify/    Verifier (checks + the resolver's AD bit), VerifyResult (JSON), LiveVerifier
├── model/     Finding, Diagnosis, Severity, FixSide
├── report/    Report, TextReport, JsonReport, Messages
└── json/      Jackson setup for dnsjava types
```

All text is in English: code, comments and everything the user reads. User-facing texts live in
`catalog/catalog.yaml` and `src/main/resources/rs/port53/dnssecretary/messages.properties`, not in the code.

## What a check gets: `ZoneSnapshot`

Everything about one zone at one moment. Checks never do I/O; time comes from `ctx.now()`.

| Field | Source | What is in it |
|-------|--------|---------------|
| `parentDs()` | P | The parent's answer to `<zone> DS`: `parentDs().rrset()` is the DS RRset with its RRSIG; when it is empty, `parentDs().authority(Type.NSEC)` / `NSEC3` is the proof that there is no DS (B6). |
| `parentDnskey()`, `chain()` | P | The parent's DNSKEY (signs the DS); the whole chain root → parent. `ChainValidator.validate(...)` checks it. |
| `nameservers()` | L | One `NameserverView` per NS address. `soa()`, `dnskey()`, `cds()`, `cdnskey()`, `nsec3param()` are `Response`s; `.rrset()` gives the RRset + RRSIGs. `samples()`: `www A`, `@ NS`, `@ MX`, `@ TXT` and the catalogue probe (e.g. `missing A` for D1, `www TXT` for D2). `denials()`: `[0]` a random name that doesn't exist (NXDOMAIN), `[1]` NODATA (`@ SRV`). `transport()`: UDP/TCP and EDNS size (F2); `Response.tc()` / `error()` show truncation and a failed TCP retry. |
| `dnskey()` | L | Shortcut: the DNSKEY RRset from the first name server that answered. |
| `fullZone()`, `zoneRRsets()` | Z | The whole signed zone file (in the lab: `auth1/`, `auth3/`), grouped into RRsets with their RRSIGs. |
| `resolver()` | R | `dnskey()`: the DNSKEY set in the resolver's cache (asked first, CD=1; TTL = what is left in the cache). `answers()`: the samples asked normally: rcode, `ad()`, `ede()`, and the RRSIGs from the cache (E1, E3). |
| `localKeys()` | K | Public keys of the zone from `keys/` and its subdirs (`""`, `old`, `next`, `retired`, `k2`, `alg8`). B1 vs E2. |
| `warnings()` | – | Problems while collecting, shown in the report. |

Useful helpers: `SignedRRset.records(DNSKEYRecord.class)`, `SignedRRset.toRRset()` for
`DNSSEC.verify(rrset, sig, key, ctx.now())`, `Dnssec.dsMatches(ds, key)`, `Dnssec.ds(key, digest)`,
`Dnssec.isSignedBy(set, keys, now)`, `Response.isNxdomain()` / `isNodata()`.

## Adding a check

1. A class in `check/<group>/` (e.g. `check/signatures/SignatureValidityCheck.java`) implementing `Check`:
   `detects()` returns the IDs it can report, `run(snapshot, ctx)` returns `Finding`s.
2. One line in `CheckRunner.defaultChecks()`, in your group's section.
3. Run `mvn verify`. `CatalogDetectionTest` skips every ID that no check declares yet; as soon as
   your check declares e.g. `A1`, the test for `a1-expired.test` runs and must find `A1`.
   `goodZoneIsClean` makes sure nothing is reported on `good.test`.

The expected result comes from the catalogue, not from `check.sh`: the tool is stricter than Unbound
(B7, C1, C2, C3, D6 and F1 resolve fine in the lab but must be reported).

## Snapshots (unit test data)

`src/test/resources/snapshots/<zone>.json` – one per catalogue zone plus `good.test`, recorded from the lab.
Records are in presentation format, so the files are readable and diffable. They contain only public data.
Tests use `Snapshots.load("a1-expired.test")` and `Snapshots.context(s)` (clock fixed at the recording time),
so they work offline, after the lab's signatures expire, and with anyone's keys.

Re-record after a change in the lab or in `collect` (lab running and freshly signed, jar built):

```sh
ROLLOVER=1 sh scripts/snapshots.sh      # all zones, E1/E3 after their rollover scripts
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh
docker compose restart root tld auth1 auth2 auth3 resolver
```
