package rs.port53.dnssecretary.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.testutil.Snapshots;

/** Applying parent-DS plans on recorded snapshots, with a fake signer (no BIND tools in unit tests). */
class FixExecutorTest {

    private static final Catalog CATALOG = Catalog.load();
    private static final String ZONE = "b1-ds-stale.test";

    @TempDir
    Path tmp;

    private final StringWriter text = new StringWriter();
    private final FakeSigner signer = new FakeSigner();

    @Test
    void labModeWritesTheDsReSignsTheParentAndListsTheReload() throws Exception {
        Fixture f = fixture(ZONE);
        FixExecutor.Result r = executor("lab").apply(f.plan, f.snapshot, f.found, f.ctx);

        assertThat(r.outcome()).isEqualTo(FixExecutor.Outcome.APPLIED);
        assertThat(r.after().diagnoses()).extracting(Diagnosis::id).doesNotContain(ErrorId.B1);
        Path ds = tmp.resolve("parent-ds/" + ZONE + ".ds");
        assertThat(Files.readAllLines(ds)).hasSize(1);
        assertThat(signer.commands).containsExactly(List.of("sh", "sign-tld.sh"));
        assertThat(Files.readAllLines(tmp.resolve("work/reload")))
                .containsExactly("restart tld", "flush " + ZONE + ".");
    }

    @Test
    void instructionsModeOnlyPrintsTheDs() throws Exception {
        Fixture f = fixture(ZONE);
        FixExecutor.Result r = executor("instructions").apply(f.plan, f.snapshot, f.found, f.ctx);

        assertThat(r.outcome()).isEqualTo(FixExecutor.Outcome.APPLIED);
        assertThat(signer.commands).isEmpty();
        assertThat(tmp.resolve("parent-ds")).doesNotExist();
        assertThat(text.toString()).contains("+ " + ZONE + ".").contains("- " + ZONE + ".");
    }

    @Test
    void aFixThatDoesNotWorkIsNotDelivered() throws Exception {
        Fixture f = fixture(ZONE);
        // "fix" B1 by publishing the same stale DS again
        FixAction.UpdateParentDs same = f.plan.parentDs().orElseThrow();
        FixPlan wrong = new FixPlan(f.plan.zone(), List.of(
                new FixAction.UpdateParentDs(same.zone(), same.current(), same.current(), Set.of(ErrorId.B1))));

        FixExecutor.Result r = executor("lab").apply(wrong, f.snapshot, f.found, f.ctx);

        assertThat(r.outcome()).isEqualTo(FixExecutor.Outcome.BLOCKED);
        assertThat(r.blockers()).extracting(Diagnosis::id).contains(ErrorId.B1);
        assertThat(tmp.resolve("parent-ds")).doesNotExist();
        assertThat(tmp.resolve("work/reload")).doesNotExist();
        assertThat(signer.commands).isEmpty();
    }

    @Test
    void cappedCopyHasNoTtlAboveTheLimit() throws IOException {
        Path src = tmp.resolve("long.test.zone");
        Files.writeString(src, """
                $TTL 2592000
                long.test. IN SOA ns1.long.test. admin.long.test. ( 1 3600 900 604800 300 )
                long.test. IN NS  ns1.long.test.
                ns1        IN A   10.53.0.11
                www   600  IN A   10.53.0.80
                """);
        Path out = tmp.resolve("capped.zone");
        Name zone = Records.name("long.test.");
        FixExecutor.writeCapped(src, out, zone, 86400);

        List<Record> records = Records.readZoneFile(out, zone);
        assertThat(records).hasSize(4);
        assertThat(records).extracting(Record::getTTL).containsExactly(86400L, 86400L, 86400L, 600L);
    }

    private FixExecutor executor(String mode) {
        AppConfig.Fix config = new AppConfig.Fix(tmp.resolve("zones").toString(), tmp.resolve("keys").toString(),
                tmp.resolve("work").toString(), Map.of("default", tmp.resolve("auth1").toString()),
                new AppConfig.Fix.Parent(mode, tmp.resolve("parent-ds").toString(), List.of("sh", "sign-tld.sh"),
                        "tld"));
        return new FixExecutor(config, CATALOG, signer, new PrintWriter(text, true));
    }

    private record Fixture(ZoneSnapshot snapshot, CheckContext ctx, List<Diagnosis> found, FixPlan plan) {}

    private static Fixture fixture(String zone) {
        ZoneSnapshot s = Snapshots.load(zone);
        CheckContext ctx = Snapshots.context(s);
        List<Diagnosis> found = Analysis.run(s, ctx, CATALOG).diagnoses();
        return new Fixture(s, ctx, found, new FixPlanner(CATALOG).plan(s, found, s.localKeys()));
    }

    /** Records commands; signing isn't part of these tests. */
    private static final class FakeSigner implements ZoneSigner {

        final List<List<String>> commands = new ArrayList<>();

        @Override
        public void sign(Name zone, Path input, Path output, boolean nsec3) {
            throw new AssertionError("no signing expected");
        }

        @Override
        public String run(List<String> command) {
            commands.add(command);
            return "";
        }
    }
}
