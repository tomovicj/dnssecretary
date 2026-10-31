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
import rs.port53.dnssecretary.fix.BindZoneSigner;
import rs.port53.dnssecretary.fix.FixAction;
import rs.port53.dnssecretary.fix.FixException;
import rs.port53.dnssecretary.fix.FixExecutor;
import rs.port53.dnssecretary.fix.FixPlan;
import rs.port53.dnssecretary.fix.FixPlanner;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.report.Report;

/** fix: collect → check → diagnose → plan → (--apply) sign, offline verify, deliver (docs/java-proposal.md 3.7). */
@Command(name = "fix", mixinStandardHelpOptions = true,
        description = "Fix a zone. By default only prints the plan (dry run).",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:nothing to fix, or fixed", "1:fixed, manual steps remain (or dry run: only risks)",
                "2:blocked by the offline check (or dry run: critical errors)", "3:tool error"})
final class FixCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<zone>")
    String zone;

    @Option(names = "--apply", description = "Really apply the plan (sign, deliver, write the reload list).")
    boolean apply;

    @Option(names = "--lab-copy-secondary", description = "F1 in the lab: copy the correct zone to auth2.")
    boolean labCopySecondary;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        AppConfig.Fix fc = main.config().fix();
        if (fc == null) {
            err.println(Messages.get("cli.noFixConfig"));
            return Main.EXIT_TOOL_ERROR;
        }
        if (labCopySecondary) {
            err.println(Messages.get("cli.notImplemented", "--lab-copy-secondary"));
            return Main.EXIT_TOOL_ERROR;
        }

        Name name = Main.zoneName(zone);
        ZoneSnapshot snapshot = main.collect(name, null);
        CheckContext ctx = new CheckContext(Clock.systemUTC(), main.config().thresholds());
        Report before = Analysis.run(snapshot, ctx, main.catalog());
        summary(before, out);
        if (!before.failures().isEmpty()) {
            before.failures().forEach(f -> err.println("  - " + f));
            return Main.EXIT_TOOL_ERROR;
        }

        List<String> warnings = new ArrayList<>();
        List<LocalKey> keys = fc.keyDir() == null ? List.of() : KeyDirectory.load(Path.of(fc.keyDir()), name, warnings);
        warnings.forEach(w -> err.println("  ! " + w));
        FixPlan plan = new FixPlanner(main.catalog()).plan(snapshot, before.diagnoses(), keys);
        if (plan.isEmpty()) {
            out.println(Messages.get("fix.nothing"));
            return 0;
        }

        out.println(Messages.get("fix.plan"));
        for (int i = 0; i < plan.actions().size(); i++) {
            out.printf("  %d. %s%n", i + 1, plan.actions().get(i).describe());
        }
        out.println();
        boolean automatic = plan.actions().stream().anyMatch(a -> !(a instanceof FixAction.Manual));
        if (!apply || !automatic) {
            out.println(Messages.get(automatic ? "fix.dryRun" : "fix.manualOnly"));
            return before.status().exitCode();
        }

        FixExecutor.Result r;
        try {
            r = new FixExecutor(fc, main.catalog(), new BindZoneSigner(Path.of(fc.keyDir())), out)
                    .apply(plan, snapshot, before.diagnoses(), ctx);
        } catch (FixException e) {
            err.println(Messages.get("fix.failed", e.getMessage()));
            return Main.EXIT_TOOL_ERROR;
        }
        out.println();
        if (r.outcome() == FixExecutor.Outcome.BLOCKED) {
            out.println(Messages.get("fix.blocked"));
            return 2;
        }
        if (plan.manual().isEmpty()) {
            out.println(Messages.get("fix.applied"));
            return 0;
        }
        out.println(Messages.get("fix.appliedManualLeft", plan.manual().size()));
        return 1;
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
