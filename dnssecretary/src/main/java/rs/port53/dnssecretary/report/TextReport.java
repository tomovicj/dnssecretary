package rs.port53.dnssecretary.report;

import java.io.PrintWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** Human-readable report. */
public final class TextReport {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Catalog catalog;

    public TextReport(Catalog catalog) {
        this.catalog = catalog;
    }

    public void write(Report r, PrintWriter out) {
        long critical = r.diagnoses().stream().filter(d -> d.severity() == Severity.CRITICAL).count();
        long risks = r.diagnoses().size() - critical;
        out.printf("%-38s %s %s%n", Messages.get("report.zone", r.zone()),
                Messages.get("report.state", Messages.get("status." + r.status())),
                Messages.get("report.counts", critical, risks));
        out.println(Messages.get("report.collectedAt", TIME.format(r.collectedAt())));
        out.println();

        if (r.diagnoses().isEmpty() && r.failures().isEmpty()) {
            out.println(Messages.get("report.noFindings"));
        }
        for (Diagnosis d : r.diagnoses()) {
            CatalogEntry e = catalog.get(d.id());
            out.printf("[%s] %-60s %s%n", d.id(), e.title(), Messages.get("severity." + d.severity()));
            Finding first = d.findings().get(0);
            line(out, "report.what", Messages.get("report.findings", d.findings().size()) + ". "
                    + Messages.get("report.example") + " " + first.owner() + " " + Type.string(first.type())
                    + (first.evidence() == null ? "" : " – " + first.evidence()));
            if (!first.facts().isEmpty()) {
                line(out, null, first.facts().toString());
            }
            line(out, "report.cause", d.rootCause());
            line(out, "report.fix", e.fix());
            line(out, "report.side", Messages.get("side." + d.side()));
            line(out, "report.refs", String.join(", ", e.rfc()));
            out.println();
        }
        if (!r.warnings().isEmpty()) {
            out.println(Messages.get("report.warnings"));
            r.warnings().forEach(w -> out.println("  - " + w));
        }
        if (!r.failures().isEmpty()) {
            out.println(Messages.get("report.failures"));
            r.failures().forEach(f -> out.println("  - " + f));
        }
        out.flush();
    }

    private static void line(PrintWriter out, String labelKey, String text) {
        String label = labelKey == null ? "" : Messages.get(labelKey);
        out.printf("  %-12s %s%n", label, text);
    }
}
