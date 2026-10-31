import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { Job, Scenario } from "../api/types";
import { shortZone } from "../pages/dashboard";
import { JOB_STATE } from "../pages/zone";

const ROLLOVER = ["e1-rollover.test.", "e3-rollover.test."];

/**
 * The drawer that changes the lab hosting for the demo (scripts/demo.sh on the server):
 * break one zone, break E1/E3 by a live key rollover, heal every zone, or reset the lab.
 * Not part of the admin's story; the header's "Demo panel" opens it.
 */
export function DemoPanel({ onClose }: { onClose: () => void }) {
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [zone, setZone] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [last, setLast] = useState("");
  const [error, setError] = useState<string | null>(null);
  const jobId = useRef<string | null>(null);
  jobId.current = job?.id ?? null;
  const close = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    api.scenarios().then((s) => {
      const breakable = s.filter((x) => !ROLLOVER.includes(x.zone));
      setScenarios(breakable);
      setZone((z) => z || (breakable[0]?.zone ?? ""));
    }, (e: Error) => setError(e.message));
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
    setError(null);
    setLast("");
    request.then(setJob, (e: Error) => setError(e.message));
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
            Puts the catalogue's broken version of the zone back on the servers. It shows up in Incidents after the
            next scan.
          </p>
          <label htmlFor="break-zone" className="muted small">
            Zone
          </label>
          <select id="break-zone" value={zone} onChange={(e) => setZone(e.target.value)}>
            {scenarios.map((s) => (
              <option key={s.zone} value={s.zone}>
                {shortZone(s.zone)} · {s.id} {s.title}
              </option>
            ))}
          </select>
          <button type="button" className="button big dark" disabled={!zone || running} onClick={() => run(api.demoBreak(zone))}>
            Break this zone
          </button>
        </section>

        <section>
          <h3>Live key rollover</h3>
          <p className="muted small">Replaces a key the wrong way while the resolver still has the old one cached.</p>
          <div className="two">
            <button type="button" className="button big" disabled={running} onClick={() => run(api.demoBreak(ROLLOVER[0]))}>
              E1 · ZSK too fast
            </button>
            <button type="button" className="button big" disabled={running} onClick={() => run(api.demoBreak(ROLLOVER[1]))}>
              E3 · old key removed
            </button>
          </div>
        </section>

        <section>
          <h3>Start state</h3>
          <div className="two">
            <button type="button" className="button big" disabled={running} onClick={() => run(api.demoHeal())}>
              Heal all zones
            </button>
            <button
              type="button"
              className="button big danger"
              disabled={running}
              onClick={() => window.confirm("Reset the lab? Every catalogue zone is broken again.") && run(api.demoReset())}
            >
              Reset lab (30 broken)
            </button>
          </div>
          <p className="muted small">
            Heal: every zone signed correctly, a healthy hosting to start the demo from. Reset: <code>sign.sh</code> and a
            restart, the lab as it is after a clone.
          </p>
        </section>
      </div>

      <div className="drawer-foot" role="status">
        {error ? (
          <span className="bad">{error}</span>
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
