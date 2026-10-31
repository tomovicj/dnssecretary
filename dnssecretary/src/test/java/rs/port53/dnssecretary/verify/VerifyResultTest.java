package rs.port53.dnssecretary.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.report.Report;
import rs.port53.dnssecretary.testutil.Snapshots;

class VerifyResultTest {

    private static final Catalog CATALOG = Catalog.load();

    static VerifyResult result(String zone) {
        ZoneSnapshot s = Snapshots.load(zone);
        Report report = Analysis.run(s, Snapshots.context(s), CATALOG);
        return VerifyResult.of(report, Verifier.verify(s, report));
    }

    @Test
    void goodZoneIsOk() {
        VerifyResult r = result(Snapshots.GOOD);
        assertThat(r.ok()).isTrue();
        assertThat(r.remaining()).isEmpty();
        assertThat(r.answers()).isNotEmpty().allMatch(VerifyResult.Answer::ad);
    }

    @Test
    void brokenZoneNamesWhatRemains() {
        VerifyResult r = result("a1-expired.test");
        assertThat(r.ok()).isFalse();
        assertThat(r.remaining()).containsExactly("A1");
        assertThat(r.answers()).anyMatch(a -> a.rcode().equals("SERVFAIL") && a.qtype().equals("A"));
    }

    @Test
    void jsonIsOneLineAndReadsBack() throws Exception {
        VerifyResult r = result("a1-expired.test");
        String json = r.toJson();
        assertThat(json).doesNotContain("\n").startsWith("{\"zone\":\"a1-expired.test.\"");
        assertThat(VerifyResult.fromJson(json)).isEqualTo(r);
    }
}
