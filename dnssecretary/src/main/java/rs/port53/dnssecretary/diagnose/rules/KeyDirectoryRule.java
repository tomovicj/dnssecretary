package rs.port53.dnssecretary.diagnose.rules;

import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.DSRecord;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.diagnose.DiagnosisRule;
import rs.port53.dnssecretary.dns.Dnssec;
import rs.port53.dnssecretary.model.Diagnosis;

/** B1 or E2: a DS that points to a key in keys/next/ is an early KSK rollover (E2), not a stale DS (B1). */
public final class KeyDirectoryRule implements DiagnosisRule {

    @Override
    public List<Diagnosis> apply(List<Diagnosis> diagnoses, ZoneSnapshot s, CheckContext ctx) {
        List<Diagnosis> out = new ArrayList<>();
        for (Diagnosis d : diagnoses) {
            // If the diagnosis is B1, we are checking the keys
            if (d.id() == ErrorId.B1 && pointsToNextKey(s)) {
                // If it points to 'next' key, change ID to E2
                // Other data (severity, side, rootCause, findings) are transferred or adjusted from the catalogue
                out.add(new Diagnosis(ErrorId.E2, d.severity(), d.side(), "KSK changed and DS is transferred too early.", d.findings()));
            } else {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean pointsToNextKey(ZoneSnapshot s) {
        // We can't point if there is no parent DS
        if (s.parentDs().rrset().isEmpty()) {
            return false;
        }

        List<DSRecord> dsRecords = s.parentDs().rrset().records(DSRecord.class);

        // localKeys() returns all keys from local (K - keys )
        List<LocalKey> localKeys = s.localKeys();

        // Going through all records
        for (DSRecord ds : dsRecords) {
            for (LocalKey localKey : localKeys) {
                if ("next".equals(localKey.location()) && Dnssec.dsMatches(ds, localKey.key())) {
                    return true;
                }
            }
        }

        return false;
    }
}