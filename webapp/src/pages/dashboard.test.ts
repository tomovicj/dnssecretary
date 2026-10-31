import { describe, expect, it } from "vitest";
import { mockRows } from "../api/mock";
import { zonePath } from "../api/client";
import { ago, counts, elapsed, expiry, lastScan, sortRows, visibleRows } from "./dashboard";

const NOW = Date.parse("2026-11-01T10:00:00Z");
const rows = mockRows(NOW);

describe("dashboard logic", () => {
  it("puts broken zones first, the newest problem first, healthy last", () => {
    const sorted = sortRows(rows);
    expect(sorted.slice(0, 4).map((r) => r.zone)).toEqual([
      "e1-rollover.test.",
      "a1-expired.test.",
      "b1-ds-stale.test.",
      "f1-stale-secondary.test.",
    ]);
    expect(sorted[4].status).toBe("RISK");
    expect(sorted.at(-1)!.status).toBe("OK");
  });

  it("counts states and the sandbox verdicts of zones with a problem", () => {
    expect(counts(rows)).toEqual({
      all: 31, broken: 4, risk: 3, healthy: 24, withProblem: 7, verified: 4, running: 1, failed: 1, noScript: 1,
    });
  });

  it("folds healthy zones away unless asked, and filters and searches", () => {
    expect(visibleRows(rows, "all", "", false)).toHaveLength(7);
    expect(visibleRows(rows, "all", "", true)).toHaveLength(31);
    expect(visibleRows(rows, "risk", "", false).map((r) => r.zone)).toEqual([
      "a6-expiring.test.", "b7-ds-sha1.test.", "c3-rsa1024.test.",
    ]);
    expect(visibleRows(rows, "healthy", "", false)).toHaveLength(24);
    expect(visibleRows(rows, "all", " A1-EXP ", false).map((r) => r.zone)).toEqual(["a1-expired.test."]);
  });

  it("words times like the design", () => {
    expect(ago(null, NOW)).toBe("");
    expect(ago(new Date(NOW - 20_000).toISOString(), NOW)).toBe("just now");
    expect(ago(new Date(NOW - 2 * 60_000).toISOString(), NOW)).toBe("2 min ago");
    expect(ago(new Date(NOW - 3 * 3600_000).toISOString(), NOW)).toBe("3 h ago");
    expect(elapsed(new Date(NOW - 12_000).toISOString(), NOW)).toBe("12 s ago");
  });

  it("warns about signatures that expire within 7 days or already did", () => {
    const day = 24 * 3600_000;
    expect(expiry(new Date(NOW + 29 * day).toISOString(), NOW)).toEqual({ text: "in 29 d", warn: false });
    expect(expiry(new Date(NOW + 2 * day).toISOString(), NOW)).toEqual({ text: "in 2 d", warn: true });
    expect(expiry(new Date(NOW - 7 * day).toISOString(), NOW)).toEqual({ text: "expired 7 d ago", warn: true });
    expect(expiry(null, NOW)).toEqual({ text: "no signatures", warn: false });
  });

  it("finds the newest scan, and writes zones in URLs without the dot", () => {
    expect(lastScan(rows)).toBe(new Date(NOW - 12_000).toISOString());
    expect(zonePath("a1-expired.test.")).toBe("a1-expired.test");
  });
});
