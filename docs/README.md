# DNSSECretary documentation

Start with [Getting started](getting-started.md); for the presentation, the [Demo guide](demo.md).

| Document | For |
| --- | --- |
| [Getting started](getting-started.md) | Requirements, `setup.sh`, setup step by step, the CLI, cleanup, troubleshooting |
| [Demo guide](demo.md) | The 5-minute live demo with what to say, the video, a checklist for the day |
| [Using the web app](web-app.md) | The dashboard, the zone screen, the demo panel, the REST API |
| [Architecture](architecture.md) | Components, containers, the scan pipeline, jobs and locks, design decisions, security |
| [Errors we detect](errors.md) | The 30 catalogue errors: severity, who fixes, how they're detected and fixed |
| [How fixes work](fixes.md) | Fix scripts, the script contract, the sandbox, execution and verification |
| [The lab](lab.md) | Servers and IPs, signing, the demo hosting, the broken zones, every script |
| [Development](development.md) | Layout, build and tests, CI, conventions, adding a check or an error |
| [Limitations and next steps](limitations.md) | What the demo simplifies, and the way to real hostings |

Module guides: [`dnssecretary/README.md`](../dnssecretary/README.md) (the Java code: packages, what
a check gets, how each error is detected) and [`webapp/README.md`](../webapp/README.md) (the front
end's structure).
