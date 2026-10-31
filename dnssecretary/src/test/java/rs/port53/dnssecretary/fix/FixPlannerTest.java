package rs.port53.dnssecretary.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.dns.Dnssec;
import rs.port53.dnssecretary.testutil.Snapshots;

/** Plans for the recorded lab zones: which action each detected error gets. */
class FixPlannerTest {

    private static final Catalog CATALOG = Catalog.load();

    static FixPlan plan(String zone) {
        ZoneSnapshot s = Snapshots.load(zone);
        var report = Analysis.run(s, Snapshots.context(s), CATALOG);
        return new FixPlanner(CATALOG).plan(s, report.diagnoses(), s.localKeys());
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
        });
    }

    @Test
    void longTtlIsCappedWhenResigning() {
        assertThat(plan("a7-ttl-long.test").resign())
                .hasValueSatisfying(a -> assertThat(a.maxTtl()).isEqualTo(FixPlanner.MAX_TTL));
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
    void weakKeyIsAManualStep() {
        FixPlan p = plan("c3-rsa1024.test");
        assertThat(p.actions()).hasSize(1);
        assertThat(p.manual()).extracting(FixAction.Manual::id).containsExactly(ErrorId.C3);
        assertThat(p.fixes()).isEmpty();
    }

    private static boolean pointsToLiveKey(DSRecord ds, ZoneSnapshot s) {
        List<DNSKEYRecord> keys = s.dnskey().records(DNSKEYRecord.class);
        return keys.stream().anyMatch(k -> Dnssec.dsMatches(ds, k));
    }
}
