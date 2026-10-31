/**
 * PLACEHOLDER for role 5 (the demo panel in the design: break a zone, live E1/E3 rollover,
 * heal, reset; api.demoBreak / demoHeal / demoReset). The header's "Demo panel" opens it.
 */
export function DemoPanel({ onClose }: { onClose: () => void }) {
  return (
    <aside
      aria-label="Demo panel"
      className="card"
      style={{ position: "fixed", top: 72, right: 16, width: 360, padding: 20, display: "flex", flexDirection: "column", gap: 12 }}
    >
      <strong>Demo panel</strong>
      <span className="muted small">Coming with role 5: break a zone, live rollover, heal, reset.</span>
      <button type="button" className="button small" onClick={onClose}>
        Close
      </button>
    </aside>
  );
}
