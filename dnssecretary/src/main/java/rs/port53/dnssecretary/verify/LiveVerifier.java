package rs.port53.dnssecretary.verify;

import java.time.Clock;
import java.util.List;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.CatalogEntry;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.CollectException;
import rs.port53.dnssecretary.collect.Collector;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.report.Report;

/** collect → check → diagnose → Verifier for one zone, against the servers the configuration names. */
public final class LiveVerifier {

    /** A finished verify: the full report (for the text output) and its plain result. */
    public record Outcome(Report report, Verifier.Result verifier, VerifyResult result) {}

    private final AppConfig config;
    private final Catalog catalog;
    private final Clock clock;

    public LiveVerifier(AppConfig config, Catalog catalog, Clock clock) {
        this.config = config;
        this.catalog = catalog;
        this.clock = clock;
    }

    public Outcome verify(Name zone) throws CollectException {
        List<String> probes = catalog.forLabZone(zone)
                .map(CatalogEntry::lab)
                .map(CatalogEntry.Lab::probe)
                .map(List::of)
                .orElse(List.of());
        ZoneSnapshot snapshot = new Collector(config, clock).collect(zone, probes);
        Report report = Analysis.run(snapshot, new CheckContext(clock, config.thresholds()), catalog);
        Verifier.Result v = Verifier.verify(snapshot, report);
        return new Outcome(report, v, VerifyResult.of(report, v));
    }
}
