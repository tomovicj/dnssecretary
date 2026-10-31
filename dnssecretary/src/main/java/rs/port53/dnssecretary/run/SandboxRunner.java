package rs.port53.dnssecretary.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.verify.VerifyResult;

/**
 * Tries a fix script in a sandbox copy of the lab (the "verified" column of the web app):
 * scripts/simulate.sh copies the lab, starts the sandbox container (scripts/sandbox/run.sh)
 * and prints its log; the last line is the result. The lab itself is never touched.
 *
 * <p>Needs the Docker CLI where it runs. Inside a container, set HOST_LAB_DIR (the lab's
 * path on the host) for simulate.sh: Docker mounts host paths.
 */
public final class SandboxRunner {

    static final String RESULT = "SANDBOX-RESULT ";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path labDir;
    private final Path simulate;
    private final Duration timeout;

    /** @param simulate the script that runs one simulation, normally &lt;lab&gt;/scripts/simulate.sh */
    public SandboxRunner(Path labDir, Path simulate, Duration timeout) {
        this.labDir = labDir;
        this.simulate = simulate;
        this.timeout = timeout;
    }

    public static SandboxRunner forLab(Path labDir) {
        return new SandboxRunner(labDir, labDir.resolve("scripts/simulate.sh"), Duration.ofMinutes(3));
    }

    /**
     * @param jobId names the copy (build/sandbox/&lt;jobId&gt;); letters, digits, - and _ only
     * @param log   every line of the run, except the result line
     */
    public RunResult run(Name zone, String script, String jobId, Consumer<String> log) {
        if (!jobId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Bad job id: " + jobId);
        }
        Path file = labDir.resolve("build/sandbox/" + jobId + ".sh");
        AtomicReference<String> result = new AtomicReference<>();
        Process p;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, script);
            p = new ProcessBuilder("sh", simulate.toString(), zone.toString(true),
                    labDir.relativize(file).toString(), jobId)
                    .directory(labDir.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return RunResult.error("sandbox did not start: " + e.getMessage());
        }
        try {
            p.getOutputStream().close();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    r.lines().forEach(line -> {
                        if (line.startsWith(RESULT)) {
                            result.set(line.substring(RESULT.length()));
                        } else {
                            log.accept(line);
                        }
                    });
                } catch (IOException e) {
                    log.accept("(output lost: " + e.getMessage() + ")");
                }
            });
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                reader.join();
                return RunResult.error("sandbox timed out after " + timeout.toSeconds() + " s");
            }
            reader.join();
            return parse(result.get(), p.exitValue());
        } catch (IOException e) {
            p.destroyForcibly();
            return RunResult.error("sandbox output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return RunResult.error("interrupted");
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a leftover script in build/sandbox/ is harmless
            }
        }
    }

    /** The result line of sandbox/run.sh: {"scriptExit": N, "verify": {...} | null}. */
    static RunResult parse(String line, int exit) {
        if (line == null) {
            return RunResult.error("the sandbox gave no result (exit " + exit + ")");
        }
        try {
            JsonNode n = JSON.readTree(line);
            JsonNode v = n.path("verify");
            VerifyResult verify = v.isObject() ? JSON.treeToValue(v, VerifyResult.class) : null;
            if (exit == 3) {
                return RunResult.error("the sandbox failed (exit 3)");
            }
            return RunResult.of(n.path("scriptExit").asInt(-1), verify);
        } catch (IOException e) {
            return RunResult.error("bad sandbox result: " + e.getMessage());
        }
    }
}
