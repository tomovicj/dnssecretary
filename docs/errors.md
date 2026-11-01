# Errors we detect

DNSSECretary knows 30 DNSSEC errors, from the team's error catalogue
([`catalog/catalog.yaml`](../catalog/catalog.yaml)). For each one the catalogue holds the title, the
symptom and EDE code, the cause, how to detect it, the fix, the RFCs, and the lab zone that
reproduces it. The tool reads its texts from there, so the report, the web app and the docs say the
same thing.

- **Severity.** *Critical*: validation fails, resolvers answer SERVFAIL, the domain is down for
  everyone behind a validating resolver. *Risk*: it works today, but is unsafe, bad practice, or
  will break (signatures about to expire).
- **Who fixes.** *Zone*: the hosting itself, automatically (re-sign, keys). *Parent*: the DS record at
  the parent zone / registrar. *Operator*: a server setting.
- **Unbound 1.20 in the lab**: what the lab's validating resolver answers for the broken zone, as
  `scripts/check.sh` asserts.
- **Detected by**: the check class (`…Check` in `dnssecretary/src/main/java/…/check/`). The exact
  rules are in [`dnssecretary/README.md`](../dnssecretary/README.md#how-each-error-is-detected).
- **On hosting zones**: the demo can put it on any of the 40 hosting zones (`hosting: true`).

### A. Signatures (RRSIG)

| ID | Error | Severity | Who fixes | Unbound 1.20 in the lab | Detected by | Automatic fix | On hosting zones |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | Expired signature | critical | zone | SERVFAIL, EDE 7 | `SignatureValidity` | re-sign | yes |
| A2 | Signature not yet valid | critical | zone | SERVFAIL, EDE 8 | `SignatureValidity` | re-sign | yes |
| A3 | Record without a signature (RRset has no RRSIG) | critical | zone | SERVFAIL, EDE 10 | `SignatureCoverage` | re-sign | yes |
| A4 | Invalid signature | critical | zone | SERVFAIL, EDE 6 | `SignatureCrypto` | re-sign | yes |
| A5 | Signature made by a key that is not in the DNSKEY set | critical | zone | SERVFAIL, EDE 9 | `SignatureCrypto` | re-sign |  |
| A6 | Signature expires soon (too little time to fix it) | risk | zone | works (expires in 2 d) | `SignatureValidity` | re-sign | yes |
| A7 | TTL longer than the remaining signature validity | risk | zone | works | `Ttl` | re-sign |  |

### B. Keys and the chain of trust (DS / DNSKEY)

| ID | Error | Severity | Who fixes | Unbound 1.20 in the lab | Detected by | Automatic fix | On hosting zones |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | DS points to a key that does not exist | critical | parent | SERVFAIL, EDE 6 | `Delegation (+ key directory rule)` | publish the right DS at the parent |  |
| B2 | DS has a wrong hash (digest) | critical | parent | SERVFAIL, EDE 6 | `Delegation` | publish the right DS at the parent | yes |
| B3 | DS and DNSKEY have different algorithms | critical | parent | SERVFAIL, EDE 6 | `Delegation` | publish the right DS at the parent | yes |
| B4 | DS exists, but the zone is not signed | critical | parent | SERVFAIL, EDE 9 | `Delegation` | re-sign | yes |
| B5 | DNSKEY set not signed by the KSK (the key the DS points to) | critical | zone | SERVFAIL, EDE 6 | `DsPolicy` | re-sign | yes |
| B6 | Zone is signed, but there is no DS in the parent zone ("island") | risk | parent | works, no AD | `Delegation` | publish the right DS at the parent | yes |
| B7 | DS uses SHA-1 (digest type 1) | risk | parent | works | `DsPolicy` | publish the right DS at the parent | yes |
| B8 | Leftover old DS records | risk | parent | works | `Delegation` | publish the right DS at the parent |  |

### C. Algorithms and key strength

| ID | Error | Severity | Who fixes | Unbound 1.20 in the lab | Detected by | Automatic fix | On hosting zones |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | Deprecated algorithm (1, 3, 5, 6, 7, 12) | risk | zone | works | `AlgorithmPolicy` | new algorithm-13 keys + re-sign + new DS |  |
| C2 | Keys of two algorithms, but records are not signed with both | critical | zone | works (Unbound is lenient) | `AlgorithmPolicy` | re-sign + the right DS |  |
| C3 | Weak RSA key (< 2048 bits) | risk | zone | works | `KeyStrength` | new algorithm-13 keys + re-sign + new DS |  |

### D. Denial of existence (NSEC / NSEC3)

| ID | Error | Severity | Who fixes | Unbound 1.20 in the lab | Detected by | Automatic fix | On hosting zones |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D1 | Broken NSEC/NSEC3 chain | critical | zone | SERVFAIL for names that don't exist | `DenialChain` | re-sign | yes |
| D2 | Wrong type list (bitmap) | critical | zone | SERVFAIL for www TXT | `DenialChain` | re-sign |  |
| D3 | Negative answer without NSEC/NSEC3 or without signatures | critical | zone | SERVFAIL for names that don't exist | `DenialChain` | re-sign | yes |
| D4 | NSEC3 settings against RFC 9276 | risk | zone | NXDOMAIN without AD | `Nsec3Policy` | re-sign | yes |
| D5 | NSEC3PARAM does not match the NSEC3 records | critical | zone | SERVFAIL for names that don't exist | `Nsec3Policy` | re-sign | yes |
| D6 | NSEC and NSEC3 mixed in the same zone | risk | zone | works | `DenialChain` | re-sign | yes |

### E. Key rollovers

| ID | Error | Severity | Who fixes | Unbound 1.20 in the lab | Detected by | Automatic fix | On hosting zones |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E1 | ZSK replaced too quickly (the new key was not pre-published) | critical | zone | SERVFAIL (after the rollover) | `CacheConsistency` | put the old key back + re-sign | yes |
| E2 | KSK replaced, and the DS changed too early or too late | critical | parent | SERVFAIL, EDE 6 | `Delegation + key directory rule` | publish the right DS at the parent |  |
| E3 | Old key removed before its TTL expired | critical | zone | delv fails; Unbound answers from cache | `CacheConsistency` | put the old key back + re-sign |  |
| E4 | CDS/CDNSKEY do not match the keys | risk | zone | works | `Cds` | re-sign | yes |

### F. Servers

| ID | Error | Severity | Who fixes | Unbound 1.20 in the lab | Detected by | Automatic fix | On hosting zones |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F1 | Secondary server has a stale zone with expired signatures | critical | operator | works (Unbound retries the primary) | `NameserverConsistency` | refresh the secondary |  |
| F2 | Server doesn't return DNSSEC records or fails on large answers | critical | operator | SERVFAIL for large answers | `Transport` | server config: TCP, EDNS 1232 |  |

## Notes

**The tool is stricter than the resolver, on purpose.** For B7, B8, C1, C2, C3, D6, E4 and F1, the lab's
Unbound answers normally (often with AD), but they are real problems: deprecated algorithms, weak
keys, leftovers that break the next change, a secondary that will serve expired data. The expected
result of every check comes from the catalogue, not from what one resolver happens to do. EDE codes
are only a hint: other resolvers (BIND, Knot) use different codes for the same problem.

**One problem, one diagnosis.** Many findings can have one cause: an expired zone has an expired
signature on every record set. The diagnosis step merges them ("16 findings. Example: …") and hides
consequences of another error: a zone with a DS but no signatures (B4) is not also reported as
unsigned record sets (A3). See `ConsequenceRule`.

**Where the data comes from.** Every check is a pure function over one `ZoneSnapshot`, collected
from the parent (the DS), from every authoritative server separately, from the whole signed zone
file, from the validating resolver's cache (key rollovers), and from the key directory (to tell B1
from E2: a DS for a key that is coming, or one that is gone).

**Time-based errors** use offsets of 7 days or more, because Unbound tolerates 1 day of clock skew.
A6 warns when signatures expire in less than 7 days (`thresholds.signatureExpiryWarnDays`); C3 fires
below 2048 RSA bits (`thresholds.minRsaBits`), both in `dnssecretary/config/lab.yaml`.
