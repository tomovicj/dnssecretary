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

public class SignatureValidityCheck implements Check {

    // Prag za A6 (možeš kasnije premestiti u Thresholds)
    private static final Duration A6_MAX_REMAINING = Duration.ofDays(7);

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.A1, ErrorId.A2, ErrorId.A6);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();
        Instant now = ctx.now();

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            for (RRSIGRecord sig : sr.sigs()) {
                Instant inception = sig.getTimeSigned();
                Instant expire = sig.getExpire();

                // A2: inception u budućnosti
                if (inception.isAfter(now)) {
                    findings.add(new Finding(
                            ErrorId.A2, Severity.CRITICAL, sr.name(), sr.type(),
                            "RRSIG još nije važeći: počinje " + inception + ". Proveri NTP.",
                            Map.of("inception", inception.toString())
                    ));
                    continue;
                }

                // A1: istekao
                if (expire.isBefore(now)) {
                    findings.add(new Finding(
                            ErrorId.A1, Severity.CRITICAL, sr.name(), sr.type(),
                            "RRSIG je istekao " + expire + ".",
                            Map.of("expiration", expire.toString())
                    ));
                    continue;
                }

                // A6: uskoro ističe
                Duration remaining = Duration.between(now, expire);
                if (remaining.compareTo(A6_MAX_REMAINING) < 0) {
                    findings.add(new Finding(
                            ErrorId.A6, Severity.RISK, sr.name(), sr.type(),
                            "RRSIG ističe za " + remaining.toDays() + " dana.",
                            Map.of("days_left", String.valueOf(remaining.toDays()))
                    ));
                }
            }
        }
        return findings;
    }
}