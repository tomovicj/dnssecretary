package rs.port53.dnssecretary.check.algorithms;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** C1, C2: deprecated algorithms and keys with multiple algorithms that aren't fully signed. */
public final class AlgorithmPolicyCheck implements Check {

    // Algoritmi koji se smatraju zastarelim (RFC 8624)
    private static final Set<Integer> DEPRECATED_ALGS = Set.of(1, 3, 5, 6, 7, 12);

    @Override
    public Set<ErrorId> detects() {
        // Registruj tek kada run() bude spreman
        return EnumSet.of(ErrorId.C1, ErrorId.C2);
    }

    @Override
    public List<Finding> run(ZoneSnapshot s, CheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        List<DNSKEYRecord> keys = s.dnskey().records(DNSKEYRecord.class);

        if (keys.isEmpty()) return out;

        // --- C1: Zastareli algoritmi ---
        for (DNSKEYRecord key : keys) {
            if (DEPRECATED_ALGS.contains(key.getAlgorithm())) {
                out.add(new Finding(
                        ErrorId.C1,
                        Severity.RISK,
                        s.zone(), // <--- SAMO OVAKO
                        Type.DNSKEY,
                        key.toString(),
                        Map.of("algorithm", String.valueOf(key.getAlgorithm()))
                ));
            }
        }

        // --- C2: Više algoritama, potpisi pokrivaju samo neke ---
        Set<Integer> keyAlgs = keys.stream()
                .map(DNSKEYRecord::getAlgorithm)
                .collect(Collectors.toSet());

        if (keyAlgs.size() > 1) {
            // Skupimo sve algoritme koji se stvarno koriste u RRSIG-ovima
            Set<Integer> sigAlgs = s.zoneRRsets().stream()
                    .flatMap(set -> set.sigs().stream())
                    .map(RRSIGRecord::getAlgorithm)
                    .collect(Collectors.toSet());

            // Ako neki ključ (algoritam) nema nijedan svoj potpis, to je C2
            Set<Integer> missingAlgs = keyAlgs.stream()
                    .filter(alg -> !sigAlgs.contains(alg))
                    .collect(Collectors.toSet());

            if (!missingAlgs.isEmpty()) {
                out.add(new Finding(
                        ErrorId.C2,
                        Severity.CRITICAL,
                        s.zone(), // <--- SAMO OVAKO
                        Type.DNSKEY,
                        "Keys have algorithms " + keyAlgs + " but signatures only for " + sigAlgs,
                        Map.of("missingAlgorithms", missingAlgs.toString())
                ));
            }
        }

        return out;
    }
}