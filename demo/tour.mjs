// tour.mjs – the demo video: a browser goes through the web app while three customer zones of the
// lab hosting break, one after another, with a caption for each step. Nobody clicks: run it with
// sh scripts/video.sh (the "tour" compose service, Playwright), the video lands in demo/out/.
//
// Environment: BASE_URL (the web app, default http://localhost:8080), OUT_DIR (default out),
// PACE (multiplies every pause; 0.3 for a quick dry run).

import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";

const BASE = process.env.BASE_URL ?? "http://localhost:8080";
const OUT = process.env.OUT_DIR ?? "out";
const PACE = Number(process.env.PACE ?? 1);
const SIZE = { width: 1440, height: 900 };

// The three incidents: a hosting zone (zones/hosting.list) and the catalogue error it gets.
const INCIDENTS = [
  {
    zone: "northwind-coffee.test",
    error: "A1",
    before: "The server that re-signs northwind-coffee.test stopped. Its signatures have expired.",
    found: "The next scan finds it. Validating resolvers now answer SERVFAIL: for them, the shop and its mail are gone.",
    fix: "The fix re-signs the zone from its source, with new dates, and reloads the server.",
  },
  {
    zone: "lindqvist-law.test",
    error: "B2",
    before: "A customer pasted a wrong DS record at the registrar for lindqvist-law.test.",
    found: "The zone itself is fine, but the chain of trust from test. is broken: SERVFAIL again.",
    fix: "This one is the parent's to fix: the script publishes the DS of the zone's real KSK. For a real registrar, it is the record to paste.",
  },
  {
    zone: "bluefin-sushi.test",
    error: "E1",
    before: "bluefin-sushi.test replaced its ZSK at once, while resolvers still cache the old DNSKEY set.",
    found: "Answers signed with the new key fail against the cached keys. DNSSECretary compares the cache with the zone.",
    fix: "The fix puts the old key back into the DNSKEY set until the cached copies expire, then the rollover can go the right way.",
  },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms * PACE));
const poll = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${await res.text()}`);
  return res.json();
}

/** Waits until check() returns something truthy. */
async function until(what, check, timeoutMs = 180_000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = await check();
    if (v) return v;
    if (Date.now() > end) throw new Error(`Timed out waiting for ${what}`);
    await poll(1000);
  }
}

/** Starts a job (a demo action) and waits for it to pass. */
async function job(start) {
  const { id } = await start;
  const j = await until(`job ${id}`, async () => {
    const j = await api("GET", `/api/jobs/${id}`);
    return j.state === "QUEUED" || j.state === "RUNNING" ? null : j;
  });
  if (j.state !== "PASS") throw new Error(`${id}: ${j.state} ${j.error ?? ""}`);
  return j;
}

// The caption bar and a cursor, drawn into the page (the video shows no real mouse).
function overlay() {
  const css = `
    #tour-caption { position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%); width: min(980px, 90vw);
      padding: 18px 28px; border-radius: 14px; background: rgba(20, 23, 28, .94); color: #fff;
      font: 500 23px/1.4 "IBM Plex Sans", system-ui, sans-serif; box-shadow: 0 12px 32px rgba(0, 0, 0, .28);
      z-index: 9999; transition: opacity .35s; opacity: 0; pointer-events: none; }
    #tour-caption.on { opacity: 1; }
    #tour-caption b { display: block; margin-bottom: 4px; font-size: 14px; font-weight: 600; letter-spacing: .08em;
      text-transform: uppercase; color: #9db7ff; }
    #tour-cursor { position: fixed; left: 0; top: 0; z-index: 10000; pointer-events: none;
      transform: translate(720px, 450px); transition: transform .8s cubic-bezier(.3, .7, .2, 1); }
    #tour-cursor::after { content: ""; position: absolute; left: -14px; top: -14px; width: 28px; height: 28px;
      border-radius: 50%; border: 3px solid #1f5edc; opacity: 0; }
    #tour-cursor.click::after { animation: tour-ring .5s ease-out; }
    @keyframes tour-ring { from { opacity: .9; transform: scale(.4); } to { opacity: 0; transform: scale(1.6); } }`;
  const add = () => {
    if (document.getElementById("tour-caption")) return;
    const style = document.createElement("style");
    style.textContent = css;
    const caption = document.createElement("div");
    caption.id = "tour-caption";
    const cursor = document.createElement("div");
    cursor.id = "tour-cursor";
    cursor.innerHTML = `<svg width="24" height="24" viewBox="0 0 24 24"><path d="M3 2 L3 19 L8 14.5 L11.5 22 L14.5 20.6 L11 13.2 L17.5 13.2 Z"
      fill="#14171c" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>`;
    document.head.append(style);
    document.body.append(caption, cursor);
  };
  window.__tour = {
    caption(label, text) {
      add();
      const c = document.getElementById("tour-caption");
      c.innerHTML = "";
      if (label) c.append(Object.assign(document.createElement("b"), { textContent: label }));
      c.append(document.createTextNode(text));
      c.classList.add("on");
    },
    hide() {
      document.getElementById("tour-caption")?.classList.remove("on");
    },
    move(x, y) {
      add();
      document.getElementById("tour-cursor").style.transform = `translate(${x}px, ${y}px)`;
    },
    click() {
      const c = document.getElementById("tour-cursor");
      c.classList.remove("click");
      void c.offsetWidth;
      c.classList.add("click");
    },
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", add);
  else add();
}

async function caption(page, label, text, ms) {
  await page.evaluate(([l, t]) => window.__tour.caption(l, t), [label, text]);
  await sleep(ms);
}

/** Moves the drawn cursor to the element (scrolled into view first) and leaves it there. */
async function pointAt(page, locator) {
  await locator.evaluate((el) => el.scrollIntoView({ behavior: "smooth", block: "center" }));
  await sleep(700);
  const box = await locator.boundingBox();
  await page.evaluate(([x, y]) => window.__tour.move(x, y), [box.x + box.width / 2, box.y + box.height / 2]);
  await sleep(900);
}

async function click(page, locator) {
  await pointAt(page, locator);
  await page.evaluate(() => window.__tour.click());
  await locator.click();
}

async function scrollTo(page, locator) {
  await locator.evaluate((el) => el.scrollIntoView({ behavior: "smooth", block: "start" }));
  await sleep(900);
}

// ---------- the hosting at its start, before the camera runs ----------

console.log(`tour: resetting the hosting on ${BASE}`);
await job(api("POST", "/api/demo/reset"));
const resetAt = new Date().toISOString();
await until("a scan of every zone after the reset", async () => {
  const rows = await api("GET", "/api/zones");
  return rows.length > 0 && rows.every((r) => r.lastScan && r.lastScan > resetAt && r.verified.state !== "RUNNING");
});
const rows = await api("GET", "/api/zones");
const atRisk = rows.filter((r) => r.status !== "OK");
console.log(`tour: ${rows.length} zones, ${atRisk.length} not healthy at the start`);

// ---------- recording ----------

await mkdir(OUT, { recursive: true });
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: SIZE, recordVideo: { dir: OUT, size: SIZE } });
await context.addInitScript(overlay);
const page = await context.newPage();
await page.goto(BASE + "/");
await page.getByRole("table").waitFor();
await page.evaluate(() => document.fonts.ready);
await sleep(1500);

await caption(page, "DNSSECretary", `A DNS hosting with ${rows.length} customer zones, every one signed with DNSSEC.`, 4500);
await pointAt(page, page.locator(".summary"));
await caption(page, "Live", "DNSSECretary checks every zone every 20 seconds: from the parent's DS down to what a validating resolver sees.", 5500);
if (atRisk.length > 0) {
  await pointAt(page, page.locator("tbody tr").first());
  await caption(page, "At risk", `${atRisk.length} zones work today, but will break or weaken later. Each already has a fix, verified in a sandbox.`, 6000);
}
await pointAt(page, page.locator(".healthy-strip"));
await caption(page, "Healthy", "The rest are healthy and stay folded away. Now, three things go wrong.", 4500);

let n = 0;
for (const inc of INCIDENTS) {
  n += 1;
  const label = `Incident ${n} of ${INCIDENTS.length}`;
  await caption(page, label, inc.before, 4500);
  const brokeAt = new Date().toISOString();
  await job(api("POST", "/api/demo/break", { zone: inc.zone, error: inc.error }));

  // the demo action rescans every zone; the row appears, then the sandbox's verdict
  const row = page.getByRole("row").filter({ has: page.getByRole("cell", { name: inc.zone, exact: true }) });
  await row.waitFor({ timeout: 120_000 });
  await pointAt(page, row);
  await caption(page, label, inc.found, 6000);
  await until(`a verified fix for ${inc.zone}`, async () => {
    const z = await api("GET", `/api/zones/${inc.zone}`);
    return z.verified.state === "PASS" && z.verified.at > brokeAt;
  });
  await pointAt(page, row.locator(".badge"));
  await caption(page, label, "Before anyone looks, the generated fix already ran in a sandbox copy of the hosting, and the zone validated: verified.", 6000);

  await click(page, row.getByRole("link", { name: /Open/ }));
  await page.getByRole("heading", { level: 1 }).waitFor();
  await sleep(800);
  await pointAt(page, page.locator("article").first().getByRole("heading", { level: 2 }));
  await caption(page, label, "The zone screen says what is wrong, what it causes and why, with the catalogue's RFC references.", 6500);

  await scrollTo(page, page.getByRole("heading", { name: "Proposed fix" }));
  await pointAt(page, page.locator(".cm-editor"));
  await caption(page, label, inc.fix, 7000);
  await caption(page, label, "It is a plain shell script: the admin can read it, edit it, and simulate the edited version first.", 5000);

  await click(page, page.getByRole("button", { name: "Execute on hosting" }));
  await caption(page, label, "Execute: one more simulation in the sandbox, then the script runs on the real servers and the zone is verified live.", 1000);
  const pill = page.locator(".run-pill");
  await pointAt(page, pill);
  await page.locator(".run-pill.PASS", { hasText: "exec-" }).waitFor({ timeout: 180_000 });
  await sleep(4000);
  await page.locator(".state-pill.OK").waitFor({ timeout: 60_000 });
  await scrollTo(page, page.getByRole("heading", { level: 1 }));
  await pointAt(page, page.locator(".state-pill"));
  await caption(page, label, `${inc.zone} is healthy again: fixed on the hosting, and verified there.`, 5000);

  await click(page, page.locator("a.brand"));
  await page.getByRole("table").waitFor();
  await sleep(1500);
}

await pointAt(page, page.locator(".summary"));
await caption(page, "DNSSECretary", "Every incident found, explained, and fixed with a script that was verified before it touched the hosting.", 7000);
await page.evaluate(() => window.__tour.hide());
await sleep(1500);

const video = page.video();
await context.close();
const file = `${OUT}/dnssecretary-demo.webm`;
await video.saveAs(file);
await video.delete();
await browser.close();
console.log(`tour: done, ${file}`);
