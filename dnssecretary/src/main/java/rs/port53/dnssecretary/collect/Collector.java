package rs.port53.dnssecretary.collect;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.dns.ChainValidator;
import rs.port53.dnssecretary.dns.DelegationWalker;
import rs.port53.dnssecretary.dns.DnsClient;
import rs.port53.dnssecretary.dns.DnsClient.Options;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.dns.Records.Probe;
import rs.port53.dnssecretary.dns.TrustAnchor;

/**
 * collect: asks the network everything the checks need about one zone and returns
 * a {@link ZoneSnapshot}. The only step (besides apply) that does I/O.
 * Steps follow docs/java-proposal.md 3.4.
 */
public final class Collector {

    private final AppConfig config;
    private final Clock clock;
    private final DnsClient client;
    private final DelegationWalker walker;
    private final ZoneSource zoneSource;

    public Collector(AppConfig config, Clock clock) {
        if (config.dns() == null) {
            throw new IllegalArgumentException("The config has no dns section");
        }
        this.config = config;
        this.clock = clock;
        this.client = new DnsClient(config.dns().timeout(), config.dns().ednsBufferSize());
        this.walker = new DelegationWalker(client, config.dns().rootAddresses(), config.dns().ipv6());
        this.zoneSource = ZoneSource.from(config.collect(), config.dns().timeout());
    }

    /**
     * @param extraProbes added to the configured samples, e.g. the catalogue probe "missing A" for D1
     */
    public ZoneSnapshot collect(Name zone, List<String> extraProbes) throws CollectException {
        Instant now = clock.instant();
        List<String> warnings = new ArrayList<>();

        // 0. trust anchor
        List<Record> anchors = List.of();
        if (config.dns().trustAnchor() != null) {
            try {
                anchors = TrustAnchor.load(Path.of(config.dns().trustAnchor()));
            } catch (IOException e) {
                warnings.add("Can't load the trust anchor " + config.dns().trustAnchor() + ": " + e.getMessage());
            }
        }

        // 1. delegation and the chain of trust down to the parent
        DelegationWalker.Delegation d = walker.walk(zone);
        List<ChainLink> chain = walker.chain(d.cuts());
        ChainValidator.Result chainResult = ChainValidator.validate(anchors, chain, now);
        if (!chainResult.secure()) {
            warnings.add("The chain of trust to the parent (" + d.parent().zone() + ") is broken: "
                    + String.join("; ", chainResult.problems()));
        }

        // 2. DS (or the proof that there is none) from the parent
        Response parentDs = client.queryAny(d.parent().servers(), zone, Type.DS, Options.AUTH);

        // 3. every name server of the zone, separately
        Map<Name, List<InetAddress>> ns = new LinkedHashMap<>(d.ns());
        addApexNameservers(zone, ns, warnings);
        List<Probe> samples = probes(zone, extraProbes);
        Name nxName = nxdomainName(zone);
        int nodataType = Records.type(config.collect().nodataType());

        List<NameserverView> views = new ArrayList<>();
        LinkedHashSet<InetAddress> seen = new LinkedHashSet<>();
        ns.forEach((host, addrs) -> {
            if (addrs.isEmpty()) {
                warnings.add("Name server " + host + " has no address.");
            }
            for (InetAddress a : addrs) {
                if (seen.add(a)) {
                    views.add(view(zone, host, a, samples, nxName, nodataType));
                }
            }
        });

        // 4. the whole zone
        Optional<List<Record>> full = zoneSource.load(zone, List.copyOf(seen), warnings);

        // K: public keys from the key directory
        List<LocalKey> keys = config.collect().keyDir() == null
                ? List.of()
                : KeyDirectory.load(Path.of(config.collect().keyDir()), zone, warnings);

        // 5. the resolver: second opinion + cache view
        Optional<ResolverView> resolver = config.dns().resolverAddress()
                .map(r -> resolverView(r, zone, samples, nxName));

        return new ZoneSnapshot(zone, now, d.parent().zone(), anchors, chain, parentDs,
                List.copyOf(d.ns().keySet()), views, full, keys, resolver, warnings);
    }

    /** NS names from the zone's own apex NS that the parent doesn't list (F1-like differences). */
    private void addApexNameservers(Name zone, Map<Name, List<InetAddress>> ns, List<String> warnings) {
        List<InetAddress> known = ns.values().stream().flatMap(List::stream).distinct().toList();
        if (known.isEmpty()) {
            return;
        }
        Response apex = client.queryAny(known, zone, Type.NS, Options.AUTH);
        for (Record r : apex.answer(zone, Type.NS).records()) {
            if (r instanceof NSRecord n && !ns.containsKey(n.getTarget())) {
                warnings.add("NS " + n.getTarget() + " is in the zone, but not in the parent's delegation.");
                ns.put(n.getTarget(), walker.resolve(n.getTarget()));
            }
        }
    }

    private NameserverView view(Name zone, Name host, InetAddress a, List<Probe> samples, Name nxName,
                                int nodataType) {
        Response soa = client.query(a, zone, Type.SOA, Options.AUTH);
        if (!soa.ok()) {
            // Unreachable: don't wait for a timeout on every other query.
            String why = "server doesn't answer SOA (" + soa.error() + ")";
            return new NameserverView(host, a, soa,
                    Response.failed(zone, Type.DNSKEY, a, "udp", why),
                    Response.failed(zone, Type.CDS, a, "udp", why),
                    Response.failed(zone, Type.CDNSKEY, a, "udp", why),
                    Response.failed(zone, Type.NSEC3PARAM, a, "udp", why),
                    samples.stream().map(p -> Response.failed(p.name(), p.type(), a, "udp", why)).toList(),
                    List.of(Response.failed(nxName, Type.A, a, "udp", why),
                            Response.failed(zone, nodataType, a, "udp", why)),
                    new TransportResult(false, false, why, null));
        }
        Response tcp = client.query(a, zone, Type.SOA, Options.AUTH_TCP);
        return new NameserverView(host, a, soa,
                client.query(a, zone, Type.DNSKEY, Options.AUTH),
                client.query(a, zone, Type.CDS, Options.AUTH),
                client.query(a, zone, Type.CDNSKEY, Options.AUTH),
                client.query(a, zone, Type.NSEC3PARAM, Options.AUTH),
                samples.stream().map(p -> client.query(a, p.name(), p.type(), Options.AUTH)).toList(),
                List.of(client.query(a, nxName, Type.A, Options.AUTH),
                        client.query(a, zone, nodataType, Options.AUTH)),
                new TransportResult(true, tcp.ok(), tcp.ok() ? null : tcp.error(), soa.ednsPayload()));
    }

    private ResolverView resolverView(InetAddress r, Name zone, List<Probe> samples, Name nxName) {
        // DNSKEY first, with CD: what is in the cache now, before our own queries refresh anything.
        Response dnskey = client.query(r, zone, Type.DNSKEY, Options.RECURSIVE_CD);
        List<Response> answers = new ArrayList<>();
        for (Probe p : samples) {
            answers.add(client.query(r, p.name(), p.type(), Options.RECURSIVE));
        }
        answers.add(client.query(r, nxName, Type.A, Options.RECURSIVE));
        return new ResolverView(r, dnskey, answers);
    }

    private List<Probe> probes(Name zone, List<String> extra) {
        LinkedHashSet<Probe> out = new LinkedHashSet<>();
        for (String s : config.collect().samples()) {
            out.add(Records.probe(s, zone));
        }
        for (String s : extra) {
            out.add(Records.probe(s, zone));
        }
        return List.copyOf(out);
    }

    /** A name that surely doesn't exist: dnssecretary-&lt;random&gt;.&lt;zone&gt; */
    private static Name nxdomainName(Name zone) {
        byte[] b = new byte[4];
        ThreadLocalRandom.current().nextBytes(b);
        try {
            return Name.fromString("dnssecretary-" + HexFormat.of().formatHex(b), zone);
        } catch (TextParseException e) {
            throw new IllegalStateException(e);
        }
    }
}
