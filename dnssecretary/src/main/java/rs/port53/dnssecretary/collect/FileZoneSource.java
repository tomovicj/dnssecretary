package rs.port53.dnssecretary.collect;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.dns.Records;

/**
 * Reads &lt;dir&gt;/&lt;zone&gt;.zone.signed (e.g. /lab/auth1/a1-expired.test.zone.signed).
 * The first directory that has the file wins.
 */
public final class FileZoneSource implements ZoneSource {

    private final List<Path> dirs;

    public FileZoneSource(List<Path> dirs) {
        this.dirs = List.copyOf(dirs);
    }

    public static String fileName(Name zone) {
        return zone.toString(true) + ".zone.signed";
    }

    @Override
    public Optional<List<Record>> load(Name zone, List<InetAddress> servers, List<String> warnings) {
        for (Path dir : dirs) {
            Path f = dir.resolve(fileName(zone));
            if (!Files.isRegularFile(f)) {
                continue;
            }
            try {
                return Optional.of(Records.readZoneFile(f, zone));
            } catch (IOException e) {
                warnings.add("Ne mogu da pročitam " + f + ": " + e.getMessage());
                return Optional.empty();
            }
        }
        warnings.add("Nema fajla " + fileName(zone) + " u " + dirs + " – provere rade samo nad uzorcima.");
        return Optional.empty();
    }
}
