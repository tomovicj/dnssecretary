# DNSSECretary (Java)

The tool that checks a zone, explains every DNSSEC error in plain language and fixes it.
Design: [`docs/java-proposal.md`](../docs/java-proposal.md). Error catalogue: [`catalog/catalog.yaml`](../catalog/catalog.yaml).

Pipeline: `collect → check → diagnose → plan → apply → verify`.
Done so far: `collect` (DNS, delegation walk, chain of trust, snapshots), the catalogue, the CLI,
the report, checks for all 30 catalogue errors (E2 via the `KeyDirectoryRule` diagnosis rule), and
`fix`/`verify` for re-signing and the parent DS.

## Fix and verify

A fix is a shell script: the tool writes it, the admin may read and edit it, and it runs on the lab
or in a sandbox copy of it.

```sh
sh scripts/dnssecretary.sh fix a1-expired.test            # dry run: the plan and the script, changes nothing
sh scripts/dnssecretary.sh fix a1-expired.test --script   # only the script
sh scripts/dnssecretary.sh fix --apply a1-expired.test    # run the script → reload → verify
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
- `fixenv/reload-list.sh`: for `fix --apply` in the `app` container (no Docker). It queues the work in
  `build/dnssecretary/reload`; `scripts/dnssecretary.sh` restarts, flushes and runs `verify`.
- `fixenv/prod.sh`: for a container with the Docker CLI and socket (the web app): `docker restart`
  and `unbound-control` on the lab's containers, found by their compose labels.
- `fixenv/sandbox.sh`: for the sandbox (web app, role 2).

`dnssec-verify` in the script stops it (`-e`) before a broken zone is copied to a server. `zones/` is
never changed, but `KeyRollover`, `RepublishKey` and `ServerConfig` change `keys/` and
`auth3/unbound.conf`. To get the broken lab back after a demo: restore `keys/` (`sign.sh` alone
doesn't), `git checkout auth3/unbound.conf`, run `sign.sh` and restart the servers.

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
├── cli/       picocli commands: check, fix, verify, catalog, snapshot
├── config/    AppConfig (config/lab.yaml), Thresholds
├── catalog/   Catalog, CatalogEntry, ErrorId (A1..F2)
├── dns/       DnsClient, DelegationWalker, ChainValidator, TrustAnchor, Dnssec (DS match, verify), Records
├── collect/   Collector → ZoneSnapshot (NameserverView, ResolverView, Response, SignedRRset, ChainLink, LocalKey),
│              ZoneSource (FileZoneSource, AxfrZoneSource), KeyDirectory, SnapshotIO
├── check/     Check, CheckContext, CheckRunner   ← checks go here, in subpackages per group
├── diagnose/  Diagnoser, DiagnosisRule, Analysis  ← rules (F1 instead of A1, B1 vs E2, …) go here
├── fix/       FixPlanner → FixPlan (FixAction) → ScriptGenerator (sh script), ScriptRunner
├── verify/    Verifier (live: checks + the resolver's AD bit)
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
