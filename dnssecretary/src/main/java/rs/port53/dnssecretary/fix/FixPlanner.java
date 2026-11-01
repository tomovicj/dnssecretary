package rs.port53.dnssecretary.fix;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Finding;

/**
 * Diagnoses → plan. Pure: no files, no processes.
 * The order of the actions is the order of the fix script: keys first, then signing,
 * then the parent, then the other servers. An error without an automatic fix becomes a
 * {@link FixAction.Manual} step with the catalogue's text.
 */
public final class FixPlanner {

    /** Errors that re-signing from the (correct) source with the active keys removes. */
    static final Set<ErrorId> RESIGN = EnumSet.of(
            ErrorId.A1, ErrorId.A2, ErrorId.A3, ErrorId.A4, ErrorId.A5, ErrorId.A6, ErrorId.A7,
            ErrorId.B4, ErrorId.B5, ErrorId.C2,
            ErrorId.D1, ErrorId.D2, ErrorId.D3, ErrorId.D4, ErrorId.D5, ErrorId.D6,
            ErrorId.E4);   // the source has no (wrong) CDS

    /** Errors fixed by publishing the right DS set at the parent. */
    static final Set<ErrorId> PARENT_DS = EnumSet.of(
            ErrorId.B1, ErrorId.B2, ErrorId.B3, ErrorId.B6, ErrorId.B7, ErrorId.B8, ErrorId.C2, ErrorId.E2);

    /** Errors of a key rollover that went wrong: the old key goes back into the zone. */
    static final Set<ErrorId> REPUBLISH = EnumSet.of(ErrorId.E1, ErrorId.E3);

    /** Errors fixed with new keys. */
    static final Set<ErrorId> NEW_KEYS = EnumSet.of(ErrorId.C1, ErrorId.C3);

    /** The algorithm of the new keys: ECDSA P-256 (13), what RFC 8624 recommends. */
    static final String NEW_KEY_ALGORITHM = "ECDSAP256SHA256";

    /** A7: TTLs are capped to one day, far below the 30-day signature validity. */
    static final long MAX_TTL = 86400;

    /** DS TTL when the parent has none yet (B6). */
    static final long DEFAULT_DS_TTL = 3600;

    private final Catalog catalog;
    private final AppConfig.Fix config;

    public FixPlanner(Catalog catalog, AppConfig.Fix config) {
        this.catalog = catalog;
        this.config = config;
    }

    /**
     * @param keys the zone's keys from fix.keyDir; the active ones (location "") are what
     *             re-signing publishes, so the DS is built from their KSKs. Empty: from the live DNSKEY set.
     */
    public FixPlan plan(ZoneSnapshot s, List<Diagnosis> diagnoses, List<LocalKey> keys) {
        Set<ErrorId> ids = EnumSet.noneOf(ErrorId.class);
        diagnoses.forEach(d -> ids.add(d.id()));
        Set<ErrorId> manual = EnumSet.noneOf(ErrorId.class);
        manual.addAll(ids);
        List<FixAction> actions = new ArrayList<>();
        Set<ErrorId> resign = only(ids, RESIGN);

        Set<ErrorId> republish = only(ids, REPUBLISH);
        if (!republish.isEmpty()) {
            List<LocalKey> back = keysToRepublish(s, diagnoses, keys);
            if (!back.isEmpty()) {
                actions.add(new FixAction.RepublishKey(s.zone(), back, republish));
                resign.addAll(republish);
            }
        }

        Set<ErrorId> newKeys = only(ids, NEW_KEYS);
        List<LocalKey> active = keys.stream().filter(k -> k.location().isEmpty()).toList();
        boolean rollover = !newKeys.isEmpty() && !active.isEmpty();
        if (rollover) {
            actions.add(new FixAction.KeyRollover(s.zone(), active, NEW_KEY_ALGORITHM, newKeys));
            resign.addAll(newKeys);
        }

        if (!resign.isEmpty()) {
            actions.add(new FixAction.ResignZone(s.zone(), ids.contains(ErrorId.A7) ? MAX_TTL : null,
                    usesNsec3(s), resign));
            manual.removeAll(resign);
        }

        Set<ErrorId> parent = only(ids, PARENT_DS);
        if (rollover) {
            // the new KSK's DS replaces whatever the parent has now
            parent.addAll(newKeys);
            actions.add(new FixAction.PublishNewDs(s.zone(), parent));
            manual.removeAll(parent);
        } else if (!parent.isEmpty()) {
            parentDs(s, keys, parent).ifPresent(a -> {
                if (!a.added().isEmpty() || !a.removed().isEmpty()) {
                    actions.add(a);
                }
                manual.removeAll(parent);
            });
        }

        if (ids.contains(ErrorId.F1)) {
            List<AppConfig.Fix.Server> stale = servers(diagnoses, ErrorId.F1);
            if (!stale.isEmpty()) {
                actions.add(new FixAction.CopyToSecondary(s.zone(), stale, EnumSet.of(ErrorId.F1)));
                manual.remove(ErrorId.F1);
            }
        }

        if (ids.contains(ErrorId.F2)) {
            List<AppConfig.Fix.Server> broken = servers(diagnoses, ErrorId.F2);
            if (!broken.isEmpty() && broken.stream().allMatch(sv -> "unbound".equals(sv.software()))) {
                broken.forEach(sv -> actions.add(new FixAction.ServerConfig(s.zone(), sv, EnumSet.of(ErrorId.F2))));
                manual.remove(ErrorId.F2);
            }
        }

        manual.stream().sorted()
                .forEach(id -> actions.add(new FixAction.Manual(id, catalog.get(id).fix())));
        return new FixPlan(s.zone(), actions);
    }

    /** The DS set the parent should have; empty when there is no KSK to build it from. */
    private Optional<FixAction.UpdateParentDs> parentDs(ZoneSnapshot s, List<LocalKey> keys,
                                                                   Set<ErrorId> fixes) {
        SignedRRset ds = s.parentDs().rrset();
        List<DSRecord> current = ds.records(DSRecord.class);
        long ttl = ds.isEmpty() ? DEFAULT_DS_TTL : ds.ttl();
        List<DSRecord> wanted = kskSource(s, keys).stream()
                .map(k -> new DSRecord(s.zone(), DClass.IN, ttl, DNSSEC.Digest.SHA256, k))
                .toList();
        return wanted.isEmpty() ? Optional.empty()
                : Optional.of(new FixAction.UpdateParentDs(s.zone(), current, wanted, fixes));
    }

    /**
     * Retired keys that caches may still need: the ones in the resolver's cached DNSKEY set (E1)
     * and the ones that made RRSIGs the resolver still hands out (E3).
     */
    static List<LocalKey> keysToRepublish(ZoneSnapshot s, List<Diagnosis> diagnoses, List<LocalKey> keys) {
        Set<Integer> wanted = new HashSet<>();
        s.resolver().ifPresent(r -> r.dnskey().rrset().records(DNSKEYRecord.class)
                .forEach(k -> wanted.add(k.getFootprint())));
        s.resolver().ifPresent(r -> r.answers().forEach(a -> a.answer().forEach(set -> set.sigs().stream()
                .map(RRSIGRecord::getFootprint).forEach(wanted::add))));
        Set<Integer> active = new HashSet<>();
        keys.stream().filter(k -> k.location().isEmpty()).forEach(k -> active.add(k.key().getFootprint()));
        return keys.stream()
                .filter(k -> k.location().equals("retired"))
                .filter(k -> wanted.contains(k.key().getFootprint()) && !active.contains(k.key().getFootprint()))
                .toList();
    }

    /** The configured servers behind the name server addresses in the findings of {@code id}. */
    private List<AppConfig.Fix.Server> servers(List<Diagnosis> diagnoses, ErrorId id) {
        Map<String, AppConfig.Fix.Server> out = new LinkedHashMap<>();
        diagnoses.stream().filter(d -> d.id() == id).flatMap(d -> d.findings().stream())
                .map(Finding::facts)
                .map(f -> f.get("nameserver"))
                .filter(ns -> ns != null && config.servers().containsKey(ns))
                .forEach(ns -> out.put(ns, config.servers().get(ns)));
        return List.copyOf(out.values());
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

    /** NSEC3 records in the zone, or an NSEC3PARAM at any name server (D6: NSEC3 is where it was going). */
    static boolean usesNsec3(ZoneSnapshot s) {
        return s.zoneRRsets().stream().anyMatch(r -> r.type() == Type.NSEC3 || r.type() == Type.NSEC3PARAM)
                || s.nameservers().stream().anyMatch(ns -> !ns.nsec3param().rrset().isEmpty());
    }

    static boolean isKsk(DNSKEYRecord k) {
        return (k.getFlags() & DNSKEYRecord.Flags.SEP_KEY) != 0 && (k.getFlags() & DNSKEYRecord.Flags.REVOKE) == 0;
    }

    private static Set<ErrorId> only(Set<ErrorId> ids, Set<ErrorId> kind) {
        Set<ErrorId> out = EnumSet.noneOf(ErrorId.class);
        out.addAll(ids);
        out.retainAll(kind);
        return out;
    }
}
