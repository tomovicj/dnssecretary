package rs.port53.dnssecretary.fix;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DSRecord;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Diagnosis;

/**
 * Diagnoses → plan (docs/java-proposal.md 3.7). Pure: no files, no processes.
 * An error without an automatic fix becomes a {@link FixAction.Manual} step with the catalogue's text.
 */
public final class FixPlanner {

    /** Errors that re-signing from the (correct) source with the active keys removes. */
    static final Set<ErrorId> RESIGN = EnumSet.of(
            ErrorId.A1, ErrorId.A2, ErrorId.A3, ErrorId.A4, ErrorId.A5, ErrorId.A6, ErrorId.A7,
            ErrorId.B4, ErrorId.B5, ErrorId.C2,
            ErrorId.D1, ErrorId.D2, ErrorId.D3, ErrorId.D4, ErrorId.D5, ErrorId.D6);

    /** Errors fixed by publishing the right DS set at the parent. */
    static final Set<ErrorId> PARENT_DS = EnumSet.of(
            ErrorId.B1, ErrorId.B2, ErrorId.B3, ErrorId.B6, ErrorId.B7, ErrorId.B8, ErrorId.C2, ErrorId.E2);

    /** A7: TTLs are capped to one day, far below the 30-day signature validity. */
    static final long MAX_TTL = 86400;

    /** DS TTL when the parent has none yet (B6). */
    static final long DEFAULT_DS_TTL = 3600;

    private final Catalog catalog;

    public FixPlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param keys the zone's keys from fix.keyDir; the active ones (location "") are what
     *             re-signing publishes, so the DS is built from their KSKs. Empty: from the live DNSKEY set.
     */
    public FixPlan plan(ZoneSnapshot s, List<Diagnosis> diagnoses, List<LocalKey> keys) {
        Set<ErrorId> ids = EnumSet.noneOf(ErrorId.class);
        diagnoses.forEach(d -> ids.add(d.id()));
        List<FixAction> actions = new ArrayList<>();

        Set<ErrorId> resign = EnumSet.noneOf(ErrorId.class);
        resign.addAll(ids);
        resign.retainAll(RESIGN);
        if (!resign.isEmpty()) {
            actions.add(new FixAction.ResignZone(s.zone(), ids.contains(ErrorId.A7) ? MAX_TTL : null, resign));
        }

        Set<ErrorId> parent = EnumSet.noneOf(ErrorId.class);
        parent.addAll(ids);
        parent.retainAll(PARENT_DS);
        List<ErrorId> manual = new ArrayList<>(ids);
        manual.removeAll(RESIGN);
        manual.removeAll(PARENT_DS);

        if (!parent.isEmpty()) {
            SignedRRset ds = s.parentDs().rrset();
            List<DSRecord> current = ds.records(DSRecord.class);
            long ttl = ds.isEmpty() ? DEFAULT_DS_TTL : ds.ttl();
            List<DSRecord> wanted = kskSource(s, keys).stream()
                    .map(k -> new DSRecord(s.zone(), DClass.IN, ttl, DNSSEC.Digest.SHA256, k))
                    .toList();
            if (wanted.isEmpty()) {
                parent.removeAll(RESIGN);   // C2 is still fixed in the zone
                manual.addAll(parent);
            } else {
                FixAction.UpdateParentDs a = new FixAction.UpdateParentDs(s.zone(), current, wanted, parent);
                if (!a.added().isEmpty() || !a.removed().isEmpty()) {
                    actions.add(a);
                }
            }
        }

        manual.stream().sorted().distinct()
                .forEach(id -> actions.add(new FixAction.Manual(id, catalog.get(id).fix())));
        return new FixPlan(s.zone(), actions);
    }

    /** The KSKs the zone will publish: active local keys, otherwise the live DNSKEY set. */
    private static List<DNSKEYRecord> kskSource(ZoneSnapshot s, List<LocalKey> keys) {
        List<DNSKEYRecord> active = keys.stream()
                .filter(k -> k.location().isEmpty())
                .map(LocalKey::key)
                .toList();
        List<DNSKEYRecord> source = active.isEmpty() ? s.dnskey().records(DNSKEYRecord.class) : active;
        return source.stream().filter(FixPlanner::isKsk).toList();
    }

    static boolean isKsk(DNSKEYRecord k) {
        return (k.getFlags() & DNSKEYRecord.Flags.SEP_KEY) != 0 && (k.getFlags() & DNSKEYRecord.Flags.REVOKE) == 0;
    }
}
