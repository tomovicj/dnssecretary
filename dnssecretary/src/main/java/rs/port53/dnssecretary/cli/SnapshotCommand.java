package rs.port53.dnssecretary.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.xbill.DNS.Name;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.collect.CollectException;
import rs.port53.dnssecretary.collect.SnapshotIO;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.report.Messages;

/** snapshot: collect only, save the ZoneSnapshot as JSON (for unit tests and debugging). */
@Command(name = "snapshot", mixinStandardHelpOptions = true,
        description = "Record a ZoneSnapshot as JSON (for tests).")
final class SnapshotCommand implements Callable<Integer> {

    /** Always recorded with --catalog: the correct zone, every check must stay silent on it. */
    static final String GOOD_ZONE = "good.test";

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Parameters(arity = "0..*", paramLabel = "<zone>")
    List<String> zones = new ArrayList<>();

    @Option(names = "--catalog",
            description = "good.test and every catalogue zone, except those that need a setup script (E1, E3).")
    boolean catalog;

    @Option(names = {"-o", "--output"}, paramLabel = "<file.json>",
            description = "One zone: into this file (\"-\" = stdout).")
    Path output;

    @Option(names = {"-d", "--dir"}, paramLabel = "<dir>",
            description = "Every zone into <dir>/<zone>.json")
    Path dir;

    @Override
    public Integer call() throws Exception {
        Set<Name> names = new LinkedHashSet<>();
        if (catalog) {
            names.add(Main.zoneName(GOOD_ZONE));
            for (CatalogEntry e : main.catalog().entries()) {
                if (e.lab() != null && e.lab().setup() == null) {
                    names.add(Catalog.labZone(e));
                }
            }
        }
        zones.forEach(z -> names.add(Main.zoneName(z)));
        if (names.isEmpty() || (output != null && (names.size() != 1 || dir != null))) {
            spec.commandLine().getErr().println("Give zones (or --catalog); -o only for one zone, -d for more.");
            return Main.EXIT_TOOL_ERROR;
        }
        if (output == null && dir == null && names.size() > 1) {
            spec.commandLine().getErr().println("For more than one zone, give -d <dir>.");
            return Main.EXIT_TOOL_ERROR;
        }

        int rc = 0;
        for (Name zone : names) {
            ZoneSnapshot s;
            try {
                s = main.collect(zone, null);
            } catch (CollectException e) {
                spec.commandLine().getErr().println(zone + ": " + Messages.get("cli.collectFailed", e.getMessage()));
                rc = Main.EXIT_TOOL_ERROR;
                continue;
            }
            if (dir == null && (output == null || output.toString().equals("-"))) {
                SnapshotIO.write(s, System.out);
                continue;
            }
            Path f = dir != null ? dir.resolve(SnapshotIO.fileName(zone)) : output;
            SnapshotIO.write(s, f);
            spec.commandLine().getErr().println(Messages.get("cli.snapshotWritten", f)
                    + (s.warnings().isEmpty() ? "" : "  (" + String.join(" | ", s.warnings()) + ")"));
        }
        return rc;
    }
}
