package rs.port53.dnssecretary.cli;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.report.Messages;

/** catalog [ID]: the error catalogue, all or one entry. */
@Command(name = "catalog", mixinStandardHelpOptions = true,
        description = "Print the error catalogue, all of it or one entry (e.g. \"catalog B6\").")
final class CatalogCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Parameters(arity = "0..1", paramLabel = "<id>")
    String id;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        if (id == null) {
            for (CatalogEntry e : main.catalog().entries()) {
                out.printf("%-3s %-9s %-9s %-26s %s%n", e.id(), e.severity(), e.side(),
                        e.lab() == null ? "" : e.lab().zone(), e.title());
            }
            return 0;
        }
        ErrorId eid;
        try {
            eid = ErrorId.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            spec.commandLine().getErr().println(Messages.get("cli.unknownError", id));
            return Main.EXIT_TOOL_ERROR;
        }
        CatalogEntry e = main.catalog().get(eid);
        out.printf("[%s] %s%n", e.id(), e.title());
        row(out, "Group", e.group());
        row(out, "Severity", Messages.get("severity." + e.severity()));
        row(out, "Symptom", e.symptom());
        row(out, "EDE", e.ede());
        row(out, "Cause", e.cause());
        row(out, "Detection", e.detection());
        row(out, "Fix", e.fix());
        row(out, "Who fixes", Messages.get("side." + e.side()));
        row(out, "Recipe", e.recipe());
        row(out, "References", String.join(", ", e.rfc()));
        if (e.lab() != null) {
            row(out, "Lab", e.lab().zone() + ", probe: " + e.lab().probe()
                    + (e.lab().setup() == null ? "" : ", after: " + e.lab().setup()));
        }
        return 0;
    }

    private static void row(PrintWriter out, String label, String value) {
        out.printf("  %-13s %s%n", label + ":", value);
    }
}
