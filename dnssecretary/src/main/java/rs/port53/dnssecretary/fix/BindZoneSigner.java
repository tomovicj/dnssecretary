package rs.port53.dnssecretary.fix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.xbill.DNS.Name;

/**
 * Signs with the BIND tools, exactly like sign.sh: dnssec-signzone -S (keys from the key
 * directory, not its subdirectories), validity from 1 hour ago to 30 days, SOA serial = unixtime,
 * then dnssec-verify. The tools are in the "app" image.
 */
public final class BindZoneSigner implements ZoneSigner {

    private static final long TIMEOUT_SECONDS = 120;

    private final Path keyDir;

    public BindZoneSigner(Path keyDir) {
        this.keyDir = keyDir;
    }

    @Override
    public void sign(Name zone, Path input, Path output, boolean nsec3) throws FixException {
        String origin = zone.toString(true);
        // -d: the dsset-<zone>. file goes next to the output, never into build/ of sign.sh
        List<String> cmd = new ArrayList<>(List.of("dnssec-signzone", "-S", "-K", keyDir.toString(),
                "-d", output.getParent().toString(), "-s", "now-3600", "-e", "now+2592000", "-N", "unixtime"));
        if (nsec3) {
            cmd.addAll(List.of("-3", "-", "-H", "0"));
        }
        cmd.addAll(List.of("-o", origin, "-f", output.toString(), input.toString()));
        run(cmd);
        run(List.of("dnssec-verify", "-q", "-o", origin, output.toString()));
    }

    @Override
    public String run(List<String> command) throws FixException {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new FixException(command.get(0) + ": " + e.getMessage(), e);
        }
        try {
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new FixException(command.get(0) + ": timeout after " + TIMEOUT_SECONDS + " s");
            }
            if (p.exitValue() != 0) {
                throw new FixException(String.join(" ", command) + " (exit " + p.exitValue() + "):\n"
                        + tail(out));
            }
            return out;
        } catch (IOException e) {
            throw new FixException(command.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new FixException(command.get(0) + ": interrupted", e);
        }
    }

    /** The last lines of a command's output, enough to see why it failed. */
    private static String tail(String out) {
        List<String> lines = new ArrayList<>(out.lines().toList());
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
    }
}
