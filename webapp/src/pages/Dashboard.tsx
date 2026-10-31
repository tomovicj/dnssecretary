import { useState } from "react";
import { Link } from "react-router-dom";
import type { LiveZones } from "../api/useZones";
import { StateLabel, VerifiedBadge } from "../components/Badges";
import {
  SIDE_LABEL,
  ago,
  counts,
  expiry,
  shortZone,
  sortRows,
  visibleRows,
  type Filter,
} from "./dashboard";

/** The incident list: zones that are broken or about to break, with the sandbox's verdict on each fix. */
export function Dashboard({ live, now }: { live: LiveZones; now: number }) {
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
  const [showHealthy, setShowHealthy] = useState(false);

  const rows = live.rows ?? [];
  const c = counts(rows);
  const n = (v: number | string) => (live.rows === null ? "–" : v); // not "0" while loading
  const shown = visibleRows(rows, filter, query, showHealthy);
  const healthy = sortRows(rows).filter((r) => r.status === "OK");
  const folded = filter === "all" && !showHealthy && healthy.length > 0;

  const chips: [Filter, string][] = [
    ["all", `All ${c.all}`],
    ["critical", `Broken ${c.broken}`],
    ["risk", `At risk ${c.risk}`],
    ["healthy", `Healthy ${c.healthy}`],
  ];
  const verifiedNote = [
    c.running && `${c.running} running`,
    c.failed && `${c.failed} failed`,
    c.noScript && `${c.noScript} without a script`,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <main className="page">
      <div className="page-head">
        <h1>Incidents</h1>
      </div>

      {live.error && (
        <div className="alert" role="alert">
          Can't load the zones: {live.error}
        </div>
      )}

      <section className="summary" aria-label="Summary">
        <Stat label="Broken" value={n(c.broken)} color="var(--critical)" note="validating resolvers return SERVFAIL" />
        <Stat label="At risk" value={n(c.risk)} color="var(--risk)" note="works today, breaks or weakens later" />
        <Stat label="Healthy" value={n(c.healthy)} color="var(--ok)" note="no finding in the last scan" />
        <Stat
          label="Fix verified in sandbox"
          value={n(`${c.verified} of ${c.withProblem}`)}
          color="var(--accent-ink)"
          note={live.rows === null ? "loading…" : verifiedNote || (c.withProblem ? "every fix passed" : "nothing to fix")}
        />
      </section>

      <section className="card" aria-label="Incident list">
        <div className="toolbar">
          <div className="chips" role="group" aria-label="Filter by state">
            {chips.map(([f, label]) => (
              <button key={f} type="button" className="chip" aria-pressed={filter === f} onClick={() => setFilter(f)}>
                {label}
              </button>
            ))}
          </div>
          <div className="spacer" />
          <label htmlFor="zone-search" className="visually-hidden">
            Search zones
          </label>
          <input
            id="zone-search"
            className="search"
            type="search"
            placeholder="Search zones"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

        <div className="table-wrap">
          <table className="incidents">
            <thead>
              <tr>
                <th scope="col">State</th>
                <th scope="col">Zone</th>
                <th scope="col">Problem</th>
                <th scope="col">Who fixes</th>
                <th scope="col">Broken since</th>
                <th scope="col">Signatures expire</th>
                <th scope="col">Auto-fix verified</th>
                <th scope="col">
                  <span className="visually-hidden">Action</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => {
                const first = r.issues[0];
                const exp = expiry(r.nextExpiry, now);
                return (
                  <tr key={r.zone} className={live.fresh.has(r.zone) ? "fresh" : undefined}>
                    <td className="nowrap">
                      <StateLabel status={r.status} />
                    </td>
                    <td className="nowrap mono">{shortZone(r.zone)}</td>
                    <td>
                      {first ? (
                        <span className="problem">
                          <span className="issue-id">{first.id}</span>
                          <span>{first.title}</span>
                          {r.issues.length > 1 && <span className="muted small">+{r.issues.length - 1}</span>}
                        </span>
                      ) : (
                        <span className="muted">No problem found</span>
                      )}
                    </td>
                    <td className="nowrap muted">{first ? SIDE_LABEL[first.side] : ""}</td>
                    <td className="nowrap muted">{ago(r.brokenSince, now)}</td>
                    <td className={`nowrap ${exp.warn ? "warn" : "muted"}`}>{exp.text}</td>
                    <td className="nowrap">{r.status !== "OK" && <VerifiedBadge verified={r.verified} />}</td>
                    <td className="action">
                      <Link className="button small" to={`/zones/${encodeURIComponent(shortZone(r.zone))}`}>
                        Open<span className="visually-hidden"> {shortZone(r.zone)}</span>
                      </Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {live.rows === null && !live.error && <p className="empty">Loading zones…</p>}
        {live.rows !== null && shown.length === 0 && (
          <p className="empty">{query ? `No zone matches “${query}”.` : "Nothing here: no zone in this state."}</p>
        )}

        {folded && (
          <div className="healthy-strip">
            <StateLabelText count={healthy.length} />
            <span className="mono muted">
              {healthy
                .slice(0, 4)
                .map((r) => shortZone(r.zone))
                .join(" · ")}
              {healthy.length > 4 && " · …"}
            </span>
            <div className="spacer" />
            <button type="button" className="button small" onClick={() => setShowHealthy(true)}>
              Show healthy zones
            </button>
          </div>
        )}
      </section>

      <p className="muted small" style={{ margin: 0 }}>
        Sorted by state, then newest first. A row lights up briefly when the scanner changes it. Verified: the
        generated fix script ran in a sandbox copy of the hosting, <code>dnssecretary verify</code> found no critical
        error and the resolver answered with AD.
      </p>
    </main>
  );
}

function Stat({ label, value, color, note }: { label: string; value: number | string; color: string; note: string }) {
  return (
    <div className="card stat">
      <span className="muted">{label}</span>
      <span className="value" style={{ color }}>
        {value}
      </span>
      <span className="note">{note}</span>
    </div>
  );
}

function StateLabelText({ count }: { count: number }) {
  return (
    <span className="state OK">
      <span className="dot" aria-hidden="true" />
      {count} healthy {count === 1 ? "zone" : "zones"}
    </span>
  );
}
