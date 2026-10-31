package rs.port53.dnssecretary.dns;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.Master;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/** Parsing records and names from presentation (zone file) format. */
public final class Records {

    private Records() {}

    /** One record in presentation format, as Record.toString() writes it (absolute owner name). */
    public static Record parse(String line) {
        try (Master m = new Master(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)), Name.root, 0)) {
            Record r = m.nextRecord();
            if (r == null) {
                throw new IllegalArgumentException("No record in: " + line);
            }
            return r;
        } catch (IOException e) {
            throw new IllegalArgumentException("Bad record: " + line + " (" + e.getMessage() + ")", e);
        }
    }

    /** All records of a zone file. Relative names are relative to origin. */
    public static List<Record> readZoneFile(Path file, Name origin) throws IOException {
        List<Record> out = new ArrayList<>();
        try (Master m = new Master(file.toString(), origin, 3600)) {
            Record r;
            while ((r = m.nextRecord()) != null) {
                out.add(r);
            }
        }
        return out;
    }

    public static Name name(String s) {
        try {
            return Name.fromString(s, Name.root);
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Bad name: " + s, e);
        }
    }

    public static int type(String s) {
        int t = Type.value(s);
        if (t < 0) {
            throw new IllegalArgumentException("Unknown RR type: " + s);
        }
        return t;
    }

    /**
     * A probe like "www A", "@ CDS" or "missing.example. A" relative to zone.
     * Returns the absolute name and the type.
     */
    public static Probe probe(String spec, Name zone) {
        String[] parts = spec.trim().split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Probe must be \"<name|@> <type>\": " + spec);
        }
        Name name;
        try {
            name = parts[0].equals("@") ? zone : Name.fromString(parts[0], zone);
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Bad probe name: " + spec, e);
        }
        return new Probe(name, type(parts[1]));
    }

    public record Probe(Name name, int type) {}
}
