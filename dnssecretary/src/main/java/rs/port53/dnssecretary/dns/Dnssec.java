package rs.port53.dnssecretary.dns;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import rs.port53.dnssecretary.collect.SignedRRset;

/** Small DNSSEC helpers on top of dnsjava, shared by the chain validation and the checks. */
public final class Dnssec {

    private Dnssec() {}

    /** The DS a parent should have for this key, e.g. {@code ds(ksk, DNSSEC.Digest.SHA256)}. */
    public static DSRecord ds(DNSKEYRecord key, int digestId) {
        return new DSRecord(key.getName(), DClass.IN, 0, digestId, key);
    }

    /** Does the DS point to this key: same key tag, algorithm and digest? */
    public static boolean dsMatches(DSRecord ds, DNSKEYRecord key) {
        if (ds.getFootprint() != key.getFootprint() || ds.getAlgorithm() != key.getAlgorithm()) {
            return false;
        }
        try {
            return Arrays.equals(ds(key, ds.getDigestID()).getDigest(), ds.getDigest());
        } catch (IllegalArgumentException e) {   // unknown digest type
            return false;
        }
    }

    /** Is at least one RRSIG of the set valid at {@code now} and made by one of the keys? */
    public static boolean isSignedBy(SignedRRset set, Collection<DNSKEYRecord> keys, Instant now) {
        if (set.isEmpty()) {
            return false;
        }
        RRset rrset = set.toRRset();
        for (RRSIGRecord sig : set.sigs()) {
            for (DNSKEYRecord key : keys) {
                if (key.getFootprint() != sig.getFootprint() || key.getAlgorithm() != sig.getAlgorithm()) {
                    continue;
                }
                try {
                    DNSSEC.verify(rrset, sig, key, now);
                    return true;
                } catch (DNSSEC.DNSSECException e) {
                    // try the next key/signature
                }
            }
        }
        return false;
    }
}
