package rs.port53.dnssecretary.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.SnapshotIO;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.Thresholds;

/**
 * Recorded lab snapshots (src/test/resources/snapshots, made by scripts/snapshots.sh).
 * Offline and deterministic: the clock is fixed at the moment of recording.
 */
public final class Snapshots {

    public static final String GOOD = "good.test";

    private Snapshots() {}

    /** e.g. load("a1-expired.test") */
    public static ZoneSnapshot load(String zone) {
        String path = "/snapshots/" + zone + ".json";
        try (InputStream in = Snapshots.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No snapshot " + path + " – run scripts/snapshots.sh");
            }
            return SnapshotIO.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ZoneSnapshot load(CatalogEntry e) {
        return load(e.lab().zone());
    }

    /** Check context as the checks would have seen it when the snapshot was recorded. */
    public static CheckContext context(ZoneSnapshot s) {
        return new CheckContext(Clock.fixed(s.collectedAt(), ZoneOffset.UTC), Thresholds.defaults());
    }

    /** good.test + the lab zone of every catalogue entry. */
    public static List<String> allZones() {
        List<String> out = new ArrayList<>();
        out.add(GOOD);
        Catalog.load().entries().forEach(e -> out.add(e.lab().zone()));
        return out;
    }
}
