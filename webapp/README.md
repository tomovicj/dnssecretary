# DNSSECretary web app (front end)

The dashboard for DNS hosting admins: a live list of zones that are broken or about to break, and
for each one the fix script the tool generated, already tried in a sandbox copy of the hosting
("Auto-fix verified"). On the zone screen the admin reads the diagnosis, edits the script,
simulates it and runs it on the hosting, with the log live. React 18 + TypeScript + Vite +
CodeMirror 6; no state library, no CSS framework.
Design: the canvas "DNSSECretary – web app dizajn" (artboards Dashboard, Zone, Demo panel).

```sh
cd webapp
npm ci            # once (Node 20+; CI uses 22)
npm run dev       # http://localhost:5173, /api goes to the backend: sh scripts/web.sh (port 8080)
npm run mock      # the same without a backend: src/api/mock.ts answers (VITE_MOCK=1, .env.mock)
npm test          # vitest: the logic, and the pages on the mock backend (real CodeMirror in ScriptEditor.test)
npm run build     # webapp/dist, which "dnssecretary serve" serves on http://localhost:8080
```

```
src/
├── api/
│   ├── types.ts      the API's JSON shapes (same as web/Api.java in the backend)
│   ├── client.ts     Backend interface; httpBackend (fetch + ONE shared EventSource on /api/events); api = real or mock
│   ├── mock.ts       a backend without a server: the lab's 31 zones, jobs that finish after a moment
│   └── useZones.ts   the zone list, kept live by the "zone" SSE events, reloaded after a lost connection
├── pages/
│   ├── Dashboard.tsx the incident list
│   ├── dashboard.ts  its logic without React: order, filters, counters, the words for times
│   ├── ZonePage.tsx  one zone: diagnoses (with the catalogue's texts), the script editor + diff against
│   │                 the generated script, Simulate / Execute (simulates first unless skipped, after a
│   │                 confirm), the last run's live log and verify result, the history; loaded lazily
│   └── zone.ts       its logic without React: edit state, job words, merging job events
├── components/
│   ├── Header.tsx    the bar on every screen (live status, demo panel button)
│   ├── Badges.tsx    state label and the "verified" badge
│   ├── ScriptEditor.tsx CodeMirror: the editable script (shell highlighting) and the read-only diff
│   └── DemoPanel.tsx the drawer: break a hosting zone with a chosen error, heal, reset (confirm)
├── App.tsx           routes: / and /zones/:zone
└── styles.css        the design's tokens; state = colour + word + icon, never colour alone
```

Every UI text is English, like everything in the repo. Talk to the server only through `api`
(`Backend` in `client.ts`): the pages then work the same against the mock, which the tests use.
