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

// A backend without a server, for "npm run mock" and the tests: the lab's 31 zones,
// a few of them broken, and jobs that finish after a moment. Same shapes as the real API.
// A script that contains "BAD" fails in the mock sandbox; everything else passes.

const MIN = 60_000;
const DAY = 24 * 60 * MIN;

/** The catalogue entries the mock's broken zones use (the real API has all 30). */
const CATALOG: CatalogEntry[] = [
  ["A1", "Expired signature", "ZONE", "CRITICAL", "SERVFAIL", "7 (Signature Expired)", "Re-signing stopped (cron job, server down, forgotten).", "Re-sign the zone with new dates.", ["RFC 4035 §5.3.1"]],
  ["A6", "Signature expires soon (too little time to fix it)", "ZONE", "RISK", "works, for now", "none", "Bad policy: signatures are short-lived or refreshed too late.", "Re-sign now and refresh signatures well before they expire.", ["RFC 6781 §4.4.2"]],
  ["B1", "DS points to a key that does not exist", "PARENT", "CRITICAL", "SERVFAIL", "9 (DNSKEY Missing)", "The KSK was replaced, but the DS at the parent was not.", "Publish the DS of the current KSK at the parent.", ["RFC 4035 §5.2"]],
  ["B7", "DS uses SHA-1 (digest type 1)", "PARENT", "RISK", "works", "none", "Old registrar defaults.", "Publish a SHA-256 DS and remove the SHA-1 one.", ["RFC 8624 §3.3"]],
  ["C3", "Weak RSA key (< 2048 bits)", "ZONE", "RISK", "works", "none", "Old settings.", "A new key of 2048+ bits, or a move to algorithm 13.", ["RFC 6781 §3.4.2", "RFC 8624"]],
  ["E1", "ZSK replaced too quickly (the new key was not pre-published)", "ZONE", "CRITICAL", "SERVFAIL", "9 or 6", "The new ZSK signed before resolvers had it in their cache.", "Put the old key back into the DNSKEY set until the TTL expires; then follow the correct order.", ["RFC 6781 §4.1.1"]],
  ["F1", "Secondary server has a stale zone with expired signatures", "OPERATOR", "CRITICAL", "SERVFAIL (sometimes)", "7 (Signature Expired)", "Zone transfers to the secondary stopped.", "Fix the transfer and refresh the secondary.", ["RFC 1996"]],
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
    scenarios: async (): Promise<Scenario[]> => CATALOG.filter((e) => e.id !== "F1").map(({ id, title }) => ({ id, title })),
    demoBreak: async (zone, error) =>
      run("DEMO", key(zone), `sh scripts/demo.sh break ${zone.replace(/\.$/, "")} ${error}`, true, {
        after: () => {
          const issue = ISSUES[error] ?? ISSUES.A1;
          put({ ...row(zone), status: issue.severity, issues: [issue], brokenSince: iso(Date.now()), verified: { state: "PASS", at: iso(Date.now()), jobId: null } });
        },
      }),
    demoHeal: async () =>
      run("DEMO", undefined, "sh scripts/demo.sh heal", true, {
        after: () => rows.forEach((r) => put({ ...r, status: "OK", issues: [], brokenSince: null, verified: { state: "NONE", at: null, jobId: null } })),
      }),
    demoReset: async () => run("DEMO", undefined, "sh scripts/demo.sh reset", true, { after: () => mockRows().forEach(put) }),
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
