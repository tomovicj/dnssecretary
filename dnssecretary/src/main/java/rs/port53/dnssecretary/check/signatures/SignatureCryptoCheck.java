package rs.port53.dnssecretary.check.signatures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.RRSIGRecord;

import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

public class SignatureCryptoCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.A4, ErrorId.A5);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();
        Instant now = ctx.now();

        SignedRRset dnskeySet = snapshot.dnskey();
        if (dnskeySet == null) {
            return findings; // Nema DNSKEY, to su druge greške (B4)
        }

        // Mapa ključeva: "algoritam/keyTag" -> DNSKEYRecord
        Map<String, DNSKEYRecord> keys = new HashMap<>();
        for (DNSKEYRecord k : dnskeySet.records(DNSKEYRecord.class)) {
            keys.put(k.getAlgorithm() + "/" + k.getFootprint(), k);
        }

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            for (RRSIGRecord sig : sr.sigs()) {
                String keyId = sig.getAlgorithm() + "/" + sig.getFootprint();
                DNSKEYRecord dnskey = keys.get(keyId);

                // A5: ključ ne postoji
                if (dnskey == null) {
                    findings.add(new Finding(
                            ErrorId.A5, Severity.CRITICAL, sr.name(), sr.type(),
                            "RRSIG koristi ključ footprint=" + sig.getFootprint() + " koga nema u DNSKEY skupu.",
                            Map.of("keyTag", String.valueOf(sig.getFootprint()))
                    ));
                    continue;
                }

                // A4: kriptografska provera
                try {
                    // U dnsjava 3.6.5, verify baca izuzetak ako ne prođe
                    DNSSEC.verify(sr.toRRset(), sig, dnskey, now);
                } catch (Exception e) {
                    findings.add(new Finding(
                            ErrorId.A4, Severity.CRITICAL, sr.name(), sr.type(),
                            "Kriptografska provera potpisa nije prošla.",
                            Map.of("keyTag", String.valueOf(sig.getFootprint()))
                    ));
                }
            }
        }
        return findings;
    }
}