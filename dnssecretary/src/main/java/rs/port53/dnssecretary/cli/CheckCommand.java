package rs.port53.dnssecretary.cli;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.check.CheckRunner;
import rs.port53.dnssecretary.collect.SnapshotIO;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.Diagnoser;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.report.JsonReport;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.report.Report;
import rs.port53.dnssecretary.report.TextReport;

/** check: collect → check → diagnose → report. Only reads. */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Check a zone and explain its errors (read only).")
final class CheckCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Parameters(arity = "0..1", paramLabel = "<zone>", description = "e.g. a1-expired.test")
    String zone;

    @Option(names = "--json", description = "Report as JSON.")
    boolean json;

    @Option(names = "--now", paramLabel = "<time>",
            description = "Check as if it were this time now (ISO-8601, e.g. 2026-11-01T12:00:00Z).")
    Instant now;

    @Option(names = "--key-dir", paramLabel = "<dir>",
            description = "Key directory (source K), instead of collect.keyDir from the configuration.")
    Path keyDir;

    @Option(names = "--from", paramLabel = "<snapshot.json>",
            description = "Don't ask the network: check a saved snapshot. Without --now the time is the moment of recording.")
    Path from;

    @Override
    public Integer call() throws Exception {
        if ((zone == null) == (from == null)) {
            spec.commandLine().getErr().println("Give exactly one of: <zone> or --from <snapshot.json>.");
            return Main.EXIT_TOOL_ERROR;
        }
        ZoneSnapshot snapshot = from != null ? SnapshotIO.read(from) : main.collect(Main.zoneName(zone), keyDir);

        Clock clock = now != null ? Clock.fixed(now, ZoneOffset.UTC)
                : from != null ? Clock.fixed(snapshot.collectedAt(), ZoneOffset.UTC)
                : Clock.systemUTC();
        CheckContext ctx = new CheckContext(clock, main.config().thresholds());

        CheckRunner runner = CheckRunner.withDefaultChecks();
        CheckRunner.Result result = runner.run(snapshot, ctx);
        List<Diagnosis> diagnoses = Diagnoser.withDefaultRules(main.catalog())
                .diagnose(result.findings(), snapshot, ctx);

        List<String> warnings = new ArrayList<>(snapshot.warnings());
        if (runner.checks().isEmpty()) {
            warnings.add(Messages.get("report.noChecks"));
        }
        Report report = Report.of(snapshot.zone(), snapshot.collectedAt(), ctx.now(), diagnoses, warnings,
                result.failures());

        if (json) {
            JsonReport.write(report, System.out);
        } else {
            new TextReport(main.catalog()).write(report, spec.commandLine().getOut());
        }
        return report.status().exitCode();
    }
}
