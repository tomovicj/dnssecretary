package rs.port53.dnssecretary.collect;

import java.net.InetAddress;
import java.util.List;

/**
 * The same questions through a validating resolver: a second opinion (rcode, AD, EDE)
 * and a view of its cache (E1, E3).
 *
 * @param dnskey  the zone's DNSKEY with CD=1, asked FIRST: what the resolver has in its cache
 *                (the TTL is the remaining cache TTL), even if it can't validate it
 * @param answers the sample queries and the catalogue probe, asked normally (RD=1, DO=1, CD=0)
 */
public record ResolverView(InetAddress address, Response dnskey, List<Response> answers) {

    public ResolverView {
        answers = List.copyOf(answers);
    }
}
