package rs.port53.dnssecretary.dns;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.collect.ChainLink;

/**
 * Validates the chain of trust from the anchor to the parent of the checked zone
 * (root DNSKEY against the anchor, DS of test. signed by the root, test. DNSKEY against that DS …).
 * In the lab this part is always correct, but a real tool must not assume it.
 * Pure function over snapshot data.
 */
public final class ChainValidator {

    /** @param problems for the report; empty when secure */
    public record Result(boolean secure, List<String> problems) {}

    private ChainValidator() {}

    public static Result validate(List<Record> anchors, List<ChainLink> chain, Instant now) {
        List<String> problems = new ArrayList<>();
        if (anchors.isEmpty()) {
            problems.add("no trust anchor");
            return new Result(false, problems);
        }
        List<DNSKEYRecord> parentKeys = List.of();
        for (int i = 0; i < chain.size(); i++) {
            ChainLink link = chain.get(i);
            String zone = link.zone().toString();
            List<DNSKEYRecord> keys = link.dnskey().records(DNSKEYRecord.class);
            if (keys.isEmpty()) {
                problems.add("zone " + zone + " has no DNSKEY");
                break;
            }

            List<DNSKEYRecord> entry;
            if (i == 0) {
                entry = keys.stream().filter(k -> anchors.stream().anyMatch(a -> anchorMatches(a, k))).toList();
                if (entry.isEmpty()) {
                    problems.add("no DNSKEY of zone " + zone + " matches the trust anchor");
                    break;
                }
            } else {
                String parent = chain.get(i - 1).zone().toString();
                if (link.ds().isEmpty()) {
                    problems.add("zone " + parent + " has no DS for " + zone);
                    break;
                }
                if (!Dnssec.isSignedBy(link.ds(), parentKeys, now)) {
                    problems.add("the DS for " + zone + " has no valid signature by zone " + parent);
                }
                List<DSRecord> ds = link.ds().records(DSRecord.class);
                entry = keys.stream().filter(k -> ds.stream().anyMatch(d -> Dnssec.dsMatches(d, k))).toList();
                if (entry.isEmpty()) {
                    problems.add("no DNSKEY of zone " + zone + " matches the DS in zone " + parent);
                    break;
                }
            }
            if (!Dnssec.isSignedBy(link.dnskey(), entry, now)) {
                problems.add("the DNSKEY set of zone " + zone + " is not validly signed by the key the DS/anchor points to");
            }
            parentKeys = keys;
        }
        return new Result(problems.isEmpty(), problems);
    }

    private static boolean anchorMatches(Record anchor, DNSKEYRecord key) {
        if (anchor instanceof DSRecord ds) {
            return Dnssec.dsMatches(ds, key);
        }
        return anchor instanceof DNSKEYRecord k && k.equals(key);
    }
}
