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
 * docs/java-proposal.md 3.8): the new signed zone instead of the old one, its DNSKEY set in every
 * name server's DNSKEY answer, and the new DS set in the parent's answer. The resolver view is
 * dropped, because it can only be checked live.
 */
public final class OfflineView {

    private OfflineView() {}

    public static ZoneSnapshot after(ZoneSnapshot s, Optional<List<Record>> newZone,
                                     Optional<List<DSRecord>> newDs) {
        List<Record> zone = newZone.orElse(null);
        List<NameserverView> nameservers = s.nameservers();
        if (zone != null) {
            SignedRRset dnskey = SignedRRset.group(zone).stream()
                    .filter(r -> r.type() == Type.DNSKEY && r.name().equals(s.zone()))
                    .findFirst()
                    .orElse(SignedRRset.empty(s.zone(), Type.DNSKEY));
            nameservers = nameservers.stream().map(ns -> new NameserverView(ns.host(), ns.address(), ns.soa(),
                    answered(ns.dnskey(), dnskey), ns.cds(), ns.cdnskey(), ns.nsec3param(), ns.samples(),
                    ns.denials(), ns.transport())).toList();
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

    /** The same question, answered with this RRset (NOERROR, nothing in authority). */
    private static Response answered(Response r, SignedRRset set) {
        return new Response(r.qname(), r.qtype(), r.server(), r.transport(), Rcode.NOERROR, true, false, false,
                set.isEmpty() ? List.of() : List.of(set), List.of(), r.additional(), r.ednsPayload(), r.size(),
                List.of(), null);
    }
}
