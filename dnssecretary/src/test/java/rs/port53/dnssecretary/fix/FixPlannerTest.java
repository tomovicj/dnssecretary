package rs.port53.dnssecretary.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.dns.Dnssec;
import rs.port53.dnssecretary.testutil.LabConfig;
import rs.port53.dnssecretary.testutil.Snapshots;

/** Plans for the recorded lab zones: which action each detected error gets. */
class FixPlannerTest {

    private static final Catalog CATALOG = Catalog.load();

    static FixPlan plan(String zone) {
        ZoneSnapshot s = Snapshots.load(zone);
        var report = Analysis.run(s, Snapshots.context(s), CATALOG);
        return new FixPlanner(CATALOG, LabConfig.fix()).plan(s, report.diagnoses(), s.localKeys());
    }

    @Test
    void goodZoneNeedsNothing() {
        assertThat(plan(Snapshots.GOOD).isEmpty()).isTrue();
    }

    @Test
    void expiredSignaturesAreFixedByResigning() {
        FixPlan p = plan("a1-expired.test");
        assertThat(p.actions()).hasSize(1);
        assertThat(p.resign()).hasValueSatisfying(a -> {
            assertThat(a.fixes()).containsExactly(ErrorId.A1);
            assertThat(a.maxTtl()).isNull();
            assertThat(a.nsec3()).isFalse();
        });
    }

    @Test
    void longTtlIsCappedWhenResigning() {
        assertThat(plan("a7-ttl-long.test").resign())
                .hasValueSatisfying(a -> assertThat(a.maxTtl()).isEqualTo(FixPlanner.MAX_TTL));
    }

    @Test
    void nsec3ZonesStayNsec3() {
        for (String zone : List.of("d4-nsec3-iter.test", "d5-nsec3param.test", "d6-nsec-mixed.test")) {
            assertThat(plan(zone).resign()).as(zone).hasValueSatisfying(a -> assertThat(a.nsec3()).isTrue());
        }
        assertThat(plan("d1-nsec-broken.test").resign())
                .hasValueSatisfying(a -> assertThat(a.nsec3()).isFalse());
    }

    @Test
    void wrongCdsIsRemovedByResigning() {
        assertThat(plan("e4-cds.test").resign())
                .hasValueSatisfying(a -> assertThat(a.fixes()).containsExactly(ErrorId.E4));
    }

    @Test
    void staleDsIsReplacedWithTheDsOfTheActiveKsk() {
        ZoneSnapshot s = Snapshots.load("b1-ds-stale.test");
        FixAction.UpdateParentDs a = plan("b1-ds-stale.test").parentDs().orElseThrow();
        assertThat(a.fixes()).contains(ErrorId.B1);
        assertThat(a.removed()).hasSize(1);
        assertThat(a.added()).hasSize(1);
        assertThat(pointsToLiveKey(a.added().get(0), s)).isTrue();
    }

    @Test
    void islandGetsItsDs() {
        FixAction.UpdateParentDs a = plan("b6-island.test").parentDs().orElseThrow();
        assertThat(a.current()).isEmpty();
        assertThat(a.added()).hasSize(1);
    }

    @Test
    void extraDsIsRemoved() {
        FixAction.UpdateParentDs a = plan("b8-ds-extra.test").parentDs().orElseThrow();
        assertThat(a.added()).isEmpty();
        assertThat(a.removed()).hasSize(1);
    }

    @Test
    void twoAlgorithmsAreFixedInTheZoneAndAtTheParent() {
        FixPlan p = plan("c2-two-alg.test");
        assertThat(p.resign()).isPresent();
        FixAction.UpdateParentDs a = p.parentDs().orElseThrow();
        assertThat(a.removed()).extracting(DSRecord::getAlgorithm).containsExactly(8);
        assertThat(a.added()).isEmpty();
    }

    @Test
    void earlyDsOfANewKskIsReplaced() {
        assertThat(plan("e2-ksk-ds-early.test").parentDs())
                .hasValueSatisfying(a -> assertThat(a.fixes()).containsExactly(ErrorId.E2));
    }

    @Test
    void weakKeysAreRolledOverAndTheirDsReplaced() {
        for (String zone : List.of("c1-rsasha1.test", "c3-rsa1024.test")) {
            FixPlan p = plan(zone);
            assertThat(p.actions()).as(zone).extracting(a -> a.getClass().getSimpleName())
                    .containsExactly("KeyRollover", "ResignZone", "PublishNewDs");
            FixAction.KeyRollover r = (FixAction.KeyRollover) p.actions().get(0);
            assertThat(r.old()).as(zone).isNotEmpty().allMatch(k -> k.location().isEmpty());
            assertThat(r.algorithm()).isEqualTo("ECDSAP256SHA256");
            assertThat(p.manual()).isEmpty();
        }
    }

    @Test
    void rolloverErrorsPutTheRetiredKeyBack() {
        for (String zone : List.of("e1-rollover.test", "e3-rollover.test")) {
            FixPlan p = plan(zone);
            assertThat(p.actions()).as(zone).extracting(a -> a.getClass().getSimpleName())
                    .containsExactly("RepublishKey", "ResignZone");
            FixAction.RepublishKey r = (FixAction.RepublishKey) p.actions().get(0);
            assertThat(r.keys()).as(zone).isNotEmpty().allMatch(k -> k.location().equals("retired"));
        }
    }

    @Test
    void staleSecondaryGetsACopy() {
        FixPlan p = plan("f1-stale-secondary.test");
        assertThat(p.actions()).hasSize(1);
        FixAction.CopyToSecondary c = (FixAction.CopyToSecondary) p.actions().get(0);
        assertThat(c.to()).extracting(sv -> Path.of(sv.dir()).getFileName().toString()).containsExactly("auth2");
    }

    @Test
    void serverWithoutTcpGetsItsConfigFixed() {
        FixPlan p = plan("f2-tcp-edns.test");
        assertThat(p.actions()).hasSize(1);
        FixAction.ServerConfig c = (FixAction.ServerConfig) p.actions().get(0);
        assertThat(Path.of(c.server().dir()).getFileName().toString()).isEqualTo("auth3");
    }

    /** Every lab zone gets a script; nothing is left as a manual step. */
    @Test
    void everyLabZoneIsFixedAutomatically() {
        for (String zone : Snapshots.allZones()) {
            FixPlan p = plan(zone);
            assertThat(p.manual()).as(zone).isEmpty();
            assertThat(p.automatic() || p.isEmpty()).as(zone).isTrue();
        }
    }

    private static boolean pointsToLiveKey(DSRecord ds, ZoneSnapshot s) {
        List<DNSKEYRecord> keys = s.dnskey().records(DNSKEYRecord.class);
        return keys.stream().anyMatch(k -> Dnssec.dsMatches(ds, k));
    }
}
