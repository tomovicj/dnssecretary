package rs.port53.dnssecretary.diagnose.rules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.xbill.DNS.NSEC3Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.DiagnosisRule;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/**
 * Drops findings that are only a consequence of another error in the same zone, so one
 * problem is reported once. A diagnosis that loses
 * all its findings is dropped. Fixing the cause also removes the consequence.
 */
public final class ConsequenceRule implements DiagnosisRule {

    /** When {@code cause} is diagnosed, the findings of {@code hidden} that match {@code which} go away. */
    record Hides(ErrorId cause, ErrorId hidden, BiPredicate<Finding, ZoneSnapshot> which) {}

    private static final BiPredicate<Finding, ZoneSnapshot> ALL = (f, s) -> true;

    static final List<Hides> TABLE = List.of(
            // An unsigned zone has no RRSIG and no NSEC/NSEC3 at all.
            new Hides(ErrorId.B4, ErrorId.A3, ALL),
            new Hides(ErrorId.B4, ErrorId.D3, ALL),
            // An NSEC/NSEC3 without an RRSIG is D3, not a generic unsigned RRset.
            new Hides(ErrorId.D3, ErrorId.A3, (f, s) -> f.type() == Type.NSEC || f.type() == Type.NSEC3),
            // With a mismatched NSEC3PARAM the server can't find the NSEC3 records for a proof.
            new Hides(ErrorId.D5, ErrorId.D3, ALL),
            // The salt / iterations come only from the mismatched NSEC3PARAM; the chain itself is fine.
            new Hides(ErrorId.D5, ErrorId.D4, (f, s) -> f.type() == Type.NSEC3PARAM && chainFollowsRfc9276(s)));

    @Override
    public List<Diagnosis> apply(List<Diagnosis> diagnoses, ZoneSnapshot s, CheckContext ctx) {
        Set<ErrorId> present = EnumSet.noneOf(ErrorId.class);
        diagnoses.forEach(d -> present.add(d.id()));

        List<Diagnosis> out = new ArrayList<>();
        for (Diagnosis d : diagnoses) {
            List<Hides> rules = TABLE.stream()
                    .filter(h -> h.hidden() == d.id() && present.contains(h.cause()))
                    .toList();
            if (rules.isEmpty()) {
                out.add(d);
                continue;
            }
            List<Finding> kept = d.findings().stream()
                    .filter(f -> rules.stream().noneMatch(h -> h.which().test(f, s)))
                    .toList();
            if (kept.size() == d.findings().size()) {
                out.add(d);
            } else if (!kept.isEmpty()) {
                Severity sev = kept.stream().map(Finding::severity).reduce(Severity.RISK, Severity::max);
                out.add(new Diagnosis(d.id(), sev, d.side(), d.rootCause(), kept));
            }
        }
        return out;
    }

    /** Every NSEC3 record in the zone has 0 extra iterations and no salt (needs the full zone). */
    static boolean chainFollowsRfc9276(ZoneSnapshot s) {
        List<NSEC3Record> chain = s.zoneRRsets().stream()
                .filter(r -> r.type() == Type.NSEC3)
                .map(r -> r.records(NSEC3Record.class))
                .flatMap(List<NSEC3Record>::stream)
                .toList();
        return !chain.isEmpty() && chain.stream().allMatch(n -> n.getIterations() == 0
                && (n.getSalt() == null || n.getSalt().length == 0));
    }
}
