import type { Status, Verified } from "../api/types";
import { STATUS_LABEL, VERIFIED_LABEL } from "../pages/dashboard";

/** Broken / At risk / Healthy: a dot and the word, in the state's colour. */
export function StateLabel({ status }: { status: Status }) {
  return (
    <span className={`state ${status}`}>
      <span className="dot" aria-hidden="true" />
      {STATUS_LABEL[status]}
    </span>
  );
}

/** The "Auto-fix verified" column: the sandbox's verdict on the zone's fix script. */
export function VerifiedBadge({ verified }: { verified: Verified }) {
  const s = verified.state;
  return (
    <span className={`badge ${s}`}>
      <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
        {s === "PASS" && (
          <path d="M3 7.5 L6 10.5 L11 4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        )}
        {s === "FAIL" && <path d="M4 4 L10 10 M10 4 L4 10" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />}
        {s === "RUNNING" && (
          <>
            <circle cx="7" cy="7" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <path d="M7 4 V7 L9 8.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </>
        )}
        {s === "NONE" && <path d="M3.5 7 H10.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />}
      </svg>
      {VERIFIED_LABEL[s]}
    </span>
  );
}
