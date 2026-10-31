# DNSSECretary in Java: architecture and how it connects to the lab

This document proposes how to build DNSSECretary in Java and how to connect it to the lab in this repo. It starts from the project proposal (Detect → Diagnose → Fix → Verify) and from what the lab already has: 30 broken zones, `check.sh`, CI.

## In short

- **The code lives in this repo**, in the `dnssecretary/` folder (a Maven project). The lab and the tool change together, and the error IDs (A1–F2) are the same in both.
- **Java 21 + dnsjava 3.6.x.** dnsjava can send DNS queries (UDP/TCP, EDNS, DO bit, AXFR), verify an RRSIG and compute a DS. For signing we use the BIND tools that are already in the lab image.
- **The tool runs in a container on the lab network.** The network is `internal: true` and stays that way. The jar is built on the host (IDE, Maven, internet) and runs in a new compose service, `app`. That service uses the lab image plus a Java runtime.
- **The tool doesn't depend on the lab.** Root server addresses, the trust anchor, the resolver and the paths to zones and keys come from the configuration. For the lab that is `config/lab.yaml`. The same jar later works on the real DNS with another configuration.
- **Pipeline:** `collect → check → diagnose → plan → apply → verify`. Each step is its own package with a clear input and output.
- **Detection is based on the zone data**, not on EDE codes. The resolver is only a second opinion, and the source for errors that only show in a cache (E1, E3). The README already says so: EDE is only a hint.
- **Tests:** unit tests on recorded "snapshot" files (offline and deterministic) and an e2e script, `check-app.sh`, modelled on `check.sh`. Both run in CI.

---

## 1. Technologies

| What | Choice | Why |
|------|--------|-----|
| Language | Java 21 (LTS) | We have the most experience with it. Alpine 3.20 (the lab's base) has `openjdk21`. |
| Build | Maven, one module, fat jar (`maven-shade-plugin`) → `target/dnssecretary.jar` | Everyone knows it, and IntelliJ opens it without any setup. |
| DNS | [dnsjava](https://github.com/dnsjava/dnsjava) 3.6.x | `SimpleResolver` (UDP/TCP/EDNS/DO), `ZoneTransferIn` (AXFR), `DNSSEC.verify(...)`, `DSRecord` (computes the digest), NSEC3 hashing, zone file parsing |
| CLI | picocli 4.7.x | Subcommands, `--help`, exit codes |
| JSON/YAML | Jackson (`jackson-databind`, `jackson-dataformat-yaml`) | Configuration, error catalogue, JSON report, snapshot files |
| Signing | BIND tools through `ProcessBuilder` (`dnssec-signzone`, `dnssec-verify`, `dnssec-keygen`) | The keys are in BIND format and the lab already uses them. For 24 hours this is far less risky than signing in pure Java. It sits behind an interface, so it can be replaced later. |
| Tests | JUnit 5 + AssertJ | |
| Later: web | Javalin or Spring Boot | Reads the same JSON report. See "Next steps". |

We pin versions like `image/Dockerfile` does, for the same reason: the results depend on the versions.

---

## 2. Connecting to the lab

### 2.1 Where the code lives

```
dnssec-lab/
├── dnssecretary/              # NEW: Java project
│   ├── pom.xml
│   ├── config/lab.yaml        # lab config (addresses, paths inside the container)
│   └── src/...
├── catalog/catalog.yaml       # NEW: error catalogue (from the sheet), shared by Java and the scripts
├── image/Dockerfile           # + "app" stage with the Java runtime
├── compose.yaml               # + "app" service
└── scripts/
    ├── dnssecretary.sh        # NEW: runs the tool in the lab (host)
    ├── check-app.sh           # NEW: e2e – does the tool detect every error (host)
    └── sign-tld.sh            # NEW: re-signs only test. (pulled out of sign.sh)
```

Why not a separate repo: the tool and the lab change together. A new error in the lab needs a new check in the tool, and CI should run both in the same pass. A separate repo with the lab as a git submodule is possible, but it adds ceremony with no benefit.

### 2.2 How Java reaches the lab network

The lab network has no internet, on purpose. On macOS and Windows the host can't even reach the containers' IP addresses. That's why the tool is **run in a container** and built on the host:

```
host (IntelliJ, Maven, internet)          lab network 10.53.0.0/24 (internal: true)
┌───────────────────────────┐             ┌──────────────────────────────────────┐
│ mvn package               │  bind mount │ app (dynamic IP from 10.53.0.128/25) │
│  → dnssecretary/target/   │ ──────────▶ │ java -jar /lab/dnssecretary/...jar   │
│     dnssecretary.jar      │   ./:/lab   │   ├─▶ root 10.53.0.2  (iterative)    │
└───────────────────────────┘             │   ├─▶ tld, auth1/2/3 (directly)      │
                                          │   └─▶ resolver 10.53.0.53 (2nd op.)  │
                                          └──────────────────────────────────────┘
```

**`image/Dockerfile`** gets a second stage. The lab services stay on the `lab` stage, and the tool gets the same BIND tools plus Java:

```dockerfile
FROM alpine:3.20.10 AS lab
RUN apk add --no-cache \
        nsd~4.9.1 \
        unbound~1.20.0 \
        bind-tools~9.18.49 \
        bind-dnssec-tools~9.18.49 \
        ldns-tools~1.8.3

# DNSSECretary: the same signing tools as the lab + a Java runtime
FROM lab AS app
RUN apk add --no-cache openjdk21-jre-headless~21.0
```

**`compose.yaml`**: `root` gets `build: { context: ./image, target: lab }`, because the last stage is now `app`. A new service `app` is added, plus an `ip_range`, so dynamic addresses can't collide with the fixed ones:

```yaml
networks:
  dnslab:
    internal: true
    ipam:
      config:
        - subnet: 10.53.0.0/24
          ip_range: 10.53.0.128/25    # dynamic addresses (app) never collide with the fixed ones

services:
  # ...
  app:                      # DNSSECretary (Java), run on demand
    build: { context: ./image, target: app }
    image: dnslab-app
    profiles: ["app"]
    volumes: ["./:/lab"]    # needs to write auth1/, tld/, build/ when fixing
    working_dir: /lab
    entrypoint: ["java", "-jar", "/lab/dnssecretary/target/dnssecretary.jar",
                 "--config", "/lab/dnssecretary/config/lab.yaml"]
    networks: [dnslab]
```

The tool **has no access to the Docker socket** and doesn't restart servers itself. A script on the host does that (2.5). This keeps the lab isolated, and the tool doesn't know Docker exists.

### 2.3 What the tool sees in the lab

| Data | Where in the lab | Where in the real world |
|------|------------------|-------------------------|
| Root servers | `10.53.0.2` (as in `resolver/root.hints`) | IANA root hints |
| Trust anchor | `/lab/resolver/root.key` (made by `sign.sh`) | IANA `root-anchors.xml` |
| Resolver for a second opinion | `10.53.0.53` | a local or public resolver |
| The whole signed zone (optional) | the file `/lab/auth1/<zone>.zone.signed`, later AXFR | AXFR, or a file the operator provides |
| Unsigned zone source (for fixing) | `/lab/zones/<zone>.zone` | the operator's source |
| Keys (for fixing) | `/lab/keys/` (+ `old/`, `next/`, `retired/`, …) | the operator's key dir |
| Where the fixed zone goes | `/lab/auth1/` (F1: `auth2/`, F2: `auth3/`) | the operator's server |
| The DS at the parent | `/lab/build/parent-ds/<zone>.ds` + `sign-tld.sh` | only instructions for the registrar |

### 2.4 Configuration

```yaml
# dnssecretary/config/lab.yaml – paths are as seen INSIDE the app container
dns:
  rootServers: [10.53.0.2]
  trustAnchor: /lab/resolver/root.key
  resolver: 10.53.0.53        # second opinion + cache view (E1, E3); never Docker DNS 127.0.0.11
  timeoutMs: 3000
  ednsBufferSize: 1232

thresholds:
  signatureExpiryWarnDays: 7  # A6
  minRsaBits: 2048            # C3

collect:
  zoneSource: file            # file | axfr | none
  zoneFileDirs: [/lab/auth1, /lab/auth3]   # file name: <zone>.zone.signed, first match wins
  keyDir: /lab/keys           # optional: public keys go into the snapshot (source K, B1 vs E2)
  samples: ["www A", "@ NS", "@ MX", "@ TXT"]
  nodataType: SRV

fix:
  sourceDir: /lab/zones       # read only, the tool NEVER writes here
  keyDir: /lab/keys
  workDir: /lab/build/dnssecretary
  deliver:                    # where the signed zone goes, per zone (default: auth1)
    default: /lab/auth1
    f2-tcp-edns.test: /lab/auth3
  parent:
    mode: lab                 # lab = write the DS and run sign-tld.sh; instructions = text for the registrar
    dsDir: /lab/build/parent-ds
    resignCommand: ["sh", "/lab/scripts/sign-tld.sh"]
```

The configuration for the real world (`config/public.yaml`) has the same shape, with `parent.mode: instructions` and `collect.zoneSource: axfr` or `none`. The exact, current version is in `dnssecretary/config/lab.yaml`.

### 2.5 Changes to the lab

1. **`image/Dockerfile`, `compose.yaml`**: the `app` stage and the `app` service (2.2).
2. **`scripts/sign-tld.sh`**: step 2 of `sign.sh` as its own script. It re-signs only `test.` from `build/dsset-good.test.` and `build/parent-ds/*.ds`, then copies the result to `tld/`. `sign.sh` calls it instead of step 2. The tool calls it when it fixes the DS at the parent in the lab (B1–B3, B6–B8, E2, C2). The root isn't touched, because the KSK of `test.` doesn't change.
3. **`scripts/dnssecretary.sh`** (host): a wrapper around `docker compose run`. After `fix --apply` it restarts the servers the tool listed, flushes the resolver cache and runs `verify`:

   ```sh
   #!/bin/sh
   # dnssecretary.sh – runs DNSSECretary (Java) inside the lab network.
   #   sh scripts/dnssecretary.sh check a1-expired.test
   #   sh scripts/dnssecretary.sh fix --apply a1-expired.test
   set -eu
   cd "$(dirname "$0")/.."

   JAR=dnssecretary/target/dnssecretary.jar
   [ -f "$JAR" ] || { echo "Missing $JAR – run: mvn -f dnssecretary/pom.xml package"; exit 1; }

   run() { docker compose run --rm -T --user "$(id -u):$(id -g)" app "$@"; }

   RELOAD=build/dnssecretary/reload     # written by "fix --apply": "restart <service>" / "flush <zone>"
   rm -f "$RELOAD"
   rc=0; run "$@" || rc=$?

   if [ -f "$RELOAD" ]; then
       while read -r what arg; do
           case $what in
               restart) docker compose restart "$arg" >/dev/null ;;
               flush)   docker compose exec -T resolver unbound-control flush_zone "$arg" >/dev/null
                        docker compose exec -T resolver unbound-control flush_bogus >/dev/null ;;
           esac
       done < "$RELOAD"
       sleep 2
       zone=$(awk '$1 == "flush" { print $2; exit }' "$RELOAD")
       run verify "$zone" || rc=$?
   fi
   exit "$rc"
   ```
4. **`scripts/check-app.sh`** (host): an e2e test of the tool (section 4).
5. **`catalog/catalog.yaml`**: the catalogue from the team sheet in machine-readable form. Java loads it as a resource (a Maven `<resource>` points to `../catalog`):

   ```yaml
   - id: A1
     title: Expired signature
     group: Signatures (RRSIG)
     severity: CRITICAL          # CRITICAL | RISK
     side: ZONE                  # ZONE | PARENT | OPERATOR
     symptom: SERVFAIL
     ede: "7 (Signature Expired)"
     cause: Re-signing stopped (cron job, server down, forgotten).
     fix: Re-sign the zone with new dates.
     rfc: ["RFC 4035 §5.3.1"]
     lab: { zone: a1-expired.test, probe: "www A" }
   ```
6. **`.gitignore`**: `dnssecretary/target/`. `clean.sh` doesn't need to change, because `build/dnssecretary/` is already under `build/`.
7. **CI**: section 4.3.
8. *(Later)* **AXFR**: add a `pattern` with `provide-xfr: 10.53.0.0/24 NOKEY` to `auth1/nsd.conf` and `auth2/nsd.conf`, and an `include-pattern` in every `zone:` block. Then the tool gets the whole zone over DNS, as in reality, and doesn't need to read the file.

### 2.6 The "break → find → fix → verify" cycle in the lab

```
sign.sh + restart                       the lab in its starting state: 30 broken zones
        │
dnssecretary.sh check a1-expired.test   collect → check → diagnose → report (read only)
        │
dnssecretary.sh fix --apply a1-...      plan → sign in build/dnssecretary/ → dnssec-verify
        │                                → offline verify of the new file → copy to auth1/
        │                                → write build/dnssecretary/reload
        ├── restart auth1 (+ tld for DS fixes), flush_zone on the resolver
        │
dnssecretary.sh verify a1-expired.test  live: direct validation + the resolver returns NOERROR AD
        │
sign.sh + restart                       breaks ALL zones again (reset for the next demo)
```

Rules for every fix:
- **`zones/` is never changed.** A corrected source (e.g. a shorter TTL for A7) goes to `build/dnssecretary/<zone>/`. That's why `sign.sh` can always bring the lab back to its starting state.
- **Dry run is the default.** Without `--apply` the tool only prints the plan.
- **`dnssec-verify` before delivery**, as in `sign.sh`. If it fails, nothing goes to the server.
- **Backup.** Before it is replaced, the old `.signed` file is kept in `build/dnssecretary/backup/`.

---

## 3. Architecture of the Java program

### 3.1 Data flow

```
             ┌──────────┐  ZoneSnapshot  ┌───────┐  List<Finding>  ┌──────────┐  List<Diagnosis>
zone ──────▶ │ collect  │ ─────────────▶ │ check │ ──────────────▶ │ diagnose │ ───────┬─────────▶ report (text / JSON)
             └──────────┘                └───────┘                 └──────────┘        │
                  ▲                                                                    ▼
                  │                                                              ┌──────────┐ FixPlan ┌───────┐
                  └────────────────────── verify (collect+check again) ◀──────── │   plan   │ ──────▶ │ apply │
                                                                                 └──────────┘         └───────┘
```

Every step is a pure function over data, except `collect` (network) and `apply` (files and processes). That's why `check` and `diagnose`, where most of the logic is, can be tested without a network.

### 3.2 Packages

```
rs.port53.dnssecretary
├── cli/          picocli commands: check, fix, verify, catalog, snapshot
├── config/       AppConfig (loads lab.yaml), Thresholds
├── catalog/      Catalog, CatalogEntry (loads catalog.yaml), ErrorId (A1..F2)
├── dns/          DnsClient (UDP/TCP/EDNS/DO on top of dnsjava SimpleResolver),
│                 DelegationWalker (iterative from the root), TrustAnchor
├── collect/      Collector, ZoneSnapshot, NameserverView, ResolverView,
│                 ZoneSource (FileZoneSource, AxfrZoneSource), SnapshotIO (JSON)
├── check/        Check (interface), CheckContext, CheckRunner
│   ├── signatures/   SignatureValidityCheck, SignatureCryptoCheck, SignatureCoverageCheck, TtlCheck
│   ├── delegation/   DelegationCheck, DsPolicyCheck, KeySetCheck, CdsCheck
│   ├── algorithms/   AlgorithmPolicyCheck, KeyStrengthCheck
│   ├── denial/       DenialChainCheck, Nsec3PolicyCheck
│   ├── rollover/     CacheConsistencyCheck
│   └── servers/      NameserverConsistencyCheck, TransportCheck
├── model/        Finding, Severity, Diagnosis, FixSide
├── diagnose/     Diagnoser + rules (merging, telling B1 from E2, F1 …)
├── fix/          FixPlanner, FixPlan, FixAction (sealed), FixExecutor
│   ├── sign/     ZoneSigner (interface), BindZoneSigner (dnssec-signzone)
│   ├── parent/   ParentPublisher: LabParentPublisher | RegistrarInstructions
│   └── deliver/  ZoneDeliverer: FileDeliverer (+ writes the reload list)
├── verify/       Verifier (offline: the new file; live: after the reload)
└── report/       TextReport, JsonReport
```

The `check`, `diagnose` and `fix` packages don't depend on `cli`. If we add a web UI later, `core` can move into its own Maven module without rewriting code.

All text is in English: code, names, comments, and everything the user reads (report, messages, catalogue). User-facing texts are in `catalog.yaml` and `messages.properties`, not in the code.

### 3.3 Key types

This is how it is implemented (`collect/`, see also `dnssecretary/README.md`). Every query keeps the whole answer (`Response`: rcode, AA/TC/AD, sections grouped into `SignedRRset`s with their RRSIGs, EDE, error), so NODATA/NXDOMAIN proofs and transport errors are visible in the same place:

```java
/** Everything collected about one zone at one moment. Pure data – checks never do I/O. */
public record ZoneSnapshot(
        Name zone,
        Instant collectedAt,
        Name parent,
        List<Record> trustAnchor,             // the configured anchor
        List<ChainLink> chain,                // root → parent: DNSKEY + DS of every zone (ChainValidator)
        Response parentDs,                    // the parent's answer to "<zone> DS": DS + RRSIG,
                                              //   or NODATA with the NSEC/NSEC3 proof (B6)
        List<Name> delegation,                // NS names in the parent's delegation
        List<NameserverView> nameservers,     // one view per NS address (F1, F2)
        Optional<List<Record>> fullZone,      // file / AXFR, if available
        List<LocalKey> localKeys,             // public keys from the key dir (K): "", old, next, retired …
        Optional<ResolverView> resolver,      // rcode, AD, EDE, cached DNSKEY and RRSIGs (E1, E3)
        List<String> warnings) {}             // problems while collecting

/** What one authoritative server returned. ns.dnskey().rrset() = DNSKEY RRset + RRSIGs. */
public record NameserverView(
        Name host, InetAddress address,
        Response soa, Response dnskey, Response cds, Response cdnskey, Response nsec3param,
        List<Response> samples,               // www A, apex NS, MX, TXT + the catalogue probe
        List<Response> denials,               // [0] a name that doesn't exist, [1] NODATA
        TransportResult transport) {}         // UDP/TCP reachable, EDNS size; TC is per Response

public record Finding(
        ErrorId id,
        Severity severity,
        Name owner, int type,                 // where: www.a1-expired.test. / A
        String evidence,                      // raw fact, e.g. the RRSIG in presentation format
        Map<String, String> facts) {}         // e.g. expiration=2026-10-22T14:03Z, nameserver=10.53.0.12

public interface Check {
    Set<ErrorId> detects();
    List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx);   // ctx: Clock, Thresholds (keys are in the snapshot)
}

public record Diagnosis(ErrorId id, Severity severity, FixSide side, String rootCause, List<Finding> findings) {}

public sealed interface FixAction permits
        ResignZone,           // re-sign from source with the published keys (A*, B4, B5, C2, D*, E4)
        UpdateParentDs,       // the right DS set at the parent: add (B6), replace (B1–B3, B7, E2), remove (B8, C2)
        RepublishKey,         // E1, E3: bring the old ZSK back until the TTL runs out (not yet)
        KeyRollover,          // C1, C3: fast in the lab, step-by-step plan for the real world (not yet)
        Manual                // F1, F2 and everything without an automatic fix: the catalogue's text
{}

public record FixPlan(Name zone, List<FixAction> actions) {}
```

A `Clock` is injected wherever time matters (`CheckContext`, `Verifier`). Tests use a fixed time, and the CLI has `--now` for demos and debugging.

### 3.4 Collect

1. **`DelegationWalker`** starts at `rootServers` and follows referrals down to the zone's parent (`test.`). On the way it checks the chain from the trust anchor to the parent (root DNSKEY against the anchor, the DS for `test.`, …). In the lab that part is always correct, but a real tool needs it.
2. **From the parent** it takes the DS + RRSIG. If there is no DS, it keeps the NSEC/NSEC3 proof that there isn't one (B6).
3. **From every NS of the zone** separately (not only the first one that answers):
   - `SOA`, `DNSKEY`, `CDS`/`CDNSKEY` and `NSEC3PARAM`, with the DO bit and EDNS 1232
   - samples (`www A`, apex `NS`, …)
   - one name that surely doesn't exist (`dnssecretary-<random>.<zone>`) and one NODATA query
   - the same query over TCP, and a large answer (TC bit) for F2
4. **The whole zone**, if available (`FileZoneSource` in the MVP, `AxfrZoneSource` later). Without it, the D1/D2/A3 checks only work on the samples.
5. **`ResolverView`**: the same queries through `10.53.0.53` (rcode, AD, EDE), and the DNSKEY set **from the resolver's cache** with its remaining TTL.

The tool **never uses the system resolver**. Every query names its server explicitly. In the container the system resolver would go through Docker DNS, which strips EDE.

`dnssecretary snapshot <zone> -o x.json` saves the `ZoneSnapshot` as JSON, with records in presentation format (`Record.toString()` / parsed back as zone file lines). The unit tests are built on that (4.1).

### 3.5 Check: how each error is detected

Data sources: **P** = parent, **L** = authoritative servers (live), **Z** = the whole zone, **R** = the resolver's cache, **K** = key directory (only if given).
Kinds of fix: **ZONE** = automatic (re-signing), **PARENT** = the DS at the parent (automatic in the lab through `sign-tld.sh`, otherwise instructions for the registrar), **GUIDED** = a multi-step plan with TTL waits, **OPERATOR** = server configuration, instructions only.

| ID | Error | Check (class: rule) | Source | Fix |
|----|-------|---------------------|--------|-----|
| A1 | Expired signature | `SignatureValidityCheck`: `expiration < now` | L, Z | ZONE |
| A2 | Signature not yet valid | `SignatureValidityCheck`: `inception > now` (+ advice: check the signer's clock) | L, Z | ZONE, `inception = now − 1h` |
| A3 | RRset without RRSIG | `SignatureCoverageCheck`: every authoritative RRset has an RRSIG | Z (L for samples) | ZONE |
| A4 | Invalid signature | `SignatureCryptoCheck`: `DNSSEC.verify` throws `SignatureVerificationException` | L, Z | ZONE |
| A5 | Signed by a key outside the DNSKEY set | `SignatureCryptoCheck`: the RRSIG key tag is not in the DNSKEY set | L, Z | ZONE (only with published keys) |
| A6 | Signature expires soon | `SignatureValidityCheck`: `expiration − now < signatureExpiryWarnDays` | L, Z | ZONE (risk) + advice on automation |
| A7 | TTL longer than signature validity | `TtlCheck`: `originalTTL > expiration − inception` | L, Z | ZONE: lower the TTL in a copy of the source, then sign |
| B1 | DS for a key that doesn't exist | `DelegationCheck`: no DS matches any DNSKEY (tag, alg, digest) | P, L | PARENT: DS from the current KSK |
| B2 | Wrong DS digest | `DelegationCheck`: tag and alg match, digest doesn't (`new DSRecord(...)` and compare) | P, L | PARENT |
| B3 | DS and DNSKEY algorithms differ | `DelegationCheck`: the tag matches, the alg doesn't | P, L | PARENT |
| B4 | DS exists, zone unsigned | `DelegationCheck`: a DS exists, no DNSKEY | P, L | ZONE (sign, if we have keys) or PARENT (remove the DS) |
| B5 | DNSKEY set not signed by the KSK | `KeySetCheck`: the RRSIG over DNSKEY is not by the key the DS points to | P, L | ZONE |
| B6 | Island (no DS) | `DelegationCheck`: the parent proves there is no DS, and the zone has a DNSKEY | P, L | PARENT: add the DS |
| B7 | DS with SHA-1 | `DsPolicyCheck`: digest type 1 (RFC 8624) | P | PARENT: replace with a SHA-256 DS |
| B8 | Leftover old DS | `DelegationCheck`: at least one DS matches, some don't | P, L | PARENT: remove the leftover |
| C1 | Deprecated algorithm (RSASHA1) | `AlgorithmPolicyCheck`: alg 5/7 (RFC 8624) | L | GUIDED: algorithm rollover |
| C2 | Two algorithms, signed with one | `AlgorithmPolicyCheck`: every RRset must have an RRSIG for every algorithm in the DNSKEY set (RFC 4035 §2.2) | L, Z | ZONE (remove the alg 8 key) + PARENT (remove the alg 8 DS) |
| C3 | Weak RSA key | `KeyStrengthCheck`: `((RSAPublicKey) dnskey.getPublicKey()).getModulus().bitLength() < minRsaBits` | L | GUIDED: key rollover |
| D1 | Broken NSEC chain | `DenialChainCheck`: the chain is closed and in canonical order, plus a probe for a name that doesn't exist | Z, L | ZONE |
| D2 | NSEC bitmap lies | `DenialChainCheck`: the types in the bitmap = the RRsets that really exist at that name | Z (L: NODATA) | ZONE |
| D3 | NSEC without signatures | `SignatureCoverageCheck` (an NSEC is an RRset like any other) + a probe | Z, L | ZONE |
| D4 | NSEC3 against RFC 9276 | `Nsec3PolicyCheck`: iterations ≠ 0 or the salt isn't empty (> 150 → validators treat it as unsigned) | L | ZONE: `-3 - -H 0` |
| D5 | NSEC3PARAM ≠ chain | `Nsec3PolicyCheck`: the NSEC3PARAM parameters ≠ the parameters of the NSEC3 records | Z, L | ZONE |
| D6 | NSEC and NSEC3 mixed | `DenialChainCheck`: the zone has both types | Z | ZONE |
| E1 | ZSK replaced without pre-publish | `CacheConsistencyCheck`: direct validation passes, the resolver returns SERVFAIL, and the RRSIG key tag is not in the DNSKEY set **from the resolver's cache** | R, L | GUIDED: put the old ZSK back in the DNSKEY set (`keys/retired/`), wait for the TTL. The tool says how long. |
| E2 | DS of the new KSK too early | like B1, plus `K`: the DS matches a key from the key dir that isn't published (`keys/next/`) | P, L, K | PARENT: restore the old DS (or ZONE: publish the new KSK) |
| E3 | Old ZSK removed too early | `CacheConsistencyCheck`: the resolver returns an RRSIG with a key tag that is no longer in the zone | R, L | GUIDED: publish the old ZSK again until the TTL runs out |
| E4 | CDS doesn't match the keys | `CdsCheck`: every CDS/CDNSKEY matches some DNSKEY (RFC 7344) | L | ZONE: remove or fix the CDS |
| F1 | Stale secondary | `NameserverConsistencyCheck`: the SOA serial and signature validity differ per NS | L (every NS) | OPERATOR (in the lab: a copy on auth2) |
| F2 | No TCP / small EDNS | `TransportCheck`: TCP doesn't work, EDNS < 1232, TC without TCP | L (every NS) | OPERATOR |

Notes:
- **The tool is stricter than Unbound, and that's the point.** For B7, C1, C2, C3, D6 and F1 the lab resolver returns NOERROR AD, but the tool reports them as risks. The tool's expected result comes from the catalogue, not from `check.sh`.
- **dnsjava's `DNSSEC.verify` already tells causes apart** through the exception type (`SignatureExpiredException`, `SignatureNotYetValidException`, `KeyMismatchException`, `SignatureVerificationException`, `UnsupportedAlgorithmException`). That's why A1, A2, A4 and A5 come out of the same check.
- dnsjava also has a `ValidatingResolver`. It can serve as an independent "second opinion" (secure/bogus/insecure) next to our checks, but it doesn't replace them, because it doesn't say which catalogue error it is.

### 3.6 Diagnose

Checks return raw findings. `Diagnoser` turns them into diagnoses with a cause. The rules are small, named, and tested separately:

- **Merging:** 15 A1 findings (every RRset has an expired signature) become one diagnosis: "signing has stopped".
- **F1 instead of A1:** if A1 shows on only one NS and the others are correct, the cause is not the zone but a secondary that doesn't get the transfer.
- **B1 or E2:** without access to the keys, these two errors look the same (the DS points to a key that isn't there). If a key dir is given, a DS that matches a key from `next/` means E2, and one that matches a key from `old/` or `retired/` means B1. Without a key dir the tool reports "B1 or E2" and explains both possibilities.
- **Priority:** when findings overlap, delegation (B) is reported first, then signatures (A), then denial (D). With B4 it makes no sense to also report A3 for every RRset.
- **Severity:** CRITICAL when validation fails (SERVFAIL), RISK when it works but isn't safe or is bad practice ("risk" in the README).

### 3.7 Fix

`FixPlanner` turns every diagnosis into actions (`FixAction`), and `FixExecutor` runs them. Every action has a readable description, so the dry run is the same code without the last step.

- **`ResignZone`**: `BindZoneSigner` runs `dnssec-signzone -S -K <keyDir> -s now-3600 -e now+30d -N unixtime` on `fix.sourceDir` and `fix.keyDir`, with the options the diagnosis needs (`-3 - -H 0` for D4/D5, the TTL from the corrected copy for A7). Then `dnssec-verify`, and `FileDeliverer` copies the result to the `deliver` directory and writes `restart auth1` / `flush <zone>` to the reload list.
  - This also fixes A5, B5 and C2 on the zone side: `-S` only picks up keys from `keys/`, and the helper keys (`k2/`, `alg8/`) are deliberately in subdirectories.
- **DS actions**: the DS is computed in Java (`new DSRecord(zone, DClass.IN, ttl, DNSSEC.Digest.SHA256, ksk)`).
  - `parent.mode: lab`: `LabParentPublisher` writes `build/parent-ds/<zone>.ds`, calls `sign-tld.sh` and adds `restart tld` to the reload list.
  - `parent.mode: instructions`: `RegistrarInstructions` prints the exact DS and the steps for the registrar, plus a note on RFC 8078 (CDS/CDNSKEY) automation.
- **`RepublishKey`** (E1, E3) and **`KeyRollover`** (C1, C3): a "fast" variant in the lab (new keys and a new DS right away, with a warning that this must not be done in reality). For the real world the tool gives a step-by-step plan with computed waits (DNSKEY TTL, DS TTL at the parent) per RFC 6781/7583.
- **`OperatorInstruction`** (F1, F2): text only, e.g. "open TCP 53" or "`max-udp-size` ≥ 1232". In the lab, F1 can be "fixed" by copying the correct zone to auth2, behind the `--lab-copy-secondary` option.

### 3.8 Verify

There are two phases, because a server doesn't see the new zone until it is restarted:
1. **Offline**, before delivery (part of `fix`): `FileZoneSource` over the new `.signed` file through the same `Check`s, plus `dnssec-verify`. If even one CRITICAL finding remains, nothing is delivered.
2. **Live**, after the reload (`dnssecretary verify`, called by `dnssecretary.sh`): a full `collect → check`, plus a check that the resolver returns NOERROR AD. The report shows the state before and after.

### 3.9 CLI and report

```
dnssecretary check  <zone> [--json] [--now <time>] [--key-dir <dir>]
dnssecretary fix    <zone> [--apply] [--lab-copy-secondary]
dnssecretary verify <zone>
dnssecretary catalog [<id>]                  # print the catalogue, e.g. "catalog B6"
dnssecretary snapshot <zone> -o <file.json>  # a snapshot for the tests
```

Exit codes: `0` ok, `1` only risks, `2` at least one critical error, `3` tool error (e.g. a timeout). Scripts and CI use them, as with `check.sh`.

Example output:

```
$ sh scripts/dnssecretary.sh check a1-expired.test
Zone: a1-expired.test.        State: BROKEN (1 critical error)

[A1] Expired signature                                             CRITICAL
  What:      9 signatures (RRSIG) expired at 2026-10-22 14:03 UTC, 7 days ago.
             Example: www.a1-expired.test. A, key 41237 (ZSK).
  Cause:     the zone is no longer re-signed (the signer has stopped).
  Effect:    every validating resolver returns SERVFAIL. Lab resolver: EDE 7.
  Fix:       automatic. Re-sign the zone:  dnssecretary fix --apply a1-expired.test
  References: RFC 4035 §5.3.1
```

`--json` returns the same data in structured form (diagnoses, findings, plan). The web report will later work on that JSON.

---

## 4. Testing

### 4.1 Unit tests (host, no lab)

- **Pure logic** with small hand-written examples: DS comparison, canonical name order, NSEC chain closure, the `Diagnoser` rules.
- **Snapshots (record & replay):** `dnssecretary snapshot` runs once for all 30 zones, and the JSON files are committed to `src/test/resources/snapshots/`. They contain only public data: DNSKEY, RRSIG, DS, no private keys. A test loads a snapshot, sets `Clock.fixed(snapshot.collectedAt())` and checks that `CheckRunner` + `Diagnoser` give exactly the expected ID from the catalogue (`lab.zone` → `id`). That's why the tests:
  - run without Docker and internet, in CI in a few seconds
  - don't break after 30 days, when the lab's signatures expire
  - don't depend on whose keys were generated (everyone on the team has their own)

### 4.2 E2E (`scripts/check-app.sh`, host, lab running)

Modelled on `check.sh`: for every zone in `catalog.yaml` it runs `dnssecretary check --json` in the `app` container and checks that the expected ID is reported and that `good.test` has no findings. `exit 1` if anything doesn't match. The second part (`FIX=1`) runs `fix --apply` → reload → `verify` for every error with a ZONE or PARENT fix and expects "OK". At the end it runs `sign.sh`, so the lab stays in its starting state.

E1 and E3 are checked after their `e*-rollover.sh` script, because only then do they show.

### 4.3 CI (`.github/workflows/lab.yml`)

- A new job, **`app`**: `actions/setup-java` (Temurin 21) → `mvn -B -f dnssecretary/pom.xml verify` (unit tests and snapshots) → upload the jar as an artifact.
- The existing job **`lab`** gets steps after `check.sh`: `mvn package -DskipTests` → `docker compose build app` → `check-app.sh` → rollover scripts (with an E1/E3 check by the tool) → `FIX=1 check-app.sh` at the end, because it changes zones.

---

## 5. Work plan

**Before the event** (if the hackathon rules allow preparing code; if not, these are the first 2–3 hours):
- Maven skeleton, picocli commands that don't do anything yet, loading `lab.yaml` and `catalog.yaml`
- The lab changes from 2.5: Dockerfile stage, `app` service, `dnssecretary.sh`, `sign-tld.sh`
- `catalog.yaml` filled in from the sheet for all 30 errors
- The `app` CI job

**Hackathon (24h), split into 5 roles:**

| Role | Responsibility |
|------|----------------|
| 1. DNS and collect | `DnsClient`, `DelegationWalker`, `Collector`, `FileZoneSource`, snapshots for all 30 zones (**everyone needs this first**, target H+4) |
| 2. Checks A and C | signatures, TTL, algorithms, key strength |
| 3. Checks B and E | delegation and DS, CDS, cache and rollover, the B1/E2 rule in the Diagnoser |
| 4. Checks D and F | NSEC/NSEC3 chain and policy, NS consistency, transport |
| 5. Fix, verify, demo | `BindZoneSigner`, `LabParentPublisher`, `FileDeliverer`, `Verifier`, `check-app.sh`, demo script |

Until role 1 finishes the collector, roles 2–4 write checks against hand-built `ZoneSnapshot` objects. That's why `ZoneSnapshot` is the first thing we agree on (already before the event).

**Checkpoints:**
- H+4: snapshots for all 30 zones
- H+10: `check-app.sh` green for the MVP errors (MVP tab)
- H+16: all errors detected, ZONE fixes work
- H+20: PARENT fixes in the lab, GUIDED/OPERATOR instructions
- H+22: code freeze and demo rehearsal

**Demo** (`scripts/demo.sh <zone>`): `dig` shows SERVFAIL → `dnssecretary check` explains the error → `dnssecretary fix --apply` → `dig` shows NOERROR AD. Good candidates are A1 (the clearest), B1 (a fix at the parent) and E1 (live, with `e1-rollover.sh`).

## 6. Next steps (after the hackathon)

- `config/public.yaml`: IANA root hints and trust anchor, `parent.mode: instructions`. The same jar checks real `.rs` domains, but only our own or with permission (see "Testing scope" in the proposal).
- AXFR instead of reading the file. A history of snapshots per zone, so rollover errors show before they cause an outage (e.g. "old ZSK removed 10 min after the new one, but the TTL is 1h").
- A web report on top of the JSON (Javalin), with the same texts from the catalogue.
- Signing in pure Java (dnsjava `DNSSEC.sign`) behind the same `ZoneSigner` interface, if we need to work without the BIND tools.

## 7. Open questions and risks

- **The hackathon rules on code written in advance.** They decide how much of section 5 we do before the event.
- **Differences between Unbound and the catalogue** (C2, D6, F1, the "catalogue: …" notes in `check.sh`). We should agree that the catalogue is authoritative for the tool, and record that in `catalog.yaml`.
- **dnsjava limits**: before the event, check RSASHA1 verification (C1), NSEC3 hashing with 200 iterations (D4) and parsing of `ldns-signzone` output. If something doesn't work, the fallback is `ldns-verify-zone` / `dnssec-verify` through `ProcessBuilder`, for that one check.
- **Restart instead of reload.** `dnssecretary.sh` restarts the servers. That's slower, but reliable and the same as in the README. Later it could use `nsd-control reload <zone>` over the lab network.
