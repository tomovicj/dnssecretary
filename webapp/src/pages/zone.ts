import type { CatalogEntry, Diagnosis, Finding, Job, JobState } from "../api/types";

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
