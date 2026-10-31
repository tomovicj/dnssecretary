package rs.port53.dnssecretary.diagnose;

import java.util.List;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Diagnosis;

/**
 * A small named rule that rewrites the list of diagnoses, e.g. "A1 only on one NS → F1",
 * "B1 or E2 by the key directory", "B4 hides A3". Rules run in order, each sees the previous result.
 */
public interface DiagnosisRule {

    List<Diagnosis> apply(List<Diagnosis> diagnoses, ZoneSnapshot snapshot, CheckContext ctx);
}
