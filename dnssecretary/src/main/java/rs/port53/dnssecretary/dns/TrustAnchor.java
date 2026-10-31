package rs.port53.dnssecretary.dns;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

/**
 * The root trust anchor: DS records (Unbound root.key format, as sign.sh writes it:
 * ". IN DS 12345 13 2 ABCD…") and/or DNSKEY records. IANA root-anchors.xml comes later.
 */
public final class TrustAnchor {

    private TrustAnchor() {}

    public static List<Record> load(Path file) throws IOException {
        List<Record> anchors = Records.readZoneFile(file, Name.root).stream()
                .filter(r -> r instanceof DSRecord || r instanceof DNSKEYRecord)
                .toList();
        if (anchors.isEmpty()) {
            throw new IOException("No DS or DNSKEY records in " + file);
        }
        return anchors;
    }
}
