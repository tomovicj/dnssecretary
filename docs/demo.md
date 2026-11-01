# Demo guide

How to show DNSSECretary in 5 minutes, live or as a video, and how to prepare so nothing surprises
you on stage.

## The story

A DNS hosting has 40 customer zones, all signed with DNSSEC. Something goes wrong in one of them,
the kind of mistake that takes a domain offline for every validating resolver. DNSSECretary finds
it within seconds, says in plain language what happened and who has to fix it, has *already* tried
the fix in a sandbox copy of the hosting, and fixes the real hosting with one click.

## Before the demo (checklist)

- [ ] `sh scripts/setup.sh --check` ends with `Errors: 0` and `Ready`. **On the day** (signatures last
      30 days, and `westgate-pharmacy.test` starts with only 2 days on purpose).
- [ ] http://localhost:8080 shows **0 broken, 3 at risk, 37 healthy**, and "Fix verified in sandbox
      3 of 3". If not: **Demo panel → Reset hosting**.
- [ ] The browser is at 100% zoom, full screen, one tab. Closing extra tabs keeps the live updates quick.
- [ ] A backup: `sh scripts/video.sh` once, and keep `demo/out/dnssecretary-demo.webm` at hand.
- [ ] No internet at the venue? Everything works; only the fonts fall back to system fonts.

## Live demo, step by step

| # | Do | Say |
| --- | --- | --- |
| 1 | Show the dashboard. | "A hosting with 40 signed customer zones. DNSSECretary checks each one every 20 seconds, from the parent's DS down to what a validating resolver sees. Three are already *at risk*: they work today, but won't for long. Each one has a fix, already verified." |
| 2 | **Demo panel**: zone `northwind-coffee.test`, error **A1 · Expired signature**, **Break this zone**. Close the panel. | "The server that re-signs this customer's zone stopped. Its signatures expired." |
| 3 | The row appears at the top, **Broken**; its "Auto-fix verified" badge goes from *Simulating* to *Verified* within ~10 s. | "Validating resolvers now answer SERVFAIL: for everyone behind one, this shop and its mail are gone. Before anyone even looks, the fix has already run in a sandbox copy of the hosting, and the zone validated there." |
| 4 | **Open** the zone. | "What is wrong, what it causes, why it happened, the RFC. And who has to fix it: here, us, automatically." |
| 5 | Scroll to **1 Review the script**. | "The fix is a plain shell script: re-sign the zone from its source, check it, deliver it, reload the server. The admin can read it and edit it; an edited script is simulated again before it may run." |
| 6 | **2 Test it, then run it → Execute on hosting**. The log streams in **3 Result of the last run**. | "Execute simulates once more, runs on the real servers, then checks the zone live." |
| 7 | The header turns **Healthy**. Back to **Incidents**. | "Fixed, and verified on the live hosting." |
| 8 *(optional)* | Break `lindqvist-law.test` with **B2 · DS has a wrong hash**, open it. | "Not every fix is ours: here the parent has the wrong DS. The script publishes the right one; for a real registrar, it's the record to paste." |
| 9 *(optional)* | Break `bluefin-sushi.test` with **E1**. | "A key rollover done too fast: caches still hold the old key. The fix puts the old key back until the caches expire." |

After the demo: **Demo panel → Reset hosting**.

Good second and third incidents, because they show the range: **B2** (fixed at the parent),
**E1** (a live key rollover, detected by comparing the resolver's cache with the zone), **D1**
(a broken NSEC chain), **B6** (a zone without a DS: works, but unprotected).

## The video

```sh
sh scripts/video.sh            # about 2 min to run; the video is about 40 s
PACE=0.3 sh scripts/video.sh   # shorter pauses, for a quick check
```

It restarts the web app, resets the hosting, then a headless Chrome (Playwright, in the `tour`
container, script [`demo/tour.mjs`](../demo/tour.mjs)) goes through steps 1–7 with a drawn cursor:
`northwind-coffee.test` breaks, the fix is verified, the zone is opened and fixed. The result is
`demo/out/dnssecretary-demo.webm` (1440×900). For an MP4: `ffmpeg -i demo/out/dnssecretary-demo.webm demo.mp4`.

The first run downloads the Playwright image (about 1.5 GB).

## The demo panel and `demo.sh`

The panel calls `scripts/demo.sh` on the server; you can do the same from a terminal:

```sh
sh scripts/demo.sh break <zone> <error>   # e.g. break atlas-dental.test d1; e1 = a live key rollover
sh scripts/demo.sh reset                  # the hosting back to its start (re-signs everything, ~10 s)
```

18 errors can be put on any hosting zone: those marked `hosting: true` in
[`catalog/catalog.yaml`](../catalog/catalog.yaml), the ones that need nothing but the zone's own
keys. See [The lab](lab.md#the-demo-hosting) for how they are made.
