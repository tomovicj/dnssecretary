package rs.port53.dnssecretary.check.servers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** F1: a secondary server has an older SOA serial (stale zone). */
public final class NameserverConsistencyCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return EnumSet.of(ErrorId.F1);
    }

    @Override
    public List<Finding> run(ZoneSnapshot s, CheckContext ctx) {
        List<Finding> out = new ArrayList<>();

        // The highest serial: most likely the primary (correct) server
        long maxSerial = -1;
        for (NameserverView ns : s.nameservers()) {
            List<SOARecord> soa = ns.soa().rrset().records(SOARecord.class);
            if (!soa.isEmpty()) {
                maxSerial = Math.max(maxSerial, soa.get(0).getSerial());
            }
        }

        if (maxSerial == -1) return out; // no SOA, nothing to compare

        // Any server with a lower serial is stale
        for (NameserverView ns : s.nameservers()) {
            List<SOARecord> soa = ns.soa().rrset().records(SOARecord.class);
            if (!soa.isEmpty()) {
                long serial = soa.get(0).getSerial();
                if (serial < maxSerial) {
                    out.add(new Finding(
                            ErrorId.F1,
                            Severity.CRITICAL,
                            s.zone(),
                            Type.SOA,
                            soa.get(0).toString(),
                            Map.of("nameserver", ns.address().getHostAddress(),
                                    "serial", String.valueOf(serial),
                                    "expectedSerial", String.valueOf(maxSerial))
                    ));
                }
            }
        }
        return out;
    }
}
