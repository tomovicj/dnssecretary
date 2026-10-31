package rs.port53.dnssecretary.check.delegation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.dns.Dnssec;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

// Checks B5, B7: DS policy and signature validation over DNSKEY set.

public final class DsPolicyCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return EnumSet.of(ErrorId.B5, ErrorId.B7);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();

        SignedRRset dsRRset = snapshot.parentDs().rrset();
        if (dsRRset == null || dsRRset.isEmpty()) {
            return findings;
        }

        List<DSRecord> dsRecords = dsRRset.records(DSRecord.class);
        SignedRRset keyRRset = snapshot.dnskey();

        if (keyRRset == null || keyRRset.isEmpty()) {
            return findings;
        }

        List<DNSKEYRecord> keys = keyRRset.records(DNSKEYRecord.class);

        // B7: Parent DS uses SHA-1 digest

        for (DSRecord ds : dsRecords) {
            if (ds.getDigestID() == 1) {
                findings.add(new Finding(
                        ErrorId.B7, Severity.RISK, ds.getName(), Type.DS, ds.toString(),
                        Map.of("dsKeyTag", String.valueOf(ds.getFootprint()), "digestType", "1 (SHA-1)")
                ));
            }
        }

        Set<Integer> signerTags = keyRRset.sigs().stream()
                .map(sig -> sig.getFootprint())
                .collect(Collectors.toSet());

        List<DNSKEYRecord> matchedKsks = new ArrayList<>();
        for (DSRecord ds : dsRecords) {
            for (DNSKEYRecord key : keys) {
                if (ds.getFootprint() == key.getFootprint()
                        && ds.getAlgorithm() == key.getAlgorithm()
                        && Dnssec.dsMatches(ds, key)
                        && !matchedKsks.contains(key)) {
                    matchedKsks.add(key);
                }
            }
        }

        boolean signedByAnyKsk = matchedKsks.stream()
                .anyMatch(k -> signerTags.contains(k.getFootprint()));

        if (!matchedKsks.isEmpty() && !signedByAnyKsk) {
            findings.add(new Finding(
                    ErrorId.B5, Severity.CRITICAL, keyRRset.name(), Type.DNSKEY, keyRRset.toString(),
                    Map.of("kskTags", matchedKsks.stream()
                            .map(k -> String.valueOf(k.getFootprint()))
                            .collect(Collectors.joining(",")))));
        }

        return findings;
    }
}