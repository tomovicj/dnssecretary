import type { Side, Status, VerifiedState, ZoneRow } from "../api/types";

// The dashboard's logic, without React: order, filters, counters and the words for times.
// The words are the ones in the design (canvas "DNSSECretary – web app dizajn", artboard Dashboard).

export type Filter = "all" | "critical" | "risk" | "healthy";

export const STATUS_LABEL: Record<Status, string> = { CRITICAL: "Broken", RISK: "At risk", OK: "Healthy" };

export const SIDE_LABEL: Record<Side, string> = { ZONE: "Zone", PARENT: "Parent (DS)", OPERATOR: "Operator" };

export const VERIFIED_LABEL: Record<VerifiedState, string> = {
  PASS: "Verified",
  FAIL: "Failed",
  RUNNING: "Simulating",
  NONE: "No script yet",
};

const RANK: Record<Status, number> = { CRITICAL: 0, RISK: 1, OK: 2 };

/**
 * Broken first, then at risk, then healthy; inside each, the newest problem first (to the minute:
 * zones that broke in the same scan differ only in milliseconds), then by name.
 */
export function sortRows(rows: ZoneRow[]): ZoneRow[] {
  const minute = (r: ZoneRow) => (r.brokenSince ?? "").slice(0, 16);
  return [...rows].sort(
    (a, b) => RANK[a.status] - RANK[b.status] || minute(b).localeCompare(minute(a)) || a.zone.localeCompare(b.zone),
  );
}

export interface Counts {
  all: number;
  broken: number;
  risk: number;
  healthy: number;
  /** Zones with a problem: what "fix verified N of M" counts. */
  withProblem: number;
  verified: number;
  running: number;
  failed: number;
  noScript: number;
}

export function counts(rows: ZoneRow[]): Counts {
  const problem = rows.filter((r) => r.status !== "OK");
  const v = (s: VerifiedState) => problem.filter((r) => r.verified.state === s).length;
  return {
    all: rows.length,
    broken: rows.filter((r) => r.status === "CRITICAL").length,
    risk: rows.filter((r) => r.status === "RISK").length,
    healthy: rows.filter((r) => r.status === "OK").length,
    withProblem: problem.length,
    verified: v("PASS"),
    running: v("RUNNING"),
    failed: v("FAIL"),
    noScript: v("NONE"),
  };
}

/**
 * The rows the table shows. Healthy zones are folded into one line under the table, unless
 * the healthy filter is on or the admin opened them.
 */
export function visibleRows(rows: ZoneRow[], filter: Filter, query: string, showHealthy: boolean): ZoneRow[] {
  const q = query.trim().toLowerCase();
  return sortRows(rows).filter((r) => {
    if (q && !r.zone.toLowerCase().includes(q)) return false;
    switch (filter) {
      case "critical":
        return r.status === "CRITICAL";
      case "risk":
        return r.status === "RISK";
      case "healthy":
        return r.status === "OK";
      default:
        return r.status !== "OK" || showHealthy;
    }
  });
}

/** "a1-expired.test." → "a1-expired.test" */
export function shortZone(zone: string): string {
  return zone.replace(/\.$/, "");
}

function span(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h`;
  return `${Math.round(h / 24)} d`;
}

/** "just now", "2 min ago", "1 h ago" */
export function ago(isoTime: string | null, now: number): string {
  if (!isoTime) return "";
  const ms = now - Date.parse(isoTime);
  return ms < 60_000 ? "just now" : `${span(ms)} ago`;
}

/** "12 s ago", "3 min ago": exact, for the last scan in the header. */
export function elapsed(isoTime: string, now: number): string {
  return `${span(Math.max(0, now - Date.parse(isoTime)))} ago`;
}

/** Signature expiry: "in 29 d", "expired 7 d ago"; warn when it is gone or less than 7 days away. */
export function expiry(isoTime: string | null, now: number): { text: string; warn: boolean } {
  if (!isoTime) return { text: "no signatures", warn: false };
  const ms = Date.parse(isoTime) - now;
  if (ms <= 0) return { text: `expired ${span(-ms)} ago`, warn: true };
  return { text: `in ${span(ms)}`, warn: ms < 7 * 24 * 3600_000 };
}

/** The newest scan of any zone. */
export function lastScan(rows: ZoneRow[]): string | null {
  return rows.reduce<string | null>((max, r) => (max === null || r.lastScan > max ? r.lastScan : max), null);
}
