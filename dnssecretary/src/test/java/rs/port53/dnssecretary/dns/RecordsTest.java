package rs.port53.dnssecretary.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.collect.FileZoneSource;
import rs.port53.dnssecretary.collect.SignedRRset;

class RecordsTest {

    private final Name zone = Records.name("example.test.");

    @Test
    void probes() {
        assertThat(Records.probe("www A", zone)).isEqualTo(new Records.Probe(Records.name("www.example.test."), Type.A));
        assertThat(Records.probe("@ CDS", zone)).isEqualTo(new Records.Probe(zone, Type.CDS));
        assertThatThrownBy(() -> Records.probe("www", zone)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordRoundTrip() {
        Record r = Records.parse("www.example.test.\t3600\tIN\tA\t10.53.0.80");
        assertThat(Records.parse(r.toString())).isEqualTo(r);
        assertThat(r.getTTL()).isEqualTo(3600);
    }

    @Test
    void addressesAreLiteralsOnly() {
        assertThat(Addresses.parse("10.53.0.2").getHostAddress()).isEqualTo("10.53.0.2");
        assertThatThrownBy(() -> Addresses.parse("ns1.example.test")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zoneFileIsGroupedIntoSignedRRsets() throws Exception {
        List<String> warnings = new java.util.ArrayList<>();
        List<Record> all = new FileZoneSource(List.of(resourceDir())).load(zone, List.of(), warnings).orElseThrow();
        assertThat(warnings).isEmpty();

        List<SignedRRset> sets = SignedRRset.group(all);
        SignedRRset www = sets.stream().filter(s -> s.type() == Type.A && s.name().equals(Records.name("www.example.test.")))
                .findFirst().orElseThrow();
        assertThat(www.records()).hasSize(2);
        assertThat(www.sigs()).hasSize(1);
        assertThat(www.ttl()).isEqualTo(300);
        // An RRSIG whose RRset is missing still shows up (A3-like data).
        assertThat(sets).anySatisfy(s -> {
            assertThat(s.type()).isEqualTo(Type.TXT);
            assertThat(s.isEmpty()).isTrue();
            assertThat(s.isSigned()).isTrue();
        });
    }

    private Path resourceDir() throws URISyntaxException {
        return Path.of(getClass().getResource("/zones/example.test.zone.signed").toURI()).getParent();
    }
}
