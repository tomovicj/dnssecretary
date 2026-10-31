import { useEffect, useRef, useState } from "react";
import { api, type Backend, type ConnectionStatus } from "./client";
import type { ZoneRow } from "./types";

export interface LiveZones {
  rows: ZoneRow[] | null; // null while loading
  error: string | null;
  connection: ConnectionStatus;
  /** Zones whose row changed in the last few seconds (the row lights up). */
  fresh: Set<string>;
}

const FRESH_MS = 3000;

/** The zone list, kept live with the "zone" SSE events; reloaded after a lost connection. */
export function useZones(backend: Backend = api): LiveZones {
  const [rows, setRows] = useState<ZoneRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [connection, setConnection] = useState<ConnectionStatus>("connecting");
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  useEffect(() => {
    let alive = true;
    let lost = false;
    const load = () =>
      backend
        .zones()
        .then((r) => {
          if (!alive) return;
          setRows(r);
          setError(null);
        })
        .catch((e: Error) => alive && setError(e.message));

    const markFresh = (zone: string) => {
      setFresh((f) => new Set(f).add(zone));
      clearTimeout(timers.current.get(zone));
      timers.current.set(
        zone,
        setTimeout(() => {
          if (!alive) return;
          setFresh((f) => {
            const next = new Set(f);
            next.delete(zone);
            return next;
          });
        }, FRESH_MS),
      );
    };

    load();
    const stop = backend.subscribe(
      (e) => {
        if (e.type !== "zone") return;
        setRows((prev) => (prev ? [...prev.filter((r) => r.zone !== e.data.zone), e.data] : prev));
        markFresh(e.data.zone);
      },
      (s) => {
        setConnection(s);
        if (s === "reconnecting") lost = true;
        if (s === "open" && lost) {
          lost = false;
          load(); // events may have been missed
        }
      },
    );
    const pending = timers.current;
    return () => {
      alive = false;
      stop();
      pending.forEach(clearTimeout);
    };
  }, [backend]);

  return { rows, error, connection, fresh };
}
