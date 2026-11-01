# Getting started

From a fresh clone to the web app, then everything you can do with it. If something doesn't
work, see [Troubleshooting](#troubleshooting) at the end.

## Requirements

| | |
| --- | --- |
| OS | Linux, or Windows with WSL 2. (macOS is untested: the scripts use GNU `stat`.) |
| Docker | Docker Engine with Compose v2 (`docker compose version`). Your user must be able to run `docker`. |
| Disk | About 3 GB for images (the lab image, Maven, Node, and the Playwright image for the video). |
| Network | Internet on the first run (images, Maven and npm packages). The lab itself never goes online. |
| Optional | Java 21 + Maven 3.9, Node 20+ (CI uses 22). Without them the build runs in Docker. |
| Port | 8080 on localhost (the web app, bound to `127.0.0.1` only). |

## One command

```sh
sh scripts/setup.sh --check
```

It runs every step below, takes about a minute once the images are built (the first build takes
longer), and ends with `Errors: 0` from `check.sh` and `Ready: http://localhost:8080`. It is safe to
run again: existing keys are kept, the zones get fresh 30-day signatures, the demo hosting goes back
to its start.

`sign.sh` prints a few warnings you can ignore: `DS digest type 0 is deprecated`, and a missing
`Kc2-two-alg.test.+008+….private`. Both are deliberate, for the broken catalogue zones.

## Step by step

All commands run from the repo root. Signing tools exist only inside the image, so those steps
run in the `signer` container; `--user` keeps the generated files yours.

```sh
# 1. The images: the lab (Alpine + NSD, Unbound, BIND tools, ldns), the tool (+ JRE), the web app (+ Docker CLI)
docker compose build
docker compose --profile app --profile web build app web

# 2. Keys, once: a KSK and a ZSK per zone (existing keys are skipped)
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/keygen.sh

# 3. Sign every zone, bottom-up, and build the trust anchor (again after a zone edit or every 30 days)
docker compose run --rm --user $(id -u):$(id -g) signer sh scripts/sign.sh

# 4. Start the lab: root, tld, auth1–3, resolver, client
docker compose up -d

# 5. Check it: every zone must answer as expected (exit 1 if not)
sh scripts/check.sh

# 6. Build the tool (dnssecretary/target/dnssecretary.jar)
mvn -f dnssecretary/pom.xml verify          # or without Java: see "Build without Java or Node" below

# 7. Build the front end (webapp/dist)
cd webapp && npm ci && npm run build && cd ..

# 8. Start the web app on http://localhost:8080
sh scripts/web.sh
```

### Build without Java or Node

```sh
docker run --rm -u $(id -u):$(id -g) -v "$PWD":/src -v "$HOME/.m2":/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 \
    -w /src/dnssecretary maven:3.9-eclipse-temurin-21 mvn -B -Duser.home=/var/maven verify

docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp -v "$PWD/webapp":/app -w /app node:22-alpine \
    sh -c 'npm ci && npm run build'
```

## What you can do now

| | |
| --- | --- |
| Use the web app | http://localhost:8080, see [Using the web app](web-app.md) |
| Break a zone | the **Demo panel** in the web app, or `sh scripts/demo.sh break northwind-coffee.test a1` |
| Back to the start | **Reset hosting** in the demo panel, or `sh scripts/demo.sh reset` (about 10 s) |
| Record the demo video | `sh scripts/video.sh` → `demo/out/dnssecretary-demo.webm` (see [Demo guide](demo.md)) |
| Use the CLI | `sh scripts/dnssecretary.sh check a1-expired.test` (runs in the `app` container on the lab network) |
| Ask the lab by hand | `docker compose exec client dig @10.53.0.53 +dnssec www.northwind-coffee.test` |
| See why validation failed | `docker compose logs resolver` |
| Web app logs | `sh scripts/web.sh logs` |

The CLI in a bit more detail:

```sh
sh scripts/dnssecretary.sh check northwind-coffee.test            # the report, in plain language
sh scripts/dnssecretary.sh check --json northwind-coffee.test     # the same as JSON
sh scripts/dnssecretary.sh fix northwind-coffee.test              # the plan and the fix script (changes nothing)
sh scripts/dnssecretary.sh verify northwind-coffee.test           # no critical error, and the resolver answers with AD
sh scripts/dnssecretary.sh catalog                                # the 30 catalogue errors (catalog a1: one in full)
```

Exit codes of `check`: `0` healthy, `1` only risks, `2` at least one critical error, `3` tool error.

## Stopping and cleaning up

```sh
sh scripts/web.sh stop                       # the web app
docker compose down                          # the lab (keeps keys and signed zones)
sh scripts/clean.sh                          # back to the post-clone state: deletes keys, build/, signed zones (asks first)
sh scripts/clean.sh -k                       # the same, but keeps the keys
```

## After a `git pull`

- **New zones or keys** (e.g. the hosting zones): run `sh scripts/setup.sh` again, or `keygen.sh`,
  `sign.sh` and `docker compose restart root tld auth1 auth2 auth3 resolver` by hand.
- **New Java code**: rebuild the jar, then `sh scripts/web.sh`. It always starts a fresh JVM, which a
  new jar needs.
- **New front-end code**: `cd webapp && npm ci && npm run build`. No restart needed; reload the page.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Every zone is broken (A1, "Expired signature") | Signatures last 30 days. Run `sh scripts/setup.sh` (or `sign.sh` + restart). |
| `westgate-pharmacy.test` is suddenly "Broken" | It starts with signatures that expire in 2 days, on purpose. `sh scripts/demo.sh reset` re-signs it. |
| `sign.sh`: `Permission denied` writing a file | A file was created by root (a container ran without `--user`). `sudo chown -R $(id -u):$(id -g) .` |
| `sign.sh` aborts in `dnssec-verify` | A zone source in `zones/` is broken; nothing was delivered. Fix the source and run it again. |
| `check.sh`: "The lab doesn't answer" | The lab isn't running: `docker compose up -d`. Fresh after a restart? Wait a few seconds. |
| Port 8080 is in use | Another program uses it. Stop it, or change the port in `compose.yaml` (`ports:` of `web`). |
| The web app shows "Reconnecting…" | The web app stopped: `sh scripts/web.sh logs`, then `sh scripts/web.sh`. |
| The web app hangs, or uses 100% CPU | It runs an old JVM over a rebuilt jar. Always restart with `sh scripts/web.sh` after a build. |
| "No running container for service …" (demo, execution) | The lab runs under another compose project. `docker compose ps`: the containers must be called `dnssecretary-…`. If they're `dnssec-lab-…` (before the rename): `docker compose -p dnssec-lab --profile web down`, then `setup.sh`. |
| The app can't reach the lab (`Timed out …`) | The lab's network was created before the `app`/`web` services existed: `docker compose down && docker compose up -d`. |
| `dig` gives no EDE codes | Ask the resolver directly (`dig @10.53.0.53`): Docker's DNS (127.0.0.11) strips them. |
| The web app looks unstyled (fonts) | No internet: the IBM Plex fonts come from Google Fonts. System fonts take over; nothing else changes. |
