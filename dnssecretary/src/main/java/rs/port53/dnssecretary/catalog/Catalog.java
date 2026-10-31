package rs.port53.dnssecretary.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.xbill.DNS.Name;

/** The error catalogue, loaded from the classpath resource katalog.yaml (copied from ../catalog by Maven). */
public final class Catalog {

    public static final String RESOURCE = "/katalog.yaml";

    /** One step that a recipe refers to by number (sheet tab "Akcije"). */
    public record Action(int id, String name, String description, String where) {}

    private record File(List<Action> actions, List<CatalogEntry> errors) {}

    private final List<Action> actions;
    private final Map<ErrorId, CatalogEntry> entries;

    private Catalog(List<Action> actions, List<CatalogEntry> errors) {
        this.actions = List.copyOf(actions);
        Map<ErrorId, CatalogEntry> map = new EnumMap<>(ErrorId.class);
        for (CatalogEntry e : errors) {
            if (map.put(e.id(), e) != null) {
                throw new IllegalStateException("Duplicate catalogue entry: " + e.id());
            }
        }
        this.entries = map;
    }

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Catalog load(InputStream in) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        File f = yaml.readValue(in, File.class);
        return new Catalog(f.actions(), f.errors());
    }

    public List<Action> actions() {
        return actions;
    }

    /** All entries, in ID order (A1 … F2). */
    public List<CatalogEntry> entries() {
        return List.copyOf(entries.values());
    }

    public CatalogEntry get(ErrorId id) {
        CatalogEntry e = entries.get(id);
        if (e == null) {
            throw new IllegalArgumentException("No catalogue entry for " + id);
        }
        return e;
    }

    /** The entry whose lab zone is this zone (e.g. a1-expired.test → A1), if any. */
    public Optional<CatalogEntry> forLabZone(Name zone) {
        return entries.values().stream()
                .filter(e -> e.lab() != null && labZone(e).equals(zone))
                .findFirst();
    }

    public static Name labZone(CatalogEntry e) {
        try {
            return Name.fromString(e.lab().zone(), Name.root);
        } catch (org.xbill.DNS.TextParseException ex) {
            throw new IllegalStateException("Bad lab zone in catalogue entry " + e.id(), ex);
        }
    }
}
