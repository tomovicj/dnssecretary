package rs.port53.dnssecretary.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.fix.ScriptRunner;
import rs.port53.dnssecretary.verify.VerifyResult;

/** ProdRunner with fake helpers and a fake live verify (the real ones need the lab). */
class ProdRunnerTest {

    private static final Name ZONE = Records.name("a1-expired.test.");
    private static final VerifyResult OK = new VerifyResult("a1-expired.test.", true, List.of(), List.of(), true);
    private static final VerifyResult BROKEN = new VerifyResult("a1-expired.test.", false, List.of("A1"), List.of(), false);

    @TempDir
    Path lab;

    private final List<String> log = new ArrayList<>();
    private ScriptRunner scripts;

    @BeforeEach
    void helpers() throws Exception {
        Path helpers = lab.resolve("prod.sh");
        Files.writeString(helpers, "reload() { echo \"reload $1\"; }\nflush() { echo \"flush $1\"; }\n");
        scripts = new ScriptRunner(lab, helpers, Duration.ofSeconds(10));
    }

    private ProdRunner runner(ProdRunner.Verify verify) {
        return new ProdRunner(scripts, lab.resolve("work"), verify, Duration.ZERO);
    }

    @Test
    void scriptThenVerifyPasses() {
        RunResult r = runner(z -> OK).run(ZONE, "reload auth1\nflush \"$ZONE\"\n", "exec-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.PASS);
        assertThat(log).containsSubsequence("reload auth1", "flush a1-expired.test", "fix script exit: 0");
        assertThat(lab.resolve("work/a1-expired.test/exec-1.sh")).exists();
    }

    @Test
    void zoneStillBrokenFails() {
        assertThat(runner(z -> BROKEN).run(ZONE, "true\n", "exec-1", log::add).outcome())
                .isEqualTo(RunResult.Outcome.FAIL);
    }

    @Test
    void failingScriptIsNotVerified() {
        RunResult r = runner(z -> { throw new AssertionError("no verify after a failed script"); })
                .run(ZONE, "false\n", "exec-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.FAIL);
        assertThat(r.verify()).isNull();
    }

    @Test
    void verifyThatThrowsIsAnError() {
        RunResult r = runner(z -> { throw new IllegalStateException("resolver down"); })
                .run(ZONE, "true\n", "exec-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.ERROR);
        assertThat(r.error()).contains("resolver down");
    }
}
