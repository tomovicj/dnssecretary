package rs.port53.dnssecretary.collect;

import java.net.InetAddress;
import java.util.List;
import org.xbill.DNS.Name;

/**
 * What one authoritative server (one address) returned. All queries are made with
 * the DO bit, RD=0, EDNS {@code ednsBufferSize}; truncated UDP answers are retried over TCP.
 *
 * <p>Apex sets: {@code ns.dnskey().rrset()} is the DNSKEY RRset with its RRSIGs.
 *
 * @param host     the NS name (from the parent delegation and/or the zone's apex NS)
 * @param samples  the configured sample queries (www A, @ NS, …) plus the catalogue probe
 * @param denials  [0] a name that surely doesn't exist (NXDOMAIN), [1] NODATA at the apex
 */
public record NameserverView(
        Name host,
        InetAddress address,
        Response soa,
        Response dnskey,
        Response cds,
        Response cdnskey,
        Response nsec3param,
        List<Response> samples,
        List<Response> denials,
        TransportResult transport) {

    public NameserverView {
        samples = List.copyOf(samples);
        denials = List.copyOf(denials);
    }

    /** Did the server answer at all (SOA query)? */
    public boolean reachable() {
        return soa.ok();
    }
}
