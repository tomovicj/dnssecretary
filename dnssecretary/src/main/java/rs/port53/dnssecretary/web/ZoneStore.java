package rs.port53.dnssecretary.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.fix.FixPlan;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Severity;
import rs.port53.dnssecretary.report.Report;

/**
 * Everything the web app knows, in memory (no database: a restart starts empty):
 * the last scan of every zone, its "verified" state, and all jobs.
 */
final class ZoneStore {

    /** What one scan of a zone produced. */
    record Scan(Report report, FixPlan plan, String script, Instant nextExpiry) {}

    /**
     * @param rowChanged the zone's row is different now (worth an SSE "zone" event)
     * @param simulate   the generated script changed and can run automatically: simulate it
     */
    record Update(boolean rowChanged, boolean simulate) {}

    private static final class State {
        Scan scan;
        Instant lastScan;
        Instant brokenSince;
        Api.Verified verified = Api.Verified.NONE;
        String lastSimulated;
        final List<String> jobIds = new ArrayList<>();
    }

    private static final Comparator<Api.ZoneRow> ORDER = Comparator
            .comparing((Api.ZoneRow r) -> rank(r.status()))
            .thenComparing(Api.ZoneRow::brokenSince, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Api.ZoneRow::zone);

    private final Catalog catalog;
    private final Map<Name, State> zones = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    ZoneStore(List<Name> zones, Catalog catalog) {
        this.catalog = catalog;
        zones.forEach(z -> this.zones.put(z, new State()));
    }

    synchronized boolean knows(Name zone) {
        return zones.containsKey(zone);
    }

    synchronized List<Name> zones() {
        return List.copyOf(zones.keySet());
    }

    synchronized Update update(Name zone, Scan scan, Instant now) {
        State st = state(zone);
        Api.ZoneRow before = st.scan == null ? null : row(zone, st);
        String status = status(scan.report());
        if (st.scan == null || !status.equals(status(st.scan.report()))) {
            st.brokenSince = status.equals("OK") ? null : now;
        }
        st.scan = scan;
        st.lastScan = now;

        boolean simulate = false;
        if (!scan.plan().automatic()) {
            st.verified = Api.Verified.NONE;
        } else if (!st.verified.state().equals("RUNNING")
                && (!scan.script().equals(st.lastSimulated) || st.verified.state().equals("NONE"))) {
            // a new script, or the zone broke again after it was healthy
            simulate = true;
        }
        Api.ZoneRow after = row(zone, st);
        return new Update(before == null || !sameIgnoringScanTime(before, after), simulate);
    }

    synchronized void simulationQueued(Name zone, String jobId) {
        state(zone).verified = new Api.Verified("RUNNING", null, jobId);
    }

    /** The outcome of any simulation of this zone (automatic or the admin's) is its "verified" state. */
    synchronized void simulationDone(Name zone, String script, Job job, Instant now) {
        State st = state(zone);
        st.lastSimulated = script;
        boolean pass = job.state() == Job.State.PASS;
        if (st.scan != null && st.scan.plan().automatic()) {
            st.verified = new Api.Verified(pass ? "PASS" : "FAIL", now, job.id);
        }
    }

    synchronized void add(Job job) {
        jobs.put(job.id, job);
        if (job.zone != null && zones.containsKey(job.zone)) {
            zones.get(job.zone).jobIds.add(job.id);
        }
    }

    synchronized Job job(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new NoSuchElementException("No job " + id);
        }
        return j;
    }

    /** All jobs, newest first, without logs. */
    synchronized List<Api.JobView> jobs() {
        List<Api.JobView> out = new ArrayList<>();
        jobs.values().forEach(j -> out.add(0, j.view(false)));
        return out;
    }

    /** The generated fix script of the last scan; "" when there is nothing automatic to do. */
    synchronized String generated(Name zone) {
        Scan s = state(zone).scan;
        return s == null || !s.plan().automatic() ? "" : s.script();
    }

    /** Every zone that has been scanned, broken first. */
    synchronized List<Api.ZoneRow> rows() {
        List<Api.ZoneRow> out = new ArrayList<>();
        zones.forEach((z, st) -> {
            if (st.scan != null) {
                out.add(row(z, st));
            }
        });
        out.sort(ORDER);
        return out;
    }

    synchronized Api.ZoneRow row(Name zone) {
        State st = state(zone);
        if (st.scan == null) {
            throw new NoSuchElementException("Zone " + zone + " not scanned yet");
        }
        return row(zone, st);
    }

    synchronized Api.ZoneDetail detail(Name zone) {
        State st = state(zone);
        Api.ZoneRow r = row(zone);
        List<Api.JobView> js = new ArrayList<>();
        st.jobIds.forEach(id -> js.add(0, jobs.get(id).view(false)));
        return Api.ZoneDetail.of(r, st.scan.report(), new Api.Script(generated(zone), st.lastSimulated), js);
    }

    private Api.ZoneRow row(Name zone, State st) {
        List<Api.Issue> issues = st.scan.report().diagnoses().stream()
                .map(d -> new Api.Issue(d.id().name(), catalog.get(d.id()).title(), d.severity().name(),
                        d.side().name()))
                .toList();
        return new Api.ZoneRow(zone.toString(), status(st.scan.report()), issues, st.brokenSince, st.lastScan,
                st.scan.nextExpiry(), st.verified);
    }

    private State state(Name zone) {
        State st = zones.get(zone);
        if (st == null) {
            throw new NoSuchElementException("Unknown zone " + zone);
        }
        return st;
    }

    /** OK | RISK | CRITICAL from the diagnoses (a tool error in one check doesn't hide the others). */
    static String status(Report r) {
        List<Diagnosis> ds = r.diagnoses();
        if (ds.stream().anyMatch(d -> d.severity() == Severity.CRITICAL)) {
            return "CRITICAL";
        }
        return ds.isEmpty() ? "OK" : "RISK";
    }

    private static int rank(String status) {
        return switch (status) {
            case "CRITICAL" -> 0;
            case "RISK" -> 1;
            default -> 2;
        };
    }

    private static boolean sameIgnoringScanTime(Api.ZoneRow a, Api.ZoneRow b) {
        return a.equals(new Api.ZoneRow(b.zone(), b.status(), b.issues(), b.brokenSince(), a.lastScan(),
                b.nextExpiry(), b.verified()));
    }
}
