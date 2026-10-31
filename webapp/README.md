# DNSSECretary web app (front end)

The dashboard for DNS hosting admins: a live list of zones that are broken or about to break, and
for each one the fix script the tool generated, already tried in a sandbox copy of the hosting
("Auto-fix verified"). React 18 + TypeScript + Vite; no state library, no CSS framework.
Design: the canvas "DNSSECretary – web app dizajn" (artboards Dashboard, Zone, Demo panel).

```sh
cd webapp
npm ci            # once (Node 20+; CI uses 22)
npm run dev       # http://localhost:5173, /api goes to the backend: sh scripts/web.sh (port 8080)
npm run mock      # the same without a backend: src/api/mock.ts answers (VITE_MOCK=1, .env.mock)
npm test          # vitest: the dashboard logic and the dashboard on the mock backend
npm run build     # webapp/dist, which "dnssecretary serve" serves on http://localhost:8080
```

```
src/
├── api/
│   ├── types.ts      the API's JSON shapes (same as web/Api.java in the backend)
│   ├── client.ts     Backend interface; httpBackend (fetch + EventSource on /api/events); api = real or mock
│   ├── mock.ts       a backend without a server: the lab's 31 zones, jobs that finish after a moment
│   └── useZones.ts   the zone list, kept live by the "zone" SSE events, reloaded after a lost connection
├── pages/
│   ├── Dashboard.tsx the incident list (role 4)
│   ├── dashboard.ts  its logic without React: order, filters, counters, the words for times
│   └── ZonePage.tsx  PLACEHOLDER for role 5: diagnoses, editor + diff, Simulate / Execute, log, verify
├── components/
│   ├── Header.tsx    the bar on every screen (live status, demo panel button)
│   ├── Badges.tsx    state label and the "verified" badge
│   └── DemoPanel.tsx PLACEHOLDER for role 5: break / rollover / heal / reset (api.demo*)
├── App.tsx           routes: / and /zones/:zone
└── styles.css        the design's tokens; state = colour + word + icon, never colour alone
```

Every UI text is English, like everything in the repo. Talk to the server only through `api`
(`Backend` in `client.ts`): the pages then work the same against the mock, which the tests use.
