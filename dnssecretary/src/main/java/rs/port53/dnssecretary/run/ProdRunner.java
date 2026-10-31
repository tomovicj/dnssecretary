package rs.port53.dnssecretary.run;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.fix.ScriptRunner;
import rs.port53.dnssecretary.verify.VerifyResult;

/**
 * Runs a fix script on the lab itself (the admin's "Execute"), with scripts/fixenv/prod.sh
 * as helpers, then verifies the zone live. Needs the Docker CLI and socket where it runs.
 */
public final class ProdRunner {

    /** Verifies one zone live, e.g. {@code z -> liveVerifier.verify(z).result()}. */
    @FunctionalInterface
    public interface Verify {
        VerifyResult verify(Name zone) throws Exception;
    }

    private final ScriptRunner scripts;
    private final Path workDir;
    private final Verify verify;
    private final Duration settle;

    /**
     * @param workDir where the executed scripts are kept, as &lt;workDir&gt;/&lt;zone&gt;/&lt;jobId&gt;.sh
     * @param settle  how long the restarted servers get before the verify
     */
    public ProdRunner(ScriptRunner scripts, Path workDir, Verify verify, Duration settle) {
        this.scripts = scripts;
        this.workDir = workDir;
        this.verify = verify;
        this.settle = settle;
    }

    public static ProdRunner forLab(Path labDir, Path workDir, Verify verify) {
        return new ProdRunner(new ScriptRunner(labDir, labDir.resolve("scripts/fixenv/prod.sh"), Duration.ofMinutes(2)),
                workDir, verify, Duration.ofSeconds(2));
    }

    public RunResult run(Name zone, String script, String jobId, Consumer<String> log) {
        Path file = workDir.resolve(zone.toString(true)).resolve(jobId + ".sh");
        int rc;
        try {
            rc = scripts.run(zone, script, file, log);
        } catch (IOException e) {
            return RunResult.error("the script did not start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RunResult.error("interrupted");
        }
        log.accept("fix script exit: " + rc);
        if (rc != 0) {
            return RunResult.of(rc, null);
        }
        try {
            Thread.sleep(settle);
            log.accept("$ dnssecretary verify " + zone.toString(true));
            return RunResult.of(rc, verify.verify(zone));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RunResult.error("interrupted");
        } catch (Exception e) {
            return new RunResult(RunResult.Outcome.ERROR, rc, null, "verify failed: " + e.getMessage());
        }
    }
}
