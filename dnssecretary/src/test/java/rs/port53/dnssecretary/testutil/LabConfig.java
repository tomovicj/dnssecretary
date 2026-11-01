package rs.port53.dnssecretary.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import rs.port53.dnssecretary.config.AppConfig;

/**
 * config/lab.yaml as the tests see it: its /lab paths point at this checkout instead
 * (the repo root, one level above the Maven module), so the sources in zones/ can be read.
 */
public final class LabConfig {

    /** The repo root: what /lab is inside the containers. */
    public static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private LabConfig() {}

    public static AppConfig.Fix fix() {
        return fix(load().fix().parent().mode());
    }

    /** With another parent.mode (lab | instructions). */
    public static AppConfig.Fix fix(String parentMode) {
        AppConfig.Fix f = load().fix();
        Map<String, String> deliver = new LinkedHashMap<>();
        f.deliver().forEach((k, v) -> deliver.put(k, at(v)));
        Map<String, AppConfig.Fix.Server> servers = new LinkedHashMap<>();
        f.servers().forEach((k, v) -> servers.put(k, new AppConfig.Fix.Server(at(v.dir()), v.software())));
        AppConfig.Fix.Parent p = f.parent();
        return new AppConfig.Fix(at(f.labDir()), at(f.sourceDir()), at(f.keyDir()), at(f.workDir()), deliver,
                new AppConfig.Fix.Parent(parentMode, at(p.dsDir()), p.resignCommand().stream().map(LabConfig::at).toList(),
                        p.service()),
                servers);
    }

    private static AppConfig load() {
        try {
            return AppConfig.load(Path.of("config/lab.yaml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String at(String path) {
        return path.equals("/lab") ? ROOT.toString()
                : path.startsWith("/lab/") ? ROOT.resolve(path.substring(5)).toString() : path;
    }
}
