package rs.port53.dnssecretary.check.rollover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xbill.DNS.CDSRecord;
import org.xbill.DNS.DNSKEYRecord;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.dns.Dnssec;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** E4: CDS/CDNSKEY records against the zone's DNSKEY set. */
public final class CdsCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.E4);
    }

    @Override
    public List<Finding> run(ZoneSnapshot s, CheckContext ctx) {
        List<Finding> out = new ArrayList<>();

        SignedRRset zoneKeysSet = s.dnskey();
        if (zoneKeysSet.isEmpty()) {
            return out;
        }

        List<DNSKEYRecord> zoneKeys = zoneKeysSet.records(DNSKEYRecord.class);

        // Every name server separately
        for (NameserverView ns : s.nameservers()) {
            Response cdsResponse = ns.cds();

            // never null, but there may be no answer
            if (!cdsResponse.ok()) {
                continue;
            }

            SignedRRset cdsRrset = cdsResponse.rrset();
            if (cdsRrset.isEmpty()) {
                continue;
            }

            List<CDSRecord> cdsRecords = cdsRrset.records(CDSRecord.class);

            for (CDSRecord cds : cdsRecords) {
                // CDSRecord extends DSRecord, so Dnssec.dsMatches works as is
                boolean matches = zoneKeys.stream().anyMatch(k -> Dnssec.dsMatches(cds, k));
                if (!matches) {
                    out.add(new Finding(
                            ErrorId.E4, Severity.RISK, cds.getName(), cds.getType(),
                            cds.toString(), Map.of("nameserver", ns.address().getHostAddress())
                    ));
                }
            }
        }

        return out;
    }
}
