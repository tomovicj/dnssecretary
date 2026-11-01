package rs.port53.dnssecretary.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.xbill.DNS.Name;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.KeyDirectory;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.fix.FixPlan;
import rs.port53.dnssecretary.fix.FixPlanner;
import rs.port53.dnssecretary.fix.ScriptGenerator;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.report.Report;

/**
 * fix: collect → check → diagnose → plan → script. Prints the plan and the script, changes nothing:
 * scripts run from the web app (on the hosting) or scripts/simulate.sh (in a sandbox).
 */
@Command(name = "fix", mixinStandardHelpOptions = true,
        description = "Print the plan and the fix script for a zone. Changes nothing.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:nothing to fix", "1:only risks", "2:critical errors", "3:tool error"})
final class FixCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<zone>")
    String zone;

    @Option(names = "--script", description = "Print only the fix script, nothing else.")
    boolean scriptOnly;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        AppConfig.Fix fc = main.config().fix();
        if (fc == null) {
            err.println(Messages.get("cli.noFixConfig"));
            return Main.EXIT_TOOL_ERROR;
        }

        Name name = Main.zoneName(zone);
        ZoneSnapshot snapshot = main.collect(name, null);
        CheckContext ctx = new CheckContext(Clock.systemUTC(), main.config().thresholds());
        Report before = Analysis.run(snapshot, ctx, main.catalog());
        if (!before.failures().isEmpty()) {
            before.failures().forEach(f -> err.println("  - " + f));
            return Main.EXIT_TOOL_ERROR;
        }

        List<String> warnings = new ArrayList<>();
        List<LocalKey> keys = fc.keyDir() == null ? List.of() : KeyDirectory.load(Path.of(fc.keyDir()), name, warnings);
        warnings.forEach(w -> err.println("  ! " + w));
        FixPlan plan = new FixPlanner(main.catalog(), fc).plan(snapshot, before.diagnoses(), keys);
        String script = new ScriptGenerator(fc, main.catalog()).generate(plan);

        if (scriptOnly) {
            out.print(script);
            out.flush();
            return 0;
        }
        summary(before, out);
        if (plan.isEmpty()) {
            out.println(Messages.get("fix.nothing"));
            return 0;
        }
        out.println(Messages.get("fix.plan"));
        for (int i = 0; i < plan.actions().size(); i++) {
            out.printf("  %d. %s%n", i + 1, plan.actions().get(i).describe());
        }
        out.println();
        if (!plan.automatic()) {
            out.println(Messages.get("fix.manualOnly"));
            return before.status().exitCode();
        }
        out.println(Messages.get("fix.script"));
        script.lines().forEach(l -> out.println("  " + l));
        out.println();
        out.println(Messages.get("fix.dryRun"));
        return before.status().exitCode();
    }

    private void summary(Report r, PrintWriter out) {
        out.println(Messages.get("report.zone", r.zone()) + "  "
                + Messages.get("report.state", Messages.get("status." + r.status())));
        for (Diagnosis d : r.diagnoses()) {
            out.printf("  [%s] %-60s %s%n", d.id(), main.catalog().get(d.id()).title(),
                    Messages.get("severity." + d.severity()));
        }
        out.println();
    }
}
