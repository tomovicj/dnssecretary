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
                // Ostali podaci (severity, side, rootCause, findings) se prenose ili prilagođavaju iz kataloga (kao što kaže dokumentacija)
                out.add(new Diagnosis(ErrorId.E2, d.severity(), d.side(), "KSK promenjen, a DS zamenjen prerano", d.findings()));
            } else {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean pointsToNextKey(ZoneSnapshot s) {
        // Ako nema parent DS zapisa, ne možemo proveriti
        if (s.parentDs().rrset().isEmpty()) {
            return false;
        }

        List<DSRecord> dsRecords = s.parentDs().rrset().records(DSRecord.class);

        // localKeys() vraća sve ključeve sa lokalnog diska (K - ključevi iz tabele "Izvori podataka u snimku")
        List<LocalKey> localKeys = s.localKeys();

        // Prolazimo kroz sve DS zapise i sve lokalne ključeve
        for (DSRecord ds : dsRecords) {
            for (LocalKey localKey : localKeys) {
                // Proveravamo da li je lokacija ključa tačno "next" (ili u folderu "next")
                // I da li se taj lokalni ključ (kada se prebaci u DNSKEY) poklapa sa DS zapisom
                if ("next".equals(localKey.location()) && Dnssec.dsMatches(ds, localKey.key())) {
                    return true; // Našli smo poklapanje sa 'next' ključem!
                }
            }
        }

        return false;
    }
}