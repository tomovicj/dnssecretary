package rs.port53.dnssecretary.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.List;
import rs.port53.dnssecretary.json.DnsJson;
import rs.port53.dnssecretary.report.Report;
import rs.port53.dnssecretary.verify.VerifyResult;

/**
 * The JSON shapes of the web API, exactly as the API contract in the pivot plan names them.
 * Instants are ISO-8601 strings; zone names end with a dot.
 */
public final class Api {

    private Api() {}

    /** The mapper for every API response and SSE event: DnsJson types, one line. */
    public static final ObjectMapper JSON = DnsJson.mapper().disable(SerializationFeature.INDENT_OUTPUT);

    public record Issue(String id, String title, String severity, String side) {}

    /**
     * @param state PASS | FAIL | RUNNING | NONE (NONE: healthy, or no automatic fix)
     */
    public record Verified(String state, Instant at, String jobId) {

        public static final Verified NONE = new Verified("NONE", null, null);
    }

    /**
     * One line of the dashboard.
     *
     * @param status      OK | RISK | CRITICAL
     * @param brokenSince the first scan with this status; null for OK
     * @param nextExpiry  the earliest RRSIG expiration in the zone; null if unknown
     */
    public record ZoneRow(String zone, String status, List<Issue> issues, Instant brokenSince, Instant lastScan,
                          Instant nextExpiry, Verified verified) {}

    /**
     * @param generated     the fix script the tool made from the last scan ("" when nothing to fix)
     * @param lastSimulated the script of the zone's last simulation (null if none yet)
     */
    public record Script(String generated, String lastSimulated) {}

    /** The zone screen: the row, the full report (as check --json), the script and the zone's jobs. */
    public record ZoneDetail(String zone, String status, List<Issue> issues, Instant brokenSince, Instant lastScan,
                             Instant nextExpiry, Verified verified, Report report, Script script, List<JobView> jobs) {

        static ZoneDetail of(ZoneRow r, Report report, Script script, List<JobView> jobs) {
            return new ZoneDetail(r.zone(), r.status(), r.issues(), r.brokenSince(), r.lastScan(), r.nextExpiry(),
                    r.verified(), report, script, jobs);
        }
    }

    /**
     * @param kind   SIMULATION | EXECUTION | DEMO
     * @param state  QUEUED | RUNNING | PASS | FAIL | ERROR
     * @param edited the script differs from the generated one
     * @param log    only in GET /api/jobs/{id}
     * @param error  why the run itself broke (ERROR), else null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobView(String id, String kind, String zone, String state, boolean edited, String script,
                          Instant startedAt, Instant finishedAt, List<String> log, VerifyResult verify, String error) {}

    /** SSE "log": one output line of a job. */
    public record LogLine(String jobId, String line) {}

    public record ScriptRequest(String script, Boolean simulateFirst) {}

    /** POST /api/demo/break: a hosting zone and an error (A1 …). */
    public record BreakRequest(String zone, String error) {}

    public record Error(String error) {}
}
