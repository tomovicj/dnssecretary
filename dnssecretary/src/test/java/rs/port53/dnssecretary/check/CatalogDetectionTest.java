package rs.port53.dnssecretary.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.catalog.ErrorId;
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

    /**
     * Errors a lab zone may report besides its own, because they are really there too.
     * A consequence of the zone's error is not an extra: hide it with a rule (ConsequenceRule).
     */
    private static final Map<ErrorId, Set<ErrorId>> EXTRAS = Map.of(
            ErrorId.A7, EnumSet.of(ErrorId.A6));   // its signatures are valid for only 7 days

    /** Precision: one error, one report. Overlaps make the report harder to read and the fix plan longer. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void reportsNothingElseOnItsLabZone(CatalogEntry e) {
        assumeTrue(runner.detectable().contains(e.id()) || e.id() == ErrorId.E2,
                "no check detects " + e.id() + " yet");

        Set<ErrorId> allowed = EnumSet.of(e.id());
        allowed.addAll(EXTRAS.getOrDefault(e.id(), Set.of()));
        assertThat(diagnose(Snapshots.load(e))).extracting(Diagnosis::id).isSubsetOf(allowed);
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
