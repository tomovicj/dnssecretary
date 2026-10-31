import { Link, NavLink, useLocation } from "react-router-dom";
import type { ConnectionStatus } from "../api/client";
import { elapsed } from "../pages/dashboard";

interface Props {
  connection: ConnectionStatus;
  lastScan: string | null;
  zoneCount: number;
  now: number;
  onDemo: () => void;
}

/** The bar on every screen: product, navigation, whether the live stream is up, the demo panel. */
export function Header({ connection, lastScan, zoneCount, now, onDemo }: Props) {
  const live = connection === "open";
  // NavLink never marks "/" active on other routes; the zone screen belongs to Incidents too
  const { pathname } = useLocation();
  const incidents = pathname === "/" || pathname.startsWith("/zones/");
  return (
    <header className="header">
      <div className="header-inner">
        <NavLink to="/" className="brand">
          <svg width="26" height="26" viewBox="0 0 26 26" aria-hidden="true">
            <path d="M13 2 L22 6 V12 C22 18 18 22 13 24 C8 22 4 18 4 12 V6 Z" fill="none" stroke="#14171C" strokeWidth="2" strokeLinejoin="round" />
            <path d="M9 13 L12 16 L17 10" fill="none" stroke="#1F5EDC" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          DNSSECretary
        </NavLink>
        <nav className="nav" aria-label="Main">
          <Link to="/" className={incidents ? "active" : undefined} aria-current={incidents ? "page" : undefined}>
            Incidents
          </Link>
        </nav>
        <div className="spacer" />
        <div className="live" role="status">
          <span className="dot small" style={{ background: live ? "var(--ok-dot)" : "var(--risk)" }} aria-hidden="true" />
          <span>
            {live ? "Live" : connection === "connecting" ? "Connecting…" : "Reconnecting…"}
            {lastScan && ` · last scan ${elapsed(lastScan, now)}`}
            {zoneCount > 0 && ` · ${zoneCount} zones on lab hosting`}
          </span>
        </div>
        <button type="button" className="button" onClick={onDemo}>
          Demo panel
        </button>
      </div>
    </header>
  );
}
