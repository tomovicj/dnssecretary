package rs.port53.dnssecretary.check.signatures;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xbill.DNS.RRSIGRecord;

import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** A7: RRset TTL longer than the remaining validity of its RRSIG. */
public class TtlCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.A7);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();
        Instant now = ctx.now();

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            long dataTtl = sr.ttl();

            for (RRSIGRecord sig : sr.sigs()) {
                long remainingSec = Duration.between(now, sig.getExpire()).getSeconds();

                if (remainingSec <= 0) {
                    continue; // that's A1, not A7
                }

                if (dataTtl > remainingSec) {
                    findings.add(new Finding(
                            ErrorId.A7, Severity.RISK, sr.name(), sr.type(),
                            "TTL (" + dataTtl + "s) is longer than the remaining signature validity (" + remainingSec + "s).",
                            Map.of("ttl", String.valueOf(dataTtl), "remaining", String.valueOf(remainingSec))
                    ));
                    break;
                }
            }
        }
        return findings;
    }
}
