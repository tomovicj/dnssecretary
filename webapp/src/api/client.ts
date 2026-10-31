import type { Job, Scenario, ServerEvent, ZoneDetail, ZoneRow } from "./types";
import { mockBackend } from "./mock";

/** Everything the UI needs from the server. The real one talks HTTP + SSE; mock.ts fakes it. */
export interface Backend {
  zones(): Promise<ZoneRow[]>;
  zone(zone: string): Promise<ZoneDetail>;
  jobs(): Promise<Job[]>;
  job(id: string): Promise<Job>;
  /** Try a script in the sandbox (default: the generated one). */
  simulate(zone: string, script?: string): Promise<Job>;
  /** Run a script on the hosting; by default only after a simulation of it passes. */
  execute(zone: string, script?: string, simulateFirst?: boolean): Promise<Job>;
  scan(): Promise<void>;
  scenarios(): Promise<Scenario[]>;
  demoBreak(zone: string): Promise<Job>;
  demoHeal(): Promise<Job>;
  demoReset(): Promise<Job>;
  /** Live events; returns the function that stops them. */
  subscribe(onEvent: (e: ServerEvent) => void, onStatus: (s: ConnectionStatus) => void): () => void;
}

export type ConnectionStatus = "connecting" | "open" | "reconnecting";

/** An error the API answered with ({"error": "..."}), or a failed request. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

/** Zone names in URLs go without the trailing dot: /api/zones/a1-expired.test */
export function zonePath(zone: string): string {
  return encodeURIComponent(zone.replace(/\.$/, ""));
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    throw new ApiError(json?.error ?? `${method} ${path}: HTTP ${res.status}`, res.status);
  }
  return json as T;
}

export function httpBackend(): Backend {
  return {
    zones: () => call("GET", "/api/zones"),
    zone: (zone) => call("GET", `/api/zones/${zonePath(zone)}`),
    jobs: () => call("GET", "/api/jobs"),
    job: (id) => call("GET", `/api/jobs/${encodeURIComponent(id)}`),
    simulate: (zone, script) => call("POST", `/api/zones/${zonePath(zone)}/simulations`, { script }),
    execute: (zone, script, simulateFirst = true) =>
      call("POST", `/api/zones/${zonePath(zone)}/executions`, { script, simulateFirst }),
    scan: () => call("POST", "/api/scan"),
    scenarios: () => call("GET", "/api/demo/scenarios"),
    demoBreak: (zone) => call("POST", "/api/demo/break", { zone }),
    demoHeal: () => call("POST", "/api/demo/heal"),
    demoReset: () => call("POST", "/api/demo/reset"),
    subscribe(onEvent, onStatus) {
      // EventSource reconnects by itself; it sends "Accept: text/event-stream", which the server needs.
      const es = new EventSource("/api/events");
      onStatus("connecting");
      es.onopen = () => onStatus("open");
      es.onerror = () => onStatus("reconnecting");
      for (const type of ["zone", "job", "log"] as const) {
        es.addEventListener(type, (m) => onEvent({ type, data: JSON.parse((m as MessageEvent).data) } as ServerEvent));
      }
      return () => es.close();
    },
  };
}

/** The backend the app uses: the real one, or the mock with "npm run mock" (VITE_MOCK=1). */
export const api: Backend = import.meta.env.VITE_MOCK ? mockBackend() : httpBackend();
