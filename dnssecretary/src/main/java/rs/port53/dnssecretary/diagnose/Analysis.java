package rs.port53.dnssecretary.diagnose;

import java.util.ArrayList;
import java.util.List;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.check.CheckRunner;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.report.Report;

/** check → diagnose → report for one snapshot. Shared by check, fix (offline verify) and verify. */
public final class Analysis {

    private Analysis() {}

    public static Report run(ZoneSnapshot snapshot, CheckContext ctx, Catalog catalog) {
        CheckRunner runner = CheckRunner.withDefaultChecks();
        CheckRunner.Result result = runner.run(snapshot, ctx);
        List<Diagnosis> diagnoses = Diagnoser.withDefaultRules(catalog)
                .diagnose(result.findings(), snapshot, ctx);

        List<String> warnings = new ArrayList<>(snapshot.warnings());
        if (runner.checks().isEmpty()) {
            warnings.add(Messages.get("report.noChecks"));
        }
        return Report.of(snapshot.zone(), snapshot.collectedAt(), ctx.now(), diagnoses, warnings,
                result.failures());
    }
}
