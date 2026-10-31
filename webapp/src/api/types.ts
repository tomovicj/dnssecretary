// The web API's JSON shapes: the same as dnssecretary/src/main/java/rs/port53/dnssecretary/web/Api.java
// (the "API contract" of the pivot plan). Instants are ISO-8601 strings; zone names end with a dot.

export type Status = "OK" | "RISK" | "CRITICAL";
export type Severity = "CRITICAL" | "RISK";
export type Side = "ZONE" | "PARENT" | "OPERATOR";
export type VerifiedState = "PASS" | "FAIL" | "RUNNING" | "NONE";
export type JobKind = "SIMULATION" | "EXECUTION" | "DEMO";
export type JobState = "QUEUED" | "RUNNING" | "PASS" | "FAIL" | "ERROR";

export interface Issue {
  id: string; // catalogue ID, e.g. "A1"
  title: string;
  severity: Severity;
  side: Side;
}

export interface Verified {
  state: VerifiedState;
  at: string | null;
  jobId: string | null;
}

/** One line of the dashboard. */
export interface ZoneRow {
  zone: string;
  status: Status;
  issues: Issue[];
  brokenSince: string | null; // the first scan with this status; null for OK
  lastScan: string;
  nextExpiry: string | null; // the earliest RRSIG expiration in the zone
  verified: Verified;
}

export interface Script {
  generated: string; // "" when there is nothing to fix automatically
  lastSimulated: string | null;
}

export interface VerifyAnswer {
  qname: string;
  qtype: string;
  rcode: string;
  ad: boolean;
  ok: boolean;
}

export interface VerifyResult {
  zone: string;
  checksOk: boolean;
  remaining: string[];
  answers: VerifyAnswer[];
  ok: boolean;
}

export interface Job {
  id: string;
  kind: JobKind;
  zone?: string;
  state: JobState;
  edited: boolean;
  script: string;
  startedAt?: string;
  finishedAt?: string;
  log?: string[]; // only from GET /api/jobs/{id}
  verify?: VerifyResult;
  error?: string;
}

/** One raw fact a check found, e.g. an expired RRSIG at "a1-expired.test. SOA". */
export interface Finding {
  id: string;
  severity: Severity;
  owner: string;
  type: string;
  evidence: string | null;
  facts: Record<string, string>;
}

/** One catalogue error found in the zone, with the findings that prove it. */
export interface Diagnosis {
  id: string;
  severity: Severity;
  side: Side;
  rootCause: string;
  findings: Finding[];
}

/** What "dnssecretary check --json" prints. */
export interface Report {
  zone: string;
  collectedAt: string;
  checkedAt: string;
  status: Status | "TOOL_ERROR";
  diagnoses: Diagnosis[];
  warnings: string[];
  failures: string[];
}

/** One error of catalog/catalog.yaml (GET /api/catalog). */
export interface CatalogEntry {
  id: string;
  title: string;
  group: string;
  mvp: boolean;
  severity: Severity;
  side: Side;
  symptom: string;
  ede: string;
  cause: string;
  detection: string;
  fix: string;
  recipe: string;
  rfc: string[];
  lab: { zone: string; probe: string; setup: string | null };
}

/** The zone screen: the row, the report as "check --json" prints it, the script and the jobs. */
export interface ZoneDetail extends ZoneRow {
  report: Report;
  script: Script;
  jobs: Job[];
}

export interface Scenario {
  zone: string;
  id: string;
  title: string;
}

/** SSE events on /api/events. */
export type ServerEvent =
  | { type: "zone"; data: ZoneRow }
  | { type: "job"; data: Job }
  | { type: "log"; data: { jobId: string; line: string } };
