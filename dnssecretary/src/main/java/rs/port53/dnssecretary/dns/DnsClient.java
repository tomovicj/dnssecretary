package rs.port53.dnssecretary.dns;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.DClass;
import org.xbill.DNS.EDNSOption;
import org.xbill.DNS.ExtendedErrorCodeOption;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import rs.port53.dnssecretary.collect.Ede;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.SignedRRset;

/**
 * Sends one query to one explicitly given server. There is no system resolver anywhere:
 * every query names its server.
 *
 * <p>Never throws on network problems: a timeout or a refused TCP connection is data
 * (F2), so it comes back as a {@link Response} with {@code error} set.
 */
public final class DnsClient {

    /**
     * @param rd       recursion desired (only towards a resolver)
     * @param cd       checking disabled: the resolver returns data even if it can't validate it
     * @param dnssecOk the DO bit: send RRSIG/NSEC/NSEC3
     * @param tcp      ask over TCP right away (otherwise UDP, and TCP only after a truncated answer)
     */
    public record Options(boolean rd, boolean cd, boolean dnssecOk, boolean tcp) {
        /** Iterative query to an authoritative server. */
        public static final Options AUTH = new Options(false, false, true, false);
        public static final Options AUTH_TCP = new Options(false, false, true, true);
        /** Normal query to a validating resolver. */
        public static final Options RECURSIVE = new Options(true, false, true, false);
        /** Resolver, checking disabled: what it has, valid or not. */
        public static final Options RECURSIVE_CD = new Options(true, true, true, false);
    }

    private final Duration timeout;
    private final int ednsBufferSize;

    public DnsClient(Duration timeout, int ednsBufferSize) {
        this.timeout = timeout;
        this.ednsBufferSize = ednsBufferSize;
    }

    public Response query(InetAddress server, Name name, int type, Options o) {
        Message q = Message.newQuery(Record.newRecord(name, type, DClass.IN));
        if (!o.rd()) {
            q.getHeader().unsetFlag(Flags.RD);
        }
        if (o.cd()) {
            q.getHeader().setFlag(Flags.CD);
        }
        q.addRecord(new OPTRecord(ednsBufferSize, 0, 0, o.dnssecOk() ? ExtendedFlags.DO : 0), Section.ADDITIONAL);

        if (o.tcp()) {
            try {
                return toResponse(name, type, server, "tcp", exchange(server, q, true), false, null);
            } catch (IOException e) {
                return Response.failed(name, type, server, "tcp", describe(e));
            }
        }

        Message udp;
        try {
            udp = exchange(server, q, false);
        } catch (IOException e) {
            return Response.failed(name, type, server, "udp", describe(e));
        }
        if (!udp.getHeader().getFlag(Flags.TC)) {
            return toResponse(name, type, server, "udp", udp, false, null);
        }
        // Truncated: retry over TCP like a resolver would. If TCP doesn't work (F2),
        // keep the truncated UDP answer and say why.
        try {
            return toResponse(name, type, server, "tcp", exchange(server, q, true), true, null);
        } catch (IOException e) {
            return toResponse(name, type, server, "udp", udp, true, "TC, TCP: " + describe(e));
        }
    }

    /**
     * Asks the servers in order and returns the first real answer
     * (not a timeout, SERVFAIL or REFUSED). If none gives one, the last response.
     */
    public Response queryAny(List<InetAddress> servers, Name name, int type, Options o) {
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("No servers to ask for " + name);
        }
        Response last = null;
        for (InetAddress s : servers) {
            last = query(s, name, type, o);
            if (last.ok() && last.rcode() != Rcode.SERVFAIL && last.rcode() != Rcode.REFUSED) {
                return last;
            }
        }
        return last;
    }

    private Message exchange(InetAddress server, Message q, boolean tcp) throws IOException {
        SimpleResolver r = new SimpleResolver(server);
        r.setTCP(tcp);
        r.setIgnoreTruncation(true);   // we handle TC ourselves, so we can see it
        r.setTimeout(timeout);
        return r.send(q.clone());
    }

    private static Response toResponse(Name name, int type, InetAddress server, String transport, Message m,
                                       boolean truncated, String error) {
        OPTRecord opt = m.getOPT();
        List<Ede> ede = new ArrayList<>();
        if (opt != null) {
            for (EDNSOption option : opt.getOptions(EDNSOption.Code.EDNS_EXTENDED_ERROR)) {
                if (option instanceof ExtendedErrorCodeOption e) {
                    ede.add(new Ede(e.getErrorCode(), e.getText()));
                }
            }
        }
        return new Response(name, type, server, transport, m.getRcode(),
                m.getHeader().getFlag(Flags.AA),
                truncated || m.getHeader().getFlag(Flags.TC),
                m.getHeader().getFlag(Flags.AD),
                SignedRRset.group(m.getSection(Section.ANSWER)),
                SignedRRset.group(m.getSection(Section.AUTHORITY)),
                SignedRRset.group(m.getSection(Section.ADDITIONAL)),
                opt == null ? null : opt.getPayloadSize(),
                m.numBytes(),
                ede,
                error);
    }

    static String describe(IOException e) {
        if (e instanceof SocketTimeoutException || e.getClass().getSimpleName().contains("Timeout")) {
            return "timeout";
        }
        if (e instanceof ConnectException) {
            return "connection refused";
        }
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
