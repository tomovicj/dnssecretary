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

        // 1. The zone's current keys
        SignedRRset zoneKeysSet = s.dnskey();
        if (zoneKeysSet.isEmpty()) {
            return out; // the zone has no keys
        }

        List<DNSKEYRecord> zoneKeys = zoneKeysSet.records(DNSKEYRecord.class);
        Set<Integer> zoneKeyTags = zoneKeys.stream()
                .map(DNSKEYRecord::getFootprint)
                .collect(Collectors.toSet());

        // 2. The resolver view (optional)
        Optional<ResolverView> resolverOpt = s.resolver();
        if (resolverOpt.isEmpty()) {
            return out; // no resolver, nothing to compare
        }
        ResolverView resolver = resolverOpt.get();

        // 3. The DNSKEY set in the resolver's cache
        Response cachedKeysResponse = resolver.dnskey();
        SignedRRset cachedKeysSet = cachedKeysResponse.rrset();

        Set<Integer> cachedKeyTags = cachedKeysSet.records(DNSKEYRecord.class).stream()
                .map(DNSKEYRecord::getFootprint)
                .collect(Collectors.toSet());

        // E1: the resolver returns SERVFAIL and a key of the zone is not in its cache
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

        // E3: an RRSIG in the resolver's answers uses a key tag the zone no longer has
        for (Response resp : resolver.answers()) {
            for (SignedRRset rrset : resp.answer()) {
                for (RRSIGRecord sig : rrset.sigs()) {
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
