package rs.port53.dnssecretary.cli;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.report.Report;
import rs.port53.dnssecretary.report.TextReport;
import rs.port53.dnssecretary.verify.Verifier;
import rs.port53.dnssecretary.verify.VerifyResult;

/** verify: live check after a fix. */
@Command(name = "verify", mixinStandardHelpOptions = true,
        description = "After a fix: check the zone live again, and that the resolver returns NOERROR AD.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:verified", "1:verified, risks remain", "2:not fixed", "3:tool error"})
final class VerifyCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<zone>")
    String zone;

    @Option(names = "--json", description = "Print the result as one line of JSON (what the sandbox reads).")
    boolean json;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        ZoneSnapshot snapshot = main.collect(Main.zoneName(zone), null);
        CheckContext ctx = new CheckContext(Clock.systemUTC(), main.config().thresholds());
        Report report = Analysis.run(snapshot, ctx, main.catalog());
        Verifier.Result v = Verifier.verify(snapshot, report);
        int rc = report.status() == Report.Status.TOOL_ERROR ? Main.EXIT_TOOL_ERROR
                : v.ok() ? report.status().exitCode() : 2;
        if (json) {
            out.println(VerifyResult.of(report, v).toJson());
            return rc;
        }

        new TextReport(main.catalog()).write(report, out);
        v.resolver().ifPresent(r -> {
            out.println(Messages.get("verify.resolver", r.getHostAddress()));
            v.answers().forEach(a -> out.println("  " + (a.ok() ? "ok   " : "FAIL ") + a));
        });
        out.println();
        if (report.status() == Report.Status.TOOL_ERROR) {
            return Main.EXIT_TOOL_ERROR;
        }
        out.println(Messages.get(v.ok() ? "verify.ok" : "verify.failed", report.zone()));
        return rc;
    }
}
