import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import type { CatalogEntry, Diagnosis, Job, ZoneDetail } from "../api/types";
import { StateLabel, VerifiedBadge } from "../components/Badges";
import { ScriptDiff, ScriptEditor } from "../components/ScriptEditor";
import { SIDE_LABEL, ago, shortZone } from "./dashboard";
import { JOB_STATE, duration, editState, findingsCount, jobTitle, symptom, upsertJob, where } from "./zone";

/** The catalogue, loaded once for every zone screen. */
let catalogOnce: Promise<Map<string, CatalogEntry>> | null = null;
function useCatalog(): Map<string, CatalogEntry> {
  const [catalog, setCatalog] = useState<Map<string, CatalogEntry>>(new Map());
  useEffect(() => {
    catalogOnce ??= api.catalog().then((es) => new Map(es.map((e) => [e.id, e])));
    catalogOnce.then(setCatalog, () => (catalogOnce = null));
  }, []);
  return catalog;
}

/**
 * One zone: what is wrong (left), the fix script the admin can edit, simulate and execute
 * (right), the last run's live log and verify result, and the zone's history.
 */
export function ZonePage() {
  const { zone = "" } = useParams();
  const catalog = useCatalog();
  const [detail, setDetail] = useState<ZoneDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [script, setScript] = useState("");
  const [view, setView] = useState<"script" | "diff">("script");
  const [skipSimulation, setSkipSimulation] = useState(false);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [current, setCurrent] = useState<Job | null>(null);
  const [output, setOutput] = useState<"log" | "verify">("log");
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  // the SSE callbacks read these without re-subscribing
  const currentId = useRef<string | null>(null);
  currentId.current = current?.id ?? null;
  const edits = useRef({ script, generated: "" });
  edits.current = { script, generated: detail?.script.generated ?? "" };

  const showJob = useCallback((id: string) => {
    api.job(id).then((j) => setCurrent(j), () => undefined);
  }, []);

  const load = useCallback(
    (first: boolean) =>
      api.zone(zone).then(
        (d) => {
          setDetail(d);
          setError(null);
          setJobs(d.jobs);
          // keep the admin's edits; follow the generated script while it is untouched
          if (first || edits.current.script === edits.current.generated) setScript(d.script.generated);
          if (first && d.jobs[0]) showJob(d.jobs[0].id);
        },
        (e: Error) => setError(e.message),
      ),
    [zone, showJob],
  );

  useEffect(() => {
    setDetail(null);
    setCurrent(null);
    load(true);
    const tick = setInterval(() => setNow(Date.now()), 5000);
    const stop = api.subscribe(
      (e) => {
        if (e.type === "zone" && e.data.zone === `${zone}.`) {
          load(false); // the report and the script may have changed too
        } else if (e.type === "job" && e.data.zone === `${zone}.`) {
          setJobs((js) => upsertJob(js, e.data));
          if (e.data.id === currentId.current) setCurrent((c) => (c ? { ...c, ...e.data, log: c.log } : c));
        } else if (e.type === "log" && e.data.jobId === currentId.current) {
          setCurrent((c) => (c ? { ...c, log: [...(c.log ?? []), e.data.line] } : c));
        }
      },
      () => undefined,
    );
    return () => {
      clearInterval(tick);
      stop();
    };
  }, [zone, load]);

  function start(request: Promise<Job>) {
    setBusy(true);
    setError(null);
    request
      .then((j) => {
        setCurrent({ ...j, log: [] });
        setJobs((js) => upsertJob(js, j));
        setOutput("log");
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  }

  function execute() {
    if (skipSimulation && !window.confirm("Run this script on the hosting without simulating it first?")) return;
    start(api.execute(zone, script, !skipSimulation));
  }

  if (!detail) {
    return (
      <main className="page">
        <BackLink />
        {error ? (
          <div className="alert" role="alert">
            {error}
          </div>
        ) : (
          <p className="muted">Loading {zone}…</p>
        )}
      </main>
    );
  }

  const generated = detail.script.generated;
  const state = editState(script, generated, detail.script.lastSimulated);
  const side = detail.issues[0] ? SIDE_LABEL[detail.issues[0].side].toLowerCase() : "";

  return (
    <main className="page">
      <BackLink />

      <div className="zone-head">
        <StateLabel status={detail.status} />
        <h1 className="mono">{shortZone(detail.zone)}</h1>
        <span className="muted">
          {detail.status === "OK" ? "no problem in the last scan" : `broken since ${ago(detail.brokenSince, now)} · who fixes: ${side}`}
        </span>
        <div className="spacer" />
        {detail.status !== "OK" && <VerifiedBadge verified={detail.verified} />}
      </div>

      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}

      <div className="zone-layout">
        <section aria-label="Diagnosis" className="zone-left">
          {detail.report.diagnoses.length === 0 && (
            <article className="card panel">
              <h2>No problem found</h2>
              <p className="muted">The last scan found no error in this zone.</p>
            </article>
          )}
          {detail.report.diagnoses.map((d) => (
            <DiagnosisCard key={d.id} d={d} entry={catalog.get(d.id)} />
          ))}
          <History jobs={jobs} currentId={current?.id ?? null} onShow={showJob} now={now} />
        </section>

        <section aria-label="Fix" className="card zone-right">
          {generated === "" ? (
            <div className="panel">
              <h2>No fix script</h2>
              <p className="muted">
                {detail.status === "OK"
                  ? "Nothing to fix."
                  : "There is no automatic fix for these errors; the catalogue's steps are on the left."}
              </p>
            </div>
          ) : (
            <>
              <div className="fix-head">
                <h2>Proposed fix</h2>
                <span className="muted small">shell script · runs in /lab with ZONE set</span>
                <div className="spacer" />
                <span className={`edit-badge${state.edited ? " edited" : ""}`}>{state.label}</span>
              </div>

              <div className="tabs" role="tablist" aria-label="Script view">
                <button type="button" role="tab" aria-selected={view === "script"} onClick={() => setView("script")}>
                  Script
                </button>
                <button type="button" role="tab" aria-selected={view === "diff"} onClick={() => setView("diff")}>
                  Changes vs generated
                </button>
                <div className="spacer" />
                {state.edited && (
                  <button type="button" className="link-button" onClick={() => setScript(generated)}>
                    Reset to generated
                  </button>
                )}
              </div>

              <div className="fix-body">
                {view === "script" ? (
                  <ScriptEditor value={script} onChange={setScript} label="Fix script" />
                ) : state.edited ? (
                  <ScriptDiff original={generated} current={script} label="Changes against the generated script" />
                ) : (
                  <p className="muted">No changes: this is the generated script.</p>
                )}
              </div>

              <div className="fix-actions">
                <button type="button" className="button big" disabled={busy} onClick={() => start(api.simulate(zone, script))}>
                  Simulate
                </button>
                <button type="button" className="button big primary" disabled={busy} onClick={execute}>
                  Execute on hosting
                </button>
                <span className="check">
                  <input
                    id="skip-sim"
                    type="checkbox"
                    checked={skipSimulation}
                    onChange={(e) => setSkipSimulation(e.target.checked)}
                  />
                  <label htmlFor="skip-sim">Skip the simulation (asks to confirm)</label>
                </span>
                <p className="muted small hint">
                  {skipSimulation
                    ? "Execute runs this script on the hosting right away. Afterwards the zone is checked live again."
                    : "Execute first simulates exactly this script and runs it on the hosting only if the simulation passes. Afterwards the zone is checked live again."}
                </p>
              </div>
            </>
          )}

          {current && <RunPanel job={current} output={output} setOutput={setOutput} />}
        </section>
      </div>
    </main>
  );
}

function BackLink() {
  return (
    <Link to="/" className="back">
      <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
        <path d="M9 3 L5 7 L9 11" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      Incidents
    </Link>
  );
}

function DiagnosisCard({ d, entry }: { d: Diagnosis; entry?: CatalogEntry }) {
  const first = d.findings[0];
  const facts = first ? Object.entries(first.facts) : [];
  return (
    <article className="card panel diagnosis">
      <div className="diagnosis-head">
        <span className="issue-id">{d.id}</span>
        <h2>{entry?.title ?? d.id}</h2>
        <span className={`severity ${d.severity}`}>{d.severity}</span>
      </div>
      {first && (
        <div className="field">
          <h3>What we found</h3>
          <p>
            {findingsCount(d)}. Example: <code>{where(first)}</code>
            {first.evidence && ` – ${first.evidence}`}
          </p>
        </div>
      )}
      {facts.length > 0 && (
        <dl className="facts">
          {facts.map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd className="mono">{v}</dd>
            </div>
          ))}
        </dl>
      )}
      {entry && (
        <div className="field">
          <h3>Effect</h3>
          <p>{symptom(entry)}</p>
        </div>
      )}
      <div className="field">
        <h3>Cause</h3>
        <p>{d.rootCause}</p>
      </div>
      {entry && (
        <div className="field">
          <h3>Fix</h3>
          <p>{entry.fix}</p>
        </div>
      )}
      <p className="muted small" style={{ margin: 0 }}>
        Who fixes: {SIDE_LABEL[d.side]}
        {entry && entry.rfc.length > 0 && ` · References: ${entry.rfc.join(", ")}`}
      </p>
    </article>
  );
}

function RunPanel({ job, output, setOutput }: { job: Job; output: "log" | "verify"; setOutput: (o: "log" | "verify") => void }) {
  const logBox = useRef<HTMLPreElement>(null);
  const lines = job.log ?? [];
  useEffect(() => {
    const el = logBox.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [lines.length]);

  const running = job.state === "QUEUED" || job.state === "RUNNING";
  return (
    <div className="run" aria-live="polite">
      <div className="run-head">
        <h2>
          Last run: {job.kind === "SIMULATION" ? "simulation" : job.kind === "EXECUTION" ? "execution" : "demo"} {job.id}
        </h2>
        <span className={`job-state ${job.state}`}>
          {JOB_STATE[job.state]}
          {duration(job) && ` · ${duration(job)}`}
        </span>
        <div className="spacer" />
        <div className="chips" role="tablist" aria-label="Run output">
          <button type="button" role="tab" className="chip" aria-pressed={output === "log"} aria-selected={output === "log"} onClick={() => setOutput("log")}>
            Log
          </button>
          <button type="button" role="tab" className="chip" aria-pressed={output === "verify"} aria-selected={output === "verify"} onClick={() => setOutput("verify")}>
            Verify result
          </button>
        </div>
      </div>
      {job.error && <p className="run-error">{job.error}</p>}
      {output === "log" ? (
        <pre ref={logBox} className="code log">
          {lines.length ? lines.join("\n") : running ? "waiting for output…" : "(no output)"}
        </pre>
      ) : job.verify ? (
        <div className="verify">
          <p style={{ margin: 0 }}>
            {job.verify.ok
              ? "No critical error left. The resolver validated every sample."
              : job.verify.remaining.length
                ? `Still found: ${job.verify.remaining.join(", ")}.`
                : "The resolver does not validate the zone."}
          </p>
          <div className="table-wrap">
            <table className="answers">
              <thead>
                <tr>
                  <th scope="col">Question</th>
                  <th scope="col">Answer</th>
                  <th scope="col">AD</th>
                  <th scope="col">Result</th>
                </tr>
              </thead>
              <tbody>
                {job.verify.answers.map((a) => (
                  <tr key={`${a.qname} ${a.qtype}`}>
                    <td className="mono">
                      {a.qname} {a.qtype}
                    </td>
                    <td className="mono">{a.rcode}</td>
                    <td>{a.ad ? "yes" : "no"}</td>
                    <td className={a.ok ? "ok" : "bad"}>{a.ok ? "ok" : "FAIL"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <p className="muted">{running ? "The verify runs after the script." : "No verify: the script did not get that far."}</p>
      )}
    </div>
  );
}

function History({ jobs, currentId, onShow, now }: { jobs: Job[]; currentId: string | null; onShow: (id: string) => void; now: number }) {
  return (
    <article className="card panel">
      <h2>History</h2>
      {jobs.length === 0 ? (
        <p className="muted">No simulation or execution yet.</p>
      ) : (
        <ol className="history">
          {jobs.map((j) => (
            <li key={j.id}>
              <button type="button" className={`history-item${j.id === currentId ? " current" : ""}`} onClick={() => onShow(j.id)}>
                <span className="mono">{j.id}</span>
                <span>{jobTitle(j)}</span>
                <span className={`job-state ${j.state}`}>{JOB_STATE[j.state]}</span>
                <span className="muted small when">
                  {j.startedAt ? ago(j.startedAt, now) : "queued"}
                  {duration(j) && ` · ${duration(j)}`}
                </span>
              </button>
            </li>
          ))}
        </ol>
      )}
    </article>
  );
}
