package rs.port53.dnssecretary.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.dns.Records;

/** SandboxRunner against a fake simulate.sh (the real one needs Docker; it is tested on the lab). */
class SandboxRunnerTest {

    private static final Name ZONE = Records.name("a1-expired.test.");
    private static final String OK = "{\"scriptExit\":0,\"verify\":{\"zone\":\"a1-expired.test.\",\"checksOk\":true,"
            + "\"remaining\":[],\"answers\":[{\"qname\":\"www.a1-expired.test.\",\"qtype\":\"A\",\"rcode\":\"NOERROR\","
            + "\"ad\":true,\"ok\":true}],\"ok\":true}}";

    @TempDir
    Path lab;

    private final List<String> log = new ArrayList<>();

    /** A simulate.sh that checks its arguments and the script file, then prints {@code body}. */
    private SandboxRunner fake(String body, Duration timeout) throws Exception {
        Path sim = lab.resolve("simulate.sh");
        Files.writeString(sim, """
                [ "$1" = a1-expired.test ] || exit 9
                [ "$2" = build/sandbox/sim-1.sh ] || exit 9
                [ "$3" = sim-1 ] || exit 9
                echo "script: $(cat "$2")"
                """ + body);
        return new SandboxRunner(lab, sim, timeout);
    }

    @Test
    void verifiedFixPasses() throws Exception {
        RunResult r = fake("echo 'SANDBOX-RESULT " + OK + "'\nexit 0\n", Duration.ofSeconds(10))
                .run(ZONE, "reload auth1", "sim-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.PASS);
        assertThat(r.verify().answers()).hasSize(1);
        assertThat(log).containsExactly("script: reload auth1");
        assertThat(lab.resolve("build/sandbox/sim-1.sh")).doesNotExist();
    }

    @Test
    void zoneStillBrokenFails() throws Exception {
        String notOk = OK.replace("\"checksOk\":true", "\"checksOk\":false").replace("\"remaining\":[]", "\"remaining\":[\"A1\"]")
                .replace(",\"ok\":true}}", ",\"ok\":false}}");
        RunResult r = fake("echo 'SANDBOX-RESULT " + notOk + "'\nexit 1\n", Duration.ofSeconds(10))
                .run(ZONE, "true", "sim-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.FAIL);
        assertThat(r.verify().remaining()).containsExactly("A1");
    }

    @Test
    void failingScriptFails() throws Exception {
        RunResult r = fake("echo 'SANDBOX-RESULT {\"scriptExit\":1,\"verify\":null}'\nexit 1\n", Duration.ofSeconds(10))
                .run(ZONE, "false", "sim-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.FAIL);
        assertThat(r.scriptExit()).isEqualTo(1);
        assertThat(r.verify()).isNull();
    }

    @Test
    void noResultIsAnError() throws Exception {
        RunResult r = fake("echo 'docker: not found'\nexit 3\n", Duration.ofSeconds(10))
                .run(ZONE, "true", "sim-1", log::add);

        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.ERROR);
        assertThat(log).contains("docker: not found");
    }

    @Test
    void hangingSandboxIsStopped() throws Exception {
        RunResult r = fake("sleep 5\n", Duration.ofMillis(300)).run(ZONE, "true", "sim-1", log::add);
        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.ERROR);
        assertThat(r.error()).contains("timed out");
    }
}
