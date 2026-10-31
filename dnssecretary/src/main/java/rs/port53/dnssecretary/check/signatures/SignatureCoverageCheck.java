package rs.port53.dnssecretary.check.signatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

public class SignatureCoverageCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.A3);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            if (sr.sigs().isEmpty()) {
                findings.add(new Finding(
                        ErrorId.A3, Severity.CRITICAL, sr.name(), sr.type(),
                        "RRset nema nijedan RRSIG.",
                        Map.of()
                ));
            }
        }
        return findings;
    }
}