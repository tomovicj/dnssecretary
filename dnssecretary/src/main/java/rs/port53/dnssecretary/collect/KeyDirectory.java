package rs.port53.dnssecretary.collect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.dns.Records;

/**
 * Public keys of a zone in a BIND key directory: K&lt;zone&gt;.+&lt;alg&gt;+&lt;tag&gt;.key,
 * in the directory itself and one level of subdirectories (old/, next/, retired/, k2/, alg8/).
 * Never reads .private files.
 */
public final class KeyDirectory {

    private KeyDirectory() {}

    public static List<LocalKey> load(Path dir, Name zone, List<String> warnings) {
        String prefix = "K" + zone + "+";
        List<LocalKey> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, 2)) {
            for (Path f : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                String name = f.getFileName().toString();
                if (!Files.isRegularFile(f) || !name.startsWith(prefix) || !name.endsWith(".key")) {
                    continue;
                }
                Path parent = dir.relativize(f.getParent());
                for (Record r : Records.readZoneFile(f, zone)) {
                    if (r instanceof DNSKEYRecord key) {
                        out.add(new LocalKey(parent.toString(), name, key));
                    }
                }
            }
        } catch (IOException e) {
            warnings.add("Ne mogu da pročitam direktorijum ključeva " + dir + ": " + e.getMessage());
        }
        return out;
    }
}
