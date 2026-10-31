package rs.port53.dnssecretary.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Diagnoser;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.testutil.Snapshots;

/**
 * The main detection test: for every catalogue entry, the checks + Diagnoser must report
 * that ID on the lab zone's snapshot. An entry is skipped until a registered check
 * declares it in {@link Check#detects()} – so registering a check switches its test on.
 */
class CatalogDetectionTest {

    private static final Catalog CATALOG = Catalog.load();
    private final CheckRunner runner = CheckRunner.withDefaultChecks();

    static List<CatalogEntry> entries() {
        return CATALOG.entries();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void detectsErrorOnItsLabZone(CatalogEntry e) {
        assumeTrue(runner.detectable().contains(e.id()), "no check detects " + e.id() + " yet");

        ZoneSnapshot s = Snapshots.load(e);
        List<Diagnosis> diagnoses = diagnose(s);

        assertThat(diagnoses).extracting(Diagnosis::id).contains(e.id());
    }

    /** No check may report anything on the correct zone. */
    @Test
    void goodZoneIsClean() {
        ZoneSnapshot s = Snapshots.load(Snapshots.GOOD);
        CheckRunner.Result r = runner.run(s, Snapshots.context(s));
        assertThat(r.failures()).isEmpty();
        assertThat(r.findings()).isEmpty();
    }

    private List<Diagnosis> diagnose(ZoneSnapshot s) {
        CheckContext ctx = Snapshots.context(s);
        CheckRunner.Result r = runner.run(s, ctx);
        assertThat(r.failures()).as("checks that threw").isEmpty();
        return Diagnoser.withDefaultRules(CATALOG).diagnose(r.findings(), s, ctx);
    }
}
