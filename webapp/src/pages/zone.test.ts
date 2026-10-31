import { describe, expect, it } from "vitest";
import type { Job } from "../api/types";
import { duration, editState, jobTitle, rfcUrl, symptom, upsertJob, verifiedText } from "./zone";

const job = (id: string, startedAt: string | undefined, extra: Partial<Job> = {}): Job => ({
  id, kind: "SIMULATION", state: "RUNNING", edited: false, script: "", startedAt, ...extra,
});

describe("zone screen logic", () => {
  it("tells generated, edited and edited-and-simulated scripts apart", () => {
    expect(editState("g", "g", null)).toEqual({ label: "Generated · unchanged", edited: false });
    expect(editState("e", "g", null)).toEqual({ label: "Edited · not simulated yet", edited: true });
    expect(editState("e", "g", "e")).toEqual({ label: "Edited · simulated", edited: true });
  });

  it("words jobs and the resolver's symptom", () => {
    expect(jobTitle(job("sim-1", undefined, { edited: true }))).toBe("Simulation · edited script");
    expect(jobTitle(job("exec-2", undefined, { kind: "EXECUTION" }))).toBe("Execution · generated script");
    expect(duration(job("sim-1", "2026-11-01T10:00:00Z", { finishedAt: "2026-11-01T10:00:18Z" }))).toBe("18 s");
    expect(duration(job("sim-1", "2026-11-01T10:00:00Z"))).toBe("");
    expect(symptom({ symptom: "SERVFAIL", ede: "7 (Signature Expired)" } as never)).toBe("SERVFAIL · EDE 7 (Signature Expired)");
    expect(symptom({ symptom: "works", ede: "none" } as never)).toBe("works");
  });

  it("words the header's verdict and links RFCs", () => {
    const now = Date.parse("2026-11-01T10:00:40Z");
    expect(verifiedText({ state: "PASS", at: "2026-11-01T10:00:00Z", jobId: "sim-1" }, now)).toBe("Fix verified in sandbox just now");
    expect(verifiedText({ state: "FAIL", at: "2026-11-01T09:58:00Z", jobId: "sim-1" }, now)).toBe("Fix failed in sandbox 3 min ago");
    expect(verifiedText({ state: "RUNNING", at: null, jobId: "sim-2" }, now)).toBe("Simulating the fix");
    expect(rfcUrl("RFC 4035 §5.3.1")).toBe("https://www.rfc-editor.org/rfc/rfc4035#section-5.3.1");
    expect(rfcUrl("RFC 8624")).toBe("https://www.rfc-editor.org/rfc/rfc8624");
    expect(rfcUrl("ISC KB 123")).toBeNull();
  });

  it("merges a job event into the list, newest first, keeping a loaded log", () => {
    const list = [job("sim-2", "2026-11-01T10:01:00Z", { log: ["a"] }), job("sim-1", "2026-11-01T10:00:00Z")];
    const next = upsertJob(list, job("sim-2", "2026-11-01T10:01:00Z", { state: "PASS" }));
    expect(next.map((j) => j.id)).toEqual(["sim-2", "sim-1"]);
    expect(next[0]).toMatchObject({ state: "PASS", log: ["a"] });
    expect(upsertJob(list, job("exec-3", undefined)).map((j) => j.id)).toEqual(["exec-3", "sim-2", "sim-1"]);
  });
});
