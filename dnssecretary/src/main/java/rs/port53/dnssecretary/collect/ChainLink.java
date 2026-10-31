package rs.port53.dnssecretary.collect;

import java.net.InetAddress;
import java.util.List;
import org.xbill.DNS.Name;

/**
 * One zone on the way from the trust anchor to the parent of the checked zone.
 *
 * @param servers the zone's name servers (addresses) used during the walk
 * @param ds      the zone's DS from its parent (empty for the root)
 * @param dnskey  the zone's DNSKEY RRset with RRSIGs
 */
public record ChainLink(Name zone, List<InetAddress> servers, SignedRRset ds, SignedRRset dnskey) {

    public ChainLink {
        servers = List.copyOf(servers);
    }

    public static List<Name> names(List<ChainLink> chain) {
        return chain.stream().map(ChainLink::zone).toList();
    }
}
