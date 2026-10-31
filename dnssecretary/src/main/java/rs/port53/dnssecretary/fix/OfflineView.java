package rs.port53.dnssecretary.fix;

import java.util.List;
import java.util.Optional;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;

/**
 * The snapshot as it will look after the fix, before anything is delivered (offline verify,
 * docs/java-proposal.md 3.8): the new signed zone instead of the old one, its apex sets (SOA,
 * DNSKEY, CDS, CDNSKEY, NSEC3PARAM) in every name server's answers, and the new DS set in the
 * parent's answer. What only the live servers can answer (samples, negative answers) and the
 * resolver view are dropped; verify checks them after the reload.
 */
public final class OfflineView {

    private OfflineView() {}

    public static ZoneSnapshot after(ZoneSnapshot s, Optional<List<Record>> newZone,
                                     Optional<List<DSRecord>> newDs) {
        List<Record> zone = newZone.orElse(null);
        List<NameserverView> nameservers = s.nameservers();
        if (zone != null) {
            List<SignedRRset> sets = SignedRRset.group(zone);
            nameservers = nameservers.stream().map(ns -> new NameserverView(ns.host(), ns.address(),
                    answered(ns.soa(), apex(sets, s, Type.SOA)), answered(ns.dnskey(), apex(sets, s, Type.DNSKEY)),
                    answered(ns.cds(), apex(sets, s, Type.CDS)), answered(ns.cdnskey(), apex(sets, s, Type.CDNSKEY)),
                    answered(ns.nsec3param(), apex(sets, s, Type.NSEC3PARAM)), List.of(), List.of(),
                    ns.transport())).toList();
        }
        Response parentDs = s.parentDs();
        if (newDs.isPresent()) {
            List<DSRecord> ds = newDs.get();
            long ttl = ds.isEmpty() ? 0 : ds.get(0).getTTL();
            // The parent re-signs its zone, so the DS signature is not part of what we check here.
            parentDs = answered(parentDs, new SignedRRset(s.zone(), Type.DS, ttl, List.copyOf(ds),
                    parentDs.rrset().sigs()));
        }
        return new ZoneSnapshot(s.zone(), s.collectedAt(), s.parent(), s.trustAnchor(), s.chain(), parentDs,
                s.delegation(), nameservers, zone != null ? Optional.of(zone) : s.fullZone(), s.localKeys(),
                Optional.empty(), s.warnings());
    }

    private static SignedRRset apex(List<SignedRRset> sets, ZoneSnapshot s, int type) {
        return sets.stream()
                .filter(r -> r.type() == type && r.name().equals(s.zone()))
                .findFirst()
                .orElse(SignedRRset.empty(s.zone(), type));
    }

    /** The same question, answered with this RRset (NOERROR, nothing in authority). */
    private static Response answered(Response r, SignedRRset set) {
        return new Response(r.qname(), r.qtype(), r.server(), r.transport(), Rcode.NOERROR, true, false, false,
                set.isEmpty() ? List.of() : List.of(set), List.of(), r.additional(), r.ednsPayload(), r.size(),
                List.of(), null);
    }
}
