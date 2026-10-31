# DNSSECretary u Javi: predlog arhitekture i povezivanja sa labom

Ovaj dokument predlaže kako da napravimo DNSSECretary u Javi i kako da ga povežemo sa labom iz ovog repoa. Polazi od projektnog predloga (Detect → Diagnose → Fix → Verify) i od toga šta lab već ima: 30 pokvarenih zona, `check.sh`, CI.

## Ukratko

- **Kod živi u ovom repou**, u folderu `dnssecretary/` (Maven projekat). Lab i alat se menjaju zajedno, a ID-jevi grešaka (A1–F2) su isti na oba mesta.
- **Java 21 + dnsjava 3.6.x.** dnsjava ume da šalje DNS upite (UDP/TCP, EDNS, DO bit, AXFR), da proveri RRSIG i da izračuna DS. Za potpisivanje koristimo BIND alate koji su već u lab image-u.
- **Alat radi u kontejneru na lab mreži.** Mreža je `internal: true` i to ostaje tako. Jar se gradi na hostu (IDE, Maven, internet), a pokreće se u novom compose servisu `app`. Taj servis koristi lab image plus Java runtime.
- **Alat ne zavisi od laba.** Adrese root servera, trust anchor, resolver i putanje do zona i ključeva dolaze iz konfiguracije. Za lab je to `config/lab.yaml`. Isti jar kasnije radi i na pravom DNS-u, sa drugom konfiguracijom.
- **Pipeline:** `collect → check → diagnose → plan → apply → verify`. Svaki korak je poseban paket sa jasnim ulazom i izlazom.
- **Detekcija se zasniva na podacima iz zone**, ne na EDE kodovima. Resolver služi samo kao drugo mišljenje i za greške koje se vide samo u kešu (E1, E3). README to već kaže: EDE je samo nagoveštaj.
- **Testovi:** unit testovi na snimljenim „snapshot“ fajlovima (offline i deterministički) i e2e skripta `check-app.sh` po uzoru na `check.sh`. Obe idu u CI.

---

## 1. Tehnologije

| Šta | Izbor | Zašto |
|-----|-------|-------|
| Jezik | Java 21 (LTS) | Imamo najviše iskustva sa njom. Alpine 3.20 (baza laba) ima `openjdk21`. |
| Build | Maven, jedan modul, fat jar (`maven-shade-plugin`) → `target/dnssecretary.jar` | Svi ga znaju i IntelliJ ga otvara bez podešavanja. |
| DNS | [dnsjava](https://github.com/dnsjava/dnsjava) 3.6.x | `SimpleResolver` (UDP/TCP/EDNS/DO), `ZoneTransferIn` (AXFR), `DNSSEC.verify(...)`, `DSRecord` (računa digest), NSEC3 heš, parsiranje zonskih fajlova |
| CLI | picocli 4.7.x | Podkomande, `--help`, exit kodovi |
| JSON/YAML | Jackson (`jackson-databind`, `jackson-dataformat-yaml`) | Konfiguracija, katalog grešaka, JSON izveštaj, snapshot fajlovi |
| Potpisivanje | BIND alati preko `ProcessBuilder` (`dnssec-signzone`, `dnssec-verify`, `dnssec-keygen`) | Ključevi su u BIND formatu i lab ih već koristi. Za 24h je ovo daleko manji rizik od potpisivanja u čistoj Javi. Skriveno je iza interfejsa, pa se kasnije može zameniti. |
| Testovi | JUnit 5 + AssertJ | |
| Kasnije: web | Javalin ili Spring Boot | Čita isti JSON izveštaj. Vidi „Sledeći koraci“. |

Verzije pinujemo kao u `image/Dockerfile`, iz istog razloga: rezultati zavise od verzija.

---

## 2. Povezivanje sa labom

### 2.1 Gde živi kod

```
dnssec-lab/
├── dnssecretary/              # NEW: Java project
│   ├── pom.xml
│   ├── config/lab.yaml        # lab config (addresses, paths inside the container)
│   └── src/...
├── catalog/katalog.yaml       # NEW: error catalogue (from the sheet), shared by Java and the scripts
├── image/Dockerfile           # + "app" stage with the Java runtime
├── compose.yaml               # + "app" service
└── scripts/
    ├── dnssecretary.sh        # NEW: runs the tool in the lab (host)
    ├── check-app.sh           # NEW: e2e – does the tool detect every error (host)
    └── sign-tld.sh            # NEW: re-signs only test. (pulled out of sign.sh)
```

Zašto ne poseban repo: alat i lab se menjaju zajedno. Nova greška u labu treba da dobije i novu proveru u alatu, a CI treba da pokrene oba u istom prolazu. Poseban repo sa labom kao git submodule je moguć, ali donosi ceremoniju bez koristi.

### 2.2 Kako Java dolazi do lab mreže

Lab mreža nema internet i to je namerno. Na macOS-u i Windows-u host ne može ni da pristupi IP adresama kontejnera. Zato se alat **pokreće u kontejneru**, a gradi na hostu:

```
host (IntelliJ, Maven, internet)          lab mreža 10.53.0.0/24 (internal: true)
┌───────────────────────────┐             ┌──────────────────────────────────────┐
│ mvn package               │  bind mount │ app (dinamička IP iz 10.53.0.128/25) │
│  → dnssecretary/target/   │ ──────────▶ │ java -jar /lab/dnssecretary/...jar   │
│     dnssecretary.jar      │   ./:/lab   │   ├─▶ root 10.53.0.2  (iterativno)   │
└───────────────────────────┘             │   ├─▶ tld, auth1/2/3 (direktno)      │
                                          │   └─▶ resolver 10.53.0.53 (2. mišlj.)│
                                          └──────────────────────────────────────┘
```

**`image/Dockerfile`** dobija drugu fazu. Lab servisi ostaju na fazi `lab`, a alat dobija iste BIND alate plus Javu:

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

**`compose.yaml`**: `root` dobija `build: { context: ./image, target: lab }`, jer je poslednja faza sada `app`. Dodaje se servis `app` i `ip_range`, da dinamičke adrese ne bi mogle da se sudare sa fiksnim:

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

Alat **nema pristup Docker socketu** i ne restartuje servere sam. To radi skripta na hostu (2.5). Tako lab ostaje izolovan, a alat ne zna da postoji Docker.

### 2.3 Šta alat vidi u labu

| Podatak | Odakle u labu | Odakle u stvarnom svetu |
|---------|---------------|-------------------------|
| Root serveri | `10.53.0.2` (kao `resolver/root.hints`) | IANA root hints |
| Trust anchor | `/lab/resolver/root.key` (pravi ga `sign.sh`) | IANA `root-anchors.xml` |
| Resolver za drugo mišljenje | `10.53.0.53` | lokalni ili javni resolver |
| Cela potpisana zona (opciono) | fajl `/lab/auth1/<zona>.zone.signed`, kasnije AXFR | AXFR ili fajl koji operater preda |
| Nepotpisani izvor zone (za popravku) | `/lab/zones/<zona>.zone` | operaterov izvor |
| Ključevi (za popravku) | `/lab/keys/` (+ `old/`, `next/`, `retired/`, …) | operaterov key dir |
| Gde ide popravljena zona | `/lab/auth1/` (F1: `auth2/`, F2: `auth3/`) | operaterov server |
| DS kod roditelja | `/lab/build/parent-ds/<zona>.ds` + `sign-tld.sh` | samo uputstvo za registra |

### 2.4 Konfiguracija

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

Konfiguracija za stvarni svet (`config/public.yaml`) ima isti oblik, sa `parent.mode: instructions` i `collect.zoneSource: axfr` ili `none`. Tačna, važeća verzija je u `dnssecretary/config/lab.yaml`.

### 2.5 Izmene u labu

1. **`image/Dockerfile`, `compose.yaml`**: faza `app` i servis `app` (2.2).
2. **`scripts/sign-tld.sh`**: korak 2 iz `sign.sh` izdvojen u posebnu skriptu. Ponovo potpisuje samo `test.` iz `build/dsset-good.test.` i `build/parent-ds/*.ds`, pa kopira rezultat u `tld/`. `sign.sh` je poziva umesto koraka 2. Alat je poziva kada u labu popravlja DS kod roditelja (B1–B3, B6–B8, E2, C2). Root se ne dira, jer se KSK zone `test.` ne menja.
3. **`scripts/dnssecretary.sh`** (host): omotač oko `docker compose run`. Posle `fix --apply` restartuje servere koje je alat naveo, čisti keš resolvera i pokreće `verify`:

   ```sh
   #!/bin/sh
   # dnssecretary.sh – runs DNSSECretary (Java) inside the lab network.
   #   sh scripts/dnssecretary.sh check a1-expired.test
   #   sh scripts/dnssecretary.sh fix --apply a1-expired.test
   set -eu
   cd "$(dirname "$0")/.."

   JAR=dnssecretary/target/dnssecretary.jar
   [ -f "$JAR" ] || { echo "Nema $JAR – pokreni: mvn -f dnssecretary/pom.xml package"; exit 1; }

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
4. **`scripts/check-app.sh`** (host): e2e test alata (sekcija 4).
5. **`catalog/katalog.yaml`**: katalog iz sheet-a `DNSSECretary-katalog-gresaka` u mašinski čitljivom obliku. Java ga učitava kao resurs (Maven `<resource>` pokazuje na `../catalog`). Tekstovi su na srpskom, ključevi na engleskom:

   ```yaml
   - id: A1
     title: Istekao potpis
     group: signatures
     severity: CRITICAL          # CRITICAL | RISK
     side: ZONE                  # ZONE | PARENT | OPERATOR
     symptom: SERVFAIL, EDE 7
     cause: Zona se više ne potpisuje ponovo, pa je RRSIG prošao datum isteka.
     fix: Ponovo potpiši zonu i automatizuj potpisivanje (cron ili inline signing).
     rfc: ["RFC 4035 §5.3.1"]
     lab: { zone: a1-expired.test, probe: "www A" }
   ```
6. **`.gitignore`**: `dnssecretary/target/`. `clean.sh` ne treba menjati, jer `build/dnssecretary/` već pada pod `build/`.
7. **CI**: sekcija 4.3.
8. *(Kasnije)* **AXFR**: u `auth1/nsd.conf` i `auth2/nsd.conf` dodati `pattern` sa `provide-xfr: 10.53.0.0/24 NOKEY` i `include-pattern` u svaki `zone:` blok. Tada alat dobija celu zonu preko DNS-a, kao u stvarnosti, i ne mora da čita fajl.

### 2.6 Ciklus „pokvari → nađi → popravi → proveri“ u labu

```
sign.sh + restart                       lab u početnom stanju: 30 pokvarenih zona
        │
dnssecretary.sh check a1-expired.test   collect → check → diagnose → izveštaj (samo čita)
        │
dnssecretary.sh fix --apply a1-...      plan → potpiši u build/dnssecretary/ → dnssec-verify
        │                                → offline verify novog fajla → kopiraj u auth1/
        │                                → upiši build/dnssecretary/reload
        ├── restart auth1 (+ tld kod DS popravki), flush_zone na resolveru
        │
dnssecretary.sh verify a1-expired.test  live: direktna validacija + resolver vraća NOERROR AD
        │
sign.sh + restart                       vraća SVE zone u pokvareno stanje (reset za sledeći demo)
```

Pravila koja važe za svaku popravku:
- **`zones/` se nikad ne menja.** Ispravljen izvor (npr. kraći TTL za A7) ide u `build/dnssecretary/<zona>/`. Zato `sign.sh` uvek može da vrati lab na početno stanje.
- **Podrazumevano je dry-run.** Bez `--apply` alat samo ispisuje plan.
- **`dnssec-verify` pre isporuke**, kao u `sign.sh`. Ako ne prođe, ništa ne ide na server.
- **Rezervna kopija.** Stari `.signed` fajl se pre zamene čuva u `build/dnssecretary/backup/`.

---

## 3. Arhitektura Java programa

### 3.1 Tok podataka

```
             ┌──────────┐  ZoneSnapshot  ┌───────┐  List<Finding>  ┌──────────┐  List<Diagnosis>
zona ──────▶ │ collect  │ ─────────────▶ │ check │ ──────────────▶ │ diagnose │ ───────┬─────────▶ report (tekst / JSON)
             └──────────┘                └───────┘                 └──────────┘        │
                  ▲                                                                    ▼
                  │                                                              ┌──────────┐ FixPlan ┌───────┐
                  └────────────────────── verify (ponovi collect+check) ◀─────── │   plan   │ ──────▶ │ apply │
                                                                                 └──────────┘         └───────┘
```

Svaki korak je čista funkcija nad podacima, osim `collect` (mreža) i `apply` (fajlovi i procesi). Zbog toga se `check` i `diagnose`, gde je najviše logike, testiraju bez mreže.

### 3.2 Paketi

```
rs.port53.dnssecretary
├── cli/          picocli komande: check, fix, verify, catalog, snapshot
├── config/       AppConfig (učitava lab.yaml), Thresholds
├── catalog/      Catalog, CatalogEntry (učitava katalog.yaml), ErrorId (A1..F2)
├── dns/          DnsClient (UDP/TCP/EDNS/DO nad dnsjava SimpleResolver),
│                 DelegationWalker (iterativno od root-a), TrustAnchor
├── collect/      Collector, ZoneSnapshot, NameserverView, ResolverView,
│                 ZoneSource (FileZoneSource, AxfrZoneSource), SnapshotIO (JSON)
├── check/        Check (interfejs), CheckContext, CheckRunner
│   ├── signatures/   SignatureValidityCheck, SignatureCryptoCheck, SignatureCoverageCheck, TtlCheck
│   ├── delegation/   DelegationCheck, DsPolicyCheck, KeySetCheck, CdsCheck
│   ├── algorithms/   AlgorithmPolicyCheck, KeyStrengthCheck
│   ├── denial/       DenialChainCheck, Nsec3PolicyCheck
│   ├── rollover/     CacheConsistencyCheck
│   └── servers/      NameserverConsistencyCheck, TransportCheck
├── model/        Finding, Severity, Diagnosis, FixSide
├── diagnose/     Diagnoser + pravila (spajanje, razlikovanje B1/E2, F1 …)
├── fix/          FixPlanner, FixPlan, FixAction (sealed), FixExecutor
│   ├── sign/     ZoneSigner (interfejs), BindZoneSigner (dnssec-signzone)
│   ├── parent/   ParentPublisher: LabParentPublisher | RegistrarInstructions
│   └── deliver/  ZoneDeliverer: FileDeliverer (+ upis reload liste)
├── verify/       Verifier (offline: novi fajl; live: posle reload-a)
└── report/       TextReport (srpski), JsonReport
```

Paketi `check`, `diagnose` i `fix` ne zavise od `cli`. Ako kasnije dodamo web, `core` se izdvaja u poseban Maven modul bez prepravljanja koda.

Jezička podela je ista kao u labu: kod, imena i komentari na engleskom, a sve što korisnik čita (izveštaj, poruke, katalog) na srpskom latinicom. Tekstovi su u `katalog.yaml` i `messages.properties`, ne u kodu.

### 3.3 Ključni tipovi

Ovako je implementirano (`collect/`, vidi i `dnssecretary/README.md`). Svaki upit čuva ceo odgovor (`Response`: rcode, AA/TC/AD, sekcije grupisane u `SignedRRset` sa RRSIG-ovima, EDE, greška), pa se NODATA/NXDOMAIN dokazi i greške transporta vide na istom mestu:

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
        ReplaceParentDs,      // B1, B2, B3, B7, E2
        AddParentDs,          // B6
        RemoveParentDs,       // B8, C2
        RepublishKey,         // E1, E3: bring the old ZSK back until the TTL runs out
        KeyRollover,          // C1, C3: fast in the lab, step-by-step plan for the real world
        OperatorInstruction   // F1, F2: server config, only text
{}

public record FixPlan(Name zone, List<FixAction> actions) {}
```

`Clock` se ubacuje svuda gde je bitno vreme (`CheckContext`, `Verifier`). Testovi tako koriste fiksno vreme, a CLI ima `--now` za demo i za otklanjanje grešaka.

### 3.4 Collect

1. **`DelegationWalker`** kreće od `rootServers` i prati referale do roditelja zone (`test.`). Pri tome proverava lanac od trust anchora do roditelja (root DNSKEY prema anchoru, DS za `test.`…). U labu je taj deo uvek ispravan, ali pravom alatu treba.
2. **Od roditelja** uzima DS + RRSIG. Ako DS ne postoji, čuva NSEC/NSEC3 dokaz da ga nema (B6).
3. **Od svakog NS-a zone** posebno (ne samo od prvog koji odgovori):
   - `SOA`, `DNSKEY`, `CDS`/`CDNSKEY` i `NSEC3PARAM`, sa DO bitom i EDNS 1232
   - uzorci (`www A`, apex `NS`, …)
   - jedno ime koje sigurno ne postoji (`dnssecretary-<random>.<zona>`) i jedan NODATA upit
   - isti upit preko TCP-a i veliki odgovor (TC bit) za F2
4. **Cela zona**, ako je dostupna (`FileZoneSource` u MVP-u, kasnije `AxfrZoneSource`). Bez nje provere D1/D2/A3 rade samo nad uzorcima.
5. **`ResolverView`**: isti upiti preko `10.53.0.53` (rcode, AD, EDE) i DNSKEY skup **iz keša resolvera** sa preostalim TTL-om.

Alat **nikad ne koristi sistemski resolver**. Svaki upit ima eksplicitan server. U kontejneru bi sistemski išao preko Docker DNS-a, koji briše EDE.

`dnssecretary snapshot <zona> -o x.json` snima `ZoneSnapshot` u JSON, sa zapisima u presentation formatu (`Record.toString()` / `Record.fromString()`). Na tome se zasnivaju unit testovi (4.1).

### 3.5 Check: kako se otkriva svaka greška

Izvori podataka: **P** = roditelj, **L** = autoritativni serveri (live), **Z** = cela zona, **R** = keš resolvera, **K** = direktorijum ključeva (samo ako je dat).
Vrste popravke: **ZONA** = automatski (ponovno potpisivanje), **RODITELJ** = DS kod roditelja (u labu automatski preko `sign-tld.sh`, inače uputstvo za registra), **VOĐENO** = plan u više koraka sa čekanjem TTL-a, **OPERATER** = konfiguracija servera, samo uputstvo.

| ID | Greška | Provera (klasa: pravilo) | Izvor | Popravka |
|----|--------|--------------------------|-------|----------|
| A1 | Istekao potpis | `SignatureValidityCheck`: `expiration < now` | L, Z | ZONA |
| A2 | Potpis još ne važi | `SignatureValidityCheck`: `inception > now` (+ savet: proveri sat potpisivača) | L, Z | ZONA, `inception = now − 1h` |
| A3 | RRset bez RRSIG | `SignatureCoverageCheck`: svaki autoritativni RRset ima RRSIG | Z (L za uzorke) | ZONA |
| A4 | Neispravan potpis | `SignatureCryptoCheck`: `DNSSEC.verify` baca `SignatureVerificationException` | L, Z | ZONA |
| A5 | Potpis ključem van DNSKEY skupa | `SignatureCryptoCheck`: key tag iz RRSIG-a nije u DNSKEY skupu | L, Z | ZONA (samo objavljenim ključevima) |
| A6 | Potpis uskoro ističe | `SignatureValidityCheck`: `expiration − now < signatureExpiryWarnDays` | L, Z | ZONA (rizik) + savet za automatizaciju |
| A7 | TTL duži od važenja potpisa | `TtlCheck`: `originalTTL > expiration − inception` | L, Z | ZONA: smanji TTL u kopiji izvora, pa potpiši |
| B1 | DS na nepostojeći ključ | `DelegationCheck`: nijedan DS ne odgovara nijednom DNSKEY-u (tag, alg, digest) | P, L | RODITELJ: DS iz trenutnog KSK |
| B2 | Pogrešan DS digest | `DelegationCheck`: tag i alg se slažu, digest ne (`new DSRecord(...)` pa poređenje) | P, L | RODITELJ |
| B3 | DS i DNSKEY različit algoritam | `DelegationCheck`: tag se slaže, alg ne | P, L | RODITELJ |
| B4 | DS postoji, zona nepotpisana | `DelegationCheck`: DS postoji, DNSKEY ne | P, L | ZONA (potpiši, ako imamo ključeve) ili RODITELJ (ukloni DS) |
| B5 | DNSKEY skup nije potpisan KSK-om | `KeySetCheck`: RRSIG nad DNSKEY nije od ključa na koji pokazuje DS | P, L | ZONA |
| B6 | Ostrvo (nema DS) | `DelegationCheck`: roditelj dokazuje da DS ne postoji, a zona ima DNSKEY | P, L | RODITELJ: dodaj DS |
| B7 | DS sa SHA-1 | `DsPolicyCheck`: digest type 1 (RFC 8624) | P | RODITELJ: zameni DS-om sa SHA-256 |
| B8 | Višak starog DS-a | `DelegationCheck`: bar jedan DS se slaže, a neki ne | P, L | RODITELJ: ukloni višak |
| C1 | Zastareo algoritam (RSASHA1) | `AlgorithmPolicyCheck`: alg 5/7 (RFC 8624) | L | VOĐENO: rollover algoritma |
| C2 | Dva algoritma, potpis jednim | `AlgorithmPolicyCheck`: svaki RRset mora imati RRSIG za svaki algoritam iz DNSKEY skupa (RFC 4035 §2.2) | L, Z | ZONA (ukloni ključ alg 8) + RODITELJ (ukloni DS alg 8) |
| C3 | Slab RSA ključ | `KeyStrengthCheck`: `((RSAPublicKey) dnskey.getPublicKey()).getModulus().bitLength() < minRsaBits` | L | VOĐENO: rollover ključa |
| D1 | Pokidan NSEC lanac | `DenialChainCheck`: lanac je zatvoren i u kanonskom redu, plus proba imena koje ne postoji | Z, L | ZONA |
| D2 | NSEC bitmap laže | `DenialChainCheck`: tipovi u bitmapi = stvarni RRset-ovi na imenu | Z (L: NODATA) | ZONA |
| D3 | NSEC bez potpisa | `SignatureCoverageCheck` (NSEC je RRset kao i svaki drugi) + proba | Z, L | ZONA |
| D4 | NSEC3 protiv RFC 9276 | `Nsec3PolicyCheck`: iterations ≠ 0 ili salt nije prazan (> 150 → validator je smatra nepotpisanom) | L | ZONA: `-3 - -H 0` |
| D5 | NSEC3PARAM ≠ lanac | `Nsec3PolicyCheck`: parametri NSEC3PARAM ≠ parametri NSEC3 zapisa | Z, L | ZONA |
| D6 | NSEC i NSEC3 pomešani | `DenialChainCheck`: zona ima oba tipa | Z | ZONA |
| E1 | ZSK zamenjen bez pre-publish | `CacheConsistencyCheck`: direktna validacija prolazi, resolver vraća SERVFAIL, a key tag iz RRSIG-a nije u DNSKEY skupu iz **keša** resolvera | R, L | VOĐENO: vrati stari ZSK u DNSKEY skup (`keys/retired/`), čekaj TTL. Alat kaže koliko još. |
| E2 | DS novog KSK prerano | kao B1, plus `K`: DS odgovara ključu iz key dir-a koji nije objavljen (`keys/next/`) | P, L, K | RODITELJ: vrati stari DS (ili ZONA: objavi novi KSK) |
| E3 | Stari ZSK uklonjen prerano | `CacheConsistencyCheck`: resolver vraća RRSIG sa key tagom koga više nema u zoni | R, L | VOĐENO: ponovo objavi stari ZSK dok ne istekne TTL |
| E4 | CDS ne odgovara ključevima | `CdsCheck`: svaki CDS/CDNSKEY odgovara nekom DNSKEY-u (RFC 7344) | L | ZONA: ukloni ili ispravi CDS |
| F1 | Zastareli sekundar | `NameserverConsistencyCheck`: SOA serial i važenje potpisa se razlikuju po NS-u | L (svaki NS) | OPERATER (u labu: kopija na auth2) |
| F2 | Nema TCP-a / mali EDNS | `TransportCheck`: TCP ne radi, EDNS < 1232, TC bez TCP-a | L (svaki NS) | OPERATER |

Napomene:
- **Alat je stroži od Unbound-a, i to je poenta.** Za B7, C1, C2, C3, D6 i F1 lab resolver vraća NOERROR AD, ali alat ih prijavljuje kao rizik. Očekivani rezultat alata dolazi iz kataloga, ne iz `check.sh`.
- **`DNSSEC.verify` iz dnsjava već razlikuje uzroke** preko tipa izuzetka (`SignatureExpiredException`, `SignatureNotYetValidException`, `KeyMismatchException`, `SignatureVerificationException`, `UnsupportedAlgorithmException`). Zato A1, A2, A4 i A5 izlaze iz iste provere.
- dnsjava ima i `ValidatingResolver`. On može da služi kao nezavisno „drugo mišljenje“ (secure/bogus/insecure) uz naše provere, ali ih ne zamenjuje, jer ne kaže koja je greška iz kataloga u pitanju.

### 3.6 Diagnose

Provere vraćaju sirove nalaze. `Diagnoser` od njih pravi dijagnoze sa uzrokom. Pravila su mala, imenovana i testirana zasebno:

- **Spajanje:** 15 nalaza A1 (svaki RRset ima istekao potpis) postaje jedna dijagnoza: „potpisivanje je stalo“.
- **F1 umesto A1:** ako je A1 samo na jednom NS-u, a ostali su ispravni, uzrok nije zona nego sekundar koji ne dobija transfer.
- **B1 ili E2:** bez pristupa ključevima ove dve greške izgledaju isto (DS pokazuje na ključ koga nema). Ako je dat key dir, DS koji odgovara ključu iz `next/` znači E2, a ključu iz `old/` ili `retired/` znači B1. Bez key dir-a alat prijavljuje „B1 ili E2“ i objašnjava obe mogućnosti.
- **Prioritet:** kada se nalazi preklapaju, prvo se prijavljuje delegacija (B), pa potpisi (A), pa denial (D). Kod B4 nema smisla prijavljivati i A3 za svaki RRset.
- **Ozbiljnost:** KRITIČNO kada validacija pada (SERVFAIL), RIZIK kada radi ali nije bezbedno ili je loša praksa (oznaka „risk“ u README-u).

### 3.7 Fix

`FixPlanner` od svake dijagnoze pravi akcije (`FixAction`), a `FixExecutor` ih izvršava. Svaka akcija ima i čitljiv opis, pa je dry-run isti kod bez poslednjeg koraka.

- **`ResignZone`**: `BindZoneSigner` iz `fix.sourceDir` i `fix.keyDir` poziva `dnssec-signzone -S -K <keyDir> -s now-3600 -e now+30d -N unixtime`, sa opcijama koje dijagnoza traži (`-3 - -H 0` za D4/D5, TTL iz ispravljene kopije za A7). Posle toga sledi `dnssec-verify`, pa `FileDeliverer` kopira rezultat u `deliver` direktorijum i upisuje `restart auth1` / `flush <zona>` u reload listu.
  - Ovo automatski popravlja i A5, B5 i C2 sa strane zone: `-S` uzima samo ključeve iz `keys/`, a pomoćni ključevi (`k2/`, `alg8/`) su namerno u poddirektorijumima.
- **DS akcije**: DS se računa u Javi (`new DSRecord(zone, DClass.IN, ttl, DNSSEC.Digest.SHA256, ksk)`).
  - `parent.mode: lab`: `LabParentPublisher` upiše `build/parent-ds/<zona>.ds`, pozove `sign-tld.sh` i doda `restart tld` u reload listu.
  - `parent.mode: instructions`: `RegistrarInstructions` ispiše tačan DS i korake za registra, plus napomenu o RFC 8078 (CDS/CDNSKEY) automatizaciji.
- **`RepublishKey`** (E1, E3) i **`KeyRollover`** (C1, C3): u labu „brza“ varijanta (odmah novi ključevi i novi DS, uz upozorenje da u stvarnosti tako ne sme). Za stvarni svet alat daje plan u koracima sa računatim čekanjima (TTL DNSKEY-a, TTL DS-a kod roditelja) po RFC 6781/7583.
- **`OperatorInstruction`** (F1, F2): samo tekst, na primer „otvori TCP 53“ ili „`max-udp-size` ≥ 1232“. U labu F1 može da se „popravi“ kopiranjem ispravne zone na auth2, iza opcije `--lab-copy-secondary`.

### 3.8 Verify

Ima dve faze, jer server ne vidi novu zonu dok se ne restartuje:
1. **Offline**, pre isporuke (u okviru `fix`): `FileZoneSource` nad novim `.signed` fajlom kroz iste `Check`-ove, plus `dnssec-verify`. Ako ostane i jedan KRITIČAN nalaz, isporuke nema.
2. **Live**, posle reload-a (`dnssecretary verify`, poziva ga `dnssecretary.sh`): pun `collect → check`, plus provera da resolver vraća NOERROR AD. Izveštaj prikazuje stanje pre i posle.

### 3.9 CLI i izveštaj

```
dnssecretary check  <zona> [--json] [--now <vreme>] [--key-dir <dir>]
dnssecretary fix    <zona> [--apply] [--lab-copy-secondary]
dnssecretary verify <zona>
dnssecretary catalog [<id>]                  # ispis kataloga, npr. "catalog B6"
dnssecretary snapshot <zona> -o <fajl.json>  # snimak za testove
```

Exit kodovi: `0` ispravno, `1` samo rizici, `2` bar jedna kritična greška, `3` greška alata (npr. timeout). Skripte i CI ih koriste kao kod `check.sh`.

Primer izlaza:

```
$ sh scripts/dnssecretary.sh check a1-expired.test
Zona: a1-expired.test.        Stanje: NEISPRAVNA (1 kritična greška)

[A1] Potpis je istekao                                             KRITIČNO
  Šta:       9 potpisa (RRSIG) je isteklo 2026-10-22 14:03 UTC, pre 7 dana.
             Primer: www.a1-expired.test. A, ključ 41237 (ZSK).
  Uzrok:     zona se više ne potpisuje ponovo (potpisivač je stao).
  Posledica: svaki validirajući resolver vraća SERVFAIL. Lab resolver: EDE 7.
  Popravka:  automatska. Ponovo potpiši zonu:  dnssecretary fix --apply a1-expired.test
  Reference: RFC 4035 §5.3.1
```

`--json` vraća iste podatke strukturirano (dijagnoze, nalazi, plan). Na tom JSON-u kasnije radi web izveštaj.

---

## 4. Testiranje

### 4.1 Unit testovi (host, bez laba)

- **Čista logika** sa malim ručno napisanim primerima: poređenje DS-a, kanonski red imena, zatvorenost NSEC lanca, pravila u `Diagnoser`-u.
- **Snimci (record & replay):** `dnssecretary snapshot` se jednom pokrene za svih 30 zona, a JSON fajlovi se commit-uju u `src/test/resources/snapshots/`. Sadrže samo javne podatke: DNSKEY, RRSIG, DS, bez privatnih ključeva. Test učitava snimak, postavi `Clock.fixed(snapshot.collectedAt())` i proverava da `CheckRunner` + `Diagnoser` daju tačno očekivani ID iz kataloga (`lab.zone` → `id`). Zato testovi:
  - rade bez Dockera i interneta, i u CI-ju za par sekundi
  - ne pucaju posle 30 dana, kada potpisi u labu isteknu
  - ne zavise od toga čije su ključeve generisali (svako u timu ima svoje)

### 4.2 E2E (`scripts/check-app.sh`, host, lab radi)

Po uzoru na `check.sh`: za svaku zonu iz `katalog.yaml` pokreće `dnssecretary check --json` u `app` kontejneru i proverava da je prijavljen očekivani ID i da `good.test` nema nalaza. Izlaz je na srpskom, `exit 1` ako se išta ne slaže. Drugi deo (`FIX=1`) za svaku grešku sa popravkom ZONA ili RODITELJ radi `fix --apply` → reload → `verify` i očekuje „ispravno“. Na kraju pokreće `sign.sh`, da lab ostane u početnom stanju.

E1 i E3 se proveravaju posle odgovarajuće `e*-rollover.sh` skripte, jer se tek tada vide.

### 4.3 CI (`.github/workflows/lab.yml`)

- Novi job **`app`**: `actions/setup-java` (Temurin 21) → `mvn -B -f dnssecretary/pom.xml verify` (unit testovi i snimci) → upload jar-a kao artefakta.
- Postojeći job **`lab`** dobija korake posle `check.sh`: `mvn package -DskipTests` → `docker compose build app` → `check-app.sh` → rollover skripte (sa proverom E1/E3 alatom) → `FIX=1 check-app.sh` na kraju, jer menja zone.

---

## 5. Plan rada

**Pre događaja** (ako pravila hakatona dozvoljavaju pripremu koda; ako ne, ovo su prva 2–3 sata):
- Maven skelet, picocli komande koje još ništa ne rade, učitavanje `lab.yaml` i `katalog.yaml`
- Izmene u labu iz 2.5: Dockerfile faza, `app` servis, `dnssecretary.sh`, `sign-tld.sh`
- `katalog.yaml` popunjen iz sheet-a za svih 30 grešaka
- CI job `app`

**Hakaton (24h), podela na 5 uloga:**

| Uloga | Odgovornost |
|-------|-------------|
| 1. DNS i collect | `DnsClient`, `DelegationWalker`, `Collector`, `FileZoneSource`, snimci za svih 30 zona (**svima treba prvo ovo**, cilj je H+4) |
| 2. Provere A i C | potpisi, TTL, algoritmi, jačina ključa |
| 3. Provere B i E | delegacija i DS, CDS, keš i rollover, B1/E2 pravilo u Diagnoser-u |
| 4. Provere D i F | NSEC/NSEC3 lanac i politika, konzistentnost NS-ova, transport |
| 5. Fix, verify, demo | `BindZoneSigner`, `LabParentPublisher`, `FileDeliverer`, `Verifier`, `check-app.sh`, demo skripta |

Dok uloga 1 ne završi collector, uloge 2–4 pišu provere nad ručno napravljenim `ZoneSnapshot` objektima. Zbog toga je `ZoneSnapshot` prva stvar koju dogovaramo (već pre događaja).

**Kontrolne tačke:**
- H+4: snimci za svih 30 zona
- H+10: `check-app.sh` zelen za MVP grešaka (tab „Prvo“)
- H+16: sve greške detektovane, ZONA popravke rade
- H+20: RODITELJ popravke u labu, VOĐENO/OPERATER uputstva
- H+22: zamrzavanje koda i proba demoa

**Demo** (`scripts/demo.sh <zona>`): `dig` pokaže SERVFAIL → `dnssecretary check` objasni grešku → `dnssecretary fix --apply` → `dig` pokaže NOERROR AD. Dobri kandidati su A1 (najjasnija), B1 (popravka kod roditelja) i E1 (uživo, uz `e1-rollover.sh`).

## 6. Sledeći koraci (posle hakatona)

- `config/public.yaml`: IANA root hints i trust anchor, `parent.mode: instructions`. Isti jar proverava stvarne `.rs` domene, ali samo naše ili uz dozvolu (vidi „Testing scope“ u predlogu).
- AXFR umesto čitanja fajla. Istorija snimaka po zoni, pa se rollover greške vide pre nego što izazovu ispad (npr. „stari ZSK uklonjen 10 min posle novog, a TTL je 1h“).
- Web izveštaj nad JSON-om (Javalin), sa istim tekstovima iz kataloga.
- Potpisivanje u čistoj Javi (dnsjava `DNSSEC.sign`) iza istog `ZoneSigner` interfejsa, ako zatreba rad bez BIND alata.

## 7. Otvorena pitanja i rizici

- **Pravila hakatona o kodu pisanom unapred.** Od njih zavisi koliko od sekcije 5 radimo pre događaja.
- **Razlike između Unbound-a i kataloga** (C2, D6, F1, `check.sh` napomene „katalog: …“). Treba da se dogovorimo da je za alat merodavan katalog i da to stoji u `katalog.yaml`.
- **dnsjava ograničenja**: pre događaja proveriti RSASHA1 verifikaciju (C1), NSEC3 heš sa 200 iteracija (D4) i parsiranje `ldns-signzone` izlaza. Ako nešto ne radi, rezerva je `ldns-verify-zone` / `dnssec-verify` preko `ProcessBuilder`-a, za tu jednu proveru.
- **Restart umesto reload-a.** `dnssecretary.sh` restartuje servere. To je sporije, ali pouzdano i isto kao u README-u. Kasnije može `nsd-control reload <zona>` preko lab mreže.
