package rs.port53.dnssecretary.fix;

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

/** The script contract: sh -eu in the lab directory, ZONE set, helpers sourced first. */
class ScriptRunnerTest {

    private static final Name ZONE = Records.name("a1-expired.test.");

    @TempDir
    Path lab;

    private final List<String> out = new ArrayList<>();
    private ScriptRunner runner;

    @BeforeEach
    void helpers() throws Exception {
        Path helpers = lab.resolve("helpers.sh");
        Files.writeString(helpers, """
                reload() { echo "reload $1"; }
                flush() { echo "flush $1"; }
                """);
        runner = new ScriptRunner(lab, helpers, Duration.ofSeconds(10));
    }

    @Test
    void runsInTheLabWithZoneAndHelpers() throws Exception {
        Files.writeString(lab.resolve("marker"), "");
        int rc = runner.run(ZONE, "ls marker\nreload auth1\nflush \"$ZONE\"\n", lab.resolve("work/fix.sh"), out::add);

        assertThat(rc).isZero();
        assertThat(out).containsExactly("marker", "reload auth1", "flush a1-expired.test");
        assertThat(lab.resolve("work/fix.sh")).exists();
    }

    @Test
    void stopsAtTheFirstFailingCommand() throws Exception {
        int rc = runner.run(ZONE, "echo before\nfalse\necho after\n", lab.resolve("fix.sh"), out::add);

        assertThat(rc).isNotZero();
        assertThat(out).containsExactly("before");
    }

    @Test
    void anUnsetVariableIsAnError() throws Exception {
        assertThat(runner.run(ZONE, "echo \"$WORK\"\n", lab.resolve("fix.sh"), out::add)).isNotZero();
    }

    @Test
    void aScriptThatHangsIsStopped() throws Exception {
        ScriptRunner quick = new ScriptRunner(lab, lab.resolve("helpers.sh"), Duration.ofMillis(300));
        assertThat(quick.run(ZONE, "sleep 5\n", lab.resolve("fix.sh"), out::add)).isEqualTo(ScriptRunner.TIMEOUT);
    }
}
