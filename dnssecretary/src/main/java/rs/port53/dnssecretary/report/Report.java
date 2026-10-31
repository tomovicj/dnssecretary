package rs.port53.dnssecretary.report;

import java.time.Instant;
import java.util.List;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.model.Severity;

/**
 * The result of "check" for one zone. TextReport and JsonReport render it;
 * the later web report reads the same JSON.
 */
public record Report(
        Name zone,
        Instant collectedAt,
        Instant checkedAt,
        Status status,
        List<Diagnosis> diagnoses,
        List<String> warnings,
        List<String> failures) {

    /** Overall state; ordinal order = exit code (0 ok, 1 risks only, 2 critical, 3 tool error). */
    public enum Status {
        OK, RISK, CRITICAL, TOOL_ERROR;

        public int exitCode() {
            return ordinal();
        }
    }

    public static Report of(Name zone, Instant collectedAt, Instant checkedAt, List<Diagnosis> diagnoses,
                            List<String> warnings, List<String> failures) {
        Status s;
        if (!failures.isEmpty()) {
            s = Status.TOOL_ERROR;
        } else if (diagnoses.stream().anyMatch(d -> d.severity() == Severity.CRITICAL)) {
            s = Status.CRITICAL;
        } else if (!diagnoses.isEmpty()) {
            s = Status.RISK;
        } else {
            s = Status.OK;
        }
        return new Report(zone, collectedAt, checkedAt, s, diagnoses, warnings, failures);
    }
}
