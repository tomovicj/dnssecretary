import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { Job, Scenario } from "../api/types";
import { shortZone } from "../pages/dashboard";
import { JOB_STATE } from "../pages/zone";

/**
 * The drawer that changes the lab hosting for the demo (scripts/demo.sh on the server):
 * break a hosting zone with one catalogue error (E1: a live key rollover), or reset the hosting
 * to its start. Not part of the admin's story; the header's "Demo panel" opens it.
 */
export function DemoPanel({ onClose }: { onClose: () => void }) {
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [zones, setZones] = useState<string[]>([]);
  const [zone, setZone] = useState("");
  const [error, setErrorId] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [last, setLast] = useState("");
  const [failure, setFailure] = useState<string | null>(null);
  const jobId = useRef<string | null>(null);
  jobId.current = job?.id ?? null;
  const close = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    api.scenarios().then((s) => {
      setScenarios(s);
      setErrorId((id) => id || (s[0]?.id ?? ""));
    }, (e: Error) => setFailure(e.message));
    api.zones().then((rows) => {
      const names = rows.map((r) => r.zone).sort();
      setZones(names);
      setZone((z) => z || (names[0] ?? ""));
    }, (e: Error) => setFailure(e.message));
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    const stop = api.subscribe(
      (e) => {
        if (e.type === "job" && e.data.id === jobId.current) setJob(e.data);
        if (e.type === "log" && e.data.jobId === jobId.current) setLast(e.data.line);
      },
      () => undefined,
    );
    return () => {
      window.removeEventListener("keydown", onKey);
      stop();
    };
  }, [onClose]);

  const running = job !== null && (job.state === "QUEUED" || job.state === "RUNNING");

  function run(request: Promise<Job>) {
    setFailure(null);
    setLast("");
    request.then(setJob, (e: Error) => setFailure(e.message));
  }

  return (
    <aside className="drawer" aria-label="Demo panel">
      <div className="drawer-head">
        <div>
          <h2>Demo panel</h2>
          <span className="muted small">Changes the lab hosting. Not part of the admin's story.</span>
        </div>
        <button ref={close} type="button" className="icon-button" aria-label="Close demo panel" onClick={onClose}>
          <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
            <path d="M3 3 L11 11 M11 3 L3 11" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
        </button>
      </div>

      <div className="drawer-body">
        <section>
          <h3>Break a zone</h3>
          <p className="muted small">
            Signs a customer's zone the way the chosen error breaks it and puts it on the servers (E1: replaces the
            ZSK while the resolver still has the old one cached). It shows up in Incidents right after.
          </p>
          <label htmlFor="break-zone" className="muted small">
            Zone
          </label>
          <select id="break-zone" value={zone} onChange={(e) => setZone(e.target.value)}>
            {zones.map((z) => (
              <option key={z} value={z}>
                {shortZone(z)}
              </option>
            ))}
          </select>
          <label htmlFor="break-error" className="muted small">
            Error
          </label>
          <select id="break-error" value={error} onChange={(e) => setErrorId(e.target.value)}>
            {scenarios.map((s) => (
              <option key={s.id} value={s.id}>
                {s.id} · {s.title}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="button big dark"
            disabled={!zone || !error || running}
            onClick={() => run(api.demoBreak(zone, error))}
          >
            Break this zone
          </button>
        </section>

        <section>
          <h3>Start state</h3>
          <button
            type="button"
            className="button big danger"
            disabled={running}
            onClick={() => window.confirm("Reset the hosting? Every zone goes back to its start.") && run(api.demoReset())}
          >
            Reset hosting
          </button>
          <p className="muted small">
            <code>sign.sh</code> and a restart: the hosting as the demo starts it (three zones at risk), keys and server
            settings as they were.
          </p>
        </section>
      </div>

      <div className="drawer-foot" role="status">
        {failure ? (
          <span className="bad">{failure}</span>
        ) : job ? (
          <span>
            <span className="mono">{job.script.replace("sh scripts/demo.sh ", "")}</span>: {JOB_STATE[job.state]}
            {running && last && <span className="muted"> · {last}</span>}
            {job.error && <span className="bad"> · {job.error}</span>}
          </span>
        ) : (
          <span className="muted">No action yet.</span>
        )}
      </div>
    </aside>
  );
}
