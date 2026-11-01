# Development

## Repository layout

```
dnssecretary/     the tool: Java 21, Maven (see its README for packages and checks)
webapp/           the front end: React + TypeScript + Vite (see its README)
catalog/          catalog.yaml, the 30 errors, read by the tool and the lab scripts
zones/            hand-edited zone sources (no DS records) + hosting.list
scripts/          lab, demo and wrapper scripts (see docs/lab.md#scripts)
  fixenv/         reload/flush helpers that fix scripts run with (prod.sh, sandbox.sh)
  sandbox/        what runs inside the sandbox container
root/ tld/ auth1/ auth2/ auth3/ resolver/ client/   each server's config dir (mounted read-only)
image/Dockerfile  the images: lab, app (+ JRE), web (+ Docker CLI); pinned versions
compose.yaml      the lab network and every service (project name: dnssecretary)
demo/             tour.mjs: the browser script that records the demo video
docs/             this documentation
```

Generated and gitignored: `keys/`, `build/`, `*/*.signed`, `auth1/hosting.conf`, `resolver/root.key`,
`client/delv-anchor.conf`, `dnssecretary/target/`, `webapp/dist/`, `webapp/node_modules/`, `demo/out/`.

## Build and test

| Part | Build and test | Notes |
| --- | --- | --- |
| Java | `mvn -f dnssecretary/pom.xml verify` | unit tests offline, on recorded snapshots; `target/dnssecretary.jar` |
| Front end | `cd webapp && npm ci && npm test && npm run build` | vitest on the mock backend; `npm run typecheck` |
| Shell | `docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:v0.10.0 scripts/*.sh scripts/fixenv/*.sh scripts/sandbox/*.sh` | settings in `.shellcheckrc` |
| The lab | `sh scripts/check.sh` (`STRICT_EDE=1` also compares EDE codes) | every zone vs. what Unbound 1.20 answers |
| End to end | `sh scripts/setup.sh --check`, then the [demo](demo.md) | |

No Java or Node on the host? See [Getting started](getting-started.md#build-without-java-or-node).
After a new jar, restart the web app with `sh scripts/web.sh`: it always starts a fresh JVM. A JVM
whose jar was replaced under it fails to load classes it hasn't used yet.

Front-end development against the real backend: `sh scripts/web.sh`, then `cd webapp && npm run dev`
(http://localhost:5173, `/api` is proxied to port 8080). Without a backend: `npm run mock`.

## What the tests cover

- **Every catalogue error is detected**: `CatalogDetectionTest` runs every check on the recorded
  snapshot of each catalogue zone (`dnssecretary/src/test/resources/snapshots/`) and expects that
  error and nothing unrelated, and nothing at all on `good.test`.
- **Every fix is planned and scripted**: the planner and script generator tests, per action.
- **The web API**: `WebAppTest` over real HTTP, with a fake sandbox and lab.
- **The script contract**: `ScriptRunnerTest`, `ProdRunnerTest` run real `sh`.
- **The front end**: the dashboard and zone logic, the pages on the mock backend, the editor.
- **The lab**: `check.sh` asserts rcode, AD and EDE for every zone, including the 40 hosting zones.

## CI

`.github/workflows/lab.yml`, on pushes to `main` and on pull requests:

1. **shellcheck** over `scripts/`.
2. **app**: `mvn verify` (Java 21), uploads the jar.
3. **webapp**: `npm ci`, typecheck, vitest, build, and `node --check demo/tour.mjs` (Node 22).
4. **lab**: the whole lab from scratch: build → `keygen.sh` → `sign.sh` → `up -d` → `check.sh` →
   DNSSECretary collects every catalogue zone (`snapshots.sh`) → both rollover scripts and a collect
   of E1/E3 → one hosting zone broken live (`demo.sh break northwind-coffee.test a1`) and found by
   `check` as A1.

## Conventions

- **All text is English**: code, comments, script output, catalogue, UI, docs, commit messages.
- **User-facing text** lives in `catalog/catalog.yaml` and `messages.properties`, not in Java code.
- **Checks are pure**: no I/O, time from `ctx.now()`. Everything they need is in the `ZoneSnapshot`.
- **Every query names its server**: the tool never uses the system resolver.
- **Pinned versions** in `image/Dockerfile`: the expected results in `check.sh` depend on Unbound 1.20.
  After a bump, run `check.sh` and update the expectations.
- **Scripts** are POSIX `sh` and pass shellcheck. Run signing scripts in the `signer` container with
  `--user $(id -u):$(id -g)`.
- **The front end talks to the server only through `src/api/client.ts`**, so the mock works the same.

## Adding a check

1. A class in `dnssecretary/src/main/java/rs/port53/dnssecretary/check/<group>/` implementing `Check`:
   `detects()` returns the IDs it can report, `run(snapshot, ctx)` returns `Finding`s.
2. One line in `CheckRunner.defaultChecks()`.
3. `mvn verify`: as soon as the check declares an ID, `CatalogDetectionTest` runs it on that error's
   snapshot.

What a check gets is documented in [`dnssecretary/README.md`](../dnssecretary/README.md#what-a-check-gets-zonesnapshot).

## Adding an error

1. The catalogue entry in `catalog/catalog.yaml` and its `ErrorId`.
2. The lab: a zone source in `zones/`, its delegation in `zones/test.zone`, the zone in `keygen.sh`,
   a `break_<id>` function in `break.sh`, a `zone:` block in `auth1/nsd.conf`, a row in `check.sh`.
3. `keygen.sh`, `sign.sh`, restart, `check.sh`.
4. A check that detects it (above), and a recorded snapshot:
   `ROLLOVER=1 sh scripts/snapshots.sh`, then `sign.sh` and a restart.
5. A fix action, if it can be fixed automatically (`fix/FixPlanner`, `fix/ScriptGenerator`).
6. `hosting: true` in the catalogue if `break.sh zone` can put it on any hosting zone.

## Re-recording snapshots

After a change in the lab or in `collect` (lab running and freshly signed, jar built):

```sh
ROLLOVER=1 sh scripts/snapshots.sh      # every catalogue zone; E1/E3 after their rollover scripts
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh
docker compose restart root tld auth1 auth2 auth3 resolver
```
