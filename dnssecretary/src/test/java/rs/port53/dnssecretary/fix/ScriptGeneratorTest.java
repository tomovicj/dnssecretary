package rs.port53.dnssecretary.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.testutil.LabConfig;
import rs.port53.dnssecretary.testutil.Snapshots;

/** The fix scripts for the recorded lab zones (the BIND tools themselves run only in the lab). */
class ScriptGeneratorTest {

    private static final Catalog CATALOG = Catalog.load();

    @TempDir
    Path tmp;

    static String script(String zone, AppConfig.Fix config) throws IOException {
        ZoneSnapshot s = Snapshots.load(zone);
        var report = Analysis.run(s, Snapshots.context(s), CATALOG);
        FixPlan plan = new FixPlanner(CATALOG, config).plan(s, report.diagnoses(), s.localKeys());
        return new ScriptGenerator(config, CATALOG).generate(plan);
    }

    static String script(String zone) throws IOException {
        return script(zone, LabConfig.fix());
    }

    static List<String> zones() {
        return Snapshots.allZones();
    }

    /** Every script is valid sh and uses only paths relative to the lab, so it runs in a sandbox copy too. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    void scriptIsValidShWithRelativePaths(String zone) throws Exception {
        String sh = script(zone);
        assertThat(sh).startsWith("#!/bin/sh\n").contains("ZONE=" + zone + "\n");
        assertThat(sh).doesNotContain(LabConfig.ROOT + "/");

        Path f = tmp.resolve("fix.sh");
        Files.writeString(f, sh);
        Process p = new ProcessBuilder("sh", "-n", f.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
    }

    @Test
    void goodZoneScriptDoesNothing() throws IOException {
        assertThat(script(Snapshots.GOOD)).doesNotContain("\nreload ").doesNotContain("dnssec-signzone");
    }

    @Test
    void expiredZoneIsResignedAndDelivered() throws IOException {
        assertThat(script("a1-expired.test"))
                .contains("# [A1] Expired signature\n")
                .contains("WORK=build/dnssecretary/$ZONE\n")
                .contains("dnssec-signzone -S -K keys -d \"$WORK\" -s now-3600 -e now+2592000 -N unixtime \\\n"
                        + "    -o \"$ZONE\" -f \"$WORK/$ZONE.zone.signed\" zones/$ZONE.zone\n")
                .contains("dnssec-verify -q -o \"$ZONE\" \"$WORK/$ZONE.zone.signed\"\n")
                .contains("cp \"$WORK/$ZONE.zone.signed\" auth1/\n")
                .endsWith("reload auth1\nflush \"$ZONE\"\n");
    }

    @Test
    void longTtlsAreCappedInACopyInsideTheScript() throws IOException {
        String sh = script("a7-ttl-long.test");
        assertThat(sh).contains("cat > \"$WORK/$ZONE.zone\" <<'EOF'\n")
                .contains("www.a7-ttl-long.test.\t86400\tIN\tA\t10.53.0.80\n")
                .contains(" -o \"$ZONE\" -f \"$WORK/$ZONE.zone.signed\" \"$WORK/$ZONE.zone\"\n")
                .doesNotContain("2592000\tIN");
    }

    @Test
    void nsec3ZoneKeepsNsec3() throws IOException {
        assertThat(script("d4-nsec3-iter.test")).contains("-N unixtime -3 - -H 0 \\\n");
    }

    @Test
    void parentDsIsWrittenAndTheParentResigned() throws IOException {
        assertThat(script("b1-ds-stale.test"))
                .contains("cat > build/parent-ds/$ZONE.ds <<'EOF'\nb1-ds-stale.test.\t3600\tIN\tDS\t")
                .contains("\nsh scripts/sign-tld.sh\n")
                .endsWith("reload tld\nflush \"$ZONE\"\n");
    }

    @Test
    void withARegistrarTheDsIsOnlyShown() throws IOException {
        String sh = script("b1-ds-stale.test", LabConfig.fix("instructions"));
        assertThat(sh).contains("#   b1-ds-stale.test.\t3600\tIN\tDS\t").doesNotContain("sign-tld").doesNotContain("\nreload ");
    }

    @Test
    void weakKeysAreReplacedThenSignedThenTheNewDsPublished() throws IOException {
        String sh = script("c1-rsasha1.test");
        assertThat(sh).containsSubsequence(
                "mkdir -p keys/old\n",
                "mv keys/Kc1-rsasha1.test.+005+",
                "dnssec-keygen -q -K keys -a ECDSAP256SHA256 -f KSK \"$ZONE\"\n",
                "dnssec-keygen -q -K keys -a ECDSAP256SHA256 \"$ZONE\"\n",
                "dnssec-signzone -S -K keys",
                "cp \"$WORK/dsset-$ZONE.\" build/parent-ds/$ZONE.ds\n",
                "sh scripts/sign-tld.sh\n",
                "reload auth1\nreload tld\nflush \"$ZONE\"\n");
        assertThat(sh).contains(".private keys/old/\n");
    }

    @Test
    void retiredKeyIsPutBackBeforeSigning() throws IOException {
        assertThat(script("e1-rollover.test")).containsSubsequence(
                "mv keys/retired/Ke1-rollover.test.+013+", ".key keys/retired/Ke1-rollover.test.+013+",
                ".private keys/\n",
                "dnssec-signzone -S -K keys");
    }

    @Test
    void staleSecondaryGetsTheCurrentZone() throws IOException {
        assertThat(script("f1-stale-secondary.test"))
                .contains("cp auth1/$ZONE.zone.signed auth2/\n")
                .endsWith("reload auth2\nflush \"$ZONE\"\n");
    }

    @Test
    void unboundGetsTcpAndALargerEdnsSize() throws IOException {
        assertThat(script("f2-tcp-edns.test"))
                .contains("sed -i -e 's/^\\([[:space:]]*do-tcp:\\).*/\\1 yes/'"
                        + " -e 's/^\\([[:space:]]*max-udp-size:\\).*/\\1 1232/' auth3/unbound.conf\n")
                .endsWith("reload auth3\nflush \"$ZONE\"\n");
    }

    @Test
    void cappedKeepsShortTtlsAndCapsLongOnes() throws IOException {
        Path src = tmp.resolve("long.test.zone");
        Files.writeString(src, """
                $TTL 2592000
                long.test. IN SOA ns1.long.test. admin.long.test. ( 1 3600 900 604800 300 )
                long.test. IN NS  ns1.long.test.
                ns1        IN A   10.53.0.11
                www   600  IN A   10.53.0.80
                """);
        Name zone = Records.name("long.test.");
        Path out = tmp.resolve("capped.zone");
        Files.writeString(out, ScriptGenerator.capped(src, zone, 86400));

        List<Record> records = Records.readZoneFile(out, zone);
        assertThat(records).extracting(Record::getTTL).containsExactly(86400L, 86400L, 86400L, 600L);
    }
}
