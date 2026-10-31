package rs.port53.dnssecretary.check.signatures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.RRSIGRecord;

import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** A4, A5: RRSIGs that don't verify, or that point to a key not in the DNSKEY set. */
public class SignatureCryptoCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.A4, ErrorId.A5);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();
        Instant now = ctx.now();

        SignedRRset dnskeySet = snapshot.dnskey();
        if (dnskeySet == null) {
            return findings; // no DNSKEY: that's another error (B4)
        }

        // Keys by "algorithm/keyTag"
        Map<String, DNSKEYRecord> keys = new HashMap<>();
        for (DNSKEYRecord k : dnskeySet.records(DNSKEYRecord.class)) {
            keys.put(k.getAlgorithm() + "/" + k.getFootprint(), k);
        }

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            for (RRSIGRecord sig : sr.sigs()) {
                String keyId = sig.getAlgorithm() + "/" + sig.getFootprint();
                DNSKEYRecord dnskey = keys.get(keyId);

                // A5: the key doesn't exist
                if (dnskey == null) {
                    findings.add(new Finding(
                            ErrorId.A5, Severity.CRITICAL, sr.name(), sr.type(),
                            "RRSIG uses key tag " + sig.getFootprint() + ", which is not in the DNSKEY set.",
                            Map.of("keyTag", String.valueOf(sig.getFootprint()))
                    ));
                    continue;
                }

                // A4: cryptographic check (dnsjava throws when it fails)
                try {
                    DNSSEC.verify(sr.toRRset(), sig, dnskey, now);
                } catch (DNSSEC.SignatureExpiredException | DNSSEC.SignatureNotYetValidException e) {
                    // validity period: A1/A2, SignatureValidityCheck
                } catch (Exception e) {
                    findings.add(new Finding(
                            ErrorId.A4, Severity.CRITICAL, sr.name(), sr.type(),
                            "Signature does not verify: " + e.getMessage(),
                            Map.of("keyTag", String.valueOf(sig.getFootprint()))
                    ));
                }
            }
        }
        return findings;
    }
}
