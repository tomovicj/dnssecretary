package rs.port53.dnssecretary.check.algorithms;

import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** C3: RSA keys shorter than 2048 bits. */
public final class KeyStrengthCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return EnumSet.of(ErrorId.C3);
    }

    @Override
    public List<Finding> run(ZoneSnapshot s, CheckContext ctx) {
        List<Finding> out = new ArrayList<>();

        for (DNSKEYRecord key : s.dnskey().records(DNSKEYRecord.class)) {
            try {
                // For RSA, dnsjava's getPublicKey() returns an RSAPublicKey
                if (key.getPublicKey() instanceof RSAPublicKey rsa) {
                    int bits = rsa.getModulus().bitLength();
                    if (bits < ctx.thresholds().minRsaBits()) {
                        out.add(new Finding(
                                ErrorId.C3,
                                Severity.RISK,
                                s.zone(),
                                Type.DNSKEY,
                                key.toString(),
                                Map.of("keyTag", String.valueOf(key.getFootprint()), "bits", String.valueOf(bits))
                        ));
                    }
                }
            } catch (Exception e) {
                // A key dnsjava can't parse is not a weak RSA key; skip it
            }
        }

        return out;
    }
}
