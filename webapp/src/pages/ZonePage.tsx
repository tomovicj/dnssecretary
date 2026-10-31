import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import type { ZoneDetail } from "../api/types";
import { StateLabel, VerifiedBadge } from "../components/Badges";
import { shortZone } from "./dashboard";

/**
 * PLACEHOLDER for role 5 (the zone screen in the design: diagnoses, editor, diff, Simulate /
 * Execute, live log, verify result, history). For now it shows the zone's issues and its script,
 * so the dashboard's "Open" already leads somewhere.
 */
export function ZonePage() {
  const { zone = "" } = useParams();
  const [detail, setDetail] = useState<ZoneDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.zone(zone).then(setDetail, (e: Error) => setError(e.message));
  }, [zone]);

  return (
    <main className="page">
      <Link to="/">← Incidents</Link>
      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}
      {detail && (
        <>
          <div className="page-head">
            <h1 className="mono">{shortZone(detail.zone)}</h1>
            <StateLabel status={detail.status} />
            {detail.status !== "OK" && <VerifiedBadge verified={detail.verified} />}
          </div>
          <ul>
            {detail.issues.map((i) => (
              <li key={i.id}>
                <span className="issue-id">{i.id}</span> {i.title}
              </li>
            ))}
          </ul>
          {detail.script.generated && <pre className="code">{detail.script.generated}</pre>}
        </>
      )}
    </main>
  );
}
