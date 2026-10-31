package rs.port53.dnssecretary.model;

import java.util.List;
import rs.port53.dnssecretary.catalog.ErrorId;

/** One error from the catalogue, with its cause and the findings that prove it. */
public record Diagnosis(ErrorId id, Severity severity, FixSide side, String rootCause, List<Finding> findings) {

    public Diagnosis {
        findings = List.copyOf(findings);
    }
}
