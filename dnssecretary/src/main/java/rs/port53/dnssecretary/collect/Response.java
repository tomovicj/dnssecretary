package rs.port53.dnssecretary.collect;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.net.InetAddress;
import java.util.List;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.json.DnsJson;

/**
 * The answer one server gave to one query, reduced to what the checks need.
 * Records are grouped into RRsets with their RRSIGs.
 *
 * @param server      who was asked
 * @param transport   "udp" or "tcp" (tcp after a truncated UDP answer, or when asked over TCP)
 * @param rcode       org.xbill.DNS.Rcode; -1 when there was no answer at all (see error)
 * @param aa          authoritative answer
 * @param tc          the UDP answer was truncated (if TCP then worked, this answer is the TCP one)
 * @param ad          authenticated data (only meaningful from a validating resolver)
 * @param answer      answer section
 * @param authority   authority section: NS of a referral, SOA + NSEC/NSEC3 of a negative answer
 * @param additional  additional section without OPT (glue addresses)
 * @param ednsPayload the server's advertised EDNS UDP size, or null if the answer had no OPT
 * @param size        size of the answer in bytes (0 if none)
 * @param ede         Extended DNS Errors (RFC 8914)
 * @param error       null when there was an answer; otherwise why not, e.g. "timeout", "TCP: connection refused"
 */
public record Response(
        Name qname,
        @JsonSerialize(using = DnsJson.TypeSerializer.class)
        @JsonDeserialize(using = DnsJson.TypeDeserializer.class)
        int qtype,
        InetAddress server,
        String transport,
        @JsonSerialize(using = DnsJson.RcodeSerializer.class)
        @JsonDeserialize(using = DnsJson.RcodeDeserializer.class)
        int rcode,
        boolean aa,
        boolean tc,
        boolean ad,
        List<SignedRRset> answer,
        List<SignedRRset> authority,
        List<SignedRRset> additional,
        Integer ednsPayload,
        int size,
        List<Ede> ede,
        String error) {

    public Response {
        answer = answer == null ? List.of() : List.copyOf(answer);
        authority = authority == null ? List.of() : List.copyOf(authority);
        additional = additional == null ? List.of() : List.copyOf(additional);
        ede = ede == null ? List.of() : List.copyOf(ede);
    }

    public static Response failed(Name qname, int qtype, InetAddress server, String transport, String error) {
        return new Response(qname, qtype, server, transport, -1, false, false, false,
                List.of(), List.of(), List.of(), null, 0, List.of(), error);
    }

    /** There was an answer (any rcode). */
    @JsonIgnore
    public boolean ok() {
        return error == null && rcode >= 0;
    }

    @JsonIgnore
    public boolean isNxdomain() {
        return ok() && rcode == Rcode.NXDOMAIN;
    }

    /** NOERROR without the asked type at the asked name (and not a referral). */
    @JsonIgnore
    public boolean isNodata() {
        return ok() && rcode == Rcode.NOERROR && rrset().isEmpty() && answer(qname, Type.CNAME).isEmpty()
                && authority.stream().noneMatch(s -> s.type() == Type.NS);
    }

    /** The answer to the question itself: qname/qtype from the answer section (empty if none). */
    public SignedRRset rrset() {
        return answer(qname, qtype);
    }

    public SignedRRset answer(Name name, int type) {
        return find(answer, name, type);
    }

    /** All RRsets of a type from the authority section, e.g. NSEC, NSEC3 or SOA of a negative answer. */
    public List<SignedRRset> authority(int type) {
        return authority.stream().filter(s -> s.type() == type).toList();
    }

    private static SignedRRset find(List<SignedRRset> section, Name name, int type) {
        return section.stream()
                .filter(s -> s.type() == type && s.name().equals(name))
                .findFirst()
                .orElse(SignedRRset.empty(name, type));
    }
}
