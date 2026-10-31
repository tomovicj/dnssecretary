package rs.port53.dnssecretary.fix;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.report.Messages;

/**
 * One step of a fix plan. {@link ScriptGenerator} turns each into a block of the fix script;
 * {@link #describe()} is its one line in the plan. The order of the plan is the order of the script.
 */
public sealed interface FixAction {

    /** The errors this action removes. */
    Set<ErrorId> fixes();

    /** One line for the plan. */
    String describe();

    /** False for a step the operator has to do by hand. */
    default boolean automatic() {
        return true;
    }

    /**
     * E1, E3: put retired keys back into the key directory, so the zone is signed with the old
     * and the new key until every cache has the new DNSKEY set (double signature).
     */
    record RepublishKey(Name zone, List<LocalKey> keys, Set<ErrorId> fixes) implements FixAction {

        public RepublishKey {
            keys = List.copyOf(keys);
            fixes = Set.copyOf(fixes);
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.republish", zone, ids(fixes), tags(keys));
        }
    }

    /**
     * C1, C3: retire the zone's keys and make new ones (fast, for the lab: the zone is re-signed and
     * the DS replaced right away; in production an algorithm rollover with waits, RFC 6781 §4.1.4).
     *
     * @param old       the active keys that are moved to the "old" subdirectory
     * @param algorithm dnssec-keygen's name, e.g. ECDSAP256SHA256
     */
    record KeyRollover(Name zone, List<LocalKey> old, String algorithm, Set<ErrorId> fixes) implements FixAction {

        public KeyRollover {
            old = List.copyOf(old);
            fixes = Set.copyOf(fixes);
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.rollover", zone, ids(fixes), tags(old), algorithm);
        }
    }

    /**
     * Re-sign the zone from its source (fix.sourceDir) with the active keys (fix.keyDir) and deliver it.
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

    /** After a key rollover: the DS of the new KSK (from the signing run) replaces the zone's DS at the parent. */
    record PublishNewDs(Name zone, Set<ErrorId> fixes) implements FixAction {

        public PublishNewDs {
            fixes = Set.copyOf(fixes);
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.newDs", zone, ids(fixes));
        }
    }

    /** F1: copy the current signed zone to the servers that serve a stale copy. */
    record CopyToSecondary(Name zone, List<AppConfig.Fix.Server> to, Set<ErrorId> fixes) implements FixAction {

        public CopyToSecondary {
            to = List.copyOf(to);
            fixes = Set.copyOf(fixes);
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.copy", zone, ids(fixes),
                    to.stream().map(sv -> service(sv.dir())).collect(Collectors.joining(", ")));
        }
    }

    /** F2: allow TCP and EDNS answers up to 1232 bytes in an Unbound server's configuration. */
    record ServerConfig(Name zone, AppConfig.Fix.Server server, Set<ErrorId> fixes) implements FixAction {

        public ServerConfig {
            fixes = Set.copyOf(fixes);
        }

        @Override
        public String describe() {
            return Messages.get("fix.action.serverConfig", zone, ids(fixes), service(server.dir()));
        }
    }

    /** No automatic fix: the catalogue's instructions for the operator. */
    record Manual(ErrorId id, String text) implements FixAction {

        @Override
        public Set<ErrorId> fixes() {
            return Set.of();
        }

        @Override
        public boolean automatic() {
            return false;
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

    /** A server directory's name, which is also its compose service (auth2, auth3, …). */
    private static String service(String dir) {
        return Path.of(dir).getFileName().toString();
    }

    private static String tags(List<LocalKey> keys) {
        return keys.stream().map(k -> String.valueOf(k.key().getFootprint())).collect(Collectors.joining(", "));
    }
}
