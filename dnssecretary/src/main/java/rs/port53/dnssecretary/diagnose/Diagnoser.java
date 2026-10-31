package rs.port53.dnssecretary.diagnose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/**
 * Findings → diagnoses. First every error ID becomes one diagnosis (15 expired RRSIGs are
 * one problem: "signing stopped"), then the rules run, then the result is sorted:
 * critical first, then by group B, A, C, D, E, F (delegation before signatures before denial).
 */
public final class Diagnoser {

    private static final String GROUP_ORDER = "BACDEF";

    private final Catalog catalog;
    private final List<DiagnosisRule> rules;

    public Diagnoser(Catalog catalog, List<DiagnosisRule> rules) {
        this.catalog = catalog;
        this.rules = List.copyOf(rules);
    }

    /** All rules of the tool. Add yours here. */
    public static List<DiagnosisRule> defaultRules() {
        return List.of();
    }

    public static Diagnoser withDefaultRules(Catalog catalog) {
        return new Diagnoser(catalog, defaultRules());
    }

    public List<Diagnosis> diagnose(List<Finding> findings, ZoneSnapshot snapshot, CheckContext ctx) {
        Map<ErrorId, List<Finding>> byId = new EnumMap<>(ErrorId.class);
        for (Finding f : findings) {
            byId.computeIfAbsent(f.id(), x -> new ArrayList<>()).add(f);
        }
        List<Diagnosis> out = new ArrayList<>();
        byId.forEach((id, fs) -> out.add(merge(id, fs)));
        for (DiagnosisRule rule : rules) {
            List<Diagnosis> next = rule.apply(List.copyOf(out), snapshot, ctx);
            out.clear();
            out.addAll(next);
        }
        out.sort(ORDER);
        return out;
    }

    /** One diagnosis from all findings of one error; texts from the catalogue. */
    public Diagnosis merge(ErrorId id, List<Finding> fs) {
        CatalogEntry e = catalog.get(id);
        Severity sev = fs.stream().map(Finding::severity).reduce(Severity.RISK, Severity::max);
        return new Diagnosis(id, sev, e.side(), e.cause(), fs);
    }

    public static final Comparator<Diagnosis> ORDER = Comparator
            .comparing(Diagnosis::severity, Comparator.reverseOrder())
            .thenComparing(d -> GROUP_ORDER.indexOf(d.id().group()))
            .thenComparing(Diagnosis::id);
}
