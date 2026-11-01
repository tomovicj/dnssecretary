package rs.port53.dnssecretary.fix;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.dns.Records;
import rs.port53.dnssecretary.report.Messages;

/**
 * FixPlan → POSIX sh script, the fix the admin sees, may edit and runs.
 *
 * <p>The contract: the script runs with {@code sh -eu} in fix.labDir, after a helpers file
 * (scripts/fixenv/) that defines {@code reload <service>} and {@code flush <zone>}. Paths under
 * fix.labDir are written relative, so the same script runs on the lab and in a sandbox copy of it.
 * Each action is one block that starts with the errors it fixes; the servers are reloaded once, at the end.
 */
public final class ScriptGenerator {

    private final AppConfig.Fix config;
    private final Catalog catalog;

    public ScriptGenerator(AppConfig.Fix config, Catalog catalog) {
        this.config = config;
        this.catalog = catalog;
    }

    /** Reads the zone's source only for A7 (the corrected copy is part of the script). */
    public String generate(FixPlan plan) throws IOException {
        Name zone = plan.zone();
        StringBuilder sh = new StringBuilder();
        sh.append("#!/bin/sh\n");
        sh.append("# DNSSECretary fix for ").append(zone).append('\n');
        sh.append("# Runs in ").append(config.labDir()).append(" with \"sh -eu\"; reload and flush come from scripts/fixenv/.\n");
        sh.append("ZONE=").append(zone.toString(true)).append('\n');
        if (plan.actions().stream().anyMatch(a -> a instanceof FixAction.ResignZone)) {
            sh.append("WORK=").append(rel(config.workDir())).append("/$ZONE\n");
        }

        List<FixAction.Manual> manual = plan.manual();
        if (!manual.isEmpty()) {
            sh.append("\n# Not fixed by this script, for the operator:\n");
            manual.forEach(m -> sh.append("#   [").append(m.id()).append("] ").append(m.text()).append('\n'));
        }

        Set<String> reload = new LinkedHashSet<>();
        for (FixAction a : plan.actions()) {
            if (!a.automatic()) {
                continue;
            }
            sh.append('\n');
            a.fixes().stream().sorted().forEach(id ->
                    sh.append("# [").append(id).append("] ").append(catalog.get(id).title()).append('\n'));
            sh.append("# Fix: ").append(a.describe()).append('\n');
            switch (a) {
                case FixAction.RepublishKey r -> republish(sh, r);
                case FixAction.KeyRollover r -> rollover(sh, r);
                case FixAction.ResignZone r -> {
                    resign(sh, r);
                    reload.add(service(deliverDir(zone)));
                }
                case FixAction.UpdateParentDs u -> {
                    if (labParent()) {
                        sh.append("cat > ").append(dsFile()).append(" <<'EOF'\n");
                        u.wanted().forEach(ds -> sh.append(ds).append('\n'));
                        sh.append("EOF\n");
                        parentResign(sh, reload);
                    } else {
                        sh.append("# ").append(Messages.get("fix.registrar")).append('\n');
                        u.wanted().forEach(ds -> sh.append("#   ").append(ds).append('\n'));
                    }
                }
                case FixAction.PublishNewDs p -> {
                    if (labParent()) {
                        sh.append("cp \"$WORK/dsset-$ZONE.\" ").append(dsFile()).append('\n');
                        parentResign(sh, reload);
                    } else {
                        sh.append("echo '").append(Messages.get("fix.registrar").replace("'", "")).append("'\n");
                        sh.append("cat \"$WORK/dsset-$ZONE.\"\n");
                    }
                }
                case FixAction.CopyToSecondary c -> c.to().forEach(sv -> {
                    sh.append("cp ").append(rel(deliverDir(zone))).append("/$ZONE.zone.signed ")
                            .append(rel(sv.dir())).append("/\n");
                    reload.add(service(sv.dir()));
                });
                case FixAction.ServerConfig c -> {
                    sh.append("sed -i -e 's/^\\([[:space:]]*do-tcp:\\).*/\\1 yes/'")
                            .append(" -e 's/^\\([[:space:]]*max-udp-size:\\).*/\\1 1232/' ")
                            .append(rel(c.server().dir())).append("/unbound.conf\n");
                    reload.add(service(c.server().dir()));
                }
                case FixAction.Manual m -> { }
            }
        }

        if (!reload.isEmpty()) {
            sh.append("\n# Load the changes on the servers and drop the resolver's cached answers.\n");
            reload.forEach(svc -> sh.append("reload ").append(svc).append('\n'));
            sh.append("flush \"$ZONE\"\n");
        }
        return sh.toString();
    }

    private void republish(StringBuilder sh, FixAction.RepublishKey r) {
        for (LocalKey k : r.keys()) {
            String dir = rel(config.keyDir()) + "/" + k.location();
            sh.append("mv ").append(dir).append('/').append(k.file()).append(' ')
                    .append(dir).append('/').append(privateFile(k)).append(' ')
                    .append(rel(config.keyDir())).append("/\n");
        }
    }

    private void rollover(StringBuilder sh, FixAction.KeyRollover r) {
        String keys = rel(config.keyDir());
        sh.append("mkdir -p ").append(keys).append("/old\n");
        for (LocalKey k : r.old()) {
            sh.append("mv ").append(keys).append('/').append(k.file()).append(' ')
                    .append(keys).append('/').append(privateFile(k)).append(' ').append(keys).append("/old/\n");
        }
        sh.append("dnssec-keygen -q -K ").append(keys).append(" -a ").append(r.algorithm()).append(" -f KSK \"$ZONE\"\n");
        sh.append("dnssec-keygen -q -K ").append(keys).append(" -a ").append(r.algorithm()).append(" \"$ZONE\"\n");
    }

    private void resign(StringBuilder sh, FixAction.ResignZone r) throws IOException {
        sh.append("mkdir -p \"$WORK\"\n");
        String source = rel(config.sourceDir()) + "/$ZONE.zone";
        if (r.maxTtl() != null) {
            sh.append("cat > \"$WORK/$ZONE.zone\" <<'EOF'\n");
            sh.append(capped(Path.of(config.sourceDir()).resolve(r.zone().toString(true) + ".zone"), r.zone(),
                    r.maxTtl()));
            sh.append("EOF\n");
            source = "\"$WORK/$ZONE.zone\"";
        }
        sh.append("dnssec-signzone -S -K ").append(rel(config.keyDir()))
                .append(" -d \"$WORK\" -s now-3600 -e now+2592000 -N unixtime")
                .append(r.nsec3() ? " -3 - -H 0" : "").append(" \\\n");
        sh.append("    -o \"$ZONE\" -f \"$WORK/$ZONE.zone.signed\" ").append(source).append('\n');
        sh.append("dnssec-verify -q -o \"$ZONE\" \"$WORK/$ZONE.zone.signed\"\n");
        sh.append("cp \"$WORK/$ZONE.zone.signed\" ").append(rel(deliverDir(r.zone()))).append("/\n");
    }

    private void parentResign(StringBuilder sh, Set<String> reload) {
        AppConfig.Fix.Parent p = config.parent();
        sh.append(p.resignCommand().stream().map(this::rel).collect(Collectors.joining(" "))).append('\n');
        if (p.service() != null) {
            reload.add(p.service());
        }
    }

    /** parent.mode: lab = the script writes the DS and re-signs the parent; otherwise it only shows the DS. */
    private boolean labParent() {
        return config.parent() != null && "lab".equals(config.parent().mode());
    }

    private String dsFile() {
        return rel(config.parent().dsDir()) + "/$ZONE.ds";
    }

    /** The zone's records with every TTL capped (A7). Owner names absolute, one record per line. */
    static String capped(Path source, Name zone, long maxTtl) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Record r : Records.readZoneFile(source, zone)) {
            out.append(r.getName()).append('\t').append(Math.min(r.getTTL(), maxTtl)).append('\t')
                    .append(DClass.string(r.getDClass())).append('\t').append(Type.string(r.getType())).append('\t')
                    .append(r.rdataToString()).append('\n');
        }
        return out.toString();
    }

    private String deliverDir(Name zone) {
        return config.deliver().getOrDefault(zone.toString(true), config.deliver().get("default"));
    }

    /** A path under fix.labDir as the script sees it (relative); anything else stays absolute. */
    private String rel(String path) {
        Path lab = Path.of(config.labDir()).normalize();
        Path p = Path.of(path).normalize();
        return p.isAbsolute() && p.startsWith(lab) && !p.equals(lab) ? lab.relativize(p).toString() : path;
    }

    /** The compose service behind a server directory: its name (auth1, tld, …). */
    private static String service(String dir) {
        return Path.of(dir).getFileName().toString();
    }

    private static String privateFile(LocalKey k) {
        return k.file().replaceFirst("\\.key$", ".private");
    }
}
