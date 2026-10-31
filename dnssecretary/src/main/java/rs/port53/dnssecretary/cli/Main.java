package rs.port53.dnssecretary.cli;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import org.xbill.DNS.Name;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.collect.CollectException;
import rs.port53.dnssecretary.collect.Collector;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.report.Messages;

/**
 * dnssecretary – finds, explains and fixes DNSSEC errors in a zone.
 * Exit codes: 0 ok, 1 only risks, 2 at least one critical error, 3 tool error.
 */
@Command(name = "dnssecretary",
        mixinStandardHelpOptions = true,
        version = "dnssecretary 1.0-SNAPSHOT",
        description = "Finds, explains and fixes DNSSEC errors in a zone.",
        subcommands = {CheckCommand.class, FixCommand.class, VerifyCommand.class,
                CatalogCommand.class, SnapshotCommand.class, ServeCommand.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:ok", "1:only risks", "2:at least one critical error", "3:tool error"})
public final class Main implements Callable<Integer> {

    public static final int EXIT_TOOL_ERROR = 3;

    @Spec
    CommandSpec spec;

    @Option(names = "--config", paramLabel = "<file>",
            description = "Configuration (e.g. config/lab.yaml). Needed for everything that goes to the network.")
    Path configFile;

    private AppConfig config;
    private Catalog catalog;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String... args) {
        CommandLine cmd = new CommandLine(new Main());
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        cmd.setOut(out);
        cmd.setErr(err);
        cmd.setParameterExceptionHandler((ex, a) -> {
            ex.getCommandLine().getErr().println(ex.getMessage());
            ex.getCommandLine().usage(ex.getCommandLine().getErr());
            return EXIT_TOOL_ERROR;
        });
        cmd.setExecutionExceptionHandler((ex, cl, parsed) -> {
            cl.getErr().println(ex instanceof CollectException
                    ? Messages.get("cli.collectFailed", ex.getMessage())
                    : ex.toString());
            return EXIT_TOOL_ERROR;
        });
        return cmd.execute(args);
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return EXIT_TOOL_ERROR;
    }

    AppConfig config() throws IOException {
        if (config == null) {
            config = configFile == null ? AppConfig.defaults() : AppConfig.load(configFile);
        }
        return config;
    }

    Catalog catalog() {
        if (catalog == null) {
            catalog = Catalog.load();
        }
        return catalog;
    }

    /** Collect a snapshot; for a lab zone the catalogue probe is added to the samples. */
    ZoneSnapshot collect(Name zone, Path keyDirOverride) throws IOException, CollectException {
        AppConfig c = config();
        if (c.dns() == null) {
            throw new CollectException(Messages.get("cli.noConfig"));
        }
        if (keyDirOverride != null) {
            AppConfig.Collect cc = c.collect();
            c = new AppConfig(c.dns(), c.thresholds(),
                    new AppConfig.Collect(cc.zoneSource(), cc.zoneFileDirs(), keyDirOverride.toString(),
                            cc.samples(), cc.nodataType()),
                    c.fix());
        }
        List<String> probes = catalog().forLabZone(zone)
                .map(CatalogEntry::lab)
                .map(CatalogEntry.Lab::probe)
                .map(List::of)
                .orElse(List.of());
        return new Collector(c, Clock.systemUTC()).collect(zone, probes);
    }

    static Name zoneName(String s) {
        try {
            return Name.fromString(s, Name.root);
        } catch (org.xbill.DNS.TextParseException e) {
            throw new IllegalArgumentException("Invalid zone name: " + s, e);
        }
    }
}
