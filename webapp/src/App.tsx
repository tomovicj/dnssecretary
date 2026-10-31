import { useEffect, useState } from "react";
import { Route, Routes } from "react-router-dom";
import { useZones } from "./api/useZones";
import { DemoPanel } from "./components/DemoPanel";
import { Header } from "./components/Header";
import { Dashboard } from "./pages/Dashboard";
import { lastScan } from "./pages/dashboard";
import { ZonePage } from "./pages/ZonePage";

/** "Now", ticking, so relative times ("2 min ago") stay right without new data. */
function useNow(everyMs = 5000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(t);
  }, [everyMs]);
  return now;
}

export function App() {
  const live = useZones();
  const now = useNow();
  const [demoOpen, setDemoOpen] = useState(false);

  return (
    <>
      <Header
        connection={live.connection}
        lastScan={live.rows ? lastScan(live.rows) : null}
        zoneCount={live.rows?.length ?? 0}
        now={now}
        onDemo={() => setDemoOpen((o) => !o)}
      />
      <Routes>
        <Route path="/" element={<Dashboard live={live} now={now} />} />
        <Route path="/zones/:zone" element={<ZonePage />} />
        <Route path="*" element={<Dashboard live={live} now={now} />} />
      </Routes>
      {demoOpen && <DemoPanel onClose={() => setDemoOpen(false)} />}
    </>
  );
}
