package rs.port53.dnssecretary.collect;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

/**
 * Everything collected about one zone at one moment. Pure data – checks never do I/O.
 * Saved as JSON by {@link SnapshotIO}; unit tests run on saved snapshots.
 *
 * <p>Sources (column "Izvor" in docs/predlog-java.md 3.5):
 * P = {@link #parentDs}, {@link #chain}; L = {@link #nameservers}; Z = {@link #fullZone};
 * R = {@link #resolver}; K = {@link #localKeys}.
 *
 * @param parent      the zone the delegation comes from (test. in the lab)
 * @param trustAnchor the configured anchor (root DS or DNSKEY records)
 * @param chain       root → parent: each zone's DNSKEY and its DS from its own parent.
 *                    Validate with {@code ChainValidator}; the last link is the parent.
 * @param parentDs    the parent's answer to "&lt;zone&gt; DS": the DS RRset + RRSIG, or a negative
 *                    answer whose authority section holds the NSEC/NSEC3 proof that there is no DS (B6)
 * @param delegation  NS names the parent delegates to
 * @param nameservers one view per name server address (from the delegation and from the zone's apex NS)
 * @param fullZone    all records of the zone (file / AXFR), if available
 * @param localKeys   public keys of this zone from the key directory (empty if not configured)
 * @param resolver    the resolver's view, if a resolver is configured
 * @param warnings    problems while collecting (Serbian, for the report), e.g. "AXFR odbijen"
 */
public record ZoneSnapshot(
        Name zone,
        Instant collectedAt,
        Name parent,
        List<Record> trustAnchor,
        List<ChainLink> chain,
        Response parentDs,
        List<Name> delegation,
        List<NameserverView> nameservers,
        Optional<List<Record>> fullZone,
        List<LocalKey> localKeys,
        Optional<ResolverView> resolver,
        List<String> warnings) {

    public ZoneSnapshot {
        trustAnchor = List.copyOf(trustAnchor);
        chain = List.copyOf(chain);
        delegation = List.copyOf(delegation);
        nameservers = List.copyOf(nameservers);
        fullZone = fullZone == null ? Optional.empty() : fullZone.map(List::copyOf);
        localKeys = localKeys == null ? List.of() : List.copyOf(localKeys);
        resolver = resolver == null ? Optional.empty() : resolver;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The parent's DNSKEY RRset (signs the DS). Empty if the chain is empty. */
    @JsonIgnore
    public SignedRRset parentDnskey() {
        return chain.isEmpty() ? SignedRRset.empty(parent, Type.DNSKEY) : chain.get(chain.size() - 1).dnskey();
    }

    /** The DNSKEY RRset as served by the first name server that answered (empty if none did). */
    @JsonIgnore
    public SignedRRset dnskey() {
        return nameservers.stream()
                .map(ns -> ns.dnskey().rrset())
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(SignedRRset.empty(zone, Type.DNSKEY));
    }

    /** The full zone grouped into RRsets with their RRSIGs (empty list if there is no full zone). */
    @JsonIgnore
    public List<SignedRRset> zoneRRsets() {
        return fullZone.map(SignedRRset::group).orElse(List.of());
    }
}
