import type { Backend, ConnectionStatus } from "./client";
import type { Issue, Job, Scenario, ServerEvent, Status, Verified, ZoneDetail, ZoneRow } from "./types";

// A backend without a server, for "npm run mock" and the tests: the lab's 31 zones,
// a few of them broken, and jobs that finish after a moment. Same shapes as the real API.

const MIN = 60_000;
const DAY = 24 * 60 * MIN;

const ISSUES: Record<string, Issue> = {
  A1: { id: "A1", title: "Expired signature", severity: "CRITICAL", side: "ZONE" },
  A6: { id: "A6", title: "Signature expires soon (too little time to fix it)", severity: "RISK", side: "ZONE" },
  B1: { id: "B1", title: "DS points to a key that does not exist", severity: "CRITICAL", side: "PARENT" },
  B7: { id: "B7", title: "DS uses SHA-1 (digest type 1)", severity: "RISK", side: "PARENT" },
  C3: { id: "C3", title: "Weak RSA key (< 2048 bits)", severity: "RISK", side: "ZONE" },
  E1: { id: "E1", title: "ZSK replaced too quickly (the new key was not pre-published)", severity: "CRITICAL", side: "ZONE" },
  F1: { id: "F1", title: "Secondary server has a stale zone with expired signatures", severity: "CRITICAL", side: "OPERATOR" },
};

const ZONES = [
  "good.test", "a1-expired.test", "a2-notyet.test", "a3-nosig.test", "a4-bogus.test", "a5-unknown-key.test",
  "a6-expiring.test", "a7-ttl-long.test", "b1-ds-stale.test", "b2-ds-digest.test", "b3-ds-alg.test",
  "b4-ds-unsigned.test", "b5-dnskey-zsk.test", "b6-island.test", "b7-ds-sha1.test", "b8-ds-extra.test",
  "c1-rsasha1.test", "c2-two-alg.test", "c3-rsa1024.test", "d1-nsec-broken.test", "d2-bitmap.test",
  "d3-nsec-nosig.test", "d4-nsec3-iter.test", "d5-nsec3param.test", "d6-nsec-mixed.test", "e1-rollover.test",
  "e2-ksk-ds-early.test", "e3-rollover.test", "e4-cds.test", "f1-stale-secondary.test", "f2-tcp-edns.test",
];

/** zone → [issue, broken since (min ago), next expiry (days from now), verified] */
const BROKEN: Record<string, [string, number, number, Verified["state"]]> = {
  "e1-rollover.test": ["E1", 0, 30, "RUNNING"],
  "a1-expired.test": ["A1", 2, -7, "PASS"],
  "b1-ds-stale.test": ["B1", 6, 29, "PASS"],
  "f1-stale-secondary.test": ["F1", 14, 29, "FAIL"],
  "a6-expiring.test": ["A6", 18, 2, "PASS"],
  "b7-ds-sha1.test": ["B7", 60, 29, "PASS"],
  "c3-rsa1024.test": ["C3", 61, 29, "NONE"],
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

  /** A job that logs two lines and ends with `pass`, then calls `after`. */
  function run(kind: Job["kind"], zone: string | undefined, text: string, pass: boolean, after?: () => void): Job {
    const prefix = kind === "SIMULATION" ? "sim" : kind === "EXECUTION" ? "exec" : "demo";
    const job: Job = { id: `${prefix}-${++ids}`, kind, zone, state: "RUNNING", edited: false, script: text, startedAt: iso(Date.now()), log: [] };
    jobs.set(job.id, job);
    emit({ type: "job", data: job });
    const line = (l: string) => {
      job.log!.push(l);
      emit({ type: "log", data: { jobId: job.id, line: l } });
    };
    setTimeout(() => line(kind === "DEMO" ? `$ ${text}` : "$ sh -eu fix.sh"), jobMs / 3);
    setTimeout(() => {
      line(pass ? "fix script exit: 0" : "fix script exit: 1");
      Object.assign(job, { state: pass ? "PASS" : "FAIL", finishedAt: iso(Date.now()) });
      emit({ type: "job", data: job });
      after?.();
    }, jobMs);
    return job;
  }

  return {
    zones: async () => [...rows.values()],
    zone: async (zone): Promise<ZoneDetail> => {
      const r = row(zone);
      const zj = [...jobs.values()].filter((j) => j.zone === r.zone).reverse();
      return { ...r, report: { zone: r.zone, diagnoses: [] }, script: { generated: generated(r), lastSimulated: null }, jobs: zj };
    },
    jobs: async () => [...jobs.values()].reverse(),
    job: async (id) => {
      const j = jobs.get(id);
      if (!j) throw new Error(`No job ${id}`);
      return j;
    },
    simulate: async (zone, text) => {
      const r = row(zone);
      const s = text ?? generated(r);
      const pass = !s.includes("BAD");
      const job = run("SIMULATION", r.zone, s, pass, () =>
        put({ ...row(zone), verified: { state: pass ? "PASS" : "FAIL", at: iso(Date.now()), jobId: job.id } }));
      put({ ...r, verified: { state: "RUNNING", at: null, jobId: job.id } });
      return job;
    },
    execute: async (zone, text) => {
      const r = row(zone);
      return run("EXECUTION", r.zone, text ?? generated(r), true, () =>
        put({ ...row(zone), status: "OK", issues: [], brokenSince: null, verified: { state: "NONE", at: null, jobId: null } }));
    },
    scan: async () => {},
    scenarios: async (): Promise<Scenario[]> =>
      ZONES.slice(1).map((z) => ({ zone: `${z}.`, id: z.slice(0, 2).toUpperCase(), title: z })),
    demoBreak: async (zone) =>
      run("DEMO", key(zone), `sh scripts/demo.sh break ${zone}`, true, () =>
        put({ ...row(zone), status: "CRITICAL", issues: [ISSUES.A1], brokenSince: iso(Date.now()), verified: { state: "PASS", at: iso(Date.now()), jobId: null } })),
    demoHeal: async () =>
      run("DEMO", undefined, "sh scripts/demo.sh heal", true, () =>
        rows.forEach((r) => put({ ...r, status: "OK", issues: [], brokenSince: null, verified: { state: "NONE", at: null, jobId: null } }))),
    demoReset: async () => run("DEMO", undefined, "sh scripts/demo.sh reset", true, () => mockRows().forEach(put)),
    subscribe(onEvent, onStatus: (s: ConnectionStatus) => void) {
      listeners.add(onEvent);
      setTimeout(() => onStatus("open"), 0);
      // the simulation that runs at the start finishes after a moment
      const t = setTimeout(() => {
        const e1 = rows.get("e1-rollover.test.");
        if (e1 && e1.verified.state === "RUNNING") put({ ...e1, verified: { state: "PASS", at: iso(Date.now()), jobId: "sim-0" } });
      }, jobMs * 2);
      return () => {
        listeners.delete(onEvent);
        clearTimeout(t);
      };
    },
  };
}
