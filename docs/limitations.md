# Limitations and next steps

DNSSECretary was built for a hackathon. The detection is real and works on real DNS; the parts
around it are simplified for a demo in a lab. This page says where, and what it would take to run it
for a real DNS hosting.

## What the demo simplifies

| | In the demo | For a real hosting |
| --- | --- | --- |
| **Zones** | 40 hosting zones, all on one server (auth1), one name server each | several name servers per zone, secondaries, anycast |
| **Zone data** | read from the server's zone files (`collect.zoneSource: file`) | AXFR from the primary (`zoneSource: axfr`, implemented, not used in the lab), or `none`: checks then work on queried samples only |
| **Signing** | `dnssec-signzone` from sources in `zones/`, keys in `keys/` | the hosting's own signer (BIND inline signing, Knot, PowerDNS, OpenDNSSEC): fix scripts would call its tools instead |
| **The parent** | the lab controls `test.`, so a fix writes the DS and re-signs it (`parent.mode: lab`) | `parent.mode: instructions` shows the DS for the registrar; next: CDS/CDNSKEY (RFC 8078) or registrar APIs |
| **Reloads** | `docker restart` of the lab's containers (`fixenv/prod.sh`) | `rndc reload`, `nsd-control reload`, `knotc`: another helpers file in `scripts/fixenv/` |
| **Key rollovers** | C1/C3 fixes replace keys at once (fast, for the lab) | a timed rollover: publish, wait for TTLs, switch, wait, remove; the catalogue's recipe lists the steps |
| **State** | memory only: a restart forgets jobs and history | a database for history, audit and who ran what |
| **Access** | no login, `127.0.0.1` only | authentication, roles (who may execute), audit log |
| **Docker socket** | the web container has it (root on the host) | a separate, narrow runner for the sandbox and executions |
| **Scale** | 40 zones every 20 s, 4 at a time | thousands of zones: smarter scheduling (by signature expiry, by change), parallel collection |

## Known limits of the checks and fixes

- **E1/E3 in the sandbox.** The sandbox resolver starts with an empty cache, so a simulation proves
  the fixed zone validates, not how caches holding the old key behave. On the hosting the fix works,
  because both keys sign until the caches expire.
- **EDE codes differ between resolvers.** The lab asserts Unbound 1.20's answers; BIND or Knot may
  return other codes or be stricter (C2). The tool detects errors from the zone data and uses EDE
  only as a hint.
- **B1 vs E2** (a DS for a key that's gone, or one that's coming) needs the key directory. Without
  it, the tool can only say "the DS doesn't match a key".
- **18 of the 30 errors** can be put on a hosting zone in the demo; the other 12 need special keys
  or servers and live only in their catalogue zones.
- **Signatures in the lab last 30 days.** After that, every zone is "expired" until `sign.sh` (or
  `setup.sh`) runs again.

## Next steps

1. **Real zones, read-only first**: watch a hosting's zones with `zoneSource: axfr` (or `none`) and
   the real root trust anchor, report only. The checks don't depend on the lab.
2. **Adapters for real signers and servers**: fix scripts for BIND, Knot and PowerDNS, a helpers file
   per server type.
3. **The parent side**: CDS/CDNSKEY publication and checks, registrar instructions per registry
   (`.rs` first).
4. **Proactive alerts**: signatures that expire within days, keys that should roll, SHA-1 DS records,
   by e-mail or chat, before anything breaks.
5. **History and audit**: a database, and who simulated and executed what.
