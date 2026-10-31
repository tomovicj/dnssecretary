package rs.port53.dnssecretary.check.rollover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.RRSIGRecord;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ResolverView;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** E1, E3: Cache consistency checks against the resolver. */
public final class CacheConsistencyCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.E1, ErrorId.E3);
    }

    @Override
    public List<Finding> run(ZoneSnapshot s, CheckContext ctx) {
        List<Finding> out = new ArrayList<>();

        // 1. Z: Ključevi iz trenutne zone
        SignedRRset zoneKeysSet = s.dnskey();
        if (zoneKeysSet.isEmpty()) {
            return out; // Zona nema ključeve
        }

        List<DNSKEYRecord> zoneKeys = zoneKeysSet.records(DNSKEYRecord.class);
        Set<Integer> zoneKeyTags = zoneKeys.stream()
                .map(DNSKEYRecord::getFootprint)
                .collect(Collectors.toSet());

        // 2. R: Pristupamo resolveru (pošto je Optional, moramo da proverimo da li postoji)
        Optional<ResolverView> resolverOpt = s.resolver();
        if (resolverOpt.isEmpty()) {
            return out; // Nema resolvera, nema šta da proveravamo
        }
        ResolverView resolver = resolverOpt.get();

        // 3. Predmemorija (keš) resolvera
        Response cachedKeysResponse = resolver.dnskey();
        SignedRRset cachedKeysSet = cachedKeysResponse.rrset(); // Sada znamo da rrset() vraća SignedRRset

        Set<Integer> cachedKeyTags = cachedKeysSet.records(DNSKEYRecord.class).stream()
                .map(DNSKEYRecord::getFootprint)
                .collect(Collectors.toSet());

        // ---------- E1 LOGIKA ----------
        // E1: resolver vraća SERVFAIL; key tag novog ZSK-a nije u kešu resolvera[cite: 1]
        boolean hasServfail = resolver.answers().stream()
                .anyMatch(r -> r.rcode() == Rcode.SERVFAIL);

        if (hasServfail) {
            for (DNSKEYRecord zk : zoneKeys) {
                if (!cachedKeyTags.contains(zk.getFootprint())) {
                    out.add(new Finding(
                            ErrorId.E1, Severity.CRITICAL, zk.getName(), zk.getType(),
                            zk.toString(), Map.of("missing_in_cache_tag", String.valueOf(zk.getFootprint()))
                    ));
                }
            }
        }

        // ---------- E3 LOGIKA ----------
        // E3: RRSIG u odgovoru resolvera ima key tag koga nema u snapshot.dnskey()[cite: 1]
        for (Response resp : resolver.answers()) {
            for (SignedRRset rrset : resp.answer()) { // resp.answer() vraća List<SignedRRset>
                for (RRSIGRecord sig : rrset.sigs()) { // rrset.sigs() vraća List<RRSIGRecord>
                    if (!zoneKeyTags.contains(sig.getFootprint())) {
                        out.add(new Finding(
                                ErrorId.E3, Severity.CRITICAL, sig.getName(), sig.getTypeCovered(),
                                sig.toString(), Map.of("orphaned_rrsig_tag", String.valueOf(sig.getFootprint()))
                        ));
                    }
                }
            }
        }

        return out;
    }
}