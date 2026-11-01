package rs.port53.dnssecretary.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.dns.Records;

class CatalogTest {

    private final Catalog catalog = Catalog.load();

    @Test
    void hasEveryErrorExactlyOnce() {
        assertThat(catalog.entries()).extracting(CatalogEntry::id).containsExactly(ErrorId.values());
    }

    @Test
    void everyEntryIsComplete() {
        for (CatalogEntry e : catalog.entries()) {
            assertThat(e.title()).as(e.id() + " title").isNotBlank();
            assertThat(e.cause()).as(e.id() + " cause").isNotBlank();
            assertThat(e.fix()).as(e.id() + " fix").isNotBlank();
            assertThat(e.detection()).as(e.id() + " detection").isNotBlank();
            assertThat(e.recipe()).as(e.id() + " recipe").isNotBlank();
            assertThat(e.severity()).as(e.id() + " severity").isNotNull();
            assertThat(e.side()).as(e.id() + " side").isNotNull();
            assertThat(e.rfc()).as(e.id() + " rfc").isNotEmpty();
            assertThat(e.lab()).as(e.id() + " lab").isNotNull();
        }
    }

    @Test
    void labZonesAreUniqueAndProbesParse() {
        Set<Name> zones = new HashSet<>();
        for (CatalogEntry e : catalog.entries()) {
            Name zone = Catalog.labZone(e);
            assertThat(zones.add(zone)).as("duplicate lab zone " + zone).isTrue();
            assertThat(zone.toString()).startsWith(e.id().name().toLowerCase());
            Records.probe(e.lab().probe(), zone);   // throws if malformed
        }
    }

    @Test
    void hostingErrorsAreOnesBreakShCanPutOnAnyZone() throws Exception {
        // scripts/break.sh "zone" mode calls break_<id> for these (E1: "rollover"); both read the flag
        String breakSh = Files.readString(Path.of("../scripts/break.sh"));
        List<ErrorId> hosting = catalog.entries().stream().filter(e -> e.lab().hosting()).map(CatalogEntry::id).toList();
        assertThat(hosting).hasSize(18).contains(ErrorId.A1, ErrorId.B2, ErrorId.E1).doesNotContain(ErrorId.B1, ErrorId.F1);
        hosting.stream().filter(id -> id != ErrorId.E1).forEach(id ->
                assertThat(breakSh).as(id + " in break.sh").contains("break_" + id.name().toLowerCase() + "() {"));
    }

    @Test
    void findsEntryByLabZone() {
        assertThat(catalog.forLabZone(Records.name("a1-expired.test"))).map(CatalogEntry::id).contains(ErrorId.A1);
        assertThat(catalog.forLabZone(Records.name("good.test"))).isEmpty();
    }

    @Test
    void mvpTabHasTenErrors() {
        assertThat(catalog.entries().stream().filter(CatalogEntry::mvp)).hasSize(10);
    }
}
