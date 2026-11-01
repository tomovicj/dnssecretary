// tour.mjs – the demo video, under a minute: a browser goes through the web app while one customer
// zone of the lab hosting breaks: found, fix verified in the sandbox, opened, executed, healthy again.
// Nobody clicks: run it with sh scripts/video.sh (the "tour" compose service, Playwright), the video
// lands in demo/out/.
//
// Environment: BASE_URL (the web app, default http://localhost:8080), OUT_DIR (default out),
// PACE (multiplies every pause; 0.3 for a quick dry run).

import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";

const BASE = process.env.BASE_URL ?? "http://localhost:8080";
const OUT = process.env.OUT_DIR ?? "out";
const PACE = Number(process.env.PACE ?? 1);
const SIZE = { width: 1440, height: 900 };

// The incident: a hosting zone (zones/hosting.list) and the catalogue error it gets.
const ZONE = "northwind-coffee.test";
const ERROR = "A1";

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
    await poll(500);
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

// A cursor drawn into the page (the video shows no real mouse), with a ring on every click.
function overlay() {
  const css = `
    #tour-cursor { position: fixed; left: 0; top: 0; z-index: 10000; pointer-events: none;
      transform: translate(720px, 450px); transition: transform .6s cubic-bezier(.3, .7, .2, 1); }
    #tour-cursor::after { content: ""; position: absolute; left: -14px; top: -14px; width: 28px; height: 28px;
      border-radius: 50%; border: 3px solid #1f5edc; opacity: 0; }
    #tour-cursor.click::after { animation: tour-ring .5s ease-out; }
    @keyframes tour-ring { from { opacity: .9; transform: scale(.4); } to { opacity: 0; transform: scale(1.6); } }`;
  const add = () => {
    if (document.getElementById("tour-cursor")) return;
    const style = document.createElement("style");
    style.textContent = css;
    const cursor = document.createElement("div");
    cursor.id = "tour-cursor";
    cursor.innerHTML = `<svg width="24" height="24" viewBox="0 0 24 24"><path d="M3 2 L3 19 L8 14.5 L11.5 22 L14.5 20.6 L11 13.2 L17.5 13.2 Z"
      fill="#14171c" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>`;
    document.head.append(style);
    document.body.append(cursor);
  };
  window.__tour = {
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

/** Moves the drawn cursor to the element (scrolled into view first) and leaves it there. */
async function pointAt(page, locator, block = "center") {
  await locator.evaluate((el, b) => el.scrollIntoView({ behavior: "smooth", block: b }), block);
  await sleep(500);
  const box = await locator.boundingBox();
  await page.evaluate(([x, y]) => window.__tour.move(x, y), [box.x + Math.min(box.width / 2, 120), box.y + box.height / 2]);
  await sleep(700);
}

async function click(page, locator) {
  await pointAt(page, locator);
  await page.evaluate(() => window.__tour.click());
  await locator.click();
}

// ---------- the hosting at its start, before the camera runs ----------

console.log(`tour: resetting the hosting on ${BASE}`);
await job(api("POST", "/api/demo/reset"));
const resetAt = new Date().toISOString();
await until("a scan of every zone after the reset", async () => {
  const rows = await api("GET", "/api/zones");
  return rows.length > 0 && rows.every((r) => r.lastScan && r.lastScan > resetAt && r.verified.state !== "RUNNING");
});

// ---------- recording ----------

await mkdir(OUT, { recursive: true });
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: SIZE, recordVideo: { dir: OUT, size: SIZE } });
await context.addInitScript(overlay);
const page = await context.newPage();
await page.goto(BASE + "/");
await page.getByRole("table").waitFor();
await page.evaluate(() => document.fonts.ready);
await sleep(2500);

// 1. the zone breaks; the scan finds it and the sandbox verifies its fix
const brokeAt = new Date().toISOString();
await job(api("POST", "/api/demo/break", { zone: ZONE, error: ERROR }));
const row = page.getByRole("row").filter({ has: page.getByRole("cell", { name: ZONE, exact: true }) });
await row.waitFor({ timeout: 120_000 });
await pointAt(page, row);
await until(`a verified fix for ${ZONE}`, async () => {
  const z = await api("GET", `/api/zones/${ZONE}`);
  return z.verified.state === "PASS" && z.verified.at > brokeAt;
});
await pointAt(page, row.locator(".badge"));
await sleep(1500);

// 2. the zone screen: the diagnosis, then the fix script
await click(page, row.getByRole("link", { name: /Open/ }));
await page.getByRole("heading", { level: 1 }).waitFor();
await sleep(2500);
await pointAt(page, page.locator(".cm-editor"));
await sleep(2500);

// 3. execute: simulated once more, run on the hosting, verified live
await click(page, page.getByRole("button", { name: "Execute on hosting" }));
await pointAt(page, page.locator(".run-pill"));
await page.locator(".run-pill.PASS", { hasText: "exec-" }).waitFor({ timeout: 180_000 });
await page.locator(".state-pill.OK").waitFor({ timeout: 60_000 });
await sleep(1000);
await pointAt(page, page.locator(".state-pill"), "start");
await sleep(2000);

// 4. back to the incident list: nothing broken
await click(page, page.locator("a.brand"));
await page.getByRole("table").waitFor();
await sleep(3000);

const video = page.video();
await context.close();
const file = `${OUT}/dnssecretary-demo.webm`;
await video.saveAs(file);
await video.delete();
await browser.close();
console.log(`tour: done, ${file}`);
