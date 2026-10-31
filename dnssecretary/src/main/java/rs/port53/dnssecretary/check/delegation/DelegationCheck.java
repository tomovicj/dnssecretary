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

/**
 * Checks B1, B2, B3, B4, B6, B8:
 * Validates the consistency between the DS records at the parent zone and the DNSKEY set in the child zone.
 */
public final class DelegationCheck implements Check {

    private static final Severity CRITICAL = Severity.CRITICAL;

    @Override
    public Set<ErrorId> detects() {
        return EnumSet.of(
                ErrorId.B1,
                ErrorId.B2,
                ErrorId.B3,
                ErrorId.B4,
                ErrorId.B6,
                ErrorId.B8
        );
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();

        // 1. Retrieve DS records from parent response
        List<DSRecord> dsSet = new ArrayList<>();
        SignedRRset dsRRset = snapshot.parentDs().rrset();
        if (dsRRset != null) {
            dsSet.addAll(dsRRset.records(DSRecord.class));
        }

        // 2. Retrieve DNSKEY records from child zone response
        List<DNSKEYRecord> keys = new ArrayList<>();
        SignedRRset keyRRset = snapshot.dnskey();
        if (keyRRset != null) {
            keys.addAll(keyRRset.records(DNSKEYRecord.class));
        }


        // B6: Parent DS is missing (NODATA proof present), but the zone has DNSKEYs

        if (dsSet.isEmpty()) {
            if (snapshot.parentDs().isNodata() && !keys.isEmpty()) {
                findings.add(new Finding(
                        ErrorId.B6, CRITICAL, snapshot.zone(), Type.DS,
                        "Parent DS missing (NODATA)",
                        Map.of("dnskeyCount", String.valueOf(keys.size()))
                ));
            }
            return findings;
        }


        // B4: Parent DS exists, but the child zone has no DNSKEY records (unsigned)

        if (keys.isEmpty()) {
            DSRecord ds = dsSet.get(0);
            findings.add(new Finding(
                    ErrorId.B4, CRITICAL, ds.getName(), Type.DS, ds.toString(),
                    Map.of("dsKeyTag", String.valueOf(ds.getFootprint()), "dnskeyCount", "0")
            ));
            return findings;
        }

        int matchingDsCount = 0;
        List<DSRecord> unmatchedDs = new ArrayList<>();


        for (DSRecord ds : dsSet) {
            boolean exactMatch = false;
            boolean sameTagDifferentAlg = false;
            boolean sameTagWrongDigest = false;

            for (DNSKEYRecord key : keys) {
                if (ds.getFootprint() == key.getFootprint()) {
                    if (ds.getAlgorithm() != key.getAlgorithm()) {
                        sameTagDifferentAlg = true;
                    } else if (Dnssec.dsMatches(ds, key)) {
                        exactMatch = true;
                        break;
                    } else {
                        sameTagWrongDigest = true;
                    }
                }
            }

            if (exactMatch) {
                matchingDsCount++;
            } else {
                unmatchedDs.add(ds);


                // B3: Matching key tag exists, but the algorithm differs

                if (sameTagDifferentAlg) {
                    findings.add(new Finding(
                            ErrorId.B3, CRITICAL, ds.getName(), Type.DS, ds.toString(),
                            Map.of("dsKeyTag", String.valueOf(ds.getFootprint()),
                                    "dsAlg", String.valueOf(ds.getAlgorithm()))
                    ));
                }

                // B2: Matching key tag and algorithm exist, but the DS digest is invalid

                else if (sameTagWrongDigest) {
                    findings.add(new Finding(
                            ErrorId.B2, CRITICAL, ds.getName(), Type.DS, ds.toString(),
                            Map.of("dsKeyTag", String.valueOf(ds.getFootprint()),
                                    "digestType", String.valueOf(ds.getDigestID()))
                    ));
                }
            }
        }


        // B1: No DS matches any DNSKEY in the zone (completely broken delegation)

        if (matchingDsCount == 0) {
            if (findings.isEmpty()) { // Only report B1 if specific B2/B3 findings weren't added
                DSRecord ds = dsSet.get(0);
                findings.add(new Finding(
                        ErrorId.B1, CRITICAL, ds.getName(), Type.DS, ds.toString(),
                        Map.of("dsKeyTag", String.valueOf(ds.getFootprint()),
                                "zoneKeyTags", keyTags(keys))
                ));
            }
        }

        // B8: Inconsistent DS set (at least one DS matches, but another is an orphan/invalid)

        else if (!unmatchedDs.isEmpty()) {
            for (DSRecord ds : unmatchedDs) {
                findings.add(new Finding(
                        ErrorId.B8, CRITICAL, ds.getName(), Type.DS, ds.toString(),
                        Map.of("dsKeyTag", String.valueOf(ds.getFootprint()),
                                "reason", "Orphan DS record in DS set")
                ));
            }
        }

        return findings;
    }

    private static String keyTags(List<DNSKEYRecord> keys) {
        return keys.stream()
                .map(k -> String.valueOf(k.getFootprint()))
                .collect(Collectors.joining(","));
    }
}
