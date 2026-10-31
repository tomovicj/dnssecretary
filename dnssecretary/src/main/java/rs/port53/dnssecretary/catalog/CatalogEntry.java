package rs.port53.dnssecretary.catalog;

import java.util.List;
import rs.port53.dnssecretary.model.FixSide;
import rs.port53.dnssecretary.model.Severity;

/** One error from catalog/katalog.yaml. Texts are in Serbian, see the comment at the top of that file. */
public record CatalogEntry(
        ErrorId id,
        String title,
        String group,
        boolean mvp,
        Severity severity,
        FixSide side,
        String symptom,
        String ede,
        String cause,
        String detection,
        String fix,
        String recipe,
        List<String> rfc,
        Lab lab) {

    /**
     * Where the lab reproduces the error.
     *
     * @param zone  the lab zone, e.g. a1-expired.test
     * @param probe the query that shows the error, "<name relative to zone or @> <type>", e.g. "www A"
     * @param setup a script that must run first (E1, E3), or null
     */
    public record Lab(String zone, String probe, String setup) {}
}
