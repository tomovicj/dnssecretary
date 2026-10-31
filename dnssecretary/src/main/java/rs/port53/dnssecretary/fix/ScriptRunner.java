package rs.port53.dnssecretary.fix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.xbill.DNS.Name;

/**
 * Runs a fix script the way the contract says (see {@link ScriptGenerator}): {@code sh -eu}
 * in the lab directory, ZONE set, after sourcing a helpers file that defines reload and flush.
 * Which helpers decides where the changes land: scripts/fixenv/reload-list.sh (CLI),
 * prod.sh (web, via Docker), sandbox.sh (simulation).
 */
public final class ScriptRunner {

    /** Exit code when the script ran out of time. */
    public static final int TIMEOUT = 124;

    private final Path labDir;
    private final Path helpers;
    private final Duration timeout;

    public ScriptRunner(Path labDir, Path helpers, Duration timeout) {
        this.labDir = labDir;
        this.helpers = helpers;
        this.timeout = timeout;
    }

    /**
     * Saves the script to {@code file} and runs it; every output line (stdout and stderr) goes to {@code out}.
     *
     * @return the script's exit code, {@link #TIMEOUT} if it was stopped
     */
    public int run(Name zone, String script, Path file, Consumer<String> out) throws IOException, InterruptedException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, script);
        ProcessBuilder pb = new ProcessBuilder("sh", "-eu", "-c", ". \"$1\"; . \"$2\"", "fix",
                helpers.toAbsolutePath().toString(), file.toAbsolutePath().toString())
                .directory(labDir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("ZONE", zone.toString(true));
        Process p = pb.start();
        p.getOutputStream().close();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(out);
            } catch (IOException e) {
                out.accept("(output lost: " + e.getMessage() + ")");
            }
        });
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            reader.join();
            return TIMEOUT;
        }
        reader.join();
        return p.exitValue();
    }
}
