import type { Backend, ConnectionStatus } from "./client";
import type {
  CatalogEntry,
  Diagnosis,
  Issue,
  Job,
  Scenario,
  ServerEvent,
  Status,
  Verified,
  VerifyResult,
  ZoneDetail,
  ZoneRow,
} from "./types";

// A backend without a server, for "npm run mock" and the tests: the demo hosting's 40 zones,
// a few of them broken, and jobs that finish after a moment. Same shapes as the real API.
// A script that contains "BAD" fails in the mock sandbox; everything else passes.

const MIN = 60_000;
const DAY = 24 * 60 * MIN;

/** The catalogue entries the mock's broken zones use (the real API has all 30). */
const CATALOG: CatalogEntry[] = [
  ["A1", "Expired signature", "ZONE", "CRITICAL", "SERVFAIL", "7 (Signature Expired)", "Re-signing stopped (cron job, server down, forgotten).", "Re-sign the zone with new dates.", ["RFC 4035 §5.3.1"]],
  ["A6", "Signature expires soon (too little time to fix it)", "ZONE", "RISK", "works, for now", "none", "Bad policy: signatures are short-lived or refreshed too late.", "Re-sign now and refresh signatures well before they expire.", ["RFC 6781 §4.4.2"]],
  ["B2", "DS has a wrong hash (digest)", "PARENT", "CRITICAL", "SERVFAIL", "6 or 9", "A mistake when copying the DS to the registrar.", "Generate the correct DS + steps for the registrar.", ["RFC 4034 §5.1.4", "RFC 4035 §5.2"]],
  ["B7", "DS uses SHA-1 (digest type 1)", "PARENT", "RISK", "works", "none", "Old registrar defaults.", "Publish a SHA-256 DS and remove the SHA-1 one.", ["RFC 8624 §3.3"]],
  ["D1", "Broken NSEC/NSEC3 chain", "ZONE", "CRITICAL", "SERVFAIL for names that don't exist", "12 (NSEC Missing) or 6", "A signed zone edited by hand, or a bug in the tool.", "Re-sign the zone (a new chain).", ["RFC 4034 §4", "RFC 4035 §3.1.3"]],
  ["D4", "NSEC3 settings against RFC 9276", "ZONE", "RISK", "NXDOMAIN without AD (Unbound)", "none", "Old settings: iterations > 0, a non-empty salt, unnecessary opt-out.", "Re-sign with iterations=0, no salt, no opt-out.", ["RFC 9276"]],
  ["E1", "ZSK replaced too quickly (the new key was not pre-published)", "ZONE", "CRITICAL", "SERVFAIL", "9 or 6", "The new ZSK signed before resolvers had it in their cache.", "Put the old key back into the DNSKEY set until the TTL expires; then follow the correct order.", ["RFC 6781 §4.1.1"]],
].map(([id, title, side, severity, symptom, ede, cause, fix, rfc]) => ({
  id: id as string,
  title: title as string,
  group: "",
  mvp: true,
  severity: severity as CatalogEntry["severity"],
  side: side as CatalogEntry["side"],
  symptom: symptom as string,
  ede: ede as string,
  cause: cause as string,
  detection: "",
  fix: fix as string,
  recipe: "",
  rfc: rfc as string[],
  lab: { zone: "", probe: "www A", setup: null },
}));

const ISSUES: Record<string, Issue> = Object.fromEntries(
  CATALOG.map((e) => [e.id, { id: e.id, title: e.title, severity: e.severity, side: e.side }]),
);

// The same names as zones/hosting.list.
const ZONES = [
  "northwind-coffee.test", "lindqvist-law.test", "bluefin-sushi.test", "atlas-dental.test", "harbor-books.test",
  "copperleaf-studio.test", "greenline-bikes.test", "maple-veterinary.test", "summit-climbing.test",
  "orchard-bakery.test", "riverside-clinic.test", "pixelforge-games.test", "silverbirch-hotel.test",
  "tidewater-marina.test", "ironbridge-garage.test", "lumen-optics.test", "foxglove-florist.test",
  "quarry-architects.test", "saltmarsh-brewery.test", "kestrel-accounting.test", "nordic-knitwear.test",
  "oakmont-school.test", "papercrane-print.test", "redwood-roofing.test", "stonepath-yoga.test",
  "sunflower-daycare.test", "velvet-records.test", "westgate-pharmacy.test", "wildberry-market.test",
  "zephyr-travel.test", "amber-jewellery.test", "bramble-cafe.test", "cobalt-it.test", "driftwood-surf.test",
  "ember-pizza.test", "falcon-logistics.test", "granite-legal.test", "hazel-photography.test", "indigo-salon.test",
  "juniper-gardens.test",
];

/** zone → [issue, broken since (min ago), next expiry (days from now), verified]. The last three zones are the hosting's start state (zones/hosting.list). */
const BROKEN: Record<string, [string, number, number, Verified["state"]]> = {
  "bluefin-sushi.test": ["E1", 0, 30, "RUNNING"],
  "northwind-coffee.test": ["A1", 2, -7, "PASS"],
  "lindqvist-law.test": ["B2", 6, 29, "PASS"],
  "pixelforge-games.test": ["D1", 14, 29, "FAIL"],
  "westgate-pharmacy.test": ["A6", 18, 2, "PASS"],
  "granite-legal.test": ["B7", 60, 29, "PASS"],
  "oakmont-school.test": ["D4", 61, 29, "NONE"],
};

const iso = (ms: number) => new Date(ms).toISOString();

function script(zone: string, id: string): string {
  return `#!/bin/sh
# DNSSECretary fix for ${zone}.
# Runs in /lab with "sh -eu"; reload and flush come from scripts/fixenv/.
ZONE=${zone}
WORK=build/dnssecretary/$ZONE

# [${id}] ${ISSUES[id]?.title ?? id}
# Fix: Re-sign ${zone}. from its source with the active keys (fixes ${id})
mkdir -p "$WORK"
dnssec-signzone -S -K keys -d "$WORK" -s now-3600 -e now+2592000 -N unixtime \\
    -o "$ZONE" -f "$WORK/$ZONE.zone.signed" zones/$ZONE.zone
dnssec-verify -q -o "$ZONE" "$WORK/$ZONE.zone.signed"
cp "$WORK/$ZONE.zone.signed" auth1/

# Load the changes on the servers and drop the resolver's cached answers.
reload auth1
flush "$ZONE"
`;
}

function verifyResult(zone: string, ok: boolean, remaining: string[]): VerifyResult {
  const answer = (qname: string, qtype: string, rcode: string) => ({ qname, qtype, rcode, ad: ok, ok });
  return {
    zone,
    checksOk: ok,
    remaining: ok ? [] : remaining,
    answers: [
      answer(`www.${zone}`, "A", ok ? "NOERROR" : "SERVFAIL"),
      answer(zone, "NS", ok ? "NOERROR" : "SERVFAIL"),
      answer(`dnssecretary-1a2b3c4d.${zone}`, "A", ok ? "NXDOMAIN" : "SERVFAIL"),
    ],
    ok,
  };
}

function diagnoses(r: ZoneRow): Diagnosis[] {
  return r.issues.map((i) => ({
    id: i.id,
    severity: i.severity,
    side: i.side,
    rootCause: CATALOG.find((e) => e.id === i.id)?.cause ?? "",
    findings: ["SOA", "NS", "A"].map((type, n) => ({
      id: i.id,
      severity: i.severity,
      owner: n === 2 ? `www.${r.zone}` : r.zone,
      type,
      evidence: i.id === "A1" ? "RRSIG expired 2026-10-24T11:28:19Z." : `${i.title}.`,
      facts: (i.id === "A1" ? { expiration: "2026-10-24T11:28:19Z", keyTag: "48281" } : { nameserver: "10.53.0.11" }) as Record<string, string>,
    })),
  }));
}

interface MockOptions {
  /** How long a mock job runs (ms). */
  jobMs?: number;
  /** The first state of the zones; default: the fixture above. */
  rows?: ZoneRow[];
}

export function mockRows(now = Date.now()): ZoneRow[] {
  return ZONES.map((name) => {
    const b = BROKEN[name];
    const issue = b ? ISSUES[b[0]] : undefined;
    const status: Status = !issue ? "OK" : issue.severity === "CRITICAL" ? "CRITICAL" : "RISK";
    return {
      zone: `${name}.`,
      status,
      issues: issue ? [issue] : [],
      brokenSince: b ? iso(now - b[1] * MIN) : null,
      lastScan: iso(now - 12_000),
      nextExpiry: iso(now + (b ? b[2] : 30) * DAY),
      verified: { state: b ? b[3] : "NONE", at: b && b[3] !== "RUNNING" ? iso(now - b[1] * MIN) : null, jobId: null },
    };
  });
}

export function mockBackend(options: MockOptions = {}): Backend {
  const jobMs = options.jobMs ?? 1500;
  const rows = new Map((options.rows ?? mockRows()).map((r) => [r.zone, r]));
  const jobs = new Map<string, Job>();
  const lastSimulated = new Map<string, string>();
  const listeners = new Set<(e: ServerEvent) => void>();
  let ids = 0;

  const emit = (e: ServerEvent) => listeners.forEach((l) => l(e));
  const key = (zone: string) => (zone.endsWith(".") ? zone : `${zone}.`);
  const row = (zone: string): ZoneRow => {
    const r = rows.get(key(zone));
    if (!r) throw Object.assign(new Error(`Unknown zone ${key(zone)}`), { status: 404 });
    return r;
  };
  const put = (r: ZoneRow) => {
    rows.set(r.zone, r);
    emit({ type: "zone", data: r });
  };
  const generated = (r: ZoneRow) => (r.issues.length ? script(r.zone.replace(/\.$/, ""), r.issues[0].id) : "");
  const view = (j: Job): Job => ({ ...j, log: undefined });

  /**
   * A job: logs a few lines, ends with `pass` (and the verify result), then calls `after`.
   * `prefix` lines come first (an execution's simulation).
   */
  function run(kind: Job["kind"], zone: string | undefined, text: string, pass: boolean,
               opts: { lines?: string[]; error?: string; after?: () => void } = {}): Job {
    const prefix = kind === "SIMULATION" ? "sim" : kind === "EXECUTION" ? "exec" : "demo";
    const r = zone ? rows.get(zone) : undefined;
    const job: Job = {
      id: `${prefix}-${++ids}`, kind, zone, state: "RUNNING", script: text, startedAt: iso(Date.now()), log: [],
      edited: kind !== "DEMO" && !!r && text !== generated(r),
    };
    jobs.set(job.id, job);
    emit({ type: "job", data: view(job) });
    const line = (l: string) => {
      job.log!.push(l);
      emit({ type: "log", data: { jobId: job.id, line: l } });
    };
    const lines = opts.lines ?? (kind === "DEMO" ? [`$ ${text}`] : ["$ sh -eu fix.sh", pass ? "fix script exit: 0" : "fix script exit: 1"]);
    lines.forEach((l, n) => setTimeout(() => line(l), ((n + 1) * jobMs) / (lines.length + 1)));
    setTimeout(() => {
      Object.assign(job, {
        state: pass ? "PASS" : "FAIL",
        finishedAt: iso(Date.now()),
        verify: zone && kind !== "DEMO" ? verifyResult(zone, pass, r?.issues.map((i) => i.id) ?? []) : undefined,
        error: opts.error,
      });
      emit({ type: "job", data: view(job) });
      opts.after?.();
    }, jobMs);
    return view(job);
  }

  return {
    zones: async () => [...rows.values()],
    zone: async (zone): Promise<ZoneDetail> => {
      const r = row(zone);
      const zj = [...jobs.values()].filter((j) => j.zone === r.zone).reverse().map(view);
      return {
        ...r,
        report: { zone: r.zone, collectedAt: r.lastScan, checkedAt: r.lastScan, status: r.status, diagnoses: diagnoses(r), warnings: [], failures: [] },
        script: { generated: generated(r), lastSimulated: lastSimulated.get(r.zone) ?? null },
        jobs: zj,
      };
    },
    jobs: async () => [...jobs.values()].reverse().map(view),
    job: async (id) => {
      const j = jobs.get(id);
      if (!j) throw new Error(`No job ${id}`);
      return { ...j, log: [...(j.log ?? [])] };
    },
    catalog: async () => CATALOG,
    simulate: async (zone, text) => {
      const r = row(zone);
      const s = text ?? generated(r);
      const pass = !s.includes("BAD");
      const job = run("SIMULATION", r.zone, s, pass, {
        after: () => {
          lastSimulated.set(r.zone, s);
          put({ ...row(zone), verified: { state: pass ? "PASS" : "FAIL", at: iso(Date.now()), jobId: job.id } });
        },
      });
      put({ ...r, verified: { state: "RUNNING", at: null, jobId: job.id } });
      return job;
    },
    execute: async (zone, text, simulateFirst = true) => {
      const r = row(zone);
      const s = text ?? generated(r);
      const pass = !s.includes("BAD");
      if (simulateFirst && !pass) {
        return run("EXECUTION", r.zone, s, false, {
          lines: ["simulating this script in the sandbox first", "[sandbox] fix script exit: 1"],
          error: "the simulation did not pass, nothing was executed",
        });
      }
      const lines = [
        ...(simulateFirst ? ["simulating this script in the sandbox first", "[sandbox] fix script exit: 0", "simulation passed; executing on the lab"] : []),
        "reload auth1: restarted", `flush ${r.zone.replace(/\.$/, "")}: done`, pass ? "fix script exit: 0" : "fix script exit: 1",
      ];
      return run("EXECUTION", r.zone, s, pass, {
        lines,
        after: () => pass && put({ ...row(zone), status: "OK", issues: [], brokenSince: null, verified: { state: "NONE", at: null, jobId: null } }),
      });
    },
    scan: async () => {},
    scenarios: async (): Promise<Scenario[]> => CATALOG.map(({ id, title }) => ({ id, title })),
    demoBreak: async (zone, error) =>
      run("DEMO", key(zone), `sh scripts/demo.sh break ${zone.replace(/\.$/, "")} ${error}`, true, {
        after: () => {
          const issue = ISSUES[error] ?? ISSUES.A1;
          put({ ...row(zone), status: issue.severity, issues: [issue], brokenSince: iso(Date.now()), verified: { state: "PASS", at: iso(Date.now()), jobId: null } });
        },
      }),
    demoReset: async () => run("DEMO", undefined, "sh scripts/demo.sh reset", true, { after: () => mockRows().forEach(put) }),
    subscribe(onEvent, onStatus: (s: ConnectionStatus) => void) {
      listeners.add(onEvent);
      setTimeout(() => onStatus("open"), 0);
      // the simulation that runs at the start finishes after a moment
      const t = setTimeout(() => {
        const e1 = rows.get("bluefin-sushi.test.");
        if (e1 && e1.verified.state === "RUNNING") put({ ...e1, verified: { state: "PASS", at: iso(Date.now()), jobId: "sim-0" } });
      }, jobMs * 2);
      return () => {
        listeners.delete(onEvent);
        clearTimeout(t);
      };
    },
  };
}
