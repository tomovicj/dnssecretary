package rs.port53.dnssecretary.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.testutil.Snapshots;

class VerifierTest {

    private static final Catalog CATALOG = Catalog.load();

    static Verifier.Result verify(String zone) {
        ZoneSnapshot s = Snapshots.load(zone);
        return Verifier.verify(s, Analysis.run(s, Snapshots.context(s), CATALOG));
    }

    @Test
    void goodZoneIsVerified() {
        Verifier.Result r = verify(Snapshots.GOOD);
        assertThat(r.resolver()).isPresent();
        assertThat(r.answers()).isNotEmpty().allMatch(Verifier.Answer::ok);
        assertThat(r.ok()).isTrue();
    }

    @Test
    void expiredZoneIsNot() {
        Verifier.Result r = verify("a1-expired.test");
        assertThat(r.checksOk()).isFalse();
        assertThat(r.resolverOk()).isFalse();
        assertThat(r.answers()).anyMatch(a -> a.rcode().equals("SERVFAIL"));
    }

    /** No check for B6 decides it alone: the resolver sees an unsigned (insecure) zone, no AD. */
    @Test
    void islandIsNotVerifiedByTheResolver() {
        assertThat(verify("b6-island.test").resolverOk()).isFalse();
    }
}
