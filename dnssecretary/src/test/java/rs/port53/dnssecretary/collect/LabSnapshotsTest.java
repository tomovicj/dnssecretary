package rs.port53.dnssecretary.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.dns.ChainValidator;
import rs.port53.dnssecretary.dns.Dnssec;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.testutil.Snapshots;

/**
 * The recorded snapshots contain what the checks need. If one of these fails after
 * re-recording, collect or the lab changed – not a check.
 */
class LabSnapshotsTest {

    static List<String> zones() {
        return Snapshots.allZones();
    }

    @ParameterizedTest
    @MethodSource("zones")
    void everySnapshotIsComplete(String zone) {
        ZoneSnapshot s = Snapshots.load(zone);
        assertThat(s.zone()).isEqualTo(Records.name(zone));
        assertThat(s.parent()).isEqualTo(Records.name("test."));
        assertThat(ChainLink.names(s.chain())).containsExactly(Records.name("."), Records.name("test."));
        assertThat(ChainValidator.validate(s.trustAnchor(), s.chain(), s.collectedAt()).secure()).isTrue();
        assertThat(s.parentDs().ok()).isTrue();
        assertThat(s.nameservers()).isNotEmpty().allSatisfy(ns -> assertThat(ns.reachable()).isTrue());
        assertThat(s.fullZone()).isPresent();
        assertThat(s.resolver()).isPresent();
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void goodZoneIsSecureEverywhere() {
        ZoneSnapshot s = Snapshots.load(Snapshots.GOOD);
        List<DSRecord> ds = s.parentDs().rrset().records(DSRecord.class);
        List<DNSKEYRecord> keys = s.dnskey().records(DNSKEYRecord.class);
        List<DNSKEYRecord> ksk = keys.stream().filter(k -> ds.stream().anyMatch(d -> Dnssec.dsMatches(d, k))).toList();

        assertThat(ksk).hasSize(1);
        assertThat(Dnssec.isSignedBy(s.parentDs().rrset(), s.parentDnskey().records(DNSKEYRecord.class),
                s.collectedAt())).isTrue();
        assertThat(Dnssec.isSignedBy(s.dnskey(), ksk, s.collectedAt())).isTrue();
        assertThat(s.resolver().orElseThrow().answers()).allSatisfy(a -> assertThat(a.ad()).isTrue());
    }

    @Test
    void islandHasProofThatThereIsNoDs() {
        ZoneSnapshot s = Snapshots.load("b6-island.test");
        assertThat(s.parentDs().rrset().isEmpty()).isTrue();
        assertThat(s.parentDs().isNodata()).isTrue();
        assertThat(s.parentDs().authority(Type.NSEC)).isNotEmpty();
    }

    @Test
    void secondaryOfF1IsSeparateNameserver() {
        ZoneSnapshot s = Snapshots.load("f1-stale-secondary.test");
        assertThat(s.nameservers()).extracting(ns -> ns.address().getHostAddress())
                .containsExactly("10.53.0.11", "10.53.0.12");
    }

    @Test
    void serverWithoutTcpIsVisible() {
        ZoneSnapshot s = Snapshots.load("f2-tcp-edns.test");
        NameserverView ns = s.nameservers().get(0);
        assertThat(ns.transport().tcp()).isFalse();
        assertThat(ns.samples()).anySatisfy(r -> {
            assertThat(r.qname()).isEqualTo(Records.name("big.f2-tcp-edns.test."));
            assertThat(r.tc()).isTrue();
            assertThat(r.error()).contains("TCP");
        });
    }

    @Test
    void cachedViewsOfRollovers() {
        // E1: the resolver still has the old DNSKEY set, the zone signs with a key that isn't in it.
        ZoneSnapshot e1 = Snapshots.load("e1-rollover.test");
        ResolverView r1 = e1.resolver().orElseThrow();
        assertThat(r1.dnskey().rrset().records()).isNotEqualTo(e1.dnskey().records());
        assertThat(r1.answers()).anySatisfy(a -> assertThat(a.rcode()).isEqualTo(Rcode.SERVFAIL));

        // E3: the resolver answers www from its cache with an RRSIG by a key the zone no longer has.
        ZoneSnapshot e3 = Snapshots.load("e3-rollover.test");
        Response www = e3.resolver().orElseThrow().answers().get(0);
        int cachedTag = www.rrset().sigs().get(0).getFootprint();
        assertThat(e3.dnskey().records(DNSKEYRecord.class)).noneMatch(k -> k.getFootprint() == cachedTag);
    }

    @Test
    void keyDirectoryShowsTheNextKsk() {
        ZoneSnapshot s = Snapshots.load("e2-ksk-ds-early.test");
        assertThat(s.localKeys()).extracting(LocalKey::location).contains("", "next");
    }
}
