package rs.port53.dnssecretary.dns;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.collect.ChainLink;
import rs.port53.dnssecretary.collect.CollectException;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.dns.DnsClient.Options;

/**
 * Iterative resolution from the root servers down to the delegation of a zone,
 * like a resolver does it, but remembering every zone cut on the way.
 */
public final class DelegationWalker {

    private static final int MAX_STEPS = 32;
    private static final int MAX_DEPTH = 4;   // nested lookups of name server addresses without glue

    /** A zone cut on the way down and the servers that answered for it. */
    public record Cut(Name zone, List<InetAddress> servers) {}

    /**
     * @param cuts     root → parent (the last one is the parent)
     * @param ns       NS names in the parent's delegation, with their addresses (glue or looked up)
     * @param referral the parent's answer that delegated to the zone
     */
    public record Delegation(Name zone, List<Cut> cuts, Map<Name, List<InetAddress>> ns, Response referral) {
        public Cut parent() {
            return cuts.get(cuts.size() - 1);
        }
    }

    private final DnsClient client;
    private final List<InetAddress> rootServers;
    private final boolean ipv6;

    public DelegationWalker(DnsClient client, List<InetAddress> rootServers, boolean ipv6) {
        if (rootServers.isEmpty()) {
            throw new IllegalArgumentException("No root servers configured");
        }
        this.client = client;
        this.rootServers = List.copyOf(rootServers);
        this.ipv6 = ipv6;
    }

    public Delegation walk(Name zone) throws CollectException {
        if (zone.equals(Name.root)) {
            throw new CollectException("Root zona nema roditelja i ne može se proveravati ovim alatom.");
        }
        List<Cut> cuts = new ArrayList<>();
        Cut cur = new Cut(Name.root, rootServers);
        cuts.add(cur);

        for (int step = 0; step < MAX_STEPS; step++) {
            Response r = client.queryAny(cur.servers(), zone, Type.NS, Options.AUTH);
            if (!r.ok()) {
                throw new CollectException("Nijedan server zone " + cur.zone() + " ne odgovara (" + r.error() + ").");
            }
            if (r.rcode() == Rcode.NXDOMAIN) {
                throw new CollectException("Zona " + zone + " ne postoji (NXDOMAIN od servera zone " + cur.zone() + ").");
            }
            if (r.rcode() != Rcode.NOERROR) {
                throw new CollectException("Server zone " + cur.zone() + " vraća " + Rcode.string(r.rcode())
                        + " za " + zone + ".");
            }

            Optional<SignedRRset> referral = referral(r, zone, cur.zone());
            if (referral.isPresent()) {
                Name cut = referral.get().name();
                Map<Name, List<InetAddress>> ns = addresses(nsNames(referral.get()), r, 0);
                if (cut.equals(zone)) {
                    return new Delegation(zone, cuts, ns, r);
                }
                List<InetAddress> servers = ns.values().stream().flatMap(List::stream).distinct().toList();
                if (servers.isEmpty()) {
                    throw new CollectException("Nema adresa name servera za zonu " + cut + ".");
                }
                cur = new Cut(cut, servers);
                cuts.add(cur);
                continue;
            }

            // No referral: the server is authoritative for the name itself. That is a zone
            // only if it has its own SOA (parent and child on the same server).
            if (r.aa() && !r.answer(zone, Type.NS).isEmpty()) {
                Response soa = client.queryAny(cur.servers(), zone, Type.SOA, Options.AUTH);
                if (!soa.answer(zone, Type.SOA).isEmpty()) {
                    Map<Name, List<InetAddress>> ns = addresses(nsNames(r.answer(zone, Type.NS)), r, 0);
                    return new Delegation(zone, cuts, ns, r);
                }
            }
            throw new CollectException(zone + " nije zona: server zone " + cur.zone() + " nema delegaciju za nju.");
        }
        throw new CollectException("Previše koraka pri traženju delegacije za " + zone + ".");
    }

    /** For every cut: its DNSKEY (from its own servers) and its DS (from the cut above it). */
    public List<ChainLink> chain(List<Cut> cuts) {
        List<ChainLink> chain = new ArrayList<>();
        for (int i = 0; i < cuts.size(); i++) {
            Cut c = cuts.get(i);
            SignedRRset dnskey = client.queryAny(c.servers(), c.zone(), Type.DNSKEY, Options.AUTH)
                    .answer(c.zone(), Type.DNSKEY);
            SignedRRset ds = i == 0
                    ? SignedRRset.empty(c.zone(), Type.DS)
                    : client.queryAny(cuts.get(i - 1).servers(), c.zone(), Type.DS, Options.AUTH)
                            .answer(c.zone(), Type.DS);
            chain.add(new ChainLink(c.zone(), c.servers(), ds, dnskey));
        }
        return chain;
    }

    /** Addresses of a host: glue from the response if there is any, otherwise iterative lookup from the root. */
    public List<InetAddress> resolve(Name host) {
        return resolve(host, 0);
    }

    private List<InetAddress> resolve(Name host, int depth) {
        List<InetAddress> out = new ArrayList<>();
        if (depth > MAX_DEPTH) {
            return out;
        }
        for (int type : addressTypes()) {
            List<InetAddress> servers = rootServers;
            for (int step = 0; step < MAX_STEPS && !servers.isEmpty(); step++) {
                Response r = client.queryAny(servers, host, type, Options.AUTH);
                if (!r.ok() || r.rcode() != Rcode.NOERROR) {
                    break;
                }
                SignedRRset answer = r.answer(host, type);
                if (!answer.isEmpty()) {
                    out.addAll(addressesOf(answer.records()));
                    break;
                }
                Optional<SignedRRset> referral = r.aa() ? Optional.empty() : referral(r, host, null);
                if (referral.isEmpty()) {
                    break;
                }
                servers = addresses(nsNames(referral.get()), r, depth + 1).values().stream()
                        .flatMap(List::stream).distinct().toList();
            }
        }
        return out;
    }

    /** NS name → addresses, from the additional section (glue) or looked up. Order kept. */
    private Map<Name, List<InetAddress>> addresses(List<Name> hosts, Response r, int depth) {
        Map<Name, List<InetAddress>> out = new LinkedHashMap<>();
        for (Name host : hosts) {
            List<InetAddress> glue = new ArrayList<>();
            for (int type : addressTypes()) {
                glue.addAll(addressesOf(find(r.additional(), host, type).records()));
            }
            out.put(host, glue.isEmpty() ? resolve(host, depth + 1) : glue);
        }
        return out;
    }

    private int[] addressTypes() {
        return ipv6 ? new int[] {Type.A, Type.AAAA} : new int[] {Type.A};
    }

    /**
     * A referral: not authoritative, NS RRset in the authority section for a name between
     * the current zone and the target (closer to the target than {@code current}).
     */
    private static Optional<SignedRRset> referral(Response r, Name target, Name current) {
        if (r.aa()) {
            return Optional.empty();
        }
        return r.authority(Type.NS).stream()
                .filter(ns -> target.subdomain(ns.name()))
                .filter(ns -> current == null || (!ns.name().equals(current) && ns.name().subdomain(current)))
                .findFirst();
    }

    private static List<Name> nsNames(SignedRRset ns) {
        Set<Name> names = new LinkedHashSet<>();
        for (Record r : ns.records()) {
            if (r instanceof NSRecord n) {
                names.add(n.getTarget());
            }
        }
        return List.copyOf(names);
    }

    private static List<InetAddress> addressesOf(List<Record> records) {
        List<InetAddress> out = new ArrayList<>();
        for (Record r : records) {
            if (r instanceof ARecord a) {
                out.add(a.getAddress());
            } else if (r instanceof AAAARecord a) {
                out.add(a.getAddress());
            }
        }
        return out;
    }

    private static SignedRRset find(List<SignedRRset> section, Name name, int type) {
        return section.stream()
                .filter(s -> s.type() == type && s.name().equals(name))
                .findFirst()
                .orElse(SignedRRset.empty(name, type));
    }
}
