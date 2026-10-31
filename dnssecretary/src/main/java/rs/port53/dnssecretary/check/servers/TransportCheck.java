package rs.port53.dnssecretary.check.servers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** F2: server doesn't support TCP or fails on large responses. */
public final class TransportCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return EnumSet.of(ErrorId.F2);
    }

    @Override
    public List<Finding> run(ZoneSnapshot s, CheckContext ctx) {
        List<Finding> out = new ArrayList<>();

        for (NameserverView ns : s.nameservers()) {
            // 1. Provera da li TCP uopšte radi
            if (ns.transport() != null && !ns.transport().tcp()) {
                out.add(new Finding(
                        ErrorId.F2,
                        Severity.CRITICAL,
                        s.zone(),
                        Type.ANY,
                        "TCP is not supported",
                        Map.of("nameserver", ns.address().getHostAddress(), "issue", "TCP disabled")
                ));
            }

            // 2. Provera da li je veliki odgovor (big TXT) pukao ili bio skraćen (TC flag)
            for (Response r : ns.samples()) {
                // Pretpostavka: tražimo probe koji ima grešku ili je truncated
                if (r.error() != null && r.error().contains("TCP")) {
                    out.add(new Finding(
                            ErrorId.F2,
                            Severity.CRITICAL,
                            s.zone(),
                            Type.TXT,
                            "Large response failed: " + r.error(),
                            Map.of("nameserver", ns.address().getHostAddress(), "error", r.error())
                    ));
                } else if (r.tc()) {
                    out.add(new Finding(
                            ErrorId.F2,
                            Severity.CRITICAL,
                            s.zone(),
                            Type.TXT,
                            "Response was truncated (TC=1)",
                            Map.of("nameserver", ns.address().getHostAddress(), "issue", "Truncated")
                    ));
                }
            }
        }
        return out;
    }
}