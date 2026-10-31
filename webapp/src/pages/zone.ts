import type { CatalogEntry, Diagnosis, Finding, Job, JobState, Side, Verified } from "../api/types";
import { ago } from "./dashboard";

// The zone screen's logic, without React (design: canvas "DNSSECretary – web app dizajn", artboard Zone).

/** The badge over the editor: is the script the generated one, and was this exact text simulated? */
export function editState(
  current: string,
  generated: string,
  lastSimulated: string | null,
): { label: string; edited: boolean } {
  if (current === generated) return { label: "Generated · unchanged", edited: false };
  if (current === lastSimulated) return { label: "Edited · simulated", edited: true };
  return { label: "Edited · not simulated yet", edited: true };
}

/** "13 findings" / "1 finding" */
export function findingsCount(d: Diagnosis): string {
  return `${d.findings.length} ${d.findings.length === 1 ? "finding" : "findings"}`;
}

/** The example finding's place: "a1-expired.test. SOA" */
export function where(f: Finding): string {
  return `${f.owner} ${f.type}`;
}

/** What the resolver does: "SERVFAIL · EDE 7 (Signature Expired)" */
export function symptom(e: CatalogEntry | undefined): string {
  if (!e) return "";
  return e.ede && e.ede !== "none" ? `${e.symptom} · EDE ${e.ede}` : e.symptom;
}

export const JOB_STATE: Record<JobState, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  PASS: "Passed",
  FAIL: "Failed",
  ERROR: "Error",
};

/** "Simulation · generated script", "Execution · edited script", "Demo action" */
export function jobTitle(j: Job): string {
  if (j.kind === "DEMO") return "Demo action";
  return `${j.kind === "SIMULATION" ? "Simulation" : "Execution"} · ${j.edited ? "edited" : "generated"} script`;
}

/** "18 s", "2 min"; "" while it runs */
export function duration(j: Job): string {
  if (!j.startedAt || !j.finishedAt) return "";
  const s = Math.max(0, Math.round((Date.parse(j.finishedAt) - Date.parse(j.startedAt)) / 1000));
  return s < 120 ? `${s} s` : `${Math.round(s / 60)} min`;
}

/** A job in the list, replaced by a newer copy of itself (from an SSE event), newest first. */
export function upsertJob(jobs: Job[], j: Job): Job[] {
  const old = jobs.find((x) => x.id === j.id);
  const merged = old ? { ...old, ...j, log: old.log ?? j.log } : j;
  return [merged, ...jobs.filter((x) => x.id !== j.id)].sort((a, b) =>
    (b.startedAt ?? "9").localeCompare(a.startedAt ?? "9") || b.id.localeCompare(a.id),
  );
}

/** "fixed by the zone (automatic)" in the zone's header */
export const FIXED_BY: Record<Side, string> = {
  ZONE: "the zone (automatic)",
  PARENT: "the parent (the DS at the registrar)",
  OPERATOR: "the server operator",
};

/** The zone header's verdict: "Fix verified in sandbox 40 s ago" */
export function verifiedText(v: Verified, now: number): string {
  const when = v.at ? ` ${ago(v.at, now)}` : "";
  switch (v.state) {
    case "PASS":
      return `Fix verified in sandbox${when}`;
    case "FAIL":
      return `Fix failed in sandbox${when}`;
    case "RUNNING":
      return "Simulating the fix";
    default:
      return "No fix script yet";
  }
}

/** "RFC 4035 §5.3.1" → https://www.rfc-editor.org/rfc/rfc4035#section-5.3.1 (null if it isn't an RFC) */
export function rfcUrl(ref: string): string | null {
  const m = /^RFC (\d+)(?: §([\d.]+))?/.exec(ref);
  if (!m) return null;
  return `https://www.rfc-editor.org/rfc/rfc${m[1]}${m[2] ? `#section-${m[2]}` : ""}`;
}
