package rs.port53.dnssecretary.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
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
    void findsEntryByLabZone() {
        assertThat(catalog.forLabZone(Records.name("a1-expired.test"))).map(CatalogEntry::id).contains(ErrorId.A1);
        assertThat(catalog.forLabZone(Records.name("good.test"))).isEmpty();
    }

    @Test
    void mvpTabHasTenErrors() {
        assertThat(catalog.entries().stream().filter(CatalogEntry::mvp)).hasSize(10);
    }
}
