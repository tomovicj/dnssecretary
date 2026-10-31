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

/** A1, A2, A6: RRSIG validity period in the zone file (expired, not yet valid, expiring soon). */
public class SignatureValidityCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.A1, ErrorId.A2, ErrorId.A6);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();
        Instant now = ctx.now();
        Duration warn = Duration.ofDays(ctx.thresholds().signatureExpiryWarnDays());

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            for (RRSIGRecord sig : sr.sigs()) {
                Instant inception = sig.getTimeSigned();
                Instant expire = sig.getExpire();

                // A2: inception in the future
                if (inception.isAfter(now)) {
                    findings.add(new Finding(
                            ErrorId.A2, Severity.CRITICAL, sr.name(), sr.type(),
                            "RRSIG not valid yet: starts " + inception + ". Check NTP.",
                            Map.of("inception", inception.toString())
                    ));
                    continue;
                }

                // A1: expired
                if (expire.isBefore(now)) {
                    findings.add(new Finding(
                            ErrorId.A1, Severity.CRITICAL, sr.name(), sr.type(),
                            "RRSIG expired " + expire + ".",
                            Map.of("expiration", expire.toString())
                    ));
                    continue;
                }

                // A6: expires soon
                Duration remaining = Duration.between(now, expire);
                if (remaining.compareTo(warn) < 0) {
                    findings.add(new Finding(
                            ErrorId.A6, Severity.RISK, sr.name(), sr.type(),
                            "RRSIG expires in " + remaining.toDays() + " days.",
                            Map.of("days_left", String.valueOf(remaining.toDays()))
                    ));
                }
            }
        }
        return findings;
    }
}
