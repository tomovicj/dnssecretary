package rs.port53.dnssecretary.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import rs.port53.dnssecretary.json.DnsJson;

/** ZoneSnapshot ⇄ JSON. Records are stored in presentation format. */
public final class SnapshotIO {

    private static final ObjectMapper MAPPER = DnsJson.mapper();

    private SnapshotIO() {}

    public static void write(ZoneSnapshot s, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            write(s, out);
        }
    }

    public static void write(ZoneSnapshot s, OutputStream out) throws IOException {
        MAPPER.writer().without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .writeValue(out, s);
        out.write('\n');
        out.flush();
    }

    public static ZoneSnapshot read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public static ZoneSnapshot read(InputStream in) throws IOException {
        return MAPPER.readValue(in, ZoneSnapshot.class);
    }

    /** File name for a zone's snapshot: a1-expired.test.json */
    public static String fileName(org.xbill.DNS.Name zone) {
        return zone.toString(true) + ".json";
    }
}
