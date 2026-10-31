package rs.port53.dnssecretary.fix;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.report.Messages;

/**
 * One step of a fix plan. Every action describes itself, so the dry run prints exactly
 * what --apply would do. Later: RepublishKey (E1, E3) and KeyRollover (C1, C3).
 */
public sealed interface FixAction {

    /** The errors this action removes; offline verify must not find them again. */
    Set<ErrorId> fixes();

    /** One line for the plan. */
    String describe();

    /**
     * Re-sign the zone from its source (fix.sourceDir) with the active keys (fix.keyDir).
     *
     * @param maxTtl A7: TTLs are capped to this in a corrected copy of the source; null = source as is
     * @param nsec3  the zone uses NSEC3: keep it, with the RFC 9276 parameters (no salt, 0 iterations)
     */
    record ResignZone(Name zone, Long maxTtl, boolean nsec3, Set<ErrorId> fixes) implements FixAction {

        public ResignZone {
            fixes = Set.copyOf(fixes);
        }

        @Override
        public String describe() {
            return Messages.get(maxTtl == null ? "fix.action.resign" : "fix.action.resignTtl",
                    zone, ids(fixes), maxTtl) + (nsec3 ? Messages.get("fix.action.nsec3") : "");
        }
    }

    /**
     * Replace the zone's DS set at the parent with {@code wanted} (adds, removes or replaces DS records).
     *
     * @param current the DS set the parent publishes now (empty for B6)
     */
    record UpdateParentDs(Name zone, List<DSRecord> current, List<DSRecord> wanted, Set<ErrorId> fixes)
            implements FixAction {

        public UpdateParentDs {
            current = List.copyOf(current);
            wanted = List.copyOf(wanted);
            fixes = Set.copyOf(fixes);
        }

        /** DS records to add: in wanted, not in current. */
        public List<DSRecord> added() {
            return wanted.stream().filter(w -> current.stream().noneMatch(c -> sameRdata(c, w))).toList();
        }

        /** DS records to remove: in current, not in wanted. */
        public List<DSRecord> removed() {
            return current.stream().filter(c -> wanted.stream().noneMatch(w -> sameRdata(c, w))).toList();
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.parentDs", zone, ids(fixes), added().size(), removed().size());
        }
    }

    /** No automatic fix (yet): the catalogue's instructions for the operator. */
    record Manual(ErrorId id, String text) implements FixAction {

        @Override
        public Set<ErrorId> fixes() {
            return Set.of();
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.manual", id, text);
        }
    }

    static boolean sameRdata(DSRecord a, DSRecord b) {
        return a.rdataToString().equalsIgnoreCase(b.rdataToString());
    }

    private static String ids(Set<ErrorId> ids) {
        return ids.stream().sorted().map(Enum::name).collect(Collectors.joining(", "));
    }
}
