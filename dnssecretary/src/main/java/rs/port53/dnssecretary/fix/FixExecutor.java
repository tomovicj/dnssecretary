package rs.port53.dnssecretary.fix;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.FileZoneSource;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Severity;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.report.Report;

/**
 * Applies a plan (fix --apply). Order: sign in fix.workDir → dnssec-verify → offline verify
 * (the same checks over the new zone and DS) → backup → deliver → parent DS → reload list.
 * If anything before delivery fails, nothing reaches a server. fix.sourceDir is never written.
 *
 * <p>The tool can't restart servers (no Docker inside the container). It writes
 * {@code <workDir>/reload} with "restart &lt;service&gt;" and "flush &lt;zone&gt;" lines, and
 * scripts/dnssecretary.sh does the rest and then runs verify.
 */
public final class FixExecutor {

    public enum Outcome { APPLIED, BLOCKED }

    /**
     * @param after    the offline check of the new zone (null if nothing was signed or changed)
     * @param blockers critical errors the offline check still found; non-empty = BLOCKED
     */
    public record Result(Outcome outcome, Report after, List<Diagnosis> blockers) {}

    private final AppConfig.Fix config;
    private final Catalog catalog;
    private final ZoneSigner signer;
    private final PrintWriter out;

    public FixExecutor(AppConfig.Fix config, Catalog catalog, ZoneSigner signer, PrintWriter out) {
        this.config = config;
        this.catalog = catalog;
        this.signer = signer;
        this.out = out;
    }

    public Result apply(FixPlan plan, ZoneSnapshot before, List<Diagnosis> found, CheckContext ctx)
            throws FixException, IOException {
        Name zone = plan.zone();
        String file = FileZoneSource.fileName(zone);
        Path work = Path.of(config.workDir());
        Path dir = work.resolve(zone.toString(true));
        Files.createDirectories(dir);

        // 1. sign in the work directory (+ dnssec-verify)
        Path signed = null;
        Optional<List<Record>> newZone = Optional.empty();
        if (plan.resign().isPresent()) {
            FixAction.ResignZone a = plan.resign().get();
            Path source = Path.of(config.sourceDir()).resolve(zone.toString(true) + ".zone");
            Path input = source;
            if (a.maxTtl() != null) {
                input = dir.resolve(zone.toString(true) + ".zone");
                writeCapped(source, input, zone, a.maxTtl());
                say("fix.step.ttl", input, a.maxTtl());
            }
            signed = dir.resolve(file);
            signer.sign(zone, input, signed);
            say("fix.step.signed", signed);
            newZone = Optional.of(Records.readZoneFile(signed, zone));
        }
        Optional<List<DSRecord>> newDs = plan.parentDs().map(FixAction.UpdateParentDs::wanted);

        // 2. offline verify: the same checks over what the servers will serve
        ZoneSnapshot after = OfflineView.after(before, newZone, newDs);
        Report report = Analysis.run(after, ctx, catalog);
        List<Diagnosis> blockers = blockers(plan, found, report);
        if (!blockers.isEmpty()) {
            say("fix.step.blocked");
            blockers.forEach(d -> out.println("     [" + d.id() + "] " + catalog.get(d.id()).title()));
            return new Result(Outcome.BLOCKED, report, blockers);
        }
        say("fix.step.offlineOk");

        // 3. deliver the zone (backup first)
        List<String> reload = new ArrayList<>();
        if (signed != null) {
            Path target = deliverDir(zone).resolve(file);
            if (Files.exists(target)) {
                Path backup = work.resolve("backup").resolve(file + "." + ctx.now().getEpochSecond());
                Files.createDirectories(backup.getParent());
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                say("fix.step.backup", backup);
            }
            Files.copy(signed, target, StandardCopyOption.REPLACE_EXISTING);
            say("fix.step.delivered", target);
            reload.add("restart " + deliverDir(zone).getFileName());
        }

        // 4. the DS at the parent
        if (plan.parentDs().isPresent()) {
            FixAction.UpdateParentDs a = plan.parentDs().get();
            AppConfig.Fix.Parent parent = config.parent();
            if (parent != null && "lab".equals(parent.mode())) {
                Path ds = Path.of(parent.dsDir()).resolve(zone.toString(true) + ".ds");
                Files.createDirectories(ds.getParent());
                Files.write(ds, a.wanted().stream().map(Record::toString).toList());
                say("fix.step.dsWritten", ds);
                signer.run(parent.resignCommand());
                say("fix.step.parentSigned", String.join(" ", parent.resignCommand()));
                if (parent.service() != null) {
                    reload.add("restart " + parent.service());
                }
            } else {
                registrarInstructions(a);
            }
        }

        // 5. reload list for scripts/dnssecretary.sh
        if (!reload.isEmpty()) {
            reload.add("flush " + zone);
            Path list = work.resolve("reload");
            Files.write(list, reload);
            say("fix.step.reload", list, String.join(", ", reload));
        }
        return new Result(Outcome.APPLIED, report, List.of());
    }

    /**
     * Critical errors after the fix that block delivery: the ones the plan should have removed,
     * and new ones. A critical error the plan doesn't touch (a manual step) doesn't block.
     */
    static List<Diagnosis> blockers(FixPlan plan, List<Diagnosis> found, Report after) {
        Set<ErrorId> before = EnumSet.noneOf(ErrorId.class);
        found.forEach(d -> before.add(d.id()));
        Set<ErrorId> fixes = plan.fixes();
        return after.diagnoses().stream()
                .filter(d -> d.severity() == Severity.CRITICAL)
                .filter(d -> fixes.contains(d.id()) || !before.contains(d.id()))
                .toList();
    }

    private Path deliverDir(Name zone) {
        String dir = config.deliver().getOrDefault(zone.toString(true), config.deliver().get("default"));
        return Path.of(dir);
    }

    /** parent.mode: instructions – what to give the registrar (no change is made). */
    private void registrarInstructions(FixAction.UpdateParentDs a) {
        say("fix.registrar.header", a.zone());
        a.removed().forEach(ds -> out.println("     - " + ds));
        a.added().forEach(ds -> out.println("     + " + ds));
        say("fix.registrar.cds");
    }

    /** A copy of the source with every TTL capped (A7). Owner names are written absolute. */
    static void writeCapped(Path source, Path target, Name zone, long maxTtl) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Record r : Records.readZoneFile(source, zone)) {
            lines.add(r.getName() + "\t" + Math.min(r.getTTL(), maxTtl) + "\t" + DClass.string(r.getDClass())
                    + "\t" + Type.string(r.getType()) + "\t" + r.rdataToString());
        }
        Files.write(target, lines);
    }

    private void say(String key, Object... args) {
        out.println("  >> " + Messages.get(key, args));
        out.flush();
    }
}
