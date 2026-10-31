package rs.port53.dnssecretary.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import rs.port53.dnssecretary.dns.Addresses;

/**
 * The tool's configuration (config/lab.yaml). Nothing about the lab is hard-coded:
 * the same jar runs on the real DNS with another file.
 */
public record AppConfig(Dns dns, Thresholds thresholds, Collect collect, Fix fix) {

    /**
     * @param rootServers    where iterative resolution starts
     * @param trustAnchor    file with the root DS (Unbound format) or DNSKEY records
     * @param resolver       validating resolver for the "second opinion" and the cache view; optional
     * @param timeoutMs      per query
     * @param ednsBufferSize advertised in every query (RFC 9715 / DNS flag day 2020: 1232)
     * @param ipv6           also use AAAA addresses of name servers
     */
    public record Dns(List<String> rootServers, String trustAnchor, String resolver,
                      int timeoutMs, int ednsBufferSize, boolean ipv6) {

        public List<InetAddress> rootAddresses() {
            return rootServers.stream().map(Addresses::parse).toList();
        }

        public Optional<InetAddress> resolverAddress() {
            return resolver == null || resolver.isBlank() ? Optional.empty() : Optional.of(Addresses.parse(resolver));
        }

        public Duration timeout() {
            return Duration.ofMillis(timeoutMs);
        }
    }

    /**
     * @param zoneSource   file | axfr | none – where the whole zone comes from
     * @param zoneFileDirs for "file": directories with &lt;zone&gt;.zone.signed, first match wins
     * @param keyDir       optional: BIND key directory; public keys go into the snapshot (B1 vs E2)
     * @param samples      queries asked at every name server, "&lt;name|@&gt; &lt;type&gt;"
     * @param nodataType   type for the NODATA probe at the apex
     */
    public record Collect(String zoneSource, List<String> zoneFileDirs, String keyDir,
                          List<String> samples, String nodataType) {}

    /** Used by fix (role 5). */
    public record Fix(String sourceDir, String keyDir, String workDir, Map<String, String> deliver, Parent parent) {

        /** @param mode lab | instructions */
        public record Parent(String mode, String dsDir, List<String> resignCommand) {}
    }

    public static AppConfig load(Path file) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var in = Files.newInputStream(file)) {
            AppConfig c = yaml.readValue(in, AppConfig.class);
            return c.withDefaults();
        }
    }

    /** Without a config file: only offline work (check --from, catalog). */
    public static AppConfig defaults() {
        return new AppConfig(null, Thresholds.defaults(), null, null).withDefaults();
    }

    private AppConfig withDefaults() {
        Collect c = collect != null ? collect : new Collect("none", List.of(), null, null, null);
        c = new Collect(
                c.zoneSource() != null ? c.zoneSource() : "none",
                c.zoneFileDirs() != null ? c.zoneFileDirs() : List.of(),
                c.keyDir(),
                c.samples() != null ? c.samples() : List.of("www A", "@ NS", "@ MX", "@ TXT"),
                c.nodataType() != null ? c.nodataType() : "SRV");
        Dns d = dns;
        if (d != null) {
            d = new Dns(d.rootServers() != null ? d.rootServers() : List.of(), d.trustAnchor(), d.resolver(),
                    d.timeoutMs() > 0 ? d.timeoutMs() : 3000,
                    d.ednsBufferSize() > 0 ? d.ednsBufferSize() : 1232,
                    d.ipv6());
        }
        return new AppConfig(d, thresholds != null ? thresholds : Thresholds.defaults(), c, fix);
    }
}
